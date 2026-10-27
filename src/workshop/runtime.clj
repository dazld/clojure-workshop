(ns workshop.runtime
  "The process that runs student code. workshop.editor starts it, sends it
  code over HTTP, and restarts it when it gets stuck. Everything printed here,
  from any thread, goes to stdout, which the editor streams to the Output pane.
  Values passed to tap> go to the View pane."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [hiccup2.core :as h]
            [org.httpkit.server :as server]))

(def eval-timeout-ms 10000)
(def max-printed-chars 20000)

(def ^:private token (System/getenv "WORKSHOP_TOKEN"))
(def ^:private editor-url (System/getenv "WORKSHOP_EDITOR_URL"))
(def ^:private runtime-id (System/getenv "WORKSHOP_RUNTIME_ID"))

(defn- truncate [s]
  (if (> (count s) max-printed-chars)
    (str (subs s 0 max-printed-chars) " …")
    s))

(defmacro ^:private bounded-printing [& body]
  ;; (range) and deeply nested data print as (0 1 2 ... 49 ...) instead of
  ;; running forever.
  `(binding [*print-length* 50
             *print-level*  8]
     ~@body))

(defn- realize!
  ;; Realizes as much of v as printing will show. pr-str sends *out* to the
  ;; string it builds, so (map #(doto % prn) xs) realized there would print
  ;; into the value. Realized here, its printing goes to the Output pane.
  ([v] (realize! v 0))
  ([v level]
   (when (and (coll? v) (< level *print-level*))
     (doseq [x (take *print-length* v)]
       (if (map-entry? x)
         (do (realize! (key x) (inc level))
             (realize! (val x) (inc level)))
         (realize! x (inc level)))))))

(defn- printed [v]
  (bounded-printing
   (realize! v)
   (let [short (truncate (pr-str v))]
     (cond-> {:value short}
       (> (count short) 60) (assoc :pretty (truncate (with-out-str (pp/pprint v))))))))

(defn- error-info [^Throwable e]
  (let [data (ex-data e)]
    (cond-> {:error (or (ex-message e) (.getName (class e)))
             :class (.getName (class e))}
      (:line data)   (assoc :line (:line data))
      (:column data) (assoc :column (:column data)))))

(defn- file-ns [{:keys [ns ns-form]}]
  ;; First use of a file: create its namespace with the file's own (ns ...)
  ;; form, so requires and aliases exist.
  (let [ns-sym (some-> ns symbol)]
    (when (and ns-sym ns-form (not (find-ns ns-sym)))
      (load-string ns-form))
    (or (some-> ns-sym find-ns) (the-ns 'user))))

(defn- eval-code [{:keys [code] :as request}]
  (binding [*ns* (file-ns request)]
    (load-string code)))

;; ---------------------------------------------------------------------------
;; Completion: names that are in scope in the file's namespace right now

(def ^:private max-completions 60)

;; Special forms are not vars, so ns-map does not have them.
(def ^:private special-forms
  '[def if do let quote var fn loop recur throw try catch finally new set!])

(defn- candidate [text v]
  (let [{:keys [arglists doc macro]} (some-> v meta)]
    (cond-> {:text text}
      arglists (assoc :arglists (str/join " " (map pr-str arglists)))
      doc      (assoc :doc (first (str/split-lines (str/trim doc))))
      macro    (assoc :macro true))))

(defn- completions [{:keys [prefix] :as request}]
  (let [n (file-ns request)
        aliases (ns-aliases n)
        found (if-let [[_ qualifier part] (re-matches #"(.+)/(.*)" prefix)]
                ;; str/up: the public names of the namespace str stands for
                (when-let [target (or (get aliases (symbol qualifier))
                                      (find-ns (symbol qualifier)))]
                  (for [[sym v] (ns-publics target)
                        :when (str/starts-with? (name sym) part)]
                    (candidate (str qualifier "/" sym) v)))
                (concat
                 (for [[sym v] (ns-map n)
                       :when (and (var? v) (str/starts-with? (name sym) prefix))]
                   (candidate (name sym) v))
                 (for [sym special-forms
                       :when (str/starts-with? (name sym) prefix)]
                   {:text (name sym) :doc "special form"})
                 (for [[alias _] aliases
                       :when (str/starts-with? (name alias) prefix)]
                   {:text (str alias "/") :doc "namespace alias"})))]
    {:completions (->> found
                       (sort-by (juxt (comp count :text) :text))
                       (take max-completions))}))

(defn- run [request]
  ;; Each evaluation gets its own thread, so a stuck one never blocks the next.
  ;; future-cancel interrupts sleeps and blocked derefs. A busy loop like
  ;; (count (range)) ignores it; the editor's Restart button handles that.
  (let [f (future
            (try
              (printed (eval-code request))
              (catch Throwable e
                (error-info e))))
        result (deref f eval-timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do (future-cancel f)
          {:timeout true :seconds (quot eval-timeout-ms 1000)})
      result)))

;; ---------------------------------------------------------------------------
;; tap> goes to the View pane

;; Only vectors that start with a real HTML or SVG tag render as HTML.
;; Other data, like [:result {:count 3}], is shown as data.
(def ^:private html-tags
  #{:a :abbr :article :aside :b :blockquote :br :button :caption :circle :code
    :dd :defs :details :div :dl :dt :ellipse :em :figcaption :figure :footer :g
    :h1 :h2 :h3 :h4 :h5 :h6 :header :hr :i :img :kbd :label :li :line
    :linearGradient :main :mark :nav :ol :p :path :polygon :polyline :pre
    :radialGradient :rect :section :small :span :stop :strong :sub :summary :sup
    :svg :table :tbody :td :text :tfoot :th :thead :tr :tspan :u :ul})

(defn- html-tag? [k]
  ;; Hiccup allows :div.class#id
  (and (keyword? k)
       (contains? html-tags (keyword (first (str/split (name k) #"[.#]"))))))

(defn- ->html [x]
  (if (and (vector? x) (html-tag? (first x)))
    (str (h/html x))
    (str (h/html [:pre.tapped (bounded-printing (with-out-str (pp/pprint x)))]))))

(defn- send-to-view [x]
  (try
    (http/post (str editor-url "/runtime/view")
               {:headers {"Content-Type"       "text/html; charset=utf-8"
                          "X-Workshop-Token"   token
                          "X-Workshop-Runtime" runtime-id}
                :body    (->html x)
                :timeout 2000})
    (catch Throwable e
      (binding [*out* *err*]
        (println "tap> could not be shown:" (ex-message e))))))

;; ---------------------------------------------------------------------------

(defn- handler [{:keys [request-method uri headers body server-port]}]
  (cond
    ;; This server runs any code it is sent, so it needs the editor's token,
    ;; and refuses everything when started without one.
    (or (nil? token)
        (not= token (get headers "x-workshop-token"))
        (not (contains? #{(str "127.0.0.1:" server-port) (str "localhost:" server-port)}
                        (get headers "host"))))
    {:status 403 :body "forbidden"}

    (and (= :post request-method) (= "/eval" uri))
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string (run (json/parse-string (slurp body) true)))}

    (and (= :post request-method) (= "/complete" uri))
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string
               (try (completions (json/parse-string (slurp body) true))
                    (catch Throwable e {:completions [] :error (ex-message e)})))}

    (= "/ping" uri)
    {:status 200 :body "ok"}

    :else
    {:status 404 :body "not found"}))

(defn- exit-with-editor! []
  ;; The editor holds our stdin open. When it goes away, for any reason, the
  ;; pipe closes and we exit too, taking any running student loops with us.
  (future
    (while (not= -1 (.read System/in)))
    (System/exit 0)))

(defn -main [port]
  ;; Only when the editor started us. Run by hand, stdin may be closed.
  (when token (exit-with-editor!))
  (when editor-url (add-tap send-to-view))
  (server/run-server handler {:ip "127.0.0.1" :port (parse-long port) :max-line 65536})
  @(promise))
