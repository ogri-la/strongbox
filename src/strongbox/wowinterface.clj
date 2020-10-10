(ns strongbox.wowinterface
  (:require
   [orchestra.core :refer [defn-spec]]
   [clojure.string :refer [trim lower-case upper-case]]
   [clojure.set]
   [slugify.core :refer [slugify]]
   [strongbox
    [tags :as tags]
    [utils :as utils]
    [http :as http]]
   [flatland.ordered.map :as omap]
   [net.cgrand.enlive-html :as html :refer [html-snippet select]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]))

(def host "https://www.wowinterface.com/downloads/")

(def category-group-pages
  {"cat23.html" "Stand-Alone addons"
   "cat39.html" "Class & Role Specific"
   "cat109.html" "Info, Plug-in Bars"
   "cat158.html" "Classic - General"})

(defn-spec -format-wowinterface-dt string?
  "formats a shitty US-style m/d/y date with a shitty 12 hour time component and no timezone
  into a glorious RFC3399 formatted UTC string."
  [dt string?]
  (let [;; https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html
        dt (java-time/local-date-time "MM-dd-yy hh:mm a" dt) ;; "09-07-18 01:27 PM" => obj with no tz
        ;; no tz info available on site, assume utc
        dt-utc (java-time/zoned-date-time dt "UTC") ;; obj with no tz => utc obj
        fmt (get java-time.format/predefined-formatters "iso-offset-date-time")]
    (java-time/format fmt dt-utc)))

(defn-spec format-wowinterface-dt string?
  "formats a shitty US-style m/d/y date with a shitty 12 hour time component and no timezone
  into a glorious RFC3399 formatted UTC string."
  [dt string?]
  (try
    (-format-wowinterface-dt (lower-case dt)) ;; lowercase (java 11) first
    (catch Exception e ;; DateTimeParseException *isn't* being thrown here
      ;; because of some locale bs, datetime formatting is case sensitive in java 11 but not java 8
      ;; and only in non-US locales. This is why it passes tests in CI but not locally.
      ;; -- https://stackoverflow.com/questions/38250379/java8-datetimeformatter-am-pm
      (-format-wowinterface-dt (upper-case dt))))) ;; upper-case (java 8) is what is returned by wowi. 

(defn scrape-category-group-page
  [category-page] ;; => "cat23.html"
  (let [snippet (-> host (str category-page) http/download html-snippet)
        cat-list (-> snippet (select [:div#colleft :div.subcats :div.subtitle :a]))
        final-url (fn [href]
                    ;; converts the href that looks like '/downloads/cat19.html' to '/downloads/index.php?cid=19"
                    (let [cat-id (str "index.php?cid=" (clojure.string/replace href #"\D*" "")) ;; index.php?cid=19
                          sort-by "&sb=dec_date" ;; updated date, most recent to least recent
                          another-sort-by "&so=desc" ;; most to least recent. must be consistent with `sort-by` prefix
                          pt "&pt=f" ;; nfi but it's mandatory
                          page "&page=1"] ;; not necessary, and `1` is default. we'll add it here to avoid a cache miss later

                      ;; => https://www.wowinterface.com/downloads/index.php?cid=160&sb=dec_date&so=desc&pt=f&page=1
                      (str host, cat-id, sort-by, another-sort-by, pt, page)))
        extractor (fn [cat]
                    {:label (-> cat :content first)
                     :url (-> cat :attrs :href final-url)})]
    (debug (format "%s categories found" (count cat-list)))
    (mapv extractor cat-list)))

(defn extract-source-id
  [a]
  ;; fileinfo.php?s=c33edd26881a6a6509fd43e9a871809c&amp;id=23145 => 23145
  (-> a :attrs :href (clojure.string/split #"&.+=") last Integer/valueOf))

(defn extract-addon-url
  [a]
  (str host "info" (extract-source-id a)))

(defn extract-addon-summary
  [snippet]
  (try
    (let [extract-updated-date #(format-wowinterface-dt
                                 (-> % (subs 8) trim)) ;; "Updated 09-07-18 01:27 PM " => "09-07-18 01:27 PM"
          anchor (-> snippet (select [[:a (html/attr-contains :href "fileinfo")]]) first)
          label (-> anchor :content first trim)]
      {:url (extract-addon-url anchor)
       :name (-> label slugify)
       :label label
       :source "wowinterface"
       :source-id (extract-source-id anchor)
       ;;:description nil ;; not available in summary
       ;;:category-list [] ;; not available in summary, added by caller
       ;;:created-date nil ;; not available in summary
       :updated-date (-> snippet (select [:div.updated html/content]) first extract-updated-date)
       :download-count (-> snippet (select [:div.downloads html/content]) first (clojure.string/replace #"\D*" "") Integer/valueOf)})
    (catch RuntimeException re
      (error re (format "failed to scrape snippet with '%s', excluding from results: %s" (.getMessage re) (utils/pprint snippet)))
      nil)))

(defn scrape-addon-list
  [category page-num]
  (let [url (clojure.string/replace (:url category) #"page=\d+" (str "page=" page-num))
        page-content (-> url http/download html-snippet)
        addon-list (-> page-content (select [:#filepage :div.file]))
        extractor (fn [snippet]
                    (assoc (extract-addon-summary snippet) :category-list #{(:label category)}))]
    (mapv extractor addon-list)))

(defn scrape-category-page-range
  [category]
  (let [;; extract the number of results from the page navigation
        page-content (-> category :url http/download html-snippet)
        page-nav (-> page-content (select [:.pagenav [:td.alt1 html/last-of-type] :a]))
        ;; just scrape first page when page-nav is empty
        page-count (if (empty? page-nav) 1 (-> page-nav first :attrs :href
                                               (clojure.string/split #"=") last Integer/valueOf))
        page-range (range 1 (inc page-count))]
    page-range))

(defn scrape-category
  [category]
  (let [extractor (partial scrape-addon-list category)
        ;; note: sometimes a category also lists other categories :(
        ;; in this case, `scrape-category-page-range` will return a single page
        ;; and `scrape-addon-list` will detect no list of addons
        page-range (scrape-category-page-range category)]
    (info (format "scraping %s pages in '%s'" (last page-range) (:label category)))
    (flatten (mapv extractor page-range))))

(defn download-parse-filelist-file
  "returns a map of wowinterface addons, keyed by their :source-id (as a string).
  wowinterface.com has a single large file with all/most of their addon data in it called 'filelist.json'.
  the addon details endpoint is missing supported versions of wow it in.
  Instead that data is in this list and must be incorporated in the catalogue."
  []
  (let [url "https://api.mmoui.com/v3/game/WOW/filelist.json"
        resp (http/download url)
        file-details (utils/from-json resp)
        file-details (mapv (fn [addon]
                             (update addon :UID #(Integer/parseInt %))) file-details)]
    (group-by :UID file-details)))

(defn expand-addon-with-filelist
  [filelist addon]
  (let [filelist-addon (->> addon :source-id (get filelist) first)
        ;; supported game version is not the same as game track ('classic' or 'retail')
        ;; wowinterface conflates the two (or am I splitting hairs?)
        ;; if 'WoW Classic' is found, then the 'classic' game track is supported
        ;; if more results are found, retail is supported as well
        compatibility (->> filelist-addon :UICompatibility (map :name) set)
        many-results? (> (count compatibility) 1)
        wowi-classic "WoW Classic"

        mapping {[wowi-classic true]  #{:classic :retail}
                 [wowi-classic false] #{:classic}
                 [nil true] #{:retail}
                 [nil false] #{:retail}}

        key [(some #{wowi-classic} compatibility) many-results?]]
    (assoc addon :game-track-list (get mapping key))))

(defn scrape
  "wowinterface uses 'groups of categories'.
  the topmost page '/' lists each of the groups and the list of categories within that group.
  each category group has it's own page, the main one being '/downloads/cat23.html' or 'Stand-Alone addons'.
  we ignore the top-level '/' list-of-groups-of-categories and scrape the individual category group pages.
  addons may live in more than one category.
  after scraping we then group and reduce the duplicates, ensuring the names of the categories are preserved."
  []
  (let [;; create a single list of all categories to scrape
        all-categories (->> category-group-pages keys (map scrape-category-group-page) flatten)

        ;; create a single list of all addons in all categories
        addon-list (->> all-categories (map scrape-category) flatten)

        ;; an addon may belong to many categories
        ;; group addons by their :source-id and then merge together, preserving the categories
        addon-groups (group-by :source-id addon-list)
        addon-list (for [[_ group-list] addon-groups
                         :let [addon (first group-list)
                               category-list (reduce clojure.set/union (map :category-list group-list))]]
                     (-> addon
                         (merge {:tag-list (tags/category-list-to-tag-list "wowinterface" category-list)})
                         (dissoc :category-list)))

        filelist (download-parse-filelist-file)

        ;; there are 195 (at time of writing) addons scraped from the site that are not present in the filelist.json file.
        ;; these appear to be discontinued/obsolete/beta-only/'removed at author's request'/etc type addons.
        ;; this removes those addons from the addon-list
        pre-filter-count (count addon-list)

        addon-list (filter (fn [addon]
                             (get filelist (:source-id addon))) addon-list)

        _ (info (format "%s addons present on site that are missing from fileList.json"
                        (- pre-filter-count (count addon-list))))

        ;; moosh extra data into each addon from the filelist
        addon-list (mapv (partial expand-addon-with-filelist filelist) addon-list)

        ;; ensure addon keys are ordered for better diffs
        addon-list (mapv #(into (omap/ordered-map) (sort %)) addon-list)

        ;; the addons themselves should be ordered now. alphabetically I suppose
        addon-list (sort-by :label addon-list)]

    addon-list))
