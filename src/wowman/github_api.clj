(ns wowman.github-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [http :as http]
    [toc :as toc]
    [utils :as utils :refer [pad if-let* nilable]]
    [specs :as sp]]))

(defn-spec releases-url ::sp/uri
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id releases-url http/download utils/from-json))

(defn-spec contents-url ::sp/uri
  [source-id string?]
  (format "https://api.github.com/repos/%s/contents" source-id))

(defn-spec download-root-listing (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id contents-url http/download utils/from-json))

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

;;

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)]
           (some-> toc-file :download_url http/download toc/-read-toc-file)
           (warn (format "failed to find/download/parse remote github '.toc' file for '%s'" source-id))))

(defn-spec parse-remote-toc-file (s/or :ok ::sp/game-track-list, :empty nil?, :error nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting the remote .toc file contents"
  [toc-data (s/nilable map?)]
  (when toc-data
    (->> (-> toc-data
             (select-keys [:interface :#interface])
             vals)
         (map utils/interface-version-to-game-version)
         (map utils/game-version-to-game-track)
         set
         vec
         utils/nilable)))

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

            ;; disabled until tests are passing
            ;;game-track-list (parse-remote-toc-file (find-remote-toc-file source-id))

            download-count (->> release-list (map :assets) flatten (map :download_count) (apply +))]

           {:uri (str "https://github.com/" source-id)
            :updated-date (-> latest-release :published_at)
            :source "github"
            :source-id source-id
            :label repo
            :name (slugify repo "")
            :download-count download-count
            ;;:game-track-list game-track-list
            :category-list []}

           ;; 'something' failed to parse :(
           ;; would love a way to pass back a more specific error
           nil))

;;

(st/instrument)
