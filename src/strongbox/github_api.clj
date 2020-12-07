(ns strongbox.github-api
  (:require
   [clojure.string :refer [index-of split]]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [http :as http]
    [http2 :as http2]
    [toc :as toc]
    [utils :as utils :refer [pad if-let* nilable]]
    [specs :as sp]]))

(defn-spec releases-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id releases-url http2/download http/sink-error utils/from-json))

(defn-spec contents-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/contents" source-id))

(defn-spec download-root-listing (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id contents-url http2/download http/sink-error utils/from-json))

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)]
           (some-> toc-file :download_url http2/download toc/-parse-toc-file)
           (debug (format "failed to find/download/parse remote github '.toc' file for '%s'" source-id))))

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
         vec ;; do I need this ..?
         utils/nilable)))

(defn-spec find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :error nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting .toc file contents"
  [source-id string?]
  (-> source-id find-remote-toc-file -find-gametracks-toc-data))

(def supported-zip-mimes #{"application/zip"  "application/x-zip-compressed"})

(defn-spec classic-asset? boolean?
  "returns true if 'classic' found in given `string`, case insensitive"
  [string string?]
  (-> string .toLowerCase (index-of "classic") nil? not))

(defn group-assets
  [addon latest-release]
  (let [;; ignore assets whose :content_type is *not* a known zip type
        supported-zips #(-> % :content_type vector set (some supported-zip-mimes))

        ;; ignore any assets that are not completely uploaded
        ;; - https://developer.github.com/v3/repos/releases/#response-for-upstream-failure
        fully-uploaded #(-> % :state (= "uploaded"))
        asset-list (->> latest-release :assets (filter supported-zips) (filter fully-uploaded))

        single-asset? (-> asset-list count (= 1))
        many-assets? (-> asset-list count (> 1))

        known-game-tracks (-> addon :game-track-list (or []))
        no-known-game-tracks? (-> known-game-tracks count (= 0))
        single-game-track? (-> known-game-tracks count (= 1))
        many-game-tracks? (-> known-game-tracks count (> 1))

        ;; true if at least one asset has 'classic' in it's name
        classic-in-any-asset? (some->> asset-list (map :name) (map classic-asset?) nilable utils/any)

        too-ambiguous (and many-game-tracks?
                           many-assets?
                           (not classic-in-any-asset?))

        track-version (fn [version gametrack]
                        (if (= gametrack :classic)
                          (str version "-classic")
                          version))

        updater ;; returns a list of updated versions of this asset. if the asset supports multiple game tracks, two versions are returned
        (fn [asset]
          (let [version (:name latest-release) ;; "v2.10.0"
                ;; todo: change this to look for 'classic' or 'retail'
                ;; so, "FooAddon-retail" or "BarAddon-classic"
                ;; no known cases but it would be forward proof

                classic? (classic-asset? (:name asset))

                update-list ;; is either a map or a list of maps
                (cond
                  ;; game track present in file name, prefer that over known-game-tracks
                  classic? {:game-track :classic :version (track-version version :classic) :-mo :classic-in-name}

                  ;; single asset, no game track present in file name, no known game tracks. default to :retail
                  (and single-asset? no-known-game-tracks?) {:game-track :retail :version version :-mo :sa--ngt}

                  ;; single asset, no game track present in file name, single known game track. use that
                  (and single-asset? single-game-track?) {:game-track (first known-game-tracks)
                                                          :version (track-version version (first known-game-tracks))  :-mo :sa--1gt}

                  ;; single asset, no game track present in file name, multiple known game tracks. assume all game tracks supported
                  (and single-asset? many-game-tracks?) [{:game-track :classic :version (track-version version :classic) :-mo :sa--Ngt}
                                                         {:game-track :retail :version version :-mo :sa--Ngt}]

                  ;; multiple assets, no game track present in file name, no known game tracks. default to :retail
                  ;; ambiguous case, other assets may have game track in their file name
                  (and many-assets? no-known-game-tracks?) {:game-track :retail :version version :-mo :ma--ngt}

                  ;; multiple assets, no game track present in file name, single known game track. use that.
                  ;; ambiguous case, other assets may have game track in their file name
                  ;; this or other assets may be variations of the 'main' addon, like '-nolib' ?
                  (and many-assets? single-game-track?) {:game-track (first known-game-tracks)
                                                         :version (track-version version (first known-game-tracks)) :-mo :ma--1gt}

                  ;; multiple assets, no game track present in file name, multiple known game tracks. default to :retail
                  ;; ambiguous case, other assets may have game track in their file name.
                  (and many-assets? many-game-tracks?) {:game-track :retail :version version :-mo :ma--Ngt}

                  :else (error (format "unhandled state attempting to determine game track(s) for asset '%s' in latest release of '%s'"
                                       asset addon)))

                update-list (if (sequential? update-list) update-list [update-list])]

            (mapv (fn [update]
                    (merge asset update)) update-list)))

        asset-list (->> asset-list (map updater) flatten)]

    (if too-ambiguous
      (warn "multiple game tracks (classic and retail) detected with many downloadable assets and unable to differentiate between them. Refusing to pick.")
      (group-by :game-track asset-list))))

(defn-spec expand-summary (s/or :ok :addon/source-updates, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. 
  one additional look-up per ::addon required"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [release-list (download-releases (:source-id addon))
        latest-release (first release-list)
        -group-assets (partial group-assets addon)
        asset (-> latest-release -group-assets (get game-track) first (dissoc :-mo))]
    (when asset
      {:download-url (:browser_download_url asset)
       :version (:version asset)
       :game-track game-track})))

;;

(defn-spec extract-source-id (s/or :ok string?, :error nil?)
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec parse-user-string (s/or :ok :addon/summary, :error nil?)
  [uin string?]
  (if-let* [;; if *all* of these conditions succeed (non-nil), return a catalogue entry
            obj (some-> uin utils/unmangle-https-url java.net.URL.)
            path (when-not (empty? (.getPath obj)) (.getPath obj))

            ;; values here are tentative because given URL may resolve to a different URL
            [-owner -repo] (-> path (subs 1) (split #"/") (pad 2))
            -source-id (when (and -owner -repo)
                         (format "%s/%s" -owner -repo))
            release-list (download-releases -source-id)
            latest-release (first release-list) ;; releases must be used
            _ (-> latest-release :assets nilable) ;; releases must be using uploaded assets

            ;; these are the values we want to be using
            source-id (-> latest-release :html_url extract-source-id)
            [owner repo] (split source-id #"/")

            download-count (->> release-list (map :assets) flatten (map :download_count) (apply +))]

           {:url (str "https://github.com/" source-id)
            :updated-date (-> latest-release :published_at)
            :source "github"
            :source-id source-id
            :label repo
            :name (slugify repo "")
            :download-count download-count
            :game-track-list (or (find-gametracks-toc-data source-id) [])
            ;; 2020-03: disabled in favour of :tag-list
            ;;:category-list []
            :tag-list []}

           ;; 'something' failed to parse :(
           ;; would love a way to pass back a more specific error
           nil))
