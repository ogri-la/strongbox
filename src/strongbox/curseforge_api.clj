(ns strongbox.curseforge-api
  (:require
   [strongbox
    [tags :as tags]
    [http :as http]
    [specs :as sp]
    [utils :as utils :refer [to-json join]]]
   [clojure.spec.alpha :as s]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def curseforge-api "https://addons-ecs.forgesvc.net/api/v2")

(defn-spec api-url ::sp/url
  [path string?, & args (s/* any?)]
  (str curseforge-api (apply format path args)))

;; addon expansion

(defn-spec release-download-url (s/or :ok ::sp/url, :error nil?)
  "returns a URL to the release file.
  'https://edge.forgecdn.net/files/1234/567/Addon-v7.8.9.zip'"
  [project-file-id int?, project-file-name string?]
  (try
    (let [project-file-id (str project-file-id) ;; "3164017"
          offset (- (count project-file-id) 3) ;; "4"
          bit1 (-> project-file-id (.substring 0 offset)) ;; "3164"
          bit2 (-> project-file-id (.substring offset) (utils/ltrim "0"))] ;; 17 (strips leading zeroes)
      (format "https://edge.forgecdn.net/files/%s/%s/%s" bit1 bit2 project-file-name))
    (catch java.lang.StringIndexOutOfBoundsException e
      (warn (format "failed to build a download url for release '%s'" project-file-name)))))

(defn-spec rename-identical-releases ::sp/list-of-maps
  "releases must have unique names otherwise we can't find them in the list of available releases.
  This function assigns each release a `:-unique-name` that is either `:projectFileName` or
  `:projectFileName` + `projectFileId`."
  [release-list ::sp/list-of-maps]
  (let [count-occurances (fn [accumulator-m m]
                           (let [key :projectFileName]
                             (update accumulator-m (get m key) (fn [x] (inc (or x 0))))))
        occurances (reduce count-occurances {} release-list) ;; {"Foo-v1.zip" 1, "Foo-v2.zip 1, "Foo.zip" 5}
        get* #(get %2 %1)
        rename-release (fn [release]
                         (let [[name _] (fs/split-ext (:projectFileName release))
                               pid (:projectFileId release)]
                           (assoc release :-unique-name
                                  (if (-> release :projectFileName (get* occurances) (> 1))
                                    (format "%s--%s" name pid) ;; "Foo--3084724"
                                    name)))) ;; "Foo"
        ]
    (mapv rename-release release-list)))

(defn-spec older-releases :addon/release-list
  "these are releases under `:gameVersionLatestFiles` that that are the most recent release by game/interface version (8.0.3 etc).
  returns only stable releases."
  [gameVersionLatestFiles ::sp/list-of-maps]
  (let [stable 1 ;; 2 is beta, 3 is alpha
        stable-releases #(-> % :fileType (= stable))
        pad-release (fn [release]
                      (when-let [download-url (release-download-url (:projectFileId release) (:projectFileName release))]
                        {:download-url download-url
                         :version (:-unique-name release) ;; see `rename-identical-releases`
                         :release-label (format "[WoW %s] %s" (:gameVersion release) (:-unique-name release))
                         :interface-version (utils/game-version-to-interface-version (:gameVersion release))
                         :game-track (if (= (:gameVersionFlavor release) "wow_classic") :classic :retail)}))]
    (->> gameVersionLatestFiles
         (filter stable-releases)
         rename-identical-releases
         (map pad-release)
         (remove nil?)
         vec)))

(defn-spec extract-release :addon/release-list
  "for each release, set the correct value for `:gameVersionFlavor` and `:gameVersion`.
  if `:gameVersion` is an empty list, use the value from `:gameVersionFlavor` to come up with a value.
  return multiple instances of the release if necessary."
  [release map?]
  (let [;; "wow_retail", "wow_classic" => :retail, :classic
        fallback (if (= (:gameVersionFlavor release) "wow_classic") :classic :retail)
        release (if (empty? (:gameVersion release))
                  (assoc release :gameVersion [(utils/game-track-to-latest-game-version fallback)])
                  release)

        ;; generate a release per-gametrack
        pad-release (fn [game-version]
                      (let [;; api value is empty in some cases (carbonite, improved loot frames, skada damage meter).
                            ;; this value overrides the one found in .toc files, so if it can't be scraped, use the .toc version.
                            interface-version (utils/game-version-to-interface-version game-version)
                            interface-version (when interface-version
                                                {:interface-version interface-version})
                            [name _] (fs/split-ext (get release :fileName "null"))]
                        (merge {:download-url (:downloadUrl release)
                                :version (:displayName release)
                                :release-label (format "[WoW %s] %s" (first (:gameVersion release)) name)
                                :game-track (utils/game-version-to-game-track game-version)}
                               interface-version)))]
    (mapv pad-release (:gameVersion release))))

(defn-spec prune-leading-duplicates (s/or :ok :addon/release-list, :garbage-in nil?)
  "curseforge may produce the same release under `:latestFiles` and `:gameVersionLatestFiles`.
  remove any `:gameVersionLatestFiles` releases in favour of releases from `:latestFiles`.
  because we're duplicating releases by `:game-track`, assume two different releases may share 
  the same download URL and only call this *after* a game track has been selected."
  [release-list (s/nilable :addon/release-list)]
  (if (empty? release-list)
    nil
    (let [[latest-release latest-release-by-game-version] release-list]
      (if (= (:download-url latest-release)
             (:download-url latest-release-by-game-version))
        (concat [latest-release] (rest (rest release-list)))
        (do (info "no match!" latest-release latest-release-by-game-version)
            release-list)))))

(defn-spec group-releases map?
  "given a curseforge api result, returns a map of release data keyed by `game-track`."
  [api-result (s/nilable map?)]

  ;; issue #63: curseforge actually allow a release to be on both retail and classic game tracks.
  ;; the single value under `gameVersionFlavor` is *inaccurate and misleading* and we can't trust it.
  ;; instead we look at the `gameVersion` list and convert the versions we find there into game tracks.
  ;; `8.2.0` and `8.2.5` => `retail`
  ;; `1.13.2` => `classic`

  ;; however! `gameVersion` is occasionally *empty* (see Adibags) and we have to guess which game track
  ;; this release supports. In these cases we fall back to `:gameVersionFlavor`.

  (let [;; results appear sorted, but lets be sure as we'll be taking the first
        desc (comp - compare) ;; most to least recent (desc)
        stable 1 ;; 2 is beta, 3 is alpha
        stable-release #(-> % :releaseType (= stable))
        concat* #(concat %2 %1)
        more-releases (when-let [gameVersionLatestFiles (:gameVersionLatestFiles api-result)]
                        (older-releases gameVersionLatestFiles))]
    (->> api-result
         :latestFiles
         (sort-by :fileDate desc)
         (filter stable-release)
         (remove :exposeAsAlternative) ;; no alternative versions, for now
         (map extract-release)
         flatten
         (concat* more-releases)
         (group-by :game-track))))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "given an `addon-summary`, adds the remaining attributes that couldn't be gleaned from the summary page ('source-updates').
  one additional HTTP call per addon required."
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  (let [url (api-url "/addon/%s" (:source-id addon-summary))
        result (some-> url http/download http/sink-error utils/from-json)]
    (-> result group-releases (get game-track) prune-leading-duplicates)))

;; catalogue building

(defn-spec extract-addon-summary :addon/summary
  "converts addon data extracted from a listing into an :addon/summary"
  [snippet map?] ;; TODO: spec out curseforge results? eh.
  {:url (:websiteUrl snippet)
   :label (:name snippet)
   :name (:slug snippet)
   :description (:summary snippet)
   ;; sorting cuts down on noise in diffs.
   ;; `set` because of curseforge duplicate categories
   ;; 2020-03: disabled in favour of :tag-list
   ;;:category-list (->> snippet :categories (map :name) set sort vec)
   :tag-list (->> snippet :categories (map :name) (tags/category-list-to-tag-list "curseforge"))
   :created-date (:dateCreated snippet) ;; omg *yes*. perfectly formed dates
   ;; we now have :dateModified and :dateReleased to pick from
   ;;:updated-date (:dateModified snippet)
   :updated-date (:dateReleased snippet) ;; this seems closest to what we originaly had
   :download-count (-> snippet :downloadCount int) ;; I got a '511.0' ...?
   :source "curseforge"
   :source-id (:id snippet)})

(defn-spec download-summary-page-alphabetically (s/or :ok (s/coll-of map?), :error nil?)
  "downloads a page of results from the curseforge API, sorted A to Z"
  [page int? page-size pos-int?]
  (info "downloading" page-size "results from api, page" (inc page))
  (let [index (* page-size page)
        game-id 1 ;; WoW
        sort-by 3 ;; alphabetically, asc (a-z)
        results (http/download (api-url "/addon/search?gameId=%s&index=%s&pageSize=%s&searchFilter=&sort=%s" game-id index page-size sort-by))
        results (utils/from-json results)]
    (mapv extract-addon-summary results)))

(defn-spec download-all-summaries-alphabetically (s/or :ok :addon/summary-list, :error nil?)
  []
  (loop [page 0
         accumulator []]
    (let [page-size 255
          results (download-summary-page-alphabetically page page-size)
          num-results (count results)]
      (if (< num-results page-size)
        (into accumulator results) ;; short page, exit loop
        (recur (inc page)
               (into accumulator results))))))
