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
  (some-> source-id releases-url http/download-with-backoff http/sink-error utils/from-json))

(defn-spec contents-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/contents" source-id))

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

(defn-spec parse-assets ::sp/list-of-maps
  "filters and classifies a release's list of assets."
  [addon :addon/expandable, release map?]
  (let [supported-zip? #(-> % :content_type vector set (some supported-zip-mimes))
        fully-uploaded? #(-> % :state (= "uploaded"))
        classify (fn [asset]
                   (let [version (pick-version-name release asset)
                         ;; perhaps: only include the 'release' and 'addon' game tracks if we can't guess a game track from the asset filename.
                         known-game-tracks (-> addon
                                               :game-track-list
                                               (or [])
                                               (conj (utils/guess-game-track (:name release)))
                                               (conj (utils/guess-game-track (:name asset))))
                         known-game-tracks (set (remove nil? known-game-tracks))
                         known-game-tracks (if (empty? known-game-tracks) #{:retail} known-game-tracks)]
                     (for [game-track known-game-tracks]
                       {:game-track game-track
                        :version version
                        :download-url (:browser_download_url asset)})))]
    (->> release
         :assets
         (filter supported-zip?)
         (filter fully-uploaded?)
         (map classify)
         flatten
         vec)))

(defn-spec parse-github-release-data vector?
  "given a `release-list` (a response from Github), parse the assets in each release."
  [release-list vector?, addon :addon/expandable, game-track ::sp/game-track]
  (let [result (->> release-list
                    (map (partial parse-assets addon))
                    flatten
                    (group-by :game-track))]

    (get result game-track [])))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (some-> addon
          :source-id
          download-releases
          (parse-github-release-data addon game-track)
          utils/nilable))

;; ---

        ;;release-json-file? #(-> % :name (= "release.json"))
        ;;release-json (->> release :assets (filter release-json-file?) (filter fully-uploaded) first)


(defn-spec download-release-json ::sp/list-of-maps
  "release.json should only be downloaded in ambiguous cases, i.e., where the game track can't be guessed from the filename."
  [release-json-asset (s/nilable map?)]
  (some->> release-json-asset
           :browser_download_url
           http/download-with-backoff
           http/sink-error
           utils/from-json
           :releases
           (remove :nolib)
           vec))

(defn-spec download-root-listing (s/or :ok (s/coll-of map?), :error nil?)
  [source-id string?]
  (some-> source-id contents-url http/download-with-backoff http/sink-error utils/from-json))

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)]
           (some-> toc-file :download_url http/download-with-backoff toc/parse-toc-file)
           (warn (format "failed to find/download/parse remote github '.toc' file for '%s'" source-id))))

(defn-spec -find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :not-tracks-found nil?)
  "returns a set of game tracks after inspecting .toc file contents"
  [toc-data map?]
  (->> (-> toc-data
           ;; hrm: this only allows for two possible game tracks, one normal and one hiding in the template area
           ;; 2021-06-10: see release.json
           (select-keys [:interface :#interface])
           vals)

       (map utils/to-int)
       (map utils/interface-version-to-game-track)

       ;; 2021-05-02: unknown game versions of 2.x (that are now considered "Classic (TBC)") were returning `nil` as the game track.
       (remove nil?)
       set
       vec
       utils/nilable))

;; todo: release.json parsing would fit in nicely here.
;; prefer release.json over fetching toc file and parsing contents.

(defn-spec find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :no-tracks-found nil?)
  "returns a set of 'retail' and/or 'classic' after inspecting .toc file contents"
  [source-id string?]
  (some-> source-id find-remote-toc-file -find-gametracks-toc-data))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  (if-let* [release-list (download-releases source-id)
            latest-release (first release-list) ;; releases must be used

            ;; releases must be using uploaded assets
            ;; todo: revisit this logic. the latest release may not be a good representative
            _ (-> latest-release :assets nilable)

            ;; will correct any case problems. see tests.
            source-id (-> latest-release :html_url parse-user-string)
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
