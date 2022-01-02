(ns strongbox.github-api
  (:require
   [clojure.data.csv :as csv]
   [clojure.string :refer [index-of split]]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [release-json :refer [download-release-json, release-json-game-tracks]]
    [http :as http]
    [toc :as toc]
    [utils :as utils :refer [pad if-let*]]
    [specs :as sp]]))

(defn-spec release-json-file? boolean?
  "returns `true` if given `asset` is a release.json file."
  [asset map?]
  (-> asset :name (= "release.json")))

(defn-spec fully-uploaded? boolean?
  "returns `true` if given `asset` is fully uploaded."
  [asset map?]
  (-> asset :state (= "uploaded")))

(defn-spec releases-url (s/or :ok ::sp/url, :empty nil?)
  [source-id string?]
  (some->> source-id utils/nilable (format "https://api.github.com/repos/%s/releases")))

(defn-spec contents-url (s/or :ok ::sp/url, :empty nil?)
  [source-id string?]
  (some->> source-id utils/nilable (format "https://api.github.com/repos/%s/contents")))

(defn-spec download-root-listing (s/or :ok ::sp/list-of-maps, :error nil?)
  [source-id string?]
  (some-> source-id contents-url http/download-with-backoff http/sink-error
          (utils/from-json "failed to parse Github API response for repository root file contents: \"%s\"")))

(defn-spec download-release-listing (s/or :ok ::sp/list-of-maps, :error nil?)
  "downloads and deserialises the list of releases for the given addon's `source-id`"
  [source-id string?]
  (some-> source-id releases-url http/download-with-backoff http/sink-error
          (utils/from-json "failed to parse Github API response for repository releases listing: \"%s\"")))

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

(defn-spec parse-assets :addon/release-list
  "filters and parses a list of assets in a `release`."
  [release map?, known-game-tracks ::sp/game-track-list]
  (let [supported-zip? #(-> % :content_type vector set (some supported-zip-mimes))
        release-game-track (utils/guess-game-track (:name release))

        latest-release? (-> release (get :-i 0) (= 0))

        release-json (->> release
                          :assets
                          (filter release-json-file?)
                          first)

        published-before-classic? (utils/published-before-classic? (:published_at release))

        ;; guess the asset game track from the asset name, the release name or the time it was published.
        classify (fn [asset]
                   (let [asset-game-track (utils/guess-game-track (:name asset))
                         source-info {:game-track nil
                                      :version (pick-version-name release asset)
                                      :download-url (:browser_download_url asset)
                                      :-name (:name asset)}
                         game-track-list
                         (cond
                           ;; game track present in file name, prefer that over `:game-track-list` and any game-track in release name
                           asset-game-track [asset-game-track]

                           ;; I imagine there were classic addons published prior to it's release.
                           ;; If we can use the asset name, brilliant, if not and it's before the cut off then it's retail.
                           published-before-classic? [:retail]

                           ;; game track present in release name, prefer that over `:game-track-list`
                           release-game-track [release-game-track]

                           ;; we don't know, we couldn't guess. return a `nil` so we can optionally deal with them later.
                           :else [nil])]

                     (mapv #(assoc source-info :game-track %) game-track-list)))

        ;; if we have a telltale single unclassified asset in a set of classified assets, use that.
        classify2 (fn [asset-list]
                    (let [;; it's possible for `nil` game tracks to still exist.
                          ;; either the release.json file was incomplete or we simply couldn't
                          ;; guess the game track from the asset name and had nothing to fall back on.
                          unclassified-assets (->> asset-list (map :game-track) (filter nil?))
                          classified-assets (->> asset-list (map :game-track) (remove nil?) set)
                          diff (clojure.set/difference sp/game-tracks classified-assets)] ;; #{:classic :classic-bc :retail} #{:classic :classic-bc} => #{:retail}
                      (if (and (= (count unclassified-assets) 1)
                               (= (count diff) 1))
                        (->> asset-list
                             (mapv #(if (nil? (:game-track %))
                                      (assoc % :game-track (first diff))
                                      %)))
                        asset-list)))

        ;; finally, try downloading the release.json file, if it exists, otherwise just use 'all' game tracks originally detected.
        classify3 (fn [asset]
                    (if (:game-track asset)
                      [asset]

                      (let [;; no game track present in asset name or release name BUT 
                            ;; a release.json file is present and this is still the first of N releases.
                            ;; fetch release and match asset to it's contents.
                            ;; if asset isn't found (!) then just use [nil]
                            game-track (when (and latest-release?
                                                  release-json)
                                         (let [release-json-data (download-release-json (:browser_download_url release-json))]
                                           (get (release-json-game-tracks release-json-data) (:-name asset)
                                                (debug "release.json missing asset:" (:-name asset)))))

                            ;; assume all entries in `:game-track-list` supported.
                            game-track-list (if (empty? known-game-tracks) [nil] known-game-tracks)
                            game-track-list (or game-track
                                                game-track-list)]

                        (mapv #(assoc asset :game-track %) game-track-list))))

        classified? (fn [asset]
                      (if-not (:game-track asset)
                        (debug "failed to detect a game track for asset" (fs/base-name (get asset :download-url "")))
                        asset))]
    (->> release
         :assets
         (filter supported-zip?)
         (filter fully-uploaded?)
         (mapcat classify)
         classify2 ;; deals with a single list of assets
         (mapcat classify3) ;; also returns a nested list of assets
         (filter classified?)
         (remove (comp nil? :game-track))
         (map #(dissoc % :-name))
         vec)))

(defn-spec -parse-assets fn?
  "convenience wrapper around `parse-assets` that returns a function that accepts a list of releases"
  [game-track-list ::sp/game-track-list]
  (fn [idx release]
    (parse-assets (assoc release :-i idx) game-track-list)))

(defn-spec parse-github-release-data (s/or :ok :addon/release-list, :fail nil?)
  "given a `release-list` (a response from Github), parse the assets in each release. 
  returns the list of releases appropriate for the given `game-track`."
  [release-list vector?, addon :addon/expandable, game-track ::sp/game-track]
  (let [game-track-list (cond
                          (not (empty? (:game-track-list addon))) (:game-track-list addon)
                          (:installed-game-track addon) [(:installed-game-track addon)]
                          :else [])]
    (->> release-list
         (map-indexed (-parse-assets game-track-list))
         flatten
         (group-by :game-track)
         game-track)))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (some-> addon
          :source-id
          download-release-listing
          (parse-github-release-data addon game-track)
          utils/nilable))

;; ---

(defn-spec find-remote-toc-file (s/or :ok map?, :error nil?)
  "returns the contents of the first .toc file it finds in the root directory of the remote addon"
  [source-id string?]
  (if-let* [contents-listing (download-root-listing source-id)
            toc-file-list (filterv #(-> % :name fs/split-ext last (= ".toc")) contents-listing)
            toc-file (first toc-file-list)] ;; assumes just one toc file :(
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
         (map-indexed (-parse-assets known-game-tracks))
         flatten
         (group-by :game-track)
         keys
         (remove nil?) ;; unguessable game tracks default to `nil` when `known-game-tracks` is empty.
         vec
         utils/nilable)))

(defn-spec find-gametracks-release-json (s/or :ok ::sp/game-track-list, :no-game-tracks nil?)
  "looks for the first release.json it can find and returns the game tracks found inside.
  returns `nil` if no release.json found or no known game tracks detected."
  [release-list ::sp/list-of-maps]
  (let [release-json-asset #(->> % :assets (filter release-json-file?) (filter fully-uploaded?) first)]
    (some->> release-list
             (map release-json-asset)
             (remove nil?)
             first
             :browser_download_url
             download-release-json
             (map :metadata) ;; a list of maps `[{:metadata [...]}, ...]` becomes a list of lists `[[...], ...]`
             flatten ;; single list of maps `[{...}, ...]`
             (map :flavor) ;; list of strings
             (map utils/guess-game-track) ;; list of keywords
             (remove nil?) ;; unguessable game tracks removed. todo: issue warning?
             set ;; distinct
             sort
             vec
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
  (if-let* [release-list (download-release-listing source-id) ;; releases must be used

            ;; must have something properly released, releases must be using uploaded assets
            latest-release (find-latest-release release-list)

            ;; we have to to find at least one game track in either a release asset name, a release.json file or a .toc file.
            ;; if we can't then refuse to guess.
            ;; later on when we scan releases and we can't find an asset's game track, we'll use these to fill in the blanks.
            game-track-list (or (find-gametracks-release-list release-list)
                                (find-gametracks-release-json release-list)
                                (find-gametracks-toc-data source-id)
                                (do (warn "failed to find any game tracks at all. assuming 'retail'.")
                                    [:retail]))

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

;;

(defn-spec build-catalogue (s/or :ok :addon/summary-list, :error nil?)
  "converts a CSV list of addons compiled by @layday to a strongbox-compatible catalogue of addon summaries."
  []
  (let [url "https://raw.githubusercontent.com/ogri-la/github-wow-addon-catalogue/main/addons.csv"
        result (-> url
                   http/download-with-backoff
                   http/sink-error
                   clojure.string/trim-newline
                   csv/read-csv)

        result-list (apply utils/csv-map result)

        split* (fn [string]
                 (clojure.string/split string #","))

        to-summary
        (fn [row]
          (let [addon {:url (:url row)
                       :name (slugify (:name row))
                       :label (:name row)
                       :tag-list []
                       :updated-date (:last_updated row)
                       :download-count 0
                       :source "github"
                       :source-id (-> row :url java.net.URL. .getPath (utils/ltrim "/"))
                       ;;:description (:description row) ;; may be empty
                       :game-track-list (->> row
                                             :flavors
                                             split*
                                             (mapv utils/guess-game-track))}]
            ;; this is so clumsy :(
            (if-let [description (utils/nilable (:description row))]
              (assoc addon :description description)
              addon)))]
    (mapv to-summary result-list)))

;;

(defn make-url
  "returns a URL to the given addon data"
  [{:keys [source-id]}]
  (when source-id
    (str "https://github.com/" source-id)))
