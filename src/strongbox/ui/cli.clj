(ns strongbox.ui.cli
  (:require
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [spy info warn error debug]]
   [strongbox
    [specs :as sp]
    [tukui-api :as tukui-api]
    [catalogue :as catalogue]
    [http :as http]
    [utils :as utils]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths find-catalogue-local-path]]]))

(comment "the UIs pool their logic here, which calls core.clj.")

(defn-spec set-addon-dir! nil?
  "adds/sets an addon-dir marks it as selected, partial refresh of application state"
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
   (search-results (core/get-state :search)))
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
   (search-has-next? (core/get-state :search)))
  ([search-state]
   (= (count (search-results search-state))
      (:results-per-page search-state))))

(defn search-has-prev?
  "true if we've navigated forwards"
  ([]
   (search-has-prev? (core/get-state :search)))
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
          (catalogue/shorten-catalogue core/release-of-previous-expansion)
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
  (core/install-update-all)
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
