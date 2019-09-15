(ns wowman.ui.cli
  (:require
   [taoensso.timbre :as timbre :refer [spy info]]
   [wowman
    [catalog :as catalog]
    [http :as http]
    [utils :as utils]
    [curseforge :as curseforge]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths]]]))

(defmulti action
  "handles the following actions:
    :scrape-addon-list - not recommended, does 330+ http requests to curseforge
    :update-addon-list - downloads the most recent addon list from online, then downloads updates
    :list - lists all installed addons
    :list-updates - lists all installed addons with updates available
    :update-all - updates all installed addons with updates available"
  (fn [x]
    (cond
      (map? x) (:action x)
      (keyword? x) x)))

(defmethod action :scrape-wowinterface-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (wowinterface/scrape (paths :wowinterface-catalog-file))))

(defmethod action :update-wowinterface-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (core/download-catalog :wowinterface-catalog-file)
    (wowinterface/scrape-updates (paths :wowinterface-catalog-file))))

(defmethod action :scrape-curseforge-catalog
  [_]
  ;; todo: move to core.clj
  (binding [http/*cache* (core/cache)]
    ;;(curseforge/download-all-addon-summaries (paths :curseforge-catalog-file))
    (let [output-file (paths :curseforge-catalog-file)
          catalog-data (curseforge-api/download-all-summaries-alphabetically)
          created (utils/datestamp-now-ymd)
          updated created
          formatted-catalog-data (catalog/format-catalog-data catalog-data created updated)]
      (catalog/write-catalog formatted-catalog-data output-file))))

(defmethod action :update-curseforge-catalog
  [_]
  ;; todo: move to core.clj
  (binding [http/*cache* (core/cache)]
    (core/download-catalog :curseforge-catalog-file)
    (when-let [{since :datestamp} (utils/load-json-file (paths :curseforge-catalog-file))]
      ;; download any updates to a file
      (curseforge/download-all-addon-summary-updates since (paths :curseforge-catalog-updates-file))
      ;; merge those updates with the main summary file
      (curseforge/update-addon-summary-file (paths :curseforge-catalog-file)
                                            (paths :curseforge-catalog-updates-file)))))

(defmethod action :write-catalog
  [_]
    ;; writes the 'full' and 'short' catalog files by combining the individual host catalogs
  (let [curseforge-catalog (paths :curseforge-catalog-file)
        wowinterface-catalog (paths :wowinterface-catalog-file)
        catalog (catalog/merge-catalogs curseforge-catalog wowinterface-catalog)]
    (-> catalog
        (catalog/write-catalog (paths :catalog-file))

        catalog/shorten-catalog
        (catalog/write-catalog (paths :catalog-file-short)))))

(defmethod action :scrape-catalog
  [_]
  (action :scrape-curseforge-catalog)
  (action :scrape-wowinterface-catalog)
  (action :write-catalog))

(defmethod action :update-catalog
  [_]
  (action :update-curseforge-catalog)
  (action :update-wowinterface-catalog)
  (action :write-catalog))

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
  (println opts))

(defn start
  [opts]
  (info "starting cli")
  (core/refresh)
  (action opts))

(defn stop
  []
  (info "stopping cli")
  nil)
