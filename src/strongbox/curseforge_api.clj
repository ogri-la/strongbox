(ns strongbox.curseforge-api
  (:require
   [strongbox
    [tags :as tags]
    [http :as http]
    [specs :as sp]
    [utils :as utils :refer [to-json join]]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def curseforge-api "https://addons-ecs.forgesvc.net/api/v2")

(defn-spec api-url ::sp/url
  [path string?, & args (s/* any?)]
  (str curseforge-api (apply format path args)))

(defn latest-versions
  "given a curseforge-api result, returns a map of release data.
  uses :gameVersion which, if present, indicates the game tracks a release supports"
  [api-result]

  ;; issue #63: curseforge actually allow a release to be on both retail and classic game tracks.
  ;; the single value under `gameVersionFlavor` is *inaccurate and misleading* and we can't trust it.
  ;; instead we look at the `gameVersion` list and convert the versions we find there into game tracks.
  ;; `8.2.0` and `8.2.5` => `retail`
  ;; `1.13.2` => `classic`

  ;; however! `gameVersion` is occasionally *empty* (see Adibags) and we have to guess which game track
  ;; this release supports. In these cases we fall back to `:gameVersionFlavor`.

  (let [latest-files (:latestFiles api-result)

        ;; results appear sorted, but lets be sure as we'll be taking the first
        desc (comp - compare) ;; most to least recent (desc)
        latest-files (sort-by :fileDate desc latest-files)

        ;; stable releases only, for now
        stable 1 ;; 2 is beta, 3 is alpha
        stable-releases (filterv #(= (:releaseType %) stable) latest-files)

        ;; no alternative versions, for now
        stable-releases (remove :exposeAsAlternative stable-releases)

        ;; for each release, set the correct value for `:gameVersionFlavor` and `:gameVersion`
        ;; if `:gameVersion` is an empty list, use the value from `:gameVersionFlavor` to come up with a value.
        ;; return multiple instances of the release if necessary.
        expand-release (fn [release]
                         (let [;; "wow_retail", "wow_classic" => :retail, :classic
                               fallback (if (= (:gameVersionFlavor release) "wow_classic") :classic :retail)
                               release (if (empty? (:gameVersion release))
                                         (assoc release :gameVersion [(utils/game-track-to-latest-game-version fallback)])
                                         release)]
                           (mapv (fn [game-version]
                                   (merge release {:gameVersionFlavor (utils/game-version-to-game-track game-version)
                                                   :gameVersion [game-version]}))
                                 (:gameVersion release))))]
    (->> stable-releases (map expand-release) flatten (group-by :gameVersionFlavor))))

(defn-spec expand-summary (s/or :ok :addon/source-updates, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  (let [url (api-url "/addon/%s" (:source-id addon-summary))
        result (some-> url http/download http/sink-error utils/from-json)
        latest-release (-> result latest-versions (get game-track) first)]
    (when latest-release
      (let [;; api value is empty in some cases (carbonite, improved loot frames, skada damage meter)
            ;; this value overrides the one found in .toc files, so if it can't be scraped, use the .toc version
            interface-version (some-> latest-release :gameVersion first utils/game-version-to-interface-version)
            interface-version (when interface-version {:interface-version interface-version})]
        (merge {:download-url (:downloadUrl latest-release)
                :version (:displayName latest-release)
                :game-track game-track}
               interface-version)))))

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
