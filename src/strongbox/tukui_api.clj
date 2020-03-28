(ns strongbox.tukui-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(def summary-url "https://www.tukui.org/api.php?addon=%s")
(def classic-summary-url "https://www.tukui.org/api.php?classic-addon=%s")

(def summary-list-url "https://www.tukui.org/api.php?addons=all")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons=all")

(def proper-url "https://www.tukui.org/api.php?ui=%s")
(def tukui-proper-url (format proper-url "tukui"))
(def elvui-proper-url (format proper-url "elvui"))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary game-track ::sp/game-track]
  (let [source-id (:source-id addon-summary)
        url (cond
              (neg? source-id) [proper-url (:name addon-summary)]
              (= game-track :classic) [classic-summary-url source-id]
              (= game-track :retail) [summary-url source-id])
        url (apply format url)

        ;; tukui addons do not share IDs across game tracks like curseforge does.
        ;; tukui will also return a successful-but-empty response (200) for addons
        ;; that don't exist in that catalogue. I'm treating empty responses as 404s
        ti (some-> url http/download utils/nilable utils/from-json)]
    (when ti
      (merge addon-summary
             {:download-url (:url ti)
              :version (:version ti)
              :interface-version (-> ti :patch utils/game-version-to-interface-version)}))))

;;

(defn-spec tukui-date-to-rfc3339 ::sp/inst
  "convert a tukui-style datestamp into a mighty RFC3339 formatted one. assumes UTC."
  [tukui-dt string?]
  (let [[date time] (clojure.string/split tukui-dt #" ")]
    (if-not time
      (str date "T00:00:00Z") ;; tukui and elvui addons proper have no time component
      (str date "T" time "Z"))))

(defn-spec process-tukui-item ::sp/addon-summary
  "process an item from a tukui catalogue into an addon-summary. slightly different values by game-track."
  [tukui-item map?, classic? boolean?]
  (let [ti tukui-item
        ;; single case of an addon with no category :(
        ;; 'SkullFlower UI', source-id 143
        category-list (if-let [cat (:category ti)] [cat] [])
        addon-summary
        {:source (if classic? "tukui-classic" "tukui")
         :source-id (-> ti :id Integer.)

         ;; 2020-03: disabled in favour of :tag-list
         ;;:category-list category-list
         :tag-list (utils/category-list-to-tag-list category-list)
         :download-count (-> ti :downloads Integer.)
         :game-track-list [(if classic? :classic :retail)]
         :label (:name ti)
         :name (slugify (:name ti))
         :description (:small_desc ti)
         :updated-date (-> ti :lastupdate tukui-date-to-rfc3339)
         :url (:web_url ti)

         ;; both of these are available in the main download
         ;; however the catalogue is updated weekly and strongbox uses a mechanism of
         ;; checking each for updates rather than relying on the catalogue.
         ;; perhaps in the future when we scrape daily
         ;;:version (:version ti)
         ;;:download-url (:url ti)
         }]

    addon-summary))

(defn-spec -download-proper-summary ::sp/addon-summary
  "downloads either the elvui or tukui addon that exists separately and outside of the catalogue"
  [url ::sp/url]
  (let [classic? false ;; retail catalogue
        addon-summary (-> url http/download utils/from-json (process-tukui-item classic?))]
    (assoc addon-summary :game-track-list [:classic :retail])))

(defn-spec download-elvui-summary ::sp/addon-summary
  "downloads the elvui addon that exists separately and outside of the catalogue"
  []
  (-download-proper-summary elvui-proper-url))

(defn-spec download-tukui-summary ::sp/addon-summary
  "downloads the tukui addon that exists separately and outside of the catalogue"
  []
  (-download-proper-summary tukui-proper-url))

(defn-spec download-retail-summaries ::sp/addon-summary-list
  "downloads and processes all items in the tukui 'live' (retail) catalogue"
  []
  (mapv #(process-tukui-item % false) (-> summary-list-url http/download utils/from-json)))

(defn-spec download-classic-summaries ::sp/addon-summary-list
  "downloads and processes all items in the tukui classic catalogue"
  []
  (mapv #(process-tukui-item % true) (-> classic-summary-list-url http/download utils/from-json)))

(defn-spec download-all-summaries ::sp/addon-summary-list
  "downloads and process all items from the tukui 'live' (retail) and classic catalogues"
  []
  (vec (concat (download-retail-summaries)
               (download-classic-summaries)
               [(download-tukui-summary)]
               [(download-elvui-summary)])))

;;

(st/instrument)
