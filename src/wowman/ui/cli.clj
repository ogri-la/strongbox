(ns wowman.ui.cli
  (:require
   [taoensso.timbre :as timbre :refer [spy info]]
   [wowman
    [utils :as utils]
    [curseforge :as curseforge]
    [core :as core :refer [get-state paths]]]))

(defmulti action
  "handles the following actions:
    :scrape-addon-list - not recommended, does 330+ http requests to curseforge
    :update-addon-list - downloads the most recent addon list from online, then downloads updates
    :list - lists all installed addons
    :list-updates - lists all installed addons with updates available
    :update-all - updates all installed addons with updates available"
  :action)

(defmethod action :scrape-addon-list
  [opts]
  (binding [utils/cache-dir (paths :cache-dir)]
    (curseforge/download-all-addon-summaries (paths :addon-summary-file))))

(defmethod action :update-addon-list
  [opts]
  (binding [utils/cache-dir (paths :cache-dir)]
    (let [{since :datestamp} (utils/load-json-file-with-decoding (paths :addon-summary-file))]
      ;; download any updates to a file
      (curseforge/download-all-addon-summary-updates since (paths :addon-summary-updates-file))
      ;; merge those updates with the main summary file
      (curseforge/update-addon-summary-file (paths :addon-summary-file)
                                            (paths :addon-summary-updates-file)))))

(defmethod action :list
  [opts]
  (let [installed-addons (get-state :installed-addon-list)]
    (println (count installed-addons) "installed addons")
    (doseq [{:keys [dirname installed-version]} installed-addons]
      (println (format "%s (%s)" dirname, installed-version)))))

(defmethod action :list-updates
  [opts]
  (let [installed-addons (get-state :installed-addon-list)
        updates (filter :update? installed-addons)]
    (println (count installed-addons) "installed addons, " (count updates) " updates")
    (doseq [{:keys [dirname installed-version version]} updates]
      (println (format "%s (%s => %s)" dirname, installed-version version)))))

(defmethod action :update-all
  [opts]
  (core/install-update-all)
  (action {:action :list-updates}))

(defmethod action :default
  [opts]
  (println opts))

(defn start
  [opts]
  (info "starting cli")
  (action opts))

(defn stop
  [state]
  (info "stopping cli")
  nil)
