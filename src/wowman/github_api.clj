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

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)]
           (some-> toc-file :download_url http/download toc/-read-toc-file)
           (warn (format "failed to find/download/parse remote github '.toc' file for '%s'" source-id))))

(defn-spec -find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :empty nil?, :error nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting .toc file contents"
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

(defn-spec find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :error nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting .toc file contents"
  [source-id string?]
  (-> source-id find-remote-toc-file -find-gametracks-toc-data))

;; matches the word 'classic' bracketed by common delimiters in an addon's release name
(def classic-regex #"^.+[\-_\.]?(classic)[\.-_]?.+$")
;; (utils/named-regex-groups classic-regex [:classic] string)))

(def supported-zip-mimes #{"application/zip"  "application/x-zip-compressed"})

(defn classic-asset?
  [string]
  (-> string .toLowerCase (clojure.string/index-of "classic") nil? not))

(defn group-assets
  [addon-summary latest-release]
  (let [asset-list (:assets latest-release)

        ;; ignore assets whose :content_type is *not* a known zip type
        asset-list (filter #(-> % :content_type vector set (some supported-zip-mimes)) asset-list)

        ;; ignore any assets that are not completely uploaded
        ;; https://developer.github.com/v3/repos/releases/#response-for-upstream-failure
        asset-list (filter #(-> % :state (= "uploaded")) asset-list)
        single-asset? (-> asset-list count (= 1))
        many-assets? (-> asset-list count (> 1))

        ;; derived from the .toc file, if found
        ;; these serve as hints 
        known-game-tracks (-> addon-summary :game-track-list (or []))
        no-known-game-tracks? (-> known-game-tracks count (= 0))
        single-game-track? (-> known-game-tracks count (= 1))
        many-game-tracks? (-> known-game-tracks count (> 1))

        track-version (fn [version gametrack]
                        (if (= gametrack "classic")
                          (str version "-classic")
                          version))

        updater (fn [asset]
                  ;; "returns a list of updated versions of this asset. if the asset supports multiple game tracks, two versions are returned"
                  (let [version (:name latest-release) ;; "v2.10.0"
                        ;; todo: change this to look for 'classic' or 'retail'
                        ;; so, "FooAddon-retail" or "BarAddon-classic"
                        ;; no known cases but it would be forward proof

                        classic? (classic-asset? (:name asset))

                        update-list ;; is either a map or a list of maps
                        (cond
                          ;; game track present in file name, prefer that over known-game-tracks
                          classic? {:game-track "classic" :version (track-version version "classic") :-mo :classic-in-name}

                          ;; single asset, no game track present in file name, no known game tracks. default to :retail
                          (and single-asset? no-known-game-tracks?) {:game-track "retail" :version version :-mo :sa--ngt}

                          ;; single asset, no game track present in file name, single known game track. use that
                          (and single-asset? single-game-track?) {:game-track (first known-game-tracks)
                                                                  :version (track-version version (first known-game-tracks))  :-mo :sa--1gt}

                          ;; single asset, no game track present in file name, multiple known game tracks. assume all game tracks supported
                          (and single-asset? many-game-tracks?) [{:game-track "classic" :version (track-version version "classic") :-mo :sa--Ngt}
                                                                 {:game-track "retail" :version version :-mo :sa--Ngt}]

                          ;; multiple assets, no game track present in file name, no known game tracks. default to :retail
                          ;; ambiguous case, other assets may have game track in their file name
                          (and many-assets? no-known-game-tracks?) {:game-track "retail" :version version :-mo :ma--ngt}

                          ;; multiple assets, no game track present in file name, single known game track. use that.
                          ;; ambiguous case, other assets may have game track in their file name
                          ;; this or other assets may be variations of the 'main' addon, like '-nolib' ?
                          (and many-assets? single-game-track?) {:game-track (first known-game-tracks)
                                                                 :version (track-version version (first known-game-tracks)) :-mo :ma--1gt}

                          ;; multiple assets, no game track present in file name, multiple known game tracks. default to :retail
                          ;; ambiguous case, other assets may have game track in their file name.
                          (and many-assets? many-game-tracks?) {:game-track "retail" :version version :-mo :ma--Ngt}

                          :else (error (format "unhandled state attempting to determine game track(s) for asset '%s' in latest release of '%s'"
                                               asset addon-summary)))

                        update-list (if (sequential? update-list) update-list [update-list])]

                    (mapv (fn [update]
                            (merge asset update)) update-list)))

        asset-list (->> asset-list (map updater) flatten)]
    (group-by :game-track asset-list)))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. 
  one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary, game-track ::sp/game-track]
  (let [release-list (download-releases (:source-id addon-summary))
        latest-release (first release-list)
        group-assets (partial group-assets addon-summary)
        asset (-> latest-release group-assets (get game-track) first (dissoc :-mo))]
    (if-not asset
      (warn (format "no '%s' release available for '%s' on github" game-track (:name addon-summary)))
      (merge addon-summary
             {:download-uri (:browser_download_url asset)
              :version (:version asset)}))))

;;

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
            :game-track-list (or (find-gametracks-toc-data source-id) [])
            :category-list []}

           ;; 'something' failed to parse :(
           ;; would love a way to pass back a more specific error
           nil))

;;

(st/instrument)
