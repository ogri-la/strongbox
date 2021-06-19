(ns strongbox.ui.cli
  (:require
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error report spy]]
   [clojure.spec.alpha :as s]
   [strongbox
    [logging :as logging]
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

(defn-spec toggle-split-pane nil?
  []
  (swap! core/state update-in [:gui-split-pane] not)
  nil)

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
  (report "refresh")
  (core/check-for-updates))

(defn-spec half-refresh nil?
  "like core/refresh but focuses on loading+matching+checking for updates"
  []
  (report "refresh")
  (core/load-installed-addons)
  (core/match-installed-addons-with-catalogue)
  (core/check-for-updates)
  (core/save-settings)
  nil)

(defn-spec set-addon-dir! nil?
  "adds/sets an addon-dir, partial refresh of application state"
  [addon-dir ::sp/addon-dir]
  (core/set-addon-dir! addon-dir)
  (half-refresh))

(defn-spec set-game-track-strictness! nil?
  "toggles the 'strict' flag for the current addon directory and reloads addons"
  [new-strictness-level ::sp/strict?]
  (core/set-game-track-strictness! new-strictness-level)
  (half-refresh))

(defn-spec remove-addon-dir! nil?
  "deletes an addon-dir, selects first available addon dir, partial refresh of application state"
  []
  (core/remove-addon-dir!)
  ;; the next addon dir is selected, if any
  (half-refresh))

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
  (let [search-term (if (empty? search-term)
                      (if (-> @core/state :search :term nil?) "" nil)
                      search-term)]
    (swap! core/state update-in [:search] merge {:term search-term :page 0}))
  nil)

(defn-spec random-search nil?
  "trigger a random sample of addons"
  []
  (search nil))

(defn-spec bump-search nil?
  "search for the given search term, if one exists, and adds some whitespace to jog the GUI into forcing an empty.
  the db search function trims whitespace so there won't be any change to results"
  []
  (search (some-> @core/state :search :term (str " "))))

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

;;

(defn-spec change-catalogue nil?
  "changes the catalogue and refreshes application state.
  a complete refresh is necessary for this action as addons accumulate keys like `:matched?` and `:update?`"
  [catalogue-name (s/nilable (s/or :simple string?, :named keyword?))]
  (when catalogue-name
    (core/set-catalogue-location! (keyword catalogue-name))
    (core/db-reload-catalogue)
    (core/empty-search-results)
    (bump-search))
  nil)

(defn init-ui-logger
  []
  (core/reset-logging!)
  (core/state-bind
   [:cfg :selected-addon-dir]
   (fn [new-state]
     (core/reset-logging!))))

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
   (run! (fn [addon]
           (logging/with-addon addon
             (info (format "pinning to \"%s\"" (:installed-version addon)))
             (addon/pin (core/selected-addon-dir) addon)))
         addon-list)
   (core/refresh)))

(defn-spec unpin nil?
  "unpins the addons in given `addon-list` regardless of whether they are pinned or not.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (unpin (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! (fn [addon]
           (logging/addon-log addon :info (format "unpinning from \"%s\"" (:pinned-version addon)))
           (addon/unpin (core/selected-addon-dir) addon))
         addon-list)
   (core/refresh)))

(defn-spec -find-replace-release (s/or :ok :addon/expanded, :release-not-found nil?)
  "looks for the `:installed-version` in the `:release-list` and, if found, updates the addon.
  this is an intermediate step before pinning or installing a previous release."
  [addon :addon/expanded]
  (if-let [matching-release (addon/find-release addon)]
    (merge addon matching-release)
    (logging/with-addon addon
      (warn (format "release \"%s\" not found, using latest instead." (:installed-version addon)))
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
  "updates all installed addons with any new releases.
  command is ignored if any addons are unsteady"
  []
  (if (empty? (get-state :unsteady-addon-list))
    (do (->> (get-state :installed-addon-list)
             (filter addon/updateable?)
             -install-update-these)
        (core/refresh))
    (warn "updates in progress, 'update all' command ignored")))

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
        (run! (fn [addon]
                (logging/with-addon addon
                  (info "ignoring")
                  (addon/ignore (core/selected-addon-dir) addon)))))
   (core/refresh)))

(defn-spec clear-ignore-selected nil?
  "removes the 'ignore' flag from each addon in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (clear-ignore-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! (fn [addon]
           (logging/with-addon addon
             (addon/clear-ignore (core/selected-addon-dir) addon)
             (info "stopped ignoring")))
         addon-list)
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

(defn-spec change-notice-logger-level nil?
  "changes the log level on the UI notice-logger widget.
  changes the log level for a tab in `:tab-list` when `tab-idx` is also given."
  ([new-log-level ::sp/log-level]
   (change-notice-logger-level new-log-level nil))
  ([new-log-level ::sp/log-level, tab-idx (s/nilable int?)]
   (if tab-idx
     (swap! core/state assoc-in [:tab-list tab-idx :log-level] new-log-level)
     (swap! core/state assoc :gui-log-level new-log-level))
   nil))

(defn-spec remove-all-tabs nil?
  "removes all dynamic addon detail tabs leaving only the static tabs"
  []
  (swap! core/state assoc :tab-list [])
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
                 :log-level :info
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
    (add-tab tab-id (or (:dirname addon) (:label addon) (:name addon) "[bug: missing tab name!]") closable? addon-id)))

(defn-spec log-entries-since-last-refresh ::sp/list-of-maps
  "returns a list of log entries since last refresh"
  ([]
   (log-entries-since-last-refresh (core/get-state :log-lines)))
  ([log-lines ::sp/list-of-maps]
   (let [not-report #(-> % :level (= :report) not)]
     (->> log-lines ;; old->new
          reverse ;; new->old
          (take-while not-report)
          reverse ;; old->new again, but truncated
          vec))))

(defn-spec addon-log-entries (s/or :ok ::sp/list-of-maps, :app-not-started nil?)
  "returns a list of addon entries for the given `:dirname` since last refresh"
  [addon map?]
  (when @core/state
    (let [not-report #(-> % :level (= :report) not)
          filter-fn (logging/log-line-filter-with-reports (core/selected-addon-dir) addon)]
      (->> (core/get-state)
           :log-lines ;; oldest first
           reverse ;; newest first
           (filter filter-fn)
           (take-while not-report)
           reverse ;; oldest first again, but truncated
           vec))))

(defn-spec addon-num-log-level int?
  "returns the number of log entries given `dirname` has for given `log-level` or 0 if not present"
  [log-level ::sp/log-level, dirname ::sp/dirname]
  (->> {:dirname dirname}
       addon-log-entries
       (filter #(= (:level %) log-level))
       count))

(defn-spec addon-num-warnings int?
  "returns the number of warnings present for the given `addon` in the log."
  [addon map?]
  (addon-num-log-level :warn (:dirname addon)))

(defn-spec addon-num-errors int?
  "returns the number of errors present for the given `addon` in the log."
  [addon map?]
  (addon-num-log-level :error (:dirname addon)))

(defn-spec addon-has-log-level? boolean?
  "returns `true` if the given `addon` has any log entries of the given log `level`."
  [log-level ::sp/log-level, dirname ::sp/dirname]
  (> (addon-num-log-level log-level dirname) 0))

(defn-spec addon-has-warnings? boolean?
  "returns `true` if the given `addon` has any warnings in the log."
  [addon map?]
  (addon-has-log-level? :warn (:dirname addon)))

(defn-spec addon-has-errors? boolean?
  "returns `true` if the given `addon` has any errors in the log."
  [addon map?]
  (addon-has-log-level? :error (:dirname addon)))


;; debug


(defn-spec touch nil?
  "used to select each addon in the GUI so the 'unsteady' colour can be tested."
  []
  (let [touch (fn [a]
                (core/start-affecting-addon a)
                (Thread/sleep 200)
                (core/stop-affecting-addon a))]
    (->> (get-state :installed-addon-list)
         ;;(filter addon/updateable?)
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
  (init-ui-logger)
  (-init-search-listener)

  (core/refresh)
  (action opts))

(defn stop
  []
  (info "stopping cli")
  nil)
