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
    [toc :as toc]
    [utils :as utils :refer [pad if-let* nilable]]
    [specs :as sp]]))

(defn-spec releases-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id releases-url http/download http/sink-error utils/from-json))

(defn-spec contents-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/contents" source-id))

(defn-spec download-root-listing (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id contents-url http/download http/sink-error utils/from-json))

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)]
           (some-> toc-file :download_url http/download toc/-parse-toc-file)
           (warn (format "failed to find/download/parse remote github '.toc' file for '%s'" source-id))))

(defn-spec -find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :not-tracks-found nil?)
  "returns a set of game tracks after inspecting .toc file contents"
  [toc-data map?]
  (->> (-> toc-data
           ;; hrm: this only allows for two possible game tracks, one normal and one hiding in the template area
           ;; 2021-06-10: see release.json
           (select-keys [:interface :#interface])
           vals)

       ;; todo: test this! I think I've been feeding it good values only, I just got a real-life string back
       (map (comp utils/interface-version-to-game-track utils/to-int))

       ;; 2021-05-02: unknown game versions of 2.x (that are now considered "Classic (TBC)") were returning `nil` as the game track.
       (remove nil?)
       set
       vec
       utils/nilable))

(defn-spec find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :no-tracks-found nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting .toc file contents"
  [source-id string?]
  (some-> source-id find-remote-toc-file -find-gametracks-toc-data))

(def supported-zip-mimes #{"application/zip"  "application/x-zip-compressed"})

(defn-spec pick-version-name (s/or :ok string? :failed nil?)
  "returns the first non-nil value that can be used as a 'version' from a list of good candidates.
  `asset` is a subset of `release` that has been filtered out from the other assets in the release.
  ideally we want to use the name the author has specifically chosen for a release.
  if that doesn't exist, we fallback to the git tag which is typically better than the asset's name."
  [release map?, asset map?]
  (let [;; most to least desirable
        candidates [(:name release) (:tag_name release) (:name asset)]]
    (->> candidates (map utils/nilable) (remove nil?) first)))

(defn-spec group-assets map?
  "filters, groups and classifies a release's assets.
  a release may have many assets, however we're only interested in fully uploaded zip files.
  an asset may contain 'classic-tbc', 'classic' or 'retail' that will help to classify which 
  game track the asset lives in."
  [addon :addon/expandable, release map?]
  (let [;; ignore assets whose :content_type is *not* a known zip type
        supported-zips #(-> % :content_type vector set (some supported-zip-mimes))

        ;; ignore any assets that are not completely uploaded
        ;; - https://developer.github.com/v3/repos/releases/#response-for-upstream-failure
        fully-uploaded #(-> % :state (= "uploaded"))
        asset-list (->> release :assets (filter supported-zips) (filter fully-uploaded))

        ;; releases with zero assets don't seem to appear in api results for /releases

        known-game-tracks (-> addon :game-track-list (or []))
        no-known-game-tracks? (-> known-game-tracks count (= 0))
        single-game-track? (-> known-game-tracks count (= 1))
        many-game-tracks? (-> known-game-tracks count (> 1))

        ;; returns the first game track it can find in the release name or nil
        release-game-track (utils/guess-game-track (:name release))

        updater ;; returns a list of updated versions of this asset. if the asset supports multiple game tracks, two versions are returned
        (fn [asset]
          (let [version (pick-version-name release asset)
                ;; todo: change this to look for 'classic' or 'retail'
                ;; so, "FooAddon-retail" or "BarAddon-classic"
                ;; no known cases but it would be forward proof

                asset-game-track (utils/guess-game-track (:name asset))

                update-list ;; is either a map or a list of maps
                (cond

                  ;; game track present in file name, prefer that over `:game-track-list` and any game-track in release name
                  asset-game-track {:game-track asset-game-track, :version version, :-mo :track-in-asset-name}

                  ;; game track present in release name, prefer that over `:game-track-list`
                  release-game-track {:game-track release-game-track, :version version, :-mo :track-in-release-name}

                  ;; no game track present in asset name or release name and no `:game-track-list`. assume `:retail`
                  no-known-game-tracks? (do (debug (format "no game track detected for release '%s' and asset '%s', assuming 'retail'"
                                                           (:name release) (:name asset)))
                                            {:game-track :retail, :version version :-mo :sa--ngt})

                  ;; no game track present in asset name or release name and just a single entry in `:game-track-list`. use that.
                  single-game-track? {:game-track (first known-game-tracks), :version version,  :-mo :sa--1gt}

                  ;; no game track present in asset name or release name with multiple entries in `:game-track-list`.
                  ;; assume all entries in `:game-track-list` supported.
                  many-game-tracks? (vec (for [game-track known-game-tracks]
                                           {:game-track game-track, :version version, :-mo :sa--Ngt}))

                  :else (error (format "unhandled state attempting to determine game track(s) for asset '%s' in release of '%s'"
                                       asset addon)))

                update-list (if (sequential? update-list) update-list [update-list])]

            (mapv (fn [update]
                    (merge asset update)) update-list)))]
    (->> asset-list (map updater) flatten (group-by :game-track))))

(defn-spec parse-github-release-data vector?
  "given a `release-list` (a response from Github), parse the assets in each release."
  [release-list vector?, addon :addon/expandable, game-track ::sp/game-track]
  (->> release-list
       (map (partial group-assets addon))
       (filter #(contains? % game-track))
       (map game-track)
       vec))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. 
  one additional look-up per ::addon required"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [wrangle-release (fn [release]
                          (when-let [asset (first release)]
                            {:download-url (:browser_download_url asset)
                             :version (:version asset)
                             :game-track game-track}))
        wrangle-release-list #(mapv wrangle-release %)]
    (some-> addon
            :source-id
            download-releases
            (parse-github-release-data addon game-track)
            wrangle-release-list
            utils/nilable)))

;;

;; todo: keep this and discard parse-user-string or vice versa?
(defn-spec extract-source-id (s/or :ok string?, :error nil?)
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  [url ::sp/url]
  (let [path (some-> url java.net.URL. .getPath nilable)
        ;; values here are tentative because given URL may eventually resolve to a different URL.
        [-owner -repo] (-> path (subs 1) (split #"/") (pad 2))]
    (when (and -owner -repo)
      (format "%s/%s" -owner -repo))))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  (if-let* [release-list (download-releases source-id)
            latest-release (first release-list) ;; releases must be used

            ;; releases must be using uploaded assets
            ;; todo: revisit this logic. the latest release may not be a good representative
            _ (-> latest-release :assets nilable)

            ;; will correct any case problems. see tests.
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
           nil))
