(ns workshop.repos
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [org.httpkit.server :as server]))

;; The task: answer questions about the GitHub repos of the clojure and
;; babashka organisations, then turn the answers into a script.
;;
;; Work through the (comment ...) block at the REPL, one exercise at a time.
;; When an answer works, promote it into a defn below the block.

(defn load-repos []
  ;; true turns JSON keys into keywords: "name" becomes :name
  (json/parse-string (slurp "data/repos.json") true))

(def repos (load-repos))

(comment

  ;; 1. How many repos are there?
  (count repos)

  ;; 2. Look at the first repo. What keys does it have?
  (first repos)
  (keys (first repos))

  ;; 3. The names of the first ten repos
  (->> repos (map :name) (take 10))

  ;; 4. How many repos does each org (:owner) have?
  (frequencies (map :owner repos))

  ;; 5. The names of the archived repos
  (->> repos (filter :archived) (map :name))

  ;; 6. The ten most-starred repos, as [name stars] pairs
  (->> repos
       (sort-by :stargazers_count >)
       (take 10)
       (map (juxt :name :stargazers_count)))

  ;; 7. Repos per language. Which language is most common?
  (->> repos
       (map :language)
       frequencies
       (sort-by val >))

  ;; 8. Total stars per language
  (->> repos
       (group-by :language)
       (map (fn [[lang rs]] [lang (reduce + (map :stargazers_count rs))]))
       (sort-by second >))

  ;; 9. How many repos were created each year? (:created_at is "2012-03-21T...")
  (->> repos
       (map #(subs (:created_at %) 0 4))
       frequencies
       (into (sorted-map)))

  ;; 10. Print the top five as a table. Try clojure.pprint/print-table.
  (pp/print-table [:name :language :stargazers_count]
                  (->> repos (sort-by :stargazers_count >) (take 5)))

  ;; 11. Write a function (top-repos repos {:lang "Clojure" :top 5}) that
  ;;     filters by language when :lang is given, and returns the :top most
  ;;     starred. -main below already calls it, so run it from a terminal:
  ;;       bb repos --lang Clojure --top 5
  (top-repos repos {:lang "Clojure" :top 5})
  (top-repos repos {:top 3}))

(defn top-repos [repos {:keys [lang top]}]
  (->> repos
       (filter #(or (nil? lang) (= lang (:language %))))
       (sort-by :stargazers_count >)
       (take top)))

(def cli-spec
  {:lang {:desc "Only repos in this language"}
   :top  {:desc "How many to show" :coerce :long :default 10}})

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (pp/print-table [:full_name :language :stargazers_count]
                    (top-repos repos opts))))

;; ---------------------------------------------------------------------------
;; Stretch goals
;;
;; A. Live data: replace load-repos with a call to the GitHub API using
;;    babashka.http-client. Look at workshop.fetch for how. The API allows
;;    60 requests an hour per IP without a token, and this room shares one IP.
;;
;; B. Serve the report as HTML. Start it with (serve) at the REPL, or
;;    `bb serve`, and open http://localhost:8080. Change the page, then
;;    re-evaluate page: no restart needed, because the handler goes through
;;    the var #'handler.
;;
;; C. Add a --org option, or a second task to bb.edn.

(defn page [repos]
  (str
   (h/html
    [:html
     [:head [:title "Repos"]]
     [:body
      [:h1 "Most-starred repos"]
      [:table
       [:tr [:th "Repo"] [:th "Language"] [:th "Stars"]]
       (for [r (top-repos repos {:top 20})]
         [:tr
          [:td (:full_name r)]
          [:td (:language r)]
          [:td (:stargazers_count r)]])]]])))

(defn handler [_request]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (page repos)})

(defonce stop-server (atom nil))

(defn serve [& _]
  (when-let [stop @stop-server] (stop))
  (reset! stop-server (server/run-server #'handler {:port 8080}))
  (println "Serving on http://localhost:8080"))
