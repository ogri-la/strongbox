(ns wowman.github-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [http :as http]
    [utils :as utils :refer [pad if-let* nilable]]
    [specs :as sp]]))

(defn-spec release-url ::sp/uri
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id release-url http/download utils/from-json))

;;

(def classic-regex #"^.+[\-_\.]?(classic)[\.-_]?.+$")

(def supported-zip-mimes #{"application/zip"  "application/x-zip-compressed"})

(defn group-assets
  [latest-release]
  (let [asset-list (:assets latest-release)

        ;; ignore assets whose :content_type is *not* a known zip type
        asset-list (filter #(-> % :content_type vector set (some supported-zip-mimes)) asset-list)

        ;; ignore any assets that are not completely uploaded
        ;; https://developer.github.com/v3/repos/releases/#response-for-upstream-failure
        asset-list (filter #(-> % :state (= "uploaded")) asset-list)

        grouper (fn [asset]
                  (let [classic? (nilable
                                  (utils/named-regex-groups classic-regex [:classic] (:name asset)))
                        version (:name latest-release)] ;; "v2.10.0"
                    (if classic?
                      ;; because we're pulling the version from the release rather than the asset,
                      ;; tack on '-classic' if the asset looks like a classic release, otherwise version checks fail
                      (merge asset {:game-track "classic" :version (str version "-classic")})
                      (merge asset {:game-track "retail" :version version}))))
        asset-list (map grouper asset-list)]
    (group-by :game-track asset-list)))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary game-track ::sp/game-track]
  ;; download latest releases
  (let [release-list (download-releases (:source-id addon-summary))
        latest-release (first release-list)
        asset (-> latest-release group-assets (get game-track) first)]
    (if-not asset
      (warn (format "no '%s' release available for '%s' on github" game-track (:name addon-summary)))
      (merge addon-summary
             {:download-uri (:browser_download_url asset)
              :version (:version asset)}))))

(defn-spec parse-user-addon (s/or :ok ::sp/addon-summary, :error nil?)
  [uin string?]
  (if-let* [;; if *all* of these conditions succeed (non-nil), return a catalog entry
            obj (some-> uin utils/unmangle-https-url java.net.URL.)
            path (when-not (empty? (.getPath obj)) (.getPath obj))
            [owner repo] (-> path (subs 1) (clojure.string/split #"/") (pad 2))
            source-id (when (and owner repo)
                        (format "%s/%s" owner repo))
            release-list (download-releases source-id)
            latest-release (first release-list) ;; releases must be used
            _ (-> latest-release :assets nilable) ;; releases must be using uploaded assets
            download-count (->> release-list (map :assets) flatten (map :download_count) (apply +))]

           {:uri (str "https://github.com/" source-id)
            :updated-date (-> latest-release :published_at)
            :source "github"
            :source-id source-id
            :label repo
            :name (slugify repo "")
            :download-count download-count
            :category-list []}

           ;; 'something' failed to parse :(
           ;; would love a way to pass back a more specific error
           nil))

;;

(st/instrument)
