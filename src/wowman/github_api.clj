(ns wowman.github-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [http :as http]
    [utils :as utils :refer [if-let*]]
    [specs :as sp]]))

(defn-spec pad coll?
  "given a collection, ensures there are at least pad-amt items in result. pad value is nil"
  [lst coll?, pad-amt int?]
  (let [lst-size (count lst)]
    (if (< lst-size pad-amt)
      (into lst (repeat (- pad-amt lst-size) nil))
      lst)))

(defn-spec parse-user-addon (s/or :ok ::sp/addon-summary, :error nil?)
  [uin string?]
  (if-let* [;; if *all* of these conditions succeed (non-nil), return a catalog entry
            obj (some-> uin utils/unmangle-https-url java.net.URL.)
            path (when-not (empty? (.getPath obj)) (.getPath obj))
            [owner repo] (-> path (subs 1) (clojure.string/split #"/") (pad 2))
            releases-url (when (and owner repo)
                           (format "https://api.github.com/repos/%s/%s/releases" owner repo))
            release-list (some-> releases-url http/download utils/from-json)
            most-recent (first release-list)
            download-count (->> release-list (map :assets) flatten (map :download_count) (apply +))]

           {:uri (format "https://github.com/%s/%s" owner repo)
            :updated-date (-> most-recent :assets first :updated_at)
            :source "github"
            :source-id (format "%s/%s" owner repo)
            :label repo
            :name (slugify repo "")
            :download-count download-count
            :category-list []}

           ;; 'something' failed to parse :(
           ;; would love a way to pass back a more specific error
           nil))

;;

(st/instrument)
