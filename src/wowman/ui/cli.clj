(ns wowman.ui.cli
  (:require
   [taoensso.timbre :as timbre :refer [spy info]]
   [wowman
    [catalog :as catalog]
    [http :as http]
    [utils :as utils]
    [curseforge :as curseforge]
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
    (wowinterface/scrape-updates (paths :wowinterface-catalog-file))))

(defmethod action :scrape-curseforge-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (curseforge/download-all-addon-summaries (paths :curseforge-catalog-file))))

(defmethod action :update-curseforge-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (when-let [{since :datestamp} (utils/load-json-file (paths :curseforge-catalog-file))]
      ;; download any updates to a file
      (curseforge/download-all-addon-summary-updates since (paths :curseforge-catalog-updates-file))
      ;; merge those updates with the main summary file
      (curseforge/update-addon-summary-file (paths :curseforge-catalog-file)
                                            (paths :curseforge-catalog-updates-file)))))

(defmethod action :merge-catalog
  [_]
  (catalog/merge-catalogs (paths :catalog-file) (paths :curseforge-catalog-file) (paths :wowinterface-catalog-file)))

(defmethod action :scrape-catalog
  [_]
  (action :scrape-curseforge-catalog)
  (action :scrape-wowinterface-catalog)
  (action :merge-catalog))

(defmethod action :update-catalog
  [_]
  (action :update-curseforge-catalog)
  (action :update-wowinterface-catalog)
  (action :merge-catalog))

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
    (println (count installed-addons) "installed addons, " (count updates) " updates")
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
  [state]
  (info "stopping cli")
  nil)
