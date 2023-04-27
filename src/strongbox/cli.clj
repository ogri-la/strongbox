(ns strongbox.cli
  (:require
   [clojure.string]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error report spy]]
   [clojure.spec.alpha :as s]
   [me.raynes.fs :as fs]
   [strongbox
    [zip :as zip]
    [joblib :as joblib]
    [github-api :as github-api]
    [tukui-api :as tukui-api]
    [gitlab-api :as gitlab-api]
    ;;[curseforge-api :as curseforge-api]
    [wowinterface-api :as wowinterface-api]
    [logging :as logging]
    [addon :as addon]
    [specs :as sp]
    [catalogue :as catalogue]
    [http :as http]
    [utils :as utils :refer [if-let* message-list]]
    [core :as core :refer [get-state paths]]]))

(comment
  "cli.clj and gui.clj pool their logic here, which calls core.clj.
   or at least that's the idea.")

(defn-spec toggle-split-pane nil?
  []
  (swap! core/state update-in [:gui-split-pane] not)
  nil)

;; selecting addons

(defn-spec select-addons-for-search! nil?
  "replaces the selected addons in the search state with the given `addon-list`."
  [addon-list :addon/summary-list]
  (swap! core/state assoc-in [:search :selected-result-list] addon-list)
  nil)

(defn-spec clear-selected-search-addons! nil?
  "removes all selected addons from the search state."
  []
  (select-addons-for-search! []))

(defn-spec select-addons* nil?
  "sets the selected list of addons to the given `selected-addons` for bulk operations like 'update', 'delete', 'ignore', etc"
  [selected-addons :addon/installed-list]
  (swap! core/state assoc :selected-addon-list selected-addons)
  nil)

(defn-spec select-addons nil?
  "creates a sub-selection of installed addons for bulk operations like 'update', 'delete', 'ignore', etc.
  called with no args, selects *all* installed addons.
  called with a function, selects just those where `(filter-fn addon)` returns `true`."
  ([]
   (select-addons identity))
  ([filter-fn fn?]
   (->> (get-state :installed-addon-list)
        (filter filter-fn)
        (remove nil?)
        vec
        select-addons*)))

(defn-spec clear-selected-addons! nil?
  "removes all addons from the `:selected-addon-list` list"
  []
  (select-addons* []))

;; ui refreshing

(defn-spec hard-refresh nil?
  "unlike `core/refresh`, `cli/hard-refresh` clears the http cache before checking for addon updates."
  []
  ;; why can't we be more specific, like just the addons for the current addon-dir?
  ;; the url used to 'expand' an addon from the catalogue isn't preserved.
  ;; it may also change with the game track (tukui, historically) or not even exist (tukui, currently).
  ;; a thorough inspection would be too much code.
  ;; this also removes the etag cache. the etag db only applies to catalogues and downloaded zip files.
  (core/delete-http-cache!)
  (core/refresh))

(defn-spec half-refresh nil?
  "like `core/refresh` but excludes reloading catalogues, focusing on re-reading installed addons,
  matching them to the catalogue and reapplying host updates."
  []
  (report "refresh")
  (core/load-all-installed-addons)
  (core/match-all-installed-addons-with-catalogue)
  (core/check-for-updates)
  (core/save-settings!))

(defn-spec set-addon-dir! nil?
  "adds and sets the given `addon-dir`, then reloads addons."
  [addon-dir ::sp/addon-dir]
  (core/set-addon-dir! addon-dir)
  (half-refresh))

(defn-spec set-game-track-strictness! nil?
  "changes the the 'strict' flag for the current addon directory, then reloads addons."
  [new-strictness-level ::sp/strict?]
  (core/set-game-track-strictness! new-strictness-level)
  (half-refresh))

(defn-spec remove-addon-dir! nil?
  "deletes an addon-dir, selects first available addon dir, partial refresh of application state"
  []
  (core/remove-addon-dir!) ;; the next addon dir is selected, if any
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
  "updates the `[:search :term]` and resets the current page of results to `0`.
  does not actually do any searching, that is up to the interface/state watchers."
  [search-term (s/nilable string?)]
  (let [;; if the given search term is empty (nil, "") and the *current* search term is empty, switch empty values.
        ;; this refresh the search results with a new random sample.
        search-term (if (empty? search-term)
                      (if (-> @core/state :search :term nil?) "" nil)
                      search-term)]
    (swap! core/state update-in [:search] merge {:term search-term :page 0}))
  nil)

(defn-spec random-search nil?
  "trigger a random sample of addons"
  []
  (search nil))

(defn-spec bump-search nil?
  "search for the set `[:search :term]`, if one exists, and adds some whitespace to jog the GUI into forcing an empty.
  the db search function trims whitespace so there won't be any change to results"
  []
  (search (some-> @core/state :search :term (str " "))))

(defn-spec search-results ::sp/list-of-maps
  "returns the current page of search results"
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
  "returns `true` if we've maxed out the number of per-page results.
  when there are *precisely* that number of results we'll get an empty next page."
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
  (let [path-list [[:search :term]
                   [:search :filter-by]]
        listener (fn [new-state]
                   (future
                     (let [{:keys [term results-per-page filter-by]} (:search new-state)
                           results (core/db-search term results-per-page filter-by)]
                       (dosync
                        (clear-selected-search-addons!)
                        (swap! core/state assoc-in [:search :results] results)))))]
    (doseq [path path-list]
      (core/state-bind path listener))))

(defn-spec search-add-filter nil?
  "adds a new filter to the search `filter-by` state."
  [filter-by :search/filter-by, val any?]
  (core/reset-search-navigation)
  (case filter-by
    :source (swap! core/state assoc-in [:search :filter-by filter-by] (utils/nilable val))
    :tag (swap! core/state update-in [:search :filter-by filter-by] conj val)
    :tag-membership (swap! core/state assoc-in [:search :filter-by filter-by] val))
  nil)

(defn-spec search-rm-filter nil?
  "removes a filter from the search `filter-by` state."
  [filter-by :search/filter-by, val any?]
  (core/reset-search-navigation)
  (swap! core/state update-in [:search :filter-by filter-by] clojure.set/difference #{val})
  nil)

(defn-spec search-toggle-filter nil?
  "toggles boolean filters on and off"
  [filter-by :search/filter-by]
  (core/reset-search-navigation)
  (swap! core/state update-in [:search :filter-by filter-by] not)
  nil)

(defn-spec clear-search! nil?
  "resets the search state to defaults and jogs the search results"
  []
  (core/reset-search-state!)
  (bump-search))

;;

(defn-spec change-catalogue nil?
  "changes the catalogue and refreshes application state.
  a complete refresh (see `core/db-reload-catalogue`) is necessary for this action as
  addons accumulate keys like `:matched?` and `:update?`"
  [catalogue-name (s/nilable (s/or :simple string?, :named keyword?))]
  (when catalogue-name
    (core/set-catalogue! (keyword catalogue-name))
    (report "switched catalogues")
    (core/db-reload-catalogue) ;; calls `core/refresh`
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
  "updates a user preference `preference-key` with given `preference-val` and saves the settings."
  [preference-key keyword?, preference-val any?]
  (swap! core/state assoc-in [:cfg :preferences preference-key] preference-val)
  (core/save-settings!))

;;

(defn-spec pin nil?
  "pins the given `addon-list` to their current `:installed-version` versions.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (pin (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! (fn [addon]
           (logging/with-addon addon
             (info (format "pinning to \"%s\"" (:installed-version addon)))
             (addon/pin! (core/selected-addon-dir) addon)))
         addon-list)
   (half-refresh)))

(defn-spec unpin nil?
  "unpins the addons in given `addon-list` regardless of whether they are pinned or not.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (unpin (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (run! (fn [addon]
           (logging/addon-log addon :info (format "unpinning from \"%s\"" (:pinned-version addon)))
           (addon/unpin! (core/selected-addon-dir) addon))
         addon-list)
   (core/refresh)))

;;

(defn-spec install-update-these-serially nil?
  "installs/updates a list of addons serially.
  2022-09: not really used to install multiple addons any more, just individual addons from search results."
  [updateable-addon-list :addon/installable-list]
  (run! core/install-addon-guard-affective updateable-addon-list))

(defn-spec zipfile-locks set?
  "returns a set of the top-level directories that will be needed after unzipping the given `downloaded-file`."
  [downloaded-file ::sp/extant-archive-file]
  (let [strip-trailing-slash #(utils/rtrim % "/")]
    (->> downloaded-file
         zip/zipfile-normal-entries
         zip/top-level-directories
         (map :path)
         (map strip-trailing-slash)
         set)))

(defn-spec install-update-these-in-parallel nil?
  "installs/updates a list of addons in parallel.
  does a clever refresh check afterwards to try and prevent a full refresh from happening."
  [updateable-addon-list :addon/installable-list]
  (let [queue-atm (core/get-state :job-queue)
        install-dir (core/selected-addon-dir)
        current-locks (atom #{})
        new-dirs (atom #{})
        job-fn (fn [addon]
                 (let [downloaded-file (core/download-addon-guard-affective addon install-dir)
                       existing-dirs (addon/dirname-set addon)
                       updated-dirs (zipfile-locks downloaded-file)
                       locks-needed (clojure.set/union existing-dirs updated-dirs)]
                   (swap! new-dirs into updated-dirs)
                   (utils/with-lock current-locks locks-needed
                     (core/install-addon-affective addon install-dir downloaded-file)
                     (core/refresh-addon addon))))]
    (run! #(joblib/create-addon-job! queue-atm % job-fn) updateable-addon-list)
    (joblib/run-jobs! queue-atm core/num-concurrent-downloads)
    ;; if any of the new directories introduced are not present in the :installed-addon-list, do a full refresh.
    (core/refresh-check @new-dirs)
    nil))

(defn-spec -find-replace-release (s/or :ok :addon/expanded, :release-not-found nil?)
  "looks for the `:installed-version` in the given `addon`'s `:release-list` and, if found, updates the addon map.
  this is an intermediate step before pinning or installing a previous release."
  [addon :addon/expanded]
  (if-let [matching-release (addon/find-release addon)]
    (merge addon matching-release)
    (logging/with-addon addon
      (warn (format "release \"%s\" not found, using latest instead." (:installed-version addon)))
      addon)))

(defn-spec re-install-or-update nil?
  "re-installs (if possible) or updates all addons in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (re-install-or-update (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter core/expanded?)
        (map -find-replace-release)
        install-update-these-in-parallel)))

(defn-spec unique-group-id-from-zip-file string?
  "generates a reasonably unique `group-id` from the given `downloaded-file` filename."
  [downloaded-file ::sp/file]
  (let [uniquish-id (subs (utils/unique-id) 0 8)]
    (-> downloaded-file ;; /foo/bar/baz--1-2-3.zip
        fs/base-name ;; baz--1-2-3.zip
        fs/split-ext ;; [baz--1-2-3, .zip]
        first ;; baz--1-2-3
        (clojure.string/split #"--") ;; [baz, 1-2-3]
        first ;; baz
        (str "-" uniquish-id)))) ;; baz-467cec22

#_(defn-spec install-addon-from-file map?
    "install an addon from a zip file."
    [downloaded-file ::sp/extant-archive-file]
    (let [addon {:group-id (unique-group-id-from-zip-file downloaded-file)}
          error-messages
          (logging/buffered-log
           :warn
           (addon/install-addon addon (core/selected-addon-dir) downloaded-file))]
      (core/refresh)
      {:label (fs/base-name downloaded-file)
       :error-messages error-messages}))

(defn-spec install-addons-from-file-in-parallel ::sp/list-of-maps
  "installs/updates a list of addon zip files in parallel.
  does a clever refresh check afterwards to try and prevent a full refresh from happening.
  very similar code to `install-update-these-in-parallel`."
  [download-file-list (s/coll-of ::sp/extant-archive-file)]
  (let [queue-atm (core/get-state :job-queue)
        install-dir (core/selected-addon-dir)
        current-locks (atom #{})
        new-dirs (atom #{})
        job-fn (fn [downloaded-file] ;;[addon]
                 (let [;;downloaded-file (core/download-addon-guard-affective addon install-dir)
                       ;;existing-dirs (addon-locks addon) ;; zip file is untethered from addon data
                       updated-dirs (zipfile-locks downloaded-file)
                       ;;locks-needed (clojure.set/union existing-dirs updated-dirs)
                       locks-needed updated-dirs
                       addon {:group-id (unique-group-id-from-zip-file downloaded-file)}]
                   (swap! new-dirs into updated-dirs)
                   (utils/with-lock current-locks locks-needed
                     (let [error-messages
                           (logging/buffered-log
                            :warn
                            (let [results (addon/install-addon addon install-dir downloaded-file)]
                              (when-let [installed-addon-dir (some-> results first fs/parent str)]
                                (core/refresh-addon* installed-addon-dir))))]

                       {:label (fs/base-name downloaded-file)
                        :error-messages error-messages}))))

        _ (run! (fn [downloaded-file]
                  (joblib/create-job! queue-atm #(job-fn downloaded-file))) download-file-list)
        results (joblib/run-jobs! queue-atm core/num-concurrent-downloads)]

    ;; if any of the new directories introduced are not present in the :installed-addon-list, do a full refresh.
    ;; a same-set of directories that replaced existing directories (but with different group-ids) may cause orphans in the installed-addon-list.
    (core/refresh-check @new-dirs)

    results))

(defn-spec install-addon nil?
  "install an addon from the catalogue. works on expanded addons as well."
  [addon :addon/summary]
  (some-> addon
          core/expand-summary-wrapper
          vector
          install-update-these-serially)
  ;; todo: half-refresh?
  (core/refresh))

;; todo: this doesn't seem to have any protection against mutual dependencies ...
;; see `install-update-these-in-parallel`
(defn-spec install-many ::sp/list-of-maps
  "install many addons from the catalogue.
  a bit different from the other installation functions, this one returns a list of maps with the installation results."
  [addon-list :addon/summary-list]
  (let [queue-atm (core/get-state :job-queue)
        job (fn [addon]
              (let [error-messages
                    (logging/buffered-log
                     :warn
                     (some-> addon
                             core/expand-summary-wrapper
                             core/install-addon-guard-affective))]
                {:label (:label addon)
                 :error-messages error-messages}))]
    (run! #(joblib/create-addon-job! queue-atm % job) addon-list)
    (joblib/run-jobs! (core/get-state :job-queue) core/num-concurrent-downloads)))

(defn-spec update-selected nil?
  "updates all addons in given `addon-list` that have updates available.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (update-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter addon/updateable?)
        install-update-these-in-parallel)))

(defn-spec update-all nil?
  "updates all installed addons with any new releases.
  command is ignored if any addons are in an unsteady state."
  []
  (if-not (empty? (get-state :unsteady-addon-list))
    (warn "updates in progress, 'update all' command ignored")
    (let [updateable-addons (->> (get-state :installed-addon-list)
                                 (filter addon/updateable?))]
      (when-not (empty? updateable-addons)
        (install-update-these-in-parallel updateable-addons)))))

(defn-spec set-version nil?
  "updates `addon` with the given `release` data and then installs it."
  [addon :addon/installable, release :addon/source-updates]
  (install-update-these-in-parallel [(merge addon release)]))

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
                  (addon/ignore! (core/selected-addon-dir) addon)))))
   ;; todo: half-refresh?
   (core/refresh)))

(defn-spec clear-ignore-selected nil?
  "removes the 'ignore' flag from each addon in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (clear-ignore-selected (get-state :selected-addon-list) (core/selected-addon-dir)))
  ([addon-list :addon/installed-list, addon-dir ::sp/addon-dir]
   (run! (fn [addon]
           (logging/with-addon addon
             (addon/clear-ignore! addon-dir addon)
             (info "stopped ignoring")))
         addon-list)
   ;; todo: half-refresh?
   (core/refresh)))

;; tabs

(defn-spec change-addon-detail-nav nil?
  "changes the selected mode of the addon detail pane between releases+grouped-addons, mutual dependencies and key+vals"
  [nav-key :ui/addon-detail-nav-key, tab-idx int?]
  (when (some #{nav-key} sp/addon-detail-nav-key-set)
    (swap! core/state assoc-in [:tab-list tab-idx :addon-detail-nav-key] nav-key)
    nil))

(defn-spec change-notice-logger-level nil?
  "changes the log level on the UI notice-logger widget.
  changes the log level for a specific tab when `tab-idx` is also given."
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
  if tab already exists then it is removed from list and the new one appended to the end.
  this is purely so the latest tab can be selected without index wrangling."
  [tab-id :ui/tab-id, tab-label ::sp/label, closable? ::sp/closable?, tab-data :ui/tab-data]
  (let [new-tab {:tab-id tab-id
                 :label tab-label
                 :closable? closable?
                 :log-level :info
                 :tab-data tab-data
                 :addon-detail-nav-key :releases+grouped-addons}
        tab-list (remove (fn [tab]
                           (= (select-keys tab [:label :tab-data])
                              (select-keys new-tab [:label :tab-data])))
                         (core/get-state :tab-list))
        tab-list (vec (concat tab-list [new-tab]))]
    (swap! core/state assoc :tab-list tab-list))
  nil)

(defn-spec add-addon-tab nil?
  "convenience, adds a tab using the given `addon` data."
  [addon map?]
  (let [tab-id (utils/unique-id)
        closable? true
        addon-id (utils/extract-addon-id addon)
        tab-label (or (:dirname addon) (:label addon) (:name addon) "[bug: missing tab name!]")]
    (add-tab tab-id tab-label closable? addon-id)))

;; log entries
;; todo: how much of this can be moved into logging.clj?

(defn-spec log-entries-since-last-refresh ::sp/list-of-maps
  "returns a list of log entries since the last 'refresh' notice-level log.
  used to hide warnings and errors belonging to *updates* in previous refreshes."
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
  "returns a list of log entries for the given `addon` since last refresh.
  used to hide warnings and errors belonging to *addons* in previous refreshes."
  [addon map?]
  (when-let [state @core/state]
    (let [not-report #(-> % :level (= :report) not)
          filter-fn (logging/log-line-filter-with-reports (core/selected-addon-dir) addon)]
      (->> state
           :log-lines ;; oldest first
           reverse ;; newest first
           (filter filter-fn)
           (take-while not-report)
           reverse ;; oldest first again, but truncated
           vec))))

(defn-spec addon-num-log-level int?
  "returns the number of log entries the given `dirname` has for the given `log-level`."
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

;; importing addons

(defn-spec import-addon nil?
  "goes looking for given `addon-url` and, if found, adds it to the user catalogue and then installs it."
  [addon-url string?]
  (binding [http/*cache* (core/cache)]
    (if-let* [dry-run? true
              addon-summary (core/find-addon addon-url dry-run?)
              addon (core/expand-summary-wrapper addon-summary)]
             ;; success! add to user-catalogue and proceed to install
             (do (core/add-user-addon! addon-summary)
                 (core/write-user-catalogue!)
                 (core/install-addon-guard addon (core/selected-addon-dir))
                 ;;(core/db-reload-catalogue) ;; db-reload-catalogue will call `refresh` which we want to trigger in the gui instead
                 (swap! core/state assoc :db nil) ;; will force a reload of db in the gui
                 nil)

             ;; failed to find or expand summary, probably because of selected game track.
             nil)))

;; TODO: update references
(def refresh-user-catalogue-item core/refresh-user-catalogue-item)
(def refresh-user-catalogue core/refresh-user-catalogue)
(def find-addon core/find-addon)

;;

;; todo: shift to core.clj or addon.clj
(defn-spec addon-source-map-to-url (s/or :ok ::sp/url, :error nil?)
  "construct a URL given a `source`, `source-id` and toc data only.
  caveats: 
  * curseforge, can't go directly to an addon with just the source-id, so we use the slug and hope for the best.
  * tukui, we also need the game track to know which url"
  [addon :addon/toc, source-map :addon/source-map]
  (case (:source source-map)
    "curseforge" (str "https://www.curseforge.com/wow/addons/" (-> addon :name)) ;; still not great but about ~80% hit rate.
    "wowinterface" (wowinterface-api/make-url source-map)
    "tukui" (tukui-api/make-url (merge addon source-map))
    "github" (github-api/make-url source-map)
    "gitlab" (gitlab-api/make-url source-map)

    nil))

(defn-spec available-versions-v1 (s/or :ok string? :no-version-available nil?)
  "formats the 'available version' string depending on the state of the addon."
  [row map?]
  (cond
    (:ignore? row) ""
    (:pinned-version row) (:pinned-version row)
    :else
    (:version row)))

(defn-spec available-versions-v2 (s/or :ok string? :no-version-available nil?)
  "formats the 'version' or 'available version' string depending on the state of the addon.
  pinned and ignored addons get a helpful prefix."
  [row map?]
  (cond
    (and (:ignore? row)
         (:installed-version row)) (str "(ignored) " (:installed-version row))
    (:ignore? row) "(ignored)"
    (:pinned-version row) (str "(pinned) " (:pinned-version row))
    :else (or (:version row)
              (:installed-version row))))

(defn-spec toggle-ui-column nil?
  "toggles the display of the given `column-id` in the user preferences."
  [column-id keyword?, selected? boolean?]
  (dosync
   (swap! core/state update-in [:cfg :preferences :ui-selected-columns] (if selected? conj utils/rmv) column-id)
   (core/save-settings!)))

(defn-spec set-column-list nil?
  "replaces the current set of columns with the given `column-list`."
  [column-list :ui/column-list]
  (dosync
   (swap! core/state assoc-in [:cfg :preferences :ui-selected-columns] column-list)
   (core/save-settings!)))

(defn-spec reset-ui-columns nil?
  "replaces the current set of columns with the default set."
  []
  (set-column-list sp/default-column-list))

(defn-spec sort-column-list :ui/column-list
  "returns the given `column-list` but in the preferred order"
  [column-list :ui/column-list]
  (sort-by (fn [x]
             (.indexOf sp/known-column-list x)) column-list))

;; source switching

(defn-spec switch-source (s/or :ok :addon/toc+nfo, :error nil?)
  "switches addon from one source (like curseforge) to another (like wowinterface), rewriting nfo data.
  `new-source` must appear in the addon's `source-map-list`."
  [addon :addon/toc+nfo, new-source-map :addon/source-map]
  (when-not (= (:source addon) (:source new-source-map))
    (addon/switch-source! (core/selected-addon-dir) addon new-source-map)
    (half-refresh)))

;;

(defn-spec add-summary-to-user-catalogue nil?
  "adds an `addon-summary` (catalogue entry) to the user-catalogue, if it's not already present."
  [addon-summary :addon/summary]
  (core/add-user-addon! addon-summary)
  (core/write-user-catalogue!))

(defn-spec add-addon-to-user-catalogue nil?
  "given an `addon` with only a `:source` and `:source-id`, find it in the catalogue and add it to the user-catalogue.
  fails if addon cannot be found in catalogue."
  [addon map?]
  (logging/with-addon addon
    (if-not (s/valid? :addon/source-map addon)
      (error "failed to star addon, it is missing some basic information ('source' and 'source-id').")
      (let [catalogue-addon (core/db-addon-by-source-and-source-id
                             (core/get-state :db) (:source addon) (:source-id addon))]
        (if-not (s/valid? :addon/summary catalogue-addon)
          (error "failed to star addon, it is not matched to the catalogue.")
          (add-summary-to-user-catalogue catalogue-addon)))))
  nil)

(defn-spec remove-summary-from-user-catalogue nil?
  "removes an `addon-summary` (catalogue entry) from the user-catalogue, but only if it's present."
  [addon-summary :addon/summary]
  (core/remove-user-addon! addon-summary)
  (core/write-user-catalogue!))

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

(defn-spec touch-bar nil?
  "used to select each addon in the GUI so the progress-bar can be tested"
  []
  (let [touch (fn [addon]
                (core/start-affecting-addon addon)
                (let [total 2
                      pieces 100]
                  (doseq [pos (range 1 (* total pieces))]
                    (joblib/*tick* (double (/ 1 (/ (* total pieces)
                                                   pos))))
                    (Thread/sleep 10)))

                (core/stop-affecting-addon addon))

        queue-atm (get-state :job-queue)

        add-job! (fn [addon]
                   (joblib/create-addon-job! queue-atm addon touch))]

    (run! add-job! (get-state :installed-addon-list))
    (joblib/run-jobs! queue-atm core/num-concurrent-downloads)
    nil))

;; ---

(defmulti action
  "handles the following actions:
    :list - lists all installed addons
    :list-updates - lists all installed addons with updates available
    :update-all - updates all installed addons with updates available"
  (fn [x]
    (cond
      (map? x) (:action x)
      (keyword? x) x)))

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
