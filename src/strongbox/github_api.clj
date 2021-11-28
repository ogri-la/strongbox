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
    [utils :as utils :refer [pad if-let*]]
    [specs :as sp]]))

(defn release-json-file?
  [asset]
  (-> asset :name (= "release.json")))

(defn fully-uploaded?
  [asset]
  (-> asset :state (= "uploaded")))

(defn-spec releases-url ::sp/url
  [source-id string?]
  (format "https://api.github.com/repos/%s/releases" source-id))

(defn-spec download-releases (s/or :ok ::sp/list-of-maps, :error nil?)
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
  [release map?, game-track-list ::sp/game-track-list]
  (let [supported-zip? #(-> % :content_type vector set (some supported-zip-mimes))

        ;; still not happy with this.
        ;; some hueristics:
        ;; 1. if we have more than 1 asset and we have exactly 1 unguessable asset, assume retail
        ;; 2. if we have one asset and an unguessable game track, try using the name of the release
        ;; 3. otherwise, download release.json and parse it's contents, but only if this is release 1 of N releases
        ;; 4. if this is release 1+N of N releases, use the previously downloaded release.json to 'fill in the blanks'.
        ;; 5. if no release.json exists, fall back on the given `game-track-list`
        
        classify (fn [asset]
                   (let [asset-game-track (utils/guess-game-track (:name asset))
                         release-game-track (utils/guess-game-track (:name release))
                         source-info (fn [game-track]
                                       {:game-track game-track
                                        :version (pick-version-name release asset)
                                        :download-url (:browser_download_url asset)})]
                     (cond
                       asset-game-track [(source-info asset-game-track)]
                       release-game-track [(source-info release-game-track)]
                       (not (empty? game-track-list)) (mapv source-info game-track-list)
                       :else (source-info nil))))
        ]
    (->> release
         :assets
         (filter supported-zip?)
         (filter fully-uploaded?)
         (map classify)
         flatten
         vec)))

(defn-spec parse-github-release-data (s/or :ok :addon/release-list, :fail nil?)
  "given a `release-list` (a response from Github), parse the assets in each release."
  [release-list vector?, addon :addon/expandable, game-track ::sp/game-track]
  (let [game-track-list (if (empty? (:game-track-list addon)) [:retail] (:game-track-list addon))]
    (->> release-list
         (map #(parse-assets % game-track-list))
         flatten
         (group-by :game-track)
         game-track)))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (some-> addon
          :source-id
          download-releases
          (parse-github-release-data addon game-track)
          utils/nilable))

;; ---

(defn-spec download-root-listing (s/or :ok ::sp/list-of-maps, :error nil?)
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

(defn-spec -find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :error nil?)
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

(defn-spec find-gametracks-toc-data (s/or :ok ::sp/game-track-list, :error nil?)
  "returns a set of game tracks after inspecting the .toc file contents"
  [source-id string?]
  (some-> source-id find-remote-toc-file -find-gametracks-toc-data))

(defn-spec find-gametracks-release-list (s/or :ok ::sp/game-track-list, :no-game-tracks nil?)
  "analyses all releases to date and returns a list of game tracks found.
  returns `nil` if no game tracks are detected.
  does not guess, fast and cheap to do."
  [release-list ::sp/list-of-maps]
  (let [known-game-tracks []]
    (->> release-list
         (map #(parse-assets % known-game-tracks))
         flatten
         (group-by :game-track)
         keys
         (remove nil?) ;; unguessable game tracks default to `nil` when `known-game-tracks` is empty.
         vec
         utils/nilable)))

(defn-spec download-release-json ::sp/list-of-maps
  "release.json should only be downloaded in ambiguous cases, i.e., where the game track can't be guessed from the filename."
  [release-json-asset map?]
  (some->> release-json-asset
           :browser_download_url
           http/download-with-backoff
           http/sink-error
           utils/from-json
           :releases
           (remove :nolib)
           vec))

(defn-spec find-gametracks-release-json (s/or :ok ::sp/game-track-list, :no-game-tracks nil?)
  "looks for the first release.json it can find and returns the game tracks found inside.
  returns `nil` if no release.json found or no known game tracks detected."
  [release-list ::sp/list-of-maps]
  (let [release-json-asset #(->> % :assets (filter release-json-file?) (filter fully-uploaded?) first)]
    (some->> release-list
             (map release-json-asset)
             (remove nil?)
             first
             download-release-json
             (map :metadata) ;; list of maps `[{:metadata [...]}, ...]` becomes a list of lists `[[...], ...]`
             flatten ;; single list of maps `[{...}, ...]`
             (map :flavor) ;; list of strings
             (map utils/guess-game-track) ;; list of keywords
             (remove nil?) ;; unguessable game tracks removed. todo: issue warning?
             set ;; distinct
             utils/nilable)))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec find-latest-release (s/or :release map?, :no-viable-release nil?)
  "the literal latest release we can find may not be the best choice and should only be "
  [release-list ::sp/list-of-maps]
  (->> release-list
       (remove :prerelease)
       (remove :draft)
       (filter (comp utils/nilable :assets))
       first))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  (if-let* [release-list (download-releases source-id) ;; releases must be used

            ;; must have something properly released, releases must be using uploaded assets
            latest-release (find-latest-release release-list)

            ;; we have to to find at least one game track in either a release asset name, a release.json file or a .toc file.
            ;; if we can't then refuse to guess.
            ;; later on when we scan releases and we can't find an asset's game track, we'll use these to fill in the blanks.
            game-track-list (or (find-gametracks-release-list release-list)
                                (find-gametracks-release-json release-list)
                                (find-gametracks-toc-data source-id)
                                [])

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
            :game-track-list game-track-list
            ;; 2020-03: disabled in favour of :tag-list
            ;;:category-list []
            :tag-list []}

           ;; 'something' failed to parse :(
           nil))
