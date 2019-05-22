(ns wowman.wowinterface
  (:require
   [clojure.string]
   [clojure.set]
   [me.raynes.fs :as fs]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [wowman
    [specs :as sp]
    [utils :as utils]
    [http :as http]]
   [flatland.ordered.map :as omap]
   [net.cgrand.enlive-html :as html]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]))

(comment
  "wowinterface.com requires browsing addons by category rather than alphabetically or by recently updated. 
   An RSS file is available to scrape the recently updated.")

(defn parse-category-list
  [root-url]
  (let [snippet (html/html-snippet (http/download root-url))
        cat-list (-> snippet (html/select [:div#colleft :div.subcats :div.subtitle :a]))

        final-url (fn [href]
                    ;; converts the href that looks like '/downloads/cat19.html' to '/downloads/index.php?cid=19"
                    (let [page (fs/base-name href) ;; cat19.html
                          cat-id (str "index.php?cid=" (clojure.string/replace href #"\D*" "")) ;; index.php?cid=19
                          sort-by "&sb=dec_date" ;; updated date, most recent to least recent
                          another-sort-by "&so=desc" ;; most recent to least recent. must be consistent with `sort-by` prefix
                          pt "&pt=f" ;; nfi but it's mandatory
                          page "&page=1" ;; not necessary, and `1` is default. we'll add it here to avoid a cache miss later
                          ]
                      (str "https://www.wowinterface.com/downloads/", cat-id, sort-by, another-sort-by, pt, page)))

        extractor (fn [cat]
                    {:label (-> cat :content first)
                     :url (-> cat :attrs :href final-url)})]
    (debug (format "%s categories found" (count cat-list)))
    (mapv extractor cat-list)))

(defn format-wowinterface-dt
  "formats a shitty US-style m/d/y date with a shitty 12 hour time component and no timezone into a glorious RFC3399 formatted UTC string"
  [dt]
  (let [dt (java-time/local-date-time "MM-dd-yy hh:mm a" dt) ;; "09-07-18 01:27 PM" => obj with no tz
        ;; no tz info available on site, assume utc
        dt-utc (java-time/zoned-date-time dt "UTC") ;; obj with no tz => utc obj
        fmt (get java-time.format/predefined-formatters "iso-offset-date-time")]
    (java-time/format fmt dt-utc)))

(defn extract-addon-summary
  [snippet]
  (try
    (let [uri "https://www.wowinterface.com/downloads/"
          extract-id #(str "info" (-> % (clojure.string/split #"&.+=") last)) ;; ...?foo=bar&id=24731 => info24731
          extract-updated-date (fn [x]
                                 (let [dmy-hm (-> x (subs 8) clojure.string/trim)] ;; "Updated 09-07-18 01:27 PM " => "09-07-18 01:27 PM"
                                   (format-wowinterface-dt dmy-hm)))
          label (-> snippet (html/select [[:a (html/attr-contains :href "fileinfo")] html/content]) first clojure.string/trim)]
      {:uri (str uri (-> snippet (html/select [:a]) last :attrs :href extract-id))
       :name (-> label slugify)
       :label label
       ;;:description nil ;; not available in summary
       ;;:category-list [] ;; not available in summary, added by caller
       ;;:created-date nil ;; not available in summary
       :updated-date (-> snippet (html/select [:div.updated html/content]) first extract-updated-date)
       :download-count (-> snippet (html/select [:div.downloads html/content]) first (clojure.string/replace #"\D*" "") Integer.)})
    (catch RuntimeException re
      (error re "failed to scrape snippet, excluding from results:" (utils/pprint snippet))
      nil)))

(defn scrape-addon-page
  [category page-num]
  (let [url (clojure.string/replace (:url category) #"page=\d+" (str "page=" page-num))
        page-content (-> url http/download html/html-snippet)
        addon-list (-> page-content (html/select [:#filepage :div.file]))
        extractor (fn [snippet]
                    (assoc (extract-addon-summary snippet) :category-list #{(:label category)}))]
    (mapv extractor addon-list)))

(defn scrape-category-page
  [category]
  (info (:label category))
  (let [;; these are sub-category pages and are handled 
        skippable ["Class & Role Specific" "Info, Plug-in Bars"]] ;; pages of sub-categories
    (if (some #{(:label category)} skippable)
      []
      (let [;; extract the number of results from the page navigation
            page-content (-> category :url http/download html/html-snippet)
            extractor (partial scrape-addon-page category)
            page-nav (-> page-content (html/select [:.pagenav [:td.alt1 html/last-of-type] :a]))
            ;; just scrape first page if page-nav is empty (page already downloaded to scrape nav ;)
            page-count (if (empty? page-nav) 1 (-> page-nav first :attrs :href
                                                   (clojure.string/split #"=") last Integer.))
            page-range (range 1 (inc page-count))]
        (info (format "scraping %s in category '%s'" page-count (:label category)))
        (flatten (mapv extractor page-range))))))

(defn scrape-latest-updates-page
  [page-n]
  (let [url (str "https://www.wowinterface.com/downloads/latest.php?sb=lastupdate&so=desc&sh=full&pt=f&page=" page-n)
        page-content (-> url http/download html/html-snippet)
        rows (-> page-content (html/select [:div#innerpage :table.tborder :tr]) rest rest) ;; discard first two rows (nav and header)
        rows (drop-last 7 rows) ;; and last 7 (nav and search)
        extractor (fn [row]
                    (let [label (-> row (html/select [:a html/content]) first clojure.string/trim) ;; two links in each row, we want the first one
                          dt (-> row (html/select [:td]) (nth 5) (html/select [:div]) last :content)
                          date (-> dt first clojure.string/trim)
                          time (-> dt second :content first)]
                      {:name (slugify label)
                       :label label
                       :downloads (-> row (html/select [:td]) (nth 4) :content first (clojure.string/replace #"\D*" "") Integer.)
                       :updated-date (format-wowinterface-dt (str date " " time))}))]

    (mapv extractor rows)))

(defn-spec scrape-latest-updates ::sp/addon-summary-list
  "downloads latest update pages until given date reached or exceeded"
  [since-date ::sp/inst]
  (loop [page-n 1
         accumulator []]
    (info "downloading addon updates page" page-n)
    (let [;;results (extract-addon-summary-list (download-summary-page-by-updated-date page-n))
          results (scrape-latest-updates-page page-n)

          target-addon (last results)
          future-date? (= -1 (compare (clojure.instant/read-instant-date since-date)
                                      (clojure.instant/read-instant-date (:updated-date target-addon))))]
      (if future-date?
      ;; loop until the dates we're seeing are in the past (compared to given date)
        (recur (inc page-n) (into accumulator results))
        (into accumulator results)))))


;;


(defn scrape
  [output-path]
  (let [category-pages ["https://www.wowinterface.com/downloads/cat23.html" ;; 'Stand-Alone addons'
                        "https://www.wowinterface.com/downloads/cat39.html" ;; 'Class & Role Specific
                        "https://www.wowinterface.com/downloads/cat109.html" ;; 'Info, Plug-in Bars'
                        ]
        category-list (flatten (mapv parse-category-list category-pages))
        addon-list (flatten (mapv scrape-category-page category-list))
          ;;addon-list (utils/load-json-file "/home/torkus/.local/share/wowman/wowinterface.json")
          ;;addon-list (:addon-summary-list addon-list) ;; temp

          ;; an addon may belong to many categories
          ;; group addons by their :label (guaranteed to be unique) and then merge the categories together
        addon-groups (group-by :label addon-list)
        addon-list (for [[_ group-list] addon-groups
                         :let [addon (first group-list)]]
                     (assoc addon :category-list
                            (reduce clojure.set/union (map :category-list group-list))))

          ;; copied from curseforge.clj, ensure addon keys are ordered for better diffs
        addon-list (mapv #(into (omap/ordered-map) (sort %)) addon-list)

          ;; the addons themselves should be ordered now. alphabetically I suppose
        addon-list (sort-by :label addon-list)

          ;; this actually reveals 5 pairs of addons whose :label is slightly different
          ;; all 10 addons are unique though, so besides being confusing for the user, it should't break anything
          ;;_ (mapv (fn [[_ group-list]]
          ;;          (when (> (count group-list) 1)
          ;;            (error "two different addons slugify to the same :name" (utils/pprint group-list))))
          ;;        (group-by :name addon-list))
        ]

    (spit output-path (utils/to-json {:spec {:version 1}
                                      :datestamp (utils/datestamp-now-ymd)
                                      :updated-datestamp (utils/datestamp-now-ymd)
                                      :total (count addon-list)
                                      :addon-summary-list addon-list}))
    output-path))

(st/instrument)
