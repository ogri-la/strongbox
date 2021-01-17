(ns strongbox.ui.cli
  (:require
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [spy info warn error debug]]
   [strongbox
    [addon :as addon]
    [nfo :as nfo]
    [constants :as constants]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [catalogue :as catalogue]
    [http :as http]
    [utils :as utils]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths find-catalogue-local-path]]]))

(comment "the UIs pool their logic here, which calls core.clj.")

(defn hard-refresh
  "unlike `core/refresh`, `cli/hard-refresh` clears the http cache before checking for addon updates."
  []
  ;; why can we be more specific, like just the addons for the current addon-dir?
  ;; the url used to 'expand' an addon from the catalogue isn't preserved.
  ;; it may also change with the game track (tukui, historically) or not even exist (tukui, currently)
  ;; a thorough accounting would be too much code.

  ;; this is also removing the etag cache.
  ;; the etag db is pretty worthless and only applies to catalogues and downloaded zip files.
  (core/delete-http-cache!)
  (core/check-for-updates))

(defn-spec set-addon-dir! nil?
  "adds/sets an addon-dir, partial refresh of application state"
  [addon-dir ::sp/addon-dir]
  (core/set-addon-dir! addon-dir)
  (core/load-installed-addons)
  (core/match-installed-addons-with-catalogue)
  (core/check-for-updates)
  (core/save-settings)
  nil)

(defn-spec remove-addon-dir! nil?
  "deletes an addon-dir, selects first available addon dir, partial refresh of application state"
  []
  (core/remove-addon-dir!)
  ;; the next addon dir is selected, if any
  (core/load-installed-addons)
  (core/match-installed-addons-with-catalogue)
  (core/check-for-updates)
  (core/save-settings)
  nil)

(defn-spec set-catalogue-location! nil?
  "changes the catalogue and refreshes application state.
  a complete refresh is necessary for this action as addons accumulate keys like `:matched?` and `:update?`"
  [catalogue-name keyword?]
  (core/set-catalogue-location! catalogue-name)
  (core/db-reload-catalogue)
  nil)

;; search

(defn-spec search-results-next-page nil?
  "increments the current page of results. relies on state watchers to update their search results.
  relies on caller for bounds checking."
  []
  (swap! core/state update-in [:search :page] inc)
  nil)

(defn-spec search-results-prev-page nil?
  "decrements the current page of results. relies on state watchers to update their search results.
  does not descend below 0."
  []
  (when (-> @core/state :search :page (> 0))
    (swap! core/state update-in [:search :page] dec))
  nil)

(defn search
  "updates the search `term` and resets the current page of results to `0`.
  does not actually do any searching, that is up to the interface"
  [search-term]
  (swap! core/state update-in [:search] merge {:term search-term :page 0})
  nil)

(defn-spec random-search nil?
  "trigger a random sample of addons"
  []
  (search (if (-> @core/state :search :term nil?) "" nil)))

(defn search-results
  "returns the current page of results"
  ([]
   (search-results (get-state :search)))
  ([search-state]
   (let [results (:results search-state)
         page (:page search-state)]
     (if-not (empty? results)
       (try
         (nth results page)
         (catch java.lang.IndexOutOfBoundsException e
           (debug (format "precisely %s results returned, there is no next page" (:results-per-page search-state)))
           []))
       []))))

(defn search-has-next?
  "true if we've maxed out the number of results per-page.
  where there are *precisely* that number of results we'll get an empty next page"
  ([]
   (search-has-next? (get-state :search)))
  ([search-state]
   (= (count (search-results search-state))
      (:results-per-page search-state))))

(defn search-has-prev?
  "true if we've navigated forwards"
  ([]
   (search-has-prev? (get-state :search)))
  ([search-state]
   (> (:page search-state) 0)))

(defn-spec -init-search-listener nil?
  "starts a listener that triggers a search whenever the search term is updated.
  results are updated at [:search :results]"
  []
  ;; todo: cancel any other searching going on?
  (core/state-bind
   [:search :term]
   (fn [new-state]
     (future
       (let [results (core/db-search-2 (-> new-state :search :term))]
         (swap! core/state assoc-in [:search :results] results))))))

(defn-spec set-preference nil?
  "updates a user preference `preference-key` with given `preference-val` and saves the settings"
  [preference-key keyword?, preference-val any?]
  (swap! core/state assoc-in [:cfg :preferences preference-key] preference-val)
  (core/save-settings)
  nil)

;;

(defn-spec pin nil?
  "pins the currently selected addons to their current versions.
  addon is ignored if it is already pinned"
  []
  (let [unpinned? (fn [addon]
                    (and (contains? addon :installed-version) ;; can be pinned
                         (not (contains? addon :pinned-version))))  ;; not already pinned
        pin (fn [addon]
              (nfo/pin (core/selected-addon-dir) (:dirname addon)))]
    (->> (get-state :selected-installed)
         (filterv unpinned?)
         (run! pin)))
  (core/refresh))

(defn-spec unpin nil?
  "unpins the currently selected addons, regardless of whether they are pinned or not."
  []
  (let [unpin (fn [addon]
                (nfo/unpin (core/selected-addon-dir) (:dirname addon)))]
    (->> (get-state :selected-installed)
         (run! unpin))
    (core/refresh)))

;;

(defn-spec -install-update-these nil?
  [updateable-addon-list :addon/installable-list]
  (run! core/install-addon updateable-addon-list))

(defn -updateable?
  [rows]
  (filterv :update? rows))

(defn -re-installable?
  "an addon can only be re-installed if it's been matched to an addon in the catalogue and a release available to download"
  [rows]
  (filterv core/expanded? rows))

(defn re-install-selected
  []
  (-> (get-state :selected-installed)
      -re-installable?
      -install-update-these)
  (core/refresh))

(defn re-install-all
  []
  (-> (get-state :installed-addon-list)
      -re-installable?
      -install-update-these)
  (core/refresh))

(defn install-update-selected
  []
  (-> (get-state :selected-installed)
      -updateable?
      -install-update-these)
  (core/refresh))

(defn-spec install-update-all nil?
  []
  (-> (get-state :installed-addon-list)
      -updateable?
      -install-update-these)
  (core/refresh))

(defn-spec remove-selected nil?
  []
  (-> (get-state) :selected-installed vec core/remove-many-addons)
  nil)

(defn-spec ignore-selected nil?
  "marks each of the selected addons as being 'ignored'"
  []
  (->> (get-state :selected-installed)
       (map :dirname)
       (run! (partial nfo/ignore (core/selected-addon-dir))))
  (core/refresh))

(defn-spec clear-ignore-selected nil?
  "removes the 'ignore' flag from each of the selected addons."
  []
  (->> (get-state :selected-installed)
       (mapv addon/ungroup-addon)
       flatten
       (mapv :dirname)
       (run! (partial addon/clear-ignore (core/selected-addon-dir))))
  (core/refresh))

;; selecting addons

(defn-spec select-addons-search* nil?
  "sets the selected list of addons in application state for a later action"
  [selected-addons :addon/summary-list]
  (swap! core/state assoc :selected-search selected-addons)
  nil)

(defn-spec select-addons* nil?
  "sets the selected list of addons to the given `selected-addons` for bulk operations like 'update', 'delete', 'ignore', etc"
  [selected-addons :addon/installed-list]
  (swap! core/state assoc :selected-installed selected-addons)
  nil)

(defn-spec select-addons nil?
  "creates a sub-selection of installed addons for bulk operations like 'update', 'delete', 'ignore', etc.
  called with no args, selects *all* installed addons.
  called with a function, selects just those where `(filter-fn addon)` is `true`."
  ([]
   (select-addons identity))
  ([filter-fn fn?]
   (->> (get-state :installed-addon-list)
        (filter filter-fn)
        (remove nil?)
        vec
        select-addons*)))

;; debug

(defn-spec touch nil?
  "used to select each addon in the GUI so the 'unsteady' colour can be tested."
  []
  (let [touch (fn [a]
                (core/start-affecting-addon a)
                (Thread/sleep 200)
                (core/stop-affecting-addon a))]
    (->> (get-state :installed-addon-list)
         -updateable?
         (run! touch))))

;;

(defmulti action
  "handles the following actions:
    :scrape-wowinterface-catalogue - scrapes wowinterface host and creates a wowinterface catalogue
    :scrape-curseforge-catalogue - scrapes curseforge host and creates a curseforge catalogue
    :scrape-catalogue - scrapes all available sources and creates a full and short catalogue
    :list - lists all installed addons
    :list-updates - lists all installed addons with updates available
    :update-all - updates all installed addons with updates available"
  (fn [x]
    (cond
      (map? x) (:action x)
      (keyword? x) x)))

(defmethod action :scrape-wowinterface-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :wowinterface)
          catalogue-data (wowinterface/scrape)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-curseforge-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :curseforge)
          catalogue-data (curseforge-api/download-all-summaries-alphabetically)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-tukui-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :tukui)
          catalogue-data (tukui-api/download-all-summaries)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :write-catalogue
  [_]
  (let [curseforge-catalogue (find-catalogue-local-path :curseforge)
        wowinterface-catalogue (find-catalogue-local-path :wowinterface)
        tukui-catalogue (find-catalogue-local-path :tukui)

        catalogue-path-list [curseforge-catalogue wowinterface-catalogue tukui-catalogue]
        catalogue (mapv catalogue/read-catalogue catalogue-path-list)
        catalogue (reduce catalogue/merge-catalogues catalogue)]
    (if-not catalogue
      (warn "no catalogue data found, nothing to write")
      (-> catalogue
          (catalogue/write-catalogue (find-catalogue-local-path :full))

          ;; 'short' catalogue is derived from the full catalogue
          (catalogue/shorten-catalogue constants/release-of-previous-expansion)
          (catalogue/write-catalogue (find-catalogue-local-path :short))))))

(defmethod action :scrape-catalogue
  [_]
  (action :scrape-curseforge-catalogue)
  (action :scrape-wowinterface-catalogue)
  (action :scrape-tukui-catalogue)
  (action :write-catalogue))

(defmethod action :list
  [_]
  (let [installed-addons (get-state :installed-addon-list)]
    (println (count installed-addons) "installed addons")
    (doseq [{:keys [dirname installed-version]} installed-addons]
      (println (format "%s (%s)" dirname, installed-version)))))

(defmethod action :list-updates
  [_]
  (let [installed-addons (get-state :installed-addon-list)
        updates (filter :update? installed-addons)]
    (println (count installed-addons) "installed")
    (println (count updates) "updates")
    (doseq [{:keys [dirname installed-version version]} updates]
      (println (format "%s (%s => %s)" dirname, installed-version version)))))

(defmethod action :update-all
  [_]
  (install-update-all)
  (action :list-updates))

(defmethod action :default
  [opts]
  (println "(no action) given:" opts))

(defn start
  [opts]
  (info "starting cli")
  (-init-search-listener)
  (core/refresh)
  (action opts))

(defn stop
  []
  (info "stopping cli")
  nil)
