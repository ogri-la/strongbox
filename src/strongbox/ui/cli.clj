(ns strongbox.ui.cli
  (:require
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [spy info warn error debug]]
   [clojure.spec.alpha :as s]
   [strongbox
    [addon :as addon]
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

(defn-spec hard-refresh nil?
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

(defn-spec search nil?
  "updates the search `term` and resets the current page of results to `0`.
  does not actually do any searching, that is up to the interface"
  [search-term (s/nilable string?)]
  (swap! core/state update-in [:search] merge {:term search-term :page 0})
  nil)

(defn-spec random-search nil?
  "trigger a random sample of addons"
  []
  (search (if (-> @core/state :search :term nil?) "" nil)))

(defn-spec search-results ::sp/list-of-maps
  "returns the current page of results"
  ([]
   (search-results (get-state :search)))
  ([search-state map?]
   (let [results (:results search-state)
         page (:page search-state)]
     (if-not (empty? results)
       (try
         (nth results page)
         (catch java.lang.IndexOutOfBoundsException e
           (debug (format "precisely %s results returned, there is no next page" (:results-per-page search-state)))
           []))
       []))))

(defn-spec search-has-next? boolean?
  "true if we've maxed out the number of results per-page.
  where there are *precisely* that number of results we'll get an empty next page"
  ([]
   (search-has-next? (get-state :search)))
  ([search-state map?]
   (= (count (search-results search-state))
      (:results-per-page search-state))))

(defn-spec search-has-prev? boolean?
  "returns `true` if we've navigated forwards and previous pages of search results exist"
  ([]
   (search-has-prev? (get-state :search)))
  ([search-state map?]
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
       (let [results (core/db-search (-> new-state :search :term))]
         (swap! core/state assoc-in [:search :results] results))))))

(defn-spec set-preference nil?
  "updates a user preference `preference-key` with given `preference-val` and saves the settings"
  [preference-key keyword?, preference-val any?]
  (swap! core/state assoc-in [:cfg :preferences preference-key] preference-val)
  (core/save-settings)
  nil)

(defn-spec set-version nil?
  "updates `addon` with the given `release` data and then installs it."
  [addon :addon/installable, release :addon/source-updates]
  (core/install-addon (merge addon release))
  (core/refresh))

;;

(defn-spec pin nil?
  "pins the addons in given `addon-list` to their current `:installed-version` versions.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (pin (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! #(addon/pin (core/selected-addon-dir) %)
         addon-list)
   (core/refresh)))

(defn-spec unpin nil?
  "unpins the addons in given `addon-list` regardless of whether they are pinned or not.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (unpin (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! #(addon/unpin (core/selected-addon-dir) %)
         addon-list)
   (core/refresh)))

(defn-spec -find-replace-release (s/or :ok :addon/expanded, :release-not-found nil?)
  "looks for the `:installed-version` in the list of available releases and, if found, updates the addon."
  [addon :addon/expanded]
  (if-let [matching-release (addon/find-release addon)]
    (merge addon matching-release)
    (do (warn (format "%s '%s' not found in known releases. Using latest release instead." (:label addon) (:installed-version addon)))
        addon)))

;;

(defn-spec -install-update-these nil?
  [updateable-addon-list :addon/installable-list]
  (run! core/install-addon updateable-addon-list))

(defn-spec re-install-or-update-selected nil?
  "re-installs (if possible) or updates all addons in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (re-install-or-update-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter core/expanded?)
        (map -find-replace-release)
        -install-update-these)
   (core/refresh)))

(defn-spec re-install-or-update-all nil?
  "re-installs (if possible) or updates all installed addons"
  []
  (->> (get-state :installed-addon-list)
       (filter core/expanded?)
       (map -find-replace-release)
       -install-update-these)
  (core/refresh))

(defn-spec install-addon nil?
  "install an addon from the catalogue.
  should work on expanded addons as well, but those are already installed ...?"
  [addon :addon/summary]
  (-> addon
      core/expand-summary-wrapper
      vector
      -install-update-these)
  (core/refresh))

(defn-spec update-selected nil?
  "updates all addons in given `addon-list` that have updates available.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (update-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter addon/updateable?)
        -install-update-these)
   (core/refresh)))

(defn-spec update-all nil?
  "updates all installed addons with updates available"
  []
  (->> (get-state :installed-addon-list)
       (filter addon/updateable?)
       -install-update-these)
  (core/refresh))

(defn-spec delete-selected nil?
  "deletes all addons in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (delete-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (core/remove-many-addons addon-list)))

(defn-spec ignore-selected nil?
  "marks each addon in given `addon-list` as being 'ignored'.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (ignore-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter addon/ignorable?)
        (run! (partial addon/ignore (core/selected-addon-dir))))
   (core/refresh)))

(defn-spec clear-ignore-selected nil?
  "removes the 'ignore' flag from each addon in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (clear-ignore-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! (partial addon/clear-ignore (core/selected-addon-dir)) addon-list)
   (core/refresh)))

;; selecting addons

(defn-spec select-addons-search* nil?
  "sets the selected list of addons in application state for a later action"
  [selected-addons :addon/summary-list]
  (swap! core/state assoc-in [:search :selected-result-list] selected-addons)
  nil)

(defn-spec select-addons* nil?
  "sets the selected list of addons to the given `selected-addons` for bulk operations like 'update', 'delete', 'ignore', etc"
  [selected-addons :addon/installed-list]
  (swap! core/state assoc :selected-addon-list selected-addons)
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

;; tabs

(defn-spec remove-all-tabs nil?
  "removes all dynamic addon detail tabs leaving only the static tabs"
  []
  (swap! core/state assoc :tab-list [])
  nil)

(defn-spec remove-tab nil?
  "removes a specific tab from the `:tab-list` using that tab's `:tab-id`"
  [tab-id string?]
  (swap! core/state update-in [:tab-list] (partial (comp vec remove) #(= tab-id (:tab-id %))))
  nil)

(defn-spec remove-tab-at-idx nil?
  "removes a tab from the `:tab-list` at a specific given `idx`."
  [idx int?]
  (swap! core/state update-in [:tab-list] utils/drop-idx idx)
  nil)

(defn-spec add-tab nil?
  "adds a tab to `:tab-list`.
  if tab already exists then it is removed from list and the new one is appeneded to the end.
  this is purely so the latest tab can be selected without index wrangling."
  [tab-id :ui/tab-id, tab-label ::sp/label, closable? ::sp/closable?, tab-data :ui/tab-data]
  (let [new-tab {:tab-id tab-id
                 :label tab-label
                 :closable? closable?
                 :tab-data tab-data}
        tab-list (remove (fn [tab]
                           (= (dissoc tab :tab-id)
                              (dissoc new-tab :tab-id)))
                         (core/get-state :tab-list))
        tab-list (vec (concat tab-list [new-tab]))]
    (swap! core/state assoc :tab-list tab-list))
  nil)

(defn-spec add-addon-tab nil?
  "convenience, adds a tab using given the `addon` data"
  [addon map?]
  (let [tab-id (utils/unique-id)
        closable? true
        addon-id (utils/extract-addon-id addon)]
    (add-tab tab-id (:label addon) closable? addon-id)))


;; debug


(defn-spec touch nil?
  "used to select each addon in the GUI so the 'unsteady' colour can be tested."
  []
  (let [touch (fn [a]
                (core/start-affecting-addon a)
                (Thread/sleep 200)
                (core/stop-affecting-addon a))]
    (->> (get-state :installed-addon-list)
         (filter addon/updateable?)
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
  (update-all)
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
