(ns strongbox.ui.cli
  (:require
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error report spy]]
   [clojure.spec.alpha :as s]
   [me.raynes.fs :as fs]
   [strongbox
    [constants :as constants]
    [joblib :as joblib]
    [github-api :as github-api]
    [db :as db]
    [logging :as logging]
    [addon :as addon]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [gitlab-api :as gitlab-api]
    [catalogue :as catalogue]
    [http :as http]
    [utils :as utils :refer [if-let* message-list]]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths find-catalogue-local-path]]]))

(comment "the UIs pool their logic here, which calls core.clj.")

(defn-spec toggle-split-pane nil?
  []
  (swap! core/state update-in [:gui-split-pane] not)
  nil)

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

;; unselect? https://english.stackexchange.com/questions/18465/unselect-or-deselect
(defn-spec deselect-addons! nil?
  "removes all addons from the `:selected-addon-list` list"
  []
  (select-addons* []))

;; ui refreshing

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
  (core/save-settings!))

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
  "updates the `[:search :term]` and resets the current page of results to `0`.
  does not actually do any searching, that is up to the interface"
  [search-term (s/nilable string?)]
  (let [;; if the given search term is empty (nil, "") and the *current* search term is empty, switch empty values.
        ;; this lets us hit the 'random' button many times for different results.
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
  "search for the `[:search :term]`, if one exists, and adds some whitespace to jog the GUI into forcing an empty.
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
  a complete refresh (see `core/db-reload-catalogue`) is necessary for this action as
  addons accumulate keys like `:matched?` and `:update?`"
  [catalogue-name (s/nilable (s/or :simple string?, :named keyword?))]
  (when catalogue-name
    (core/set-catalogue-location! (keyword catalogue-name))
    (report "switched catalogues")
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
  (core/save-settings!))

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

(defn-spec install-update-these-serially nil?
  "installs/updates a list of addons serially"
  [updateable-addon-list :addon/installable-list]
  (run! core/install-addon-guard-affective updateable-addon-list))

(defn-spec install-update-these-in-parallel nil?
  "installs/updates a list of addons in parallel, pushing guard checks into threads and then installing serially."
  [updateable-addon-list :addon/installable-list]
  (let [queue-atm (core/get-state :job-queue)
        install-dir (core/selected-addon-dir)
        add-download-job! (fn [addon]
                            (let [job-fn (fn []
                                           [addon (core/download-addon-guard-affective addon install-dir)])
                                  job-id (joblib/addon-job-id addon :download-addon)]
                              (joblib/create-job! queue-atm job-fn job-id)))
        _ (run! add-download-job! updateable-addon-list)
        addon+downloaded-file-list (joblib/run-jobs! queue-atm core/num-concurrent-downloads)]

    ;; install addons serially, skip download checks, mark addons as unsteady
    (doseq [[addon downloaded-file] addon+downloaded-file-list]
      (core/install-addon-affective addon install-dir downloaded-file))))

(defn-spec re-install-or-update-selected nil?
  "re-installs (if possible) or updates all addons in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (re-install-or-update-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter core/expanded?)
        (map -find-replace-release)
        install-update-these-in-parallel)
   (core/refresh)))

(defn-spec re-install-or-update-all nil?
  "re-installs (if possible) or updates all installed addons"
  []
  (re-install-or-update-selected (get-state :installed-addon-list)))

(defn-spec install-addon nil?
  "install an addon from the catalogue. works on expanded addons as well."
  [addon :addon/summary]
  (some-> addon
          core/expand-summary-wrapper
          vector
          install-update-these-serially)
  (core/refresh))

(defn-spec install-many ::sp/list-of-maps
  "install many addons from the catalogue.
  a bit different from the other installation functions, this one returns a list of maps with the installation results."
  [addon-list :addon/summary-list]
  (let [queue-atm (core/get-state :job-queue)
        job (fn [addon]
              (fn []
                (let [error-messages
                      (logging/buffered-log
                       :warn
                       (some-> addon
                               core/expand-summary-wrapper
                               core/install-addon-guard-affective))]
                  {:label (:label addon)
                   :error-messages error-messages})))]
    (run! (partial joblib/create-job! queue-atm) (mapv job addon-list))
    (joblib/run-jobs! (core/get-state :job-queue) core/num-concurrent-downloads)))

(defn-spec update-selected nil?
  "updates all addons in given `addon-list` that have updates available.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (update-selected (get-state :selected-addon-list)))
  ([addon-list :addon/installed-list]
   (->> addon-list
        (filter addon/updateable?)
        install-update-these-in-parallel)
   (core/refresh)))

(defn-spec update-all nil?
  "updates all installed addons with any new releases.
  command is ignored if any addons are unsteady"
  []
  (if (empty? (get-state :unsteady-addon-list))
    (do (->> (get-state :installed-addon-list)
             (filter addon/updateable?)
             install-update-these-in-parallel)
        (core/refresh))
    (warn "updates in progress, 'update all' command ignored")))

(defn-spec set-version nil?
  "updates `addon` with the given `release` data and then installs it."
  [addon :addon/installable, release :addon/source-updates]
  ;;(core/install-addon-guard-affective (merge addon release))
  (install-update-these-in-parallel [(merge addon release)])
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
        (run! (fn [addon]
                (logging/with-addon addon
                  (info "ignoring")
                  (addon/ignore (core/selected-addon-dir) addon)))))
   (core/refresh)))

(defn-spec clear-ignore-selected nil?
  "removes the 'ignore' flag from each addon in given `addon-list`.
  defaults to all addons in `:selected-addon-list` when called without parameters."
  ([]
   (clear-ignore-selected (get-state :selected-addon-list) (core/selected-addon-dir)))
  ([addon-list :addon/installed-list, addon-dir ::sp/addon-dir]
   (run! (fn [addon]
           (logging/with-addon addon
             (addon/clear-ignore addon-dir addon)
             (info "stopped ignoring")))
         addon-list)
   (core/refresh)))

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

;; log entries
;; todo: how much of this can be moved into logging.clj?

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

;; importing addons

;; todo: logic might be better off in core.clj
(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  "given a URL of a support addon host, parses it, looks for it in the catalogue, expands addon and attempts a dry run installation.
  if successful, returns the addon-summary."
  [addon-url string?, dry-run? boolean?]
  (binding [http/*cache* (core/cache)]
    (if-let* [addon-summary-stub (catalogue/parse-user-string addon-url)
              source (:source addon-summary-stub)
              match-on-list [[[:source :url] [:source :url]]
                             [[:source :source-id] [:source :source-id]]]
              addon-summary (cond
                              (= source "github")
                              (or (github-api/find-addon (:source-id addon-summary-stub))
                                  (error (message-list
                                          "Failed. URL must be:"
                                          ["valid"
                                           "originate from github.com"
                                           "addon uses 'releases'"
                                           "latest release has a packaged 'asset'"
                                           "asset must be a .zip file"
                                           "zip file must be structured like an addon"])))

                              (= source "gitlab")
                              (or (gitlab-api/find-addon (:source-id addon-summary-stub))
                                  (error (message-list
                                          "Failed. URL must be:"
                                          ["valid"
                                           "originate from gitlab.com"
                                           "addon uses releases"
                                           "latest release has a custom asset with a 'link'"
                                           "link type must be either a 'package' or 'other'"])))

                              :else
                              ;; look in the current catalogue. emit an error if we fail
                              (or (:catalogue-match (db/-find-first-in-db (or (core/get-state :db) []) addon-summary-stub match-on-list))
                                  (error (format "couldn't find addon in catalogue '%s'"
                                                 (name (core/get-state :cfg :selected-catalogue))))))

              ;; game track doesn't matter when adding it to the user catalogue.
              ;; prefer retail though (it's the most common) and `strict` here is `false`
              addon (or (catalogue/expand-summary addon-summary :retail false)
                        (error "failed to fetch details of addon"))

              ;; a dry-run is good when importing an addon for the first time but
              ;; not necessary when updating the user-catalogue.
              _ (if-not dry-run?
                  true
                  (or (core/install-addon-guard addon (core/selected-addon-dir) true)
                      (error "failed dry-run installation")))]

             ;; if-let* was successful!
             addon-summary

             ;; failed if-let* :(
             nil)))

(defn-spec import-addon nil?
  "goes looking for given url and if found adds it to the user catalogue and then installs it."
  [addon-url string?]
  (binding [http/*cache* (core/cache)]
    (if-let* [dry-run? true
              addon-summary (find-addon addon-url dry-run?)
              addon (core/expand-summary-wrapper addon-summary)]
             ;; success! add to user-catalogue and proceed to install
             (do (core/add-user-addon! addon-summary)
                 (core/install-addon-guard addon (core/selected-addon-dir))
                 ;;(core/db-reload-catalogue) ;; db-reload-catalogue will call `refresh` which we want to trigger in the gui instead
                 (swap! core/state assoc :db nil) ;; will force a reload of db
                 nil)

             ;; failed to find or expand summary, probably because of selected game track.
             nil)))

(defn-spec refresh-user-catalogue-item nil?
  "refresh the details of an individual addon in the user catalogue."
  [addon :addon/summary]
  (logging/with-addon addon
    (info "refreshing details")
    (try
      (let [dry-run? false
            refreshed-addon (find-addon (:url addon) dry-run?)]
        (if refreshed-addon
          (do (core/add-user-addon! refreshed-addon)
              (info "... done!"))
          (warn "failed to refresh catalogue entry")))
      (catch Exception e
        (error (format "an unexpected error happened while updating the details for '%s' in the user-catalogue: %s"
                       (:name addon) (.getMessage e)))))))

(defn-spec refresh-user-catalogue nil?
  "refresh the details of all addons in the user catalogue."
  []
  (binding [http/*cache* (core/cache)]
    (info (format "refreshing \"%s\", this may take a minute ..."
                  (-> (core/paths :user-catalogue-file) fs/base-name)))
    (->> (core/get-create-user-catalogue)
         :addon-summary-list
         (mapv refresh-user-catalogue-item)))
  nil)

;;

(defn-spec available-versions-v1 (s/or :ok string? :no-version-available nil?)
  "formats the 'available version' string depending on the state of the addon.
  pinned and ignored addons get a helpful prefix."
  [row map?]
  (cond
    (:ignore? row) "(ignored)"
    (:pinned-version row) (str "(pinned) " (:pinned-version row))
    :else
    (:version row)))

(defn-spec available-versions-v2 (s/or :ok string? :no-version-available nil?)
  "formats the 'version' or 'available version' string depending on the state of the addon.
  pinned and ignored addons get a helpful prefix."
  [row map?]
  (cond
    ;; when wouldn't we have an `installed-version`? search result?
    (and (:ignore? row) (:installed-version row)) (str "(ignored) " (:installed-version row))
    (:ignore? row) "(ignored)"
    (:pinned-version row) (str "(pinned) " (:pinned-version row))
    :else
    (or (:version row) (:installed-version row))))

(def column-map
  {:browse-local {:label "browse" :value-fn :addon-dir}
   :source {:label "source" :value-fn :source}
   :source-id {:label "ID" :value-fn :source-id}
   :name {:label "name" :value-fn (comp utils/no-new-lines :label)}
   :description {:label "description" :value-fn (comp utils/no-new-lines :description)}
   :tag-list {:label "tags" :value-fn (fn [row]
                                        (when-not (empty? (:tag-list row))
                                          (str (:tag-list row))))}
   :updated-date {:label "updated" :value-fn (comp utils/format-dt :updated-date)}
   :created-date {:label "created" :value-fn (comp utils/format-dt :created-date)}
   :installed-version {:label "installed" :value-fn :installed-version}
   :available-version {:label "available" :value-fn available-versions-v1}
   :combined-version {:label "version" :value-fn available-versions-v2}
   :game-version {:label "WoW"
                  :value-fn (fn [row]
                              (some-> row :interface-version str utils/interface-version-to-game-version))}

   :uber-button {:label nil ;; the gui will use the column-id (`:uber-button`) for the column menu when label is `nil`
                 :value-fn (fn [row]
                             (let [queue (core/get-state :job-queue)
                                   job-id (joblib/addon-id row)]
                               (if (and (core/unsteady? (:name row))
                                        (joblib/has-job? queue job-id))
                                 ;; parallel job in progress, show a ticker.
                                 "*"
                                 (cond
                                   (:ignore? row) (:ignored constants/glyph-map)
                                   (:pinned-version row) (:pinned constants/glyph-map)
                                   (core/unsteady? (:name row)) (:unsteady constants/glyph-map)
                                   (addon-has-errors? row) (:errors constants/glyph-map)
                                   (addon-has-warnings? row) (:warnings constants/glyph-map)
                                   :else (:tick constants/glyph-map)))))}})

(defn-spec toggle-ui-column nil?
  "toggles the given `column-id` in the user preferences depending on `selected?` boolean"
  [column-id keyword?, selected? boolean?]
  (dosync
   (swap! core/state update-in [:cfg :preferences :ui-selected-columns] (if selected? conj utils/rmv) column-id)
   (core/save-settings!)))

(defn-spec reset-ui-columns nil?
  "replaces user column preferences with the default set"
  []
  (dosync
   (swap! core/state assoc-in [:cfg :preferences :ui-selected-columns] sp/default-column-list)
   (core/save-settings!)))

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
  (addon/switch-source! (core/selected-addon-dir) addon new-source-map)
  (half-refresh))


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
                    (joblib/tick (double (/ 1 (/ (* total pieces)
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

(defmethod action :scrape-github-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :github)
          catalogue-data (github-api/build-catalogue)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data-for-output catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-wowinterface-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :wowinterface)
          catalogue-data (wowinterface/scrape)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data-for-output catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-curseforge-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :curseforge)
          catalogue-data (curseforge-api/download-all-summaries-alphabetically)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data-for-output catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-tukui-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :tukui)
          catalogue-data (tukui-api/download-all-summaries)
          created (utils/datestamp-now-ymd)
          formatted-catalogue-data (catalogue/format-catalogue-data-for-output catalogue-data created)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :write-catalogue
  [_]
  (let [curseforge-catalogue (find-catalogue-local-path :curseforge)
        wowinterface-catalogue (find-catalogue-local-path :wowinterface)
        tukui-catalogue (find-catalogue-local-path :tukui)
        github-catalogue (find-catalogue-local-path :github)

        catalogue-path-list [curseforge-catalogue wowinterface-catalogue tukui-catalogue github-catalogue]
        catalogue (mapv catalogue/read-catalogue catalogue-path-list)
        catalogue (reduce catalogue/merge-catalogues catalogue)
        ;; 2021-09: `merge-catalogues` no longer converts an addon to an `ordered-map`.
        ;; turns out this is fast individually but slow in aggregate and not necessary for regular usage of strongbox,
        ;; just generating catalogues.
        catalogue (catalogue/format-catalogue-data-for-output (:addon-summary-list catalogue) (:datestamp catalogue))
        short-catalogue (when catalogue
                          (catalogue/shorten-catalogue catalogue))]
    (if-not catalogue
      (warn "no catalogue data found, nothing to write")
      (do (catalogue/write-catalogue catalogue (find-catalogue-local-path :full))
          (catalogue/write-catalogue short-catalogue (find-catalogue-local-path :short))))))

(defmethod action :scrape-catalogue
  [_]
  (action :scrape-curseforge-catalogue)
  (action :scrape-wowinterface-catalogue)
  (action :scrape-tukui-catalogue)
  (action :scrape-github-catalogue)
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
