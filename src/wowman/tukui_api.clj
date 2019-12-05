(ns wowman.tukui-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(def summary-list-url "https://www.tukui.org/api.php?addons=all")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons=all")

(defn tukui-date-to-rfc3339
  [tukui-dt]
  (let [[date time] (clojure.string/split tukui-dt #" ")]
    ;; assume UTC, no other tz information available
    (str date "T" time "Z")))

(defn process-tukui-item
  [tukui-item classic?]
  (let [ti tukui-item
        addon-summary
        {:source (if classic? "tukui-classic" "tukui")
         :source-id (-> ti :id Integer.)
         
         ;; single case of an addon with no category :(
         ;; 'SkullFlower UI', source-id 143
         :category-list (if-let [cat (:category ti)] [cat] [])
         :download-count (-> ti :downloads Integer.)
         :game-track-list [(if classic? "classic" "retail")]
         :label (:name ti)
         :name (slugify (:name ti))
         :alt-name (slugify (:name ti) "")
         :description (:small_desc ti)
         :updated-date (-> ti :lastupdate tukui-date-to-rfc3339)
         :uri (:web_url ti)

         ;; when did I drop support for this? db barfs but it's still there in spec
         ;;:interface-version (-> ti :patch utils/game-version-to-interface-version)

         ;; both of these are available in the main download
         ;; however the catalogue is updated weekly and wowman uses a mechanism of
         ;; checking each for updates rather than relying on the catalog.
         ;; perhaps in the future when we scrape daily
         ;;:version (:version ti)
         ;;:download-uri (:url ti)

         }
        ]
    addon-summary))

(defn download-retail-summaries
  []
  (mapv #(process-tukui-item % false) (-> summary-list-url http/download utils/from-json)))

(defn download-classic-summaries
  []
  (mapv #(process-tukui-item % true) (-> classic-summary-list-url http/download utils/from-json)))

(defn download-all-summaries
  []
  (into (download-retail-summaries)
        (download-classic-summaries)))
    

(st/instrument)
