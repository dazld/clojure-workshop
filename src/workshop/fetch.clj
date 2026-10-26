(ns workshop.fetch
  "Builds data/repos.json. Facilitator tool, not part of the exercises."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def orgs ["clojure" "babashka"])

(defn- headers []
  (if-let [token (System/getenv "GITHUB_TOKEN")]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn- fetch-page [org n]
  (-> (http/get (str "https://api.github.com/orgs/" org "/repos")
                {:headers      (headers)
                 :query-params {"per_page" "100" "page" (str n)}})
      :body
      (json/parse-string true)))

(defn- fetch-org [org]
  ;; A loop, not (map fetch-page (range ...)): range is chunked, so the lazy
  ;; version would request all pages up front and burn the rate limit.
  (loop [n 1 repos []]
    (let [page (fetch-page org n)]
      (if (seq page)
        (recur (inc n) (into repos page))
        repos))))

(defn- slim [repo]
  (-> repo
      (select-keys [:name :full_name :description :language :stargazers_count
                    :forks_count :open_issues_count :archived :created_at
                    :pushed_at :topics])
      (assoc :owner   (get-in repo [:owner :login])
             :license (get-in repo [:license :spdx_id]))))

(defn -main [& _]
  (let [repos (->> orgs (mapcat fetch-org) (map slim))]
    (spit "data/repos.json" (json/generate-string repos {:pretty true}))
    (println "Wrote" (count repos) "repos:" (frequencies (map :owner repos)))))
