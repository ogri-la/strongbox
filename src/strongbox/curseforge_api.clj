(ns strongbox.curseforge-api
  (:require
   [strongbox
    [tags :as tags]
    [http :as http]
    [specs :as sp]
    [utils :as utils :refer [to-int to-json fmap join from-epoch to-url]]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def curseforge-api "https://addons-ecs.forgesvc.net/api/v2")

(defn-spec api-url ::sp/url
  [path string?, & args (s/* any?)]
  (str curseforge-api (apply format path args)))

(defn latest-versions-by-gameVersionFlavor
  "given a curseforge-api result, returns a map of release data.
  uses :gameVersionFlavor which, while always present, is inaccurate as a release may support both classic and retail"
  [api-result]

  ;; 'latestFiles' :
  ;; - gameVersionFlavour: that is either "wow_retail" or "wow_classic"
  ;; - fileStatus: 4 means ... ?
  ;; - releaseType: 1 is regular/stable, 2 is beta, 3 is alpha
  ;; - exposeAsAlternative: nil is no, true is yes
  ;;    - see Recount. there is a '-nolib' alternative available.

  ;; we want to say "give me the latest stable release of the wow classic track of this addon
  ;; for now we're going to ignore anything that isn't a releaseType of 1 (stable)

  (let [latest-files (:latestFiles api-result)

        ;; results appear sorted, but lets be sure as we'll be taking the first
        desc (comp - compare) ;; most to least recent (desc)
        latest-files (sort-by :fileDate desc latest-files)

        ;; stable releases only, for now
        stable 1 ;; 2 is beta, 3 is alpha
        stable-releases (filterv #(= (:releaseType %) stable) latest-files)

        ;; no alternative versions, for now
        stable-releases (remove :exposeAsAlternative stable-releases)

        ;; replace usage of "wow_retail" and "wow_classic" with :retail and :classic
        stable-releases (mapv (fn [release]
                                (let [new-flavor (if (= "wow_classic" (:gameVersionFlavor release))
                                                   :classic :retail)]
                                  (assoc release :gameVersionFlavor new-flavor)))
                              stable-releases)]

    ;; I don't know if it's possible, but a group may still have more than one result
    ;; results are ordered and group-by preserves ordering, so take the first
    (group-by :gameVersionFlavor stable-releases)))

(defn latest-versions-by-gameVersion
  "given a curseforge-api result, returns a map of release data
  uses :gameVersion which, if present, indicates the game tracks a release supports
  prefer this over `latest-versions-by-gameVersionFlavor`"
  [api-result]

  ;; issue #63: curseforge actually allow a release to be on both retail and classic game tracks.
  ;; the single value under "gameVersionFlavor" is inaccurate and misleading and we can't trust it.
  ;; instead we look at the "gameVersion" list and convert the versions we find there into game tracks.
  ;; 8.2.0 and 8.2.5 => 'retail'
  ;; 1.13.2 => 'classic'

  (let [latest-files (:latestFiles api-result)

        ;; results appear sorted, but lets be sure as we'll be taking the first
        desc (comp - compare) ;; most to least recent (desc)
        latest-files (sort-by :fileDate desc latest-files)

        ;; stable releases only, for now
        stable 1 ;; 2 is beta, 3 is alpha
        stable-releases (filterv #(= (:releaseType %) stable) latest-files)

        ;; no alternative versions, for now
        stable-releases (remove :exposeAsAlternative stable-releases)

        ;; for each release, set the correct value for :gameVersionFlavor and :gameVersion
        ;; returning multiple instances of the release if necessary
        expand-release (fn [release]
                         (mapv (fn [game-version]
                                 (merge release {:gameVersionFlavor (utils/game-version-to-game-track game-version)
                                                 :gameVersion [game-version]}))
                               (:gameVersion release)))]

    (->> stable-releases (map expand-release) flatten (group-by :gameVersionFlavor))))

(defn latest-versions
  [api-result]
  (if (->> api-result :latestFiles (map :gameVersion) (some empty?))
    ;; at least one release is missing :gameVersion data, use :gameVersionFlavor instead
    (latest-versions-by-gameVersionFlavor api-result)
    (latest-versions-by-gameVersion api-result)))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary game-track ::sp/game-track]
  (let [pid (-> addon-summary :source-id)
        url (api-url "/addon/%s" pid)
        result (-> url http/download utils/from-json)
        latest-release (-> result latest-versions (get game-track) first)]
    (if-not latest-release
      (warn (format "no '%s' release available for '%s' on curseforge" game-track (:name addon-summary)))
      (let [;; api value is empty in some cases (carbonite, improved loot frames, skada damage meter)
            ;; this value overrides the one found in .toc files, so if it can't be scraped, use the .toc version
            interface-version (some-> latest-release :gameVersion first utils/game-version-to-interface-version)
            interface-version (when interface-version {:interface-version interface-version})

            details {:download-url (:downloadUrl latest-release)
                     :version (:displayName latest-release)}]
        (merge addon-summary details interface-version)))))

(defn-spec extract-addon-summary ::sp/addon-summary
  "converts addon data extracted from a listing into an ::sp/addon-summary"
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

(defn-spec download-all-summaries-alphabetically (s/or :ok ::sp/addon-summary-list, :error nil?)
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
