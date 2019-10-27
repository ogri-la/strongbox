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

(defn-spec releases-url ::sp/uri
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id releases-url http/download utils/from-json))

;;

;; matches the word 'classic' bracketed by common delimiters in an addon's release name
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

        updater (fn [asset]
                  (let [classic? (nilable
                                  (utils/named-regex-groups classic-regex [:classic] (:name asset)))
                        version (:name latest-release)] ;; "v2.10.0"
                    (if classic?
                      ;; because we're pulling the version from the release rather than the asset,
                      ;; tack on '-classic' if the asset looks like a classic release (else version checks fail)
                      (merge asset {:game-track "classic" :version (str version "-classic")})
                      (merge asset {:game-track "retail" :version version}))))
        asset-list (map updater asset-list)]
    (group-by :game-track asset-list)))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. 
  one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary game-track ::sp/game-track]
  (let [release-list (download-releases (:source-id addon-summary))
        latest-release (first release-list)
        asset (-> latest-release group-assets (get game-track) first)]
    (if-not asset
      (warn (format "no '%s' release available for '%s' on github" game-track (:name addon-summary)))
      (merge addon-summary
             {:download-uri (:browser_download_url asset)
              :version (:version asset)}))))

(defn-spec extract-source-id (s/or :ok string?, :error nil?)
  [url ::sp/uri]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec parse-user-string (s/or :ok ::sp/addon-summary, :error nil?)
  [uin string?]
  (if-let* [;; if *all* of these conditions succeed (non-nil), return a catalog entry
            obj (some-> uin utils/unmangle-https-url java.net.URL.)
            path (when-not (empty? (.getPath obj)) (.getPath obj))

            ;; values here are tentative because user URL may resolve to a different URL
            [-owner -repo] (-> path (subs 1) (clojure.string/split #"/") (pad 2))
            -source-id (when (and -owner -repo)
                         (format "%s/%s" -owner -repo))
            release-list (download-releases -source-id)
            latest-release (first release-list) ;; releases must be used
            _ (-> latest-release :assets nilable) ;; releases must be using uploaded assets

            ;; these are the values we want to be using
            source-id (-> latest-release :html_url extract-source-id)
            [owner repo] (clojure.string/split source-id #"/")

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
