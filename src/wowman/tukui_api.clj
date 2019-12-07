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

(def summary-url "https://www.tukui.org/api.php?addon=%s")
(def classic-summary-url "https://www.tukui.org/api.php?classic-addon=%s")

(def summary-list-url "https://www.tukui.org/api.php?addons=all")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons=all")

(defn expand-summary
  [addon-summary game-track]
  (let [url (format (if (= game-track "classic") classic-summary-url summary-url)
                    (:source-id addon-summary))

        ;; tukui addons do not share IDs across game tracks like curseforge does.
        ;; tukui will also return a successful-but-empty response (200) for addons
        ;; that don't exist in that catalogue. I'm treating empty responses as 404s
        ti (some-> url http/download utils/nilable utils/from-json)]
    (when ti
      (merge addon-summary
             {:download-uri (:url ti)
              :version (:version ti)
              :interface-version (-> ti :patch utils/game-version-to-interface-version)}))))

;;


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

         ;; both of these are available in the main download
         ;; however the catalogue is updated weekly and wowman uses a mechanism of
         ;; checking each for updates rather than relying on the catalog.
         ;; perhaps in the future when we scrape daily
         ;;:version (:version ti)
         ;;:download-uri (:url ti)
         }]

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

;;

(st/instrument)
