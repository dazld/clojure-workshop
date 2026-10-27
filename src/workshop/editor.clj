(ns workshop.editor
  "The workshop editor: bb editor, then open http://localhost:1234.
  bb editor --port 4000 uses another port.

  This process serves the page and the files, and never runs student code.
  Evaluation happens in a child bb process (workshop.runtime). If that gets
  stuck or runs out of memory, the editor kills it and starts a fresh one."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

'(1 12 )

(def default-port 1234)
(defonce port (atom default-port))
(def runtime-heap "-Xmx1g")
(def src-dir "src")
(def public-dir "resources/public")

;; Every request that can run code must carry this token. It stops other
;; websites in the same browser from posting code to localhost.
(def token (str (random-uuid)))

;; ---------------------------------------------------------------------------
;; Browser connections (server-sent events)

(defonce clients (atom #{}))

(defn- send-event! [ch event]
  (server/send! ch (str "data: " (json/generate-string event) "\n\n") false))

(defn- broadcast! [event]
  (doseq [ch @clients] (send-event! ch event)))

;; Output and View updates are batched, so a print loop or a tap> at 100 fps
;; cannot flood the browser.

(def ^:private max-buffered-chars 50000)
(def ^:private max-chars-per-flush 10000)

;; The current runtime process: {:proc :port :ready :id}
(defonce ^:private runtime (atom nil))

(defonce ^:private output (atom ""))
(defonce ^:private dropped? (atom false))
(defonce ^:private pending-view (atom nil))
(defonce ^:private last-view (atom nil))

(defn- buffer-output! [s]
  (swap! output (fn [buf]
                  (let [buf (str buf s)]
                    (if (> (count buf) max-buffered-chars)
                      (do (reset! dropped? true)
                          (subs buf (- (count buf) max-buffered-chars)))
                      buf)))))

(defn- flush-output! []
  (let [[buf _] (reset-vals! output "")]
    (when (seq buf)
      (when @dropped?
        (reset! dropped? false)
        (broadcast! {:type "out" :text "\n… output too fast, some was dropped …\n"}))
      (broadcast! {:type "out"
                   :text (if (> (count buf) max-chars-per-flush)
                           (str "… " (subs buf (- (count buf) max-chars-per-flush)))
                           buf)})))
  (when-let [{:keys [html runtime-id]} (first (reset-vals! pending-view nil))]
    ;; A frame from a runtime that has since been replaced is dropped.
    (when (= runtime-id (:id @runtime))
      (reset! last-view html)
      (broadcast! {:type "view" :html html}))))

(defn- clear-view! []
  (reset! pending-view nil)
  (reset! last-view nil)
  (broadcast! {:type "view" :html ""}))

(defonce ^:private flusher
  (future
    (while true
      (try (flush-output!) (catch Throwable _))
      (Thread/sleep 50))))

;; ---------------------------------------------------------------------------
;; The runtime process

;; Starting, restarting and replacing a crashed runtime all hold this lock,
;; so two of them can never overlap and leave a runtime nobody owns.
(defonce ^:private lifecycle (Object.))

(declare spawn-runtime! await-ready!)

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn- pump! [stream]
  ;; Forward characters as they arrive, not line by line, so (print "x")
  ;; shows up too.
  (future
    (let [r (io/reader stream)
          buf (char-array 4096)]
      (loop []
        (let [n (.read r buf)]
          (when (pos? n)
            (buffer-output! (String. buf 0 n))
            (recur)))))))

(defn- wait-for-port [p timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (try (with-open [_ (java.net.Socket. "127.0.0.1" (int p))] true)
               (catch Exception _ false))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn- status-event [state text]
  ;; :runtime identifies the runtime process. When it changes, the page knows
  ;; everything it evaluated is gone. A dropped and resumed connection to the
  ;; same runtime keeps the same id.
  {:type "status" :state state :text text :runtime (:id @runtime)})

(defn- status! [state text]
  (broadcast! (status-event state text)))

(defn- current? [proc]
  (= proc (:proc @runtime)))

(defn- watch-exit! [proc started-at]
  (future
    (let [{:keys [exit]} @proc]
      ;; A restart replaces the process first, so only an unexpected exit
      ;; gets here with this process still current.
      (when (current? proc)
        (status! "restarting" (str "The runtime stopped (exit " exit "). Starting a fresh one…"))
        (clear-view!)
        (when (< (- (System/currentTimeMillis) started-at) 3000)
          (Thread/sleep 2000))
        ;; Check again: Restart may have been pressed during the sleep.
        (when-let [rt (locking lifecycle
                        (when (current? proc)
                          (spawn-runtime!)))]
          (await-ready! rt))))))

(defn- spawn-runtime!
  "Starts a runtime process and makes it current. Call with lifecycle held."
  []
  (let [rt-port (free-port)
        id (str (random-uuid))
        proc (process/process
              {:dir       "."
               ;; Never close (:in proc). The runtime exits when its stdin
               ;; closes, which happens whenever this process dies, even
               ;; from kill -9.
               :in        :pipe
               :out       :pipe
               :err       :pipe
               :extra-env {"WORKSHOP_TOKEN"      token
                           "WORKSHOP_RUNTIME_ID" id
                           "WORKSHOP_EDITOR_URL" (str "http://127.0.0.1:" @port)}}
              "bb" runtime-heap "-m" "workshop.runtime" (str rt-port))]
    (reset! runtime {:proc proc :port rt-port :ready false :id id})
    (pump! (:out proc))
    (pump! (:err proc))
    (watch-exit! proc (System/currentTimeMillis))
    @runtime))

(defn- await-ready!
  "Waits for rt to listen, then marks it ready, unless it has been replaced."
  [rt]
  (let [up? (wait-for-port (:port rt) 15000)
        [_ now] (swap-vals! runtime #(if (and up? (= (:id %) (:id rt)))
                                        (assoc % :ready true)
                                        %))]
    (when (= (:id now) (:id rt))
      (if up?
        (status! "ready" "Runtime ready")
        (status! "error" "The runtime did not start. Check the terminal running bb editor.")))))

(defn start-runtime! []
  (await-ready! (locking lifecycle (spawn-runtime!))))

(defn- kill-runtime!
  "Stops the current runtime. Call with lifecycle held."
  []
  (let [{old :proc} @runtime]
    ;; Clear it first, so watch-exit! does not take this for a crash.
    (reset! runtime nil)
    (some-> old process/destroy-tree)))

(defn restart-runtime! []
  (let [rt (locking lifecycle
             (status! "restarting" "Restarting the runtime…")
             (kill-runtime!)
             (clear-view!)
             (spawn-runtime!))]
    (await-ready! rt)))

(defn- to-runtime!
  "Passes a request from the page on to the runtime, at path."
  [path body]
  (let [{rt-port :port ready :ready} @runtime]
    (if-not ready
      {:error "The runtime is starting. Try again in a moment."}
      (try
        (-> (http/post (str "http://127.0.0.1:" rt-port path)
                       {:headers {"Content-Type"     "application/json"
                                  "X-Workshop-Token" token}
                        :body    body
                        :timeout 15000})
            :body
            (json/parse-string true))
        (catch Exception e
          {:error (str "Lost contact with the runtime: " (ex-message e))})))))

;; ---------------------------------------------------------------------------
;; Files: only .clj and .edn files under src/ can be read or written

;; The editor's own code and the facilitator's data fetcher are not shown,
;; and cannot be overwritten from the page.
(def ^:private hidden-files
  #{"src/workshop/editor.clj" "src/workshop/runtime.clj" "src/workshop/fetch.clj"})

(defn- hidden? [rel]
  ;; macOS and Windows file names ignore case: Editor.clj is editor.clj.
  (contains? hidden-files (str/lower-case rel)))

(defn- rel-path [f]
  ;; Always forward slashes, so paths match on Windows too.
  (str/replace (str (fs/relativize (fs/absolutize ".") (fs/absolutize f))) "\\" "/"))

(defn- src-file [path]
  (when (and (string? path) (re-find #"\.(clj|edn)$" path))
    (let [f (fs/normalize (fs/absolutize path))
          root (fs/normalize (fs/absolutize src-dir))]
      (when (and (fs/starts-with? f root)
                 (not (hidden? (rel-path f))))
        f))))

;; The browser sends back the version it last read or saved. If the file has
;; changed since, another editor wrote it, and the save is refused rather
;; than overwriting that.
(defn- version [text]
  (-> (java.security.MessageDigest/getInstance "SHA-1")
      (.digest (.getBytes ^String text "UTF-8"))
      (->> (BigInteger. 1))
      (.toString 16)))

(defn- read-src [f]
  (if (fs/exists? f) (slurp (fs/file f)) ""))

(defn- list-files []
  (->> (fs/glob src-dir "**.clj")
       (map rel-path)
       (remove hidden?)
       sort))

;; ---------------------------------------------------------------------------
;; HTTP

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"})

(defn- static [uri]
  (let [rel (if (= "/" uri) "index.html" (subs uri 1))
        f (fs/normalize (fs/absolutize (fs/path public-dir rel)))]
    (when (and (fs/starts-with? f (fs/normalize (fs/absolutize public-dir)))
               (fs/regular-file? f))
      {:status  200
       :headers {"Content-Type"  (get content-types (fs/extension f) "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body    (fs/file f)})))

(defn- json-response [x]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string x)})

(defn- query-param [query-string k]
  (some (fn [pair]
          (let [[pk v] (str/split pair #"=" 2)]
            (when (= k pk) (java.net.URLDecoder/decode (or v "") "UTF-8"))))
        (some-> query-string (str/split #"&"))))

(defn- local-host? [headers]
  ;; Refuse requests whose Host is not us (DNS rebinding).
  (contains? #{(str "localhost:" @port) (str "127.0.0.1:" @port)} (get headers "host")))

(defn- api [{:keys [request-method uri query-string headers body] :as req}]
  (let [path (query-param query-string "path")]
    (case [request-method uri]
      [:get "/api/token"]
      ;; Same-origin only: other sites cannot read this response.
      (json-response {:token token})

      [:get "/api/events"]
      (server/as-channel req
                         {:on-open  (fn [ch]
                                      (server/send! ch {:status  200
                                                        :headers {"Content-Type"  "text/event-stream"
                                                                  "Cache-Control" "no-store"}}
                                                    false)
                                      (swap! clients conj ch)
                                      (send-event! ch (if (:ready @runtime)
                                                        (status-event "ready" "Runtime ready")
                                                        (status-event "restarting" "Starting the runtime…")))
                                      (when-let [html @last-view]
                                        (send-event! ch {:type "view" :html html})))
                          :on-close (fn [ch _] (swap! clients disj ch))})

      [:get "/api/files"]
      (json-response {:files (list-files)})

      [:get "/api/file"]
      (if-let [f (src-file path)]
        (let [text (read-src f)]
          (json-response {:path path :text text :version (version text)}))
        {:status 400 :body "not a file under src/"})

      [:post "/api/file"]
      (if-let [f (src-file path)]
        (let [on-disk (read-src f)]
          (if (not= (version on-disk) (get headers "x-workshop-version"))
            (assoc (json-response {:text on-disk :version (version on-disk)}) :status 409)
            (let [text (slurp body)]
              (fs/create-dirs (fs/parent f))
              (spit (fs/file f) text)
              (json-response {:saved path :version (version text)}))))
        {:status 400 :body "not a file under src/"})

      [:post "/api/eval"]
      (json-response (to-runtime! "/eval" (slurp body)))

      [:post "/api/complete"]
      (json-response (to-runtime! "/complete" (slurp body)))

      [:post "/api/restart"]
      (do (future (restart-runtime!))
          (json-response {:restarting true}))

      [:post "/runtime/view"]
      (do (reset! pending-view {:html       (slurp body)
                                :runtime-id (get headers "x-workshop-runtime")})
          {:status 204})

      nil)))

(defn- has-token? [{:keys [headers query-string]}]
  ;; EventSource cannot send headers, so /api/events passes it in the URL.
  (= token (or (get headers "x-workshop-token")
               (query-param query-string "token"))))

(defn- handler [{:keys [uri headers] :as req}]
  (cond
    (not (local-host? headers))
    {:status 403 :body "forbidden"}

    (str/starts-with? uri "/api/")
    (if (or (= "/api/token" uri) (has-token? req))
      (or (api req) {:status 404 :body "not found"})
      {:status 403 :body "forbidden"})

    (= "/runtime/view" uri)
    (if (has-token? req)
      (api req)
      {:status 403 :body "forbidden"})

    :else
    (or (static uri) {:status 404 :body "not found"})))

(defn- exit-with [message]
  (println message)
  (System/exit 1))

(defn- parse-port [args]
  (let [{:keys [opts args]}
        (try
          (cli/parse-args args {:spec {:port {:coerce :long}} :restrict true})
          (catch Exception e
            (exit-with (str (ex-message e) "\nUsage: bb editor [--port 4000]"))))
        p (:port opts default-port)]
    (cond
      (seq args)
      (exit-with (str "Unexpected argument: " (first args) "\nUsage: bb editor [--port 4000]"))

      (not (and (int? p) (<= 1024 p 65535)))
      (exit-with (str "--port must be a number from 1024 to 65535, not " p))

      :else p)))

(defn -main [& args]
  (reset! port (parse-port args))
  (try
    (server/run-server handler {:ip "127.0.0.1" :port @port :thread 16 :max-line 65536})
    (catch java.net.BindException e
      (exit-with
       (if (str/includes? (str (ex-message e)) "in use")
         (str "Port " @port " is already in use. Is bb editor already running in another terminal?\n"
              "Stop that one, or use another port: bb editor --port 4000")
         (str "Cannot listen on port " @port ": " (ex-message e) "\n"
              "Try another port: bb editor --port 4000")))))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(locking lifecycle (kill-runtime!))))
  (println (str "Workshop editor: http://localhost:" @port "  (Ctrl+C to stop)"))
  (start-runtime!)
  @(promise))
