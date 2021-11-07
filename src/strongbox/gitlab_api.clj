(ns strongbox.gitlab-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(defn api-url
  [source-id]
  (let [encoded-source-id (-> source-id clojure.string/lower-case (java.net.URLEncoder/encode "UTF-8"))]
    (str "https://gitlab.com/api/v4/projects/" encoded-source-id)))

(defn parse-release
  [release]
  (let [supported-link-types #{"package" "other"} ;; the others are 'runbook' and 'image'
        link-list (->> release
                       :assets
                       :links
                       (filter (juxt supported-link-types :link-type)))
        link (first link-list)]
    {;; "The physical location of the asset can change at any time and the direct link remains unchanged"
     ;; - https://docs.gitlab.com/ee/user/project/releases/index.html#permanent-links-to-release-assets
     :download-url (:direct_asset_url link)
     ;;:download-url (:url link)
     :version (:tag_name release)
     :game-track :retail}))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  (let [url (-> addon-summary :source-id api-url (str "/releases"))
        result (-> url http/download-with-backoff utils/from-json)]
    (mapv parse-release result)))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (let [bits (take 3 (-> url java.net.URL. .getPath
                         (clojure.string/split #"/-")
                         first
                         (utils/trim "/")
                         (clojure.string/split #"/")))]
    (clojure.string/join "/" bits)))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  (let [url (api-url source-id)
        result (-> url http/download-with-backoff utils/from-json)]

    {:url (:web_url result)
     :created-date (:created_at result)
     :updated-date (:last_activity_at result)
     :source "gitlab"
     ;; prefer the github-like "owner/repo" id over the number id.
     ;; we have a choice so go with the more consistent and humane option.
     :source-id (:path_with_namespace result)
     :label (:name result)
     :name (:path result)
     ;; not available to the public. must be present, must be >= 0 :(
     ;; - https://docs.gitlab.com/ee/api/project_statistics.html
     :download-count 0
     :game-track-list []
     :tag-list []}))
