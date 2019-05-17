(ns wowman.wowinterface
  (:require
   [slugify.core :refer [slugify]]
   [wowman
    [core :as core]
    [logging]
    [utils :as utils]
    [http :as http]]
   [net.cgrand.enlive-html :as html]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]
   ))

(comment
  "wowinterface.com requires browing addons by category rather than alphabetically or by recently updated. 
   An RSS file is available to scrape the recently updated.")

(defn download-category-list
  []
  (http/download "https://www.wowinterface.com/downloads/cat23.html"))

(defn parse-category-list
  []
  (let [snippet (html/html-snippet (download-category-list))
        cat-list (-> snippet (html/select [:div#colleft :div.subcats :div.subtitle :a]))]
    (debug (format "%s categories found" (count cat-list)))
    (mapv (fn [cat]
            {:label (-> cat :content first)
             :url (-> cat :attrs :href)}) cat-list)))

(defn format-wowinterface-dt
  "formats a shitty US-style m/d/y date with shitty 12 hour time component sans timezone"
  [dt]
  (let [dt (java-time/local-date-time "MM-dd-yy hh:mm a" dt) ;; "09-07-18 01:27 PM" => obj with no tz
        ;; no tz info available on site, assume utc
        dt-utc (java-time/zoned-date-time dt "UTC") ;; obj with no tz => utc obj
        fmt (get java-time.format/predefined-formatters "iso-offset-date-time")]
    (java-time/format fmt dt-utc)))

(defn extract-addon-summary
  [snippet]
  ;;(clojure.pprint/pprint snippet)
  ;;(prn "---")
  (html/set-ns-parser! net.cgrand.tagsoup/parser)
  (let [uri "https://www.wowinterface.com/downloads/"
        extract-id #(str "info" (-> % (clojure.string/split #"&.+=") last)) ;; ...?foo=bar&id=24731 => info24731
        extract-updated-date (fn [x]
                               (let [dmy-hm (-> x (subs 8) clojure.string/trim)] ;; "Updated 09-07-18 01:27 PM " => "09-07-18 01:27 PM"
                                 (format-wowinterface-dt dmy-hm)))
        
        label (-> snippet (html/select [:a]) second :content first)]
    {:uri (str uri (-> snippet (html/select [:a]) last :attrs :href extract-id))
     :name (-> label (or "") slugify)
     :label label
     ;;:description nil ;; not available in summary
     :category-list []
     :created-date nil
     :updated-date (-> snippet (html/select [:div.updated html/content]) first extract-updated-date)
     :download-count 0}))

(defn scrape-addon-page
  [category]
  (let [snippet (-> category :url http/download html/html-snippet)]
    (mapv extract-addon-summary (-> snippet (html/select [:#filepage :div.file])))))

;; todo: this will be moved to core
(defn scrape
  []
  (binding [http/*cache* (core/cache)]    
    (-> (parse-category-list) first scrape-addon-page)))
    
