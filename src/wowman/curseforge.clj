(ns wowman.curseforge
  (:require
   [wowman
    [http :as http]
    [specs :as sp]
    [utils :as utils :refer [to-int to-json fmap join from-epoch to-uri]]]
   [slugify.core :refer [slugify]]
   [flatland.ordered.map :as omap]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [net.cgrand.enlive-html :as html]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def curseforge-host "https://www.curseforge.com")

(defn suffix
  [v sfx]
  (when-not (empty? v)
    (str v sfx)))

(defn-spec formatted-str-to-num int?
  [string string?]
  (-> string (clojure.string/replace #"[^\d]*" "") clojure.string/trim Integer.))

;;

;; TODO: test 'nil?' return value
(defn-spec download-summary-page-alphabetically (s/or :ok ::sp/html, :error nil?)
  "downloads a page of results from curseforge, sorted A to Z"
  [page int?]
  (let [;; 'filter-sort=name' means 'order alphabetically, a to z'
        uri-template (str curseforge-host "/wow/addons?filter-sort=name&page=%s")]
    (http/download (format uri-template page))))

(defn-spec download-summary-page-by-updated-date ::sp/html
  "downloads a page of results from curseforge, sorted by most recently updated"
  [page int?]
  (let [;; 'filter-sort=2' means 'order by updated date, most recent to least recent'
        uri-template (str curseforge-host "/wow/addons?filter-sort=2&page=%s")]
    (http/download (format uri-template page))))

;; TODO: test 'nil?' return value
(defn-spec num-summary-pages (s/or :ok int?, :error nil?)
  "returns the total number of summary pages available"
  []
  (let [p1 (html/html-snippet (download-summary-page-alphabetically 1))
        ;; content of the second-to-last list item (the last page of results) converted to an integer 
        pN (-> (html/select p1 [:ul.paging-list :li.b-pagination-item :a]) butlast last :content first Integer.)]
    pN))

;;

(defn-spec extract-addon-summary ::sp/addon-summary
  "converts a snippet of html extracted from a listing into an ::sp/addon-summary"
  [snippet map?]
  (let [label (-> snippet (html/select [:h2]) first :content first clojure.string/trim)]
    {:uri (str curseforge-host (-> snippet (html/select [:div.list-item__details :a]) first :attrs :href))
     :name (-> snippet (html/select [:div.list-item__details :a]) first :attrs :href (clojure.string/split #"/") last)
     :label label
     :description (-> snippet (html/select [:div.list-item__description :p]) first :content first)
     :category-list (mapv #(-> % :attrs :title)
                          (-> snippet (html/select [:div.list-item__categories :a.category__item])))
     :created-date (-> snippet (html/select [:span.date--created :abbr]) first :attrs :data-epoch Integer. from-epoch)
     :updated-date (-> snippet (html/select [:span.date--updated :abbr]) first :attrs :data-epoch Integer. from-epoch)
     :download-count (-> snippet (html/select [:span.count--download]) first :content first formatted-str-to-num)

     ;; deprecated, to be removed from curseforge catalog in 8.0
     ;; is now part of catalog generation 
     :alt-name (-> label (slugify ""))}))

(defn-spec extract-addon-summary-list (s/or :ok ::sp/addon-summary-list, :error empty?)
  "returns a list of snippets extracted from a page of html"
  [html string?]
  (let [parsed (html/html-snippet html)]
    (map extract-addon-summary (html/select parsed [:ul.listing :li.project-list-item]))))

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary]
  (let [message (str "downloading summary data: " (:name addon-summary))
        versions-uri (-> addon-summary :uri (str "/files"))
        versions-data (http/download versions-uri :message message)]
    (when (string? versions-data) ;; map on error
      (let [versions-html (html/html-snippet versions-data)
            latest-release (-> (html/select versions-html [:article :div :div]) first)

            ;; urgh. no fucking #ids in this new version of curseforge
            header (-> (html/select versions-html [:header :div :div :div]) (nth 4) :content rest butlast)

            ;; first inner box on right hand side column
            info-box (-> (html/select versions-html [:aside :div]) (nth 4))
            info-box-links (-> (html/select info-box [[:a (html/attr= "href")]]) vec)

            ;; donation button may not exist, 'report' and 'follow' buttons always exist
            info-box-links (first (utils/filter+map (fn [x]
                                                      (try
                                                        (if (= (some-> x :content second :content first clojure.string/trim) "Donate")
                                                          (-> x :attrs :href to-uri))
                                                        (catch Exception e nil))) info-box-links))
            prefix #(str curseforge-host %)]
        (merge addon-summary
               {:download-uri (-> (html/select latest-release [:a]) first :attrs :href prefix)
                :version (-> (html/select latest-release [:h3 html/content]) first)
                :interface-version (-> header (nth 4) :content first (subs 14) utils/game-version-to-interface-version) ;; (count "Game Version: ") => 14
                :donation-uri info-box-links})))))

;;

(defn-spec download-all-summaries-alphabetically (s/or :ok ::sp/addon-summary-list, :error nil?)
  []
  (let [num-to-download (num-summary-pages)
        _ (info num-to-download "pages to download from curseforge")
        results (mapv download-summary-page-alphabetically (range 1 (inc num-to-download)))
        all-summary-html (flatten results)]
    (flatten (mapv extract-addon-summary-list all-summary-html))))

(defn-spec download-recent-addon-summaries ::sp/addon-summary-list
  "downloads latest update pages until given date reached or exceeded"
  [since-date ::sp/inst]
  (loop [page-n 1
         accumulator []]
    (info "downloading addon updates page" page-n)
    (let [results (extract-addon-summary-list (download-summary-page-by-updated-date page-n))

          ;; curseforge have updated addons appearing out of order, sometimes months out of order.
          ;; this happens often and may cause the loop to exit early if the last addon date is malformed.
          ;; we take the last three, order by :updated-date and choose the last (most recent) one.
          target-addon (last (sort-by :updated-date (take-last 3 results)))

          future-date? (= -1 (compare (clojure.instant/read-instant-date since-date)
                                      (clojure.instant/read-instant-date (:updated-date target-addon))))]
      (if future-date?
        ;; loop until the dates we're seeing are in the past (compared to given date)
        (recur (inc page-n) (into accumulator results))
        (into accumulator results)))))

(defn-spec update-addon-summary-file ::sp/extant-file
  [addon-summary-file ::sp/extant-file, addon-summary-updates-file ::sp/extant-file]
  (let [{created-date :datestamp, addons-list :addon-summary-list} (utils/load-json-file addon-summary-file)
        updated-addons-list (utils/load-json-file addon-summary-updates-file)
        updated-date (utils/datestamp-now-ymd)
        merged-addons-list (utils/merge-lists :name addons-list updated-addons-list :prepend? true)
        ;; ensure consistent key order during serialisation for nicer diffs
        merged-addons-list (mapv #(into (omap/ordered-map) (sort %)) merged-addons-list)]
    (info "updating addon summary file:" addon-summary-file)
    (spit addon-summary-file (utils/to-json {:spec {:version 1}
                                             :datestamp created-date
                                             :updated-datestamp updated-date
                                             :total (count merged-addons-list)
                                             :addon-summary-list merged-addons-list}))
    addon-summary-file))

(defn-spec download-all-addon-summaries ::sp/extant-file
  "downloads all addon summaries from curseforge. path to data file is returned"
  [output-path ::sp/file]
  (let [all-summaries (sort-by :name (download-all-summaries-alphabetically))
        ;; ensure consistent key order during serialisation for nicer diffs
        all-summaries (mapv #(into (omap/ordered-map) (sort %)) all-summaries)]
    (info "writing addon updates:" output-path)
    (spit output-path (utils/to-json {:spec {:version 1}
                                      :datestamp (utils/datestamp-now-ymd)
                                      :updated-datestamp (utils/datestamp-now-ymd)
                                      :total (count all-summaries)
                                      :addon-summary-list all-summaries}))
    output-path))

(defn-spec download-all-addon-summary-updates (s/or :ok ::sp/extant-file, :no-updates nil?)
  "fetches updates from curseforge since the last major scrape"
  [datestamp ::sp/inst, output-path ::sp/file]
  (when (> (utils/days-between-then-and-now datestamp) 0)
    (spit output-path (utils/to-json (download-recent-addon-summaries datestamp)))
    output-path))

;;

(st/instrument)
