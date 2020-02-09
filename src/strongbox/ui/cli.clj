(ns strongbox.ui.cli
  (:require
   [taoensso.timbre :as timbre :refer [spy info]]
   [strongbox
    [tukui-api :as tukui-api]
    [catalogue :as catalogue]
    [http :as http]
    [utils :as utils]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths find-catalogue-local-path]]]))

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
    (wowinterface/scrape (find-catalogue-local-path :wowinterface))))

(defmethod action :scrape-curseforge-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :curseforge)
          catalogue-data (curseforge-api/download-all-summaries-alphabetically)
          created (utils/datestamp-now-ymd)
          updated created
          formatted-catalogue-data (catalogue/format-catalogue-data catalogue-data created updated)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :scrape-tukui-catalogue
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalogue-local-path :tukui)
          catalogue-data (tukui-api/download-all-summaries)
          created (utils/datestamp-now-ymd)
          updated created
          formatted-catalogue-data (catalogue/format-catalogue-data catalogue-data created updated)]
      (catalogue/write-catalogue formatted-catalogue-data output-file))))

(defmethod action :write-catalogue
  [_]
  (let [curseforge-catalogue (find-catalogue-local-path :curseforge)
        wowinterface-catalogue (find-catalogue-local-path :wowinterface)
        tukui-catalogue (find-catalogue-local-path :tukui)

        catalogue-path-list [curseforge-catalogue wowinterface-catalogue tukui-catalogue]
        catalogue (map utils/load-json-file catalogue-path-list)
        catalogue (reduce catalogue/merge-catalogs catalogue)]
    (-> catalogue
        (catalogue/write-catalogue (find-catalogue-local-path :full))

        ;; 'short' catalogue is derived from the full catalogue
        catalogue/shorten-catalogue
        (catalogue/write-catalogue (find-catalogue-local-path :short)))))

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
