(ns wowman.github-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(defn-spec parse-user-addon-url (s/or :ok ::sp/addon-summary, :error nil?)
  [uin ::sp/uri]
  (try
    (let [obj (java.net.URL. uin)
          ;; <obj> => /owner/repo => owner/repo ["owner" "repo"]
          [owner repo] (-> obj .getPath (subs 1) (clojure.string/split #"/"))
          releases-url (format "https://api.github.com/repos/%s/%s/releases" owner repo)
          release-list (-> releases-url http/download utils/from-json)
          most-recent (first release-list)
          ;; probably not a great idea to just grab the first thing we find, but we'll see
          first-asset (-> most-recent :assets first)

          download-count (->> release-list (map :assets) flatten (map :download_count) (apply +))]
      {:uri (str obj)
       :updated-date (-> first-asset :updated_at)
       :source "github"
       :source-id (format "%s/%s" owner repo)
       :label repo
       :name (slugify repo "")
       ;;:download-count (-> first-asset :download_count)
       :download-count download-count
       :category-list []})
    (catch Exception e
      (error e "unhandled exception attempting to fetch URL:" uin))))

(st/instrument)
