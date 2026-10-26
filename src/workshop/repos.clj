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
  ;;    Hint: count
  (count repos)

  ;; 2. Look at the first repo. What keys does it have?
  ;;    Hint: first, keys

  ;; 3. The names of the first ten repos
  ;;    Hint: map, take, and remember keywords are functions

  ;; 4. How many repos does each org (:owner) have?
  ;;    Hint: frequencies

  ;; 5. The names of the archived repos
  ;;    Hint: filter. What does (:archived repo) return?

  ;; 6. The ten most-starred repos, as [name stars] pairs
  ;;    Hint: sort-by takes an optional comparator. Look up juxt.

  ;; 7. Repos per language. Which language is most common?
  ;;    Hint: frequencies, sort-by. A map entry works with key and val.

  ;; 8. Total stars per language
  ;;    Hint: group-by, then reduce over each group

  ;; 9. How many repos were created each year? (:created_at is "2012-03-21T...")
  ;;    Hint: subs, frequencies. (into (sorted-map) ...) sorts a map.

  ;; 10. Print the top five as a table. Try clojure.pprint/print-table.
  ;;     It is already required as pp.

  ;; 11. Write a function (top-repos repos {:lang "Clojure" :top 5}) that
  ;;     filters by language when :lang is given, and returns the :top most
  ;;     starred. -main below already calls it, so run it from a terminal:
  ;;       bb repos --lang Clojure --top 5
  (top-repos repos {:lang "Clojure" :top 5})
  (top-repos repos {:top 3}))

(defn top-repos [repos {:keys [lang top]}]
  ;; Exercise 11: filter by lang when it is given, sort by stars, take top.
  (take top repos))

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
