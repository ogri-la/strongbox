(ns strongbox.catalogue
  (:require
   [flatland.ordered.map :as omap]
   [clojure.set]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [strongbox
    [utils :as utils :refer [todt utcnow]]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [curseforge-api :as curseforge-api]
    [wowinterface-api :as wowinterface-api]
    [github-api :as github-api]]))

(defn expand-summary
  [addon-summary game-track]
  (let [dispatch-map {"curseforge" curseforge-api/expand-summary
                      "wowinterface" wowinterface-api/expand-summary
                      "github" github-api/expand-summary
                      "tukui" tukui-api/expand-summary
                      "tukui-classic" tukui-api/expand-summary
                      nil (fn [_ _] (error "malformed addon-summary:" (utils/pprint addon-summary)))}
        key (:source addon-summary)]
    (try
      (if-let [dispatch-fn (get dispatch-map key)]
        (dispatch-fn addon-summary game-track)
        (error (format "addon '%s' is from source '%s' that is unsupported" (:label addon-summary) key)))
      (catch Exception e
        (error e "unhandled exception attempting to expand addon summary")
        (error "please report this! https://github.com/ogri-la/strongbox/issues")))))

;;

(defn-spec format-catalogue-data ::sp/catalogue
  "formats given catalogue data" ;; todo: this is a bad docstr
  [addon-list ::sp/addon-summary-list, created-date ::sp/catalogue-created-date, updated-date ::sp/catalogue-updated-date]
  (let [addon-list (mapv #(into (omap/ordered-map) (sort %))
                         (sort-by :name addon-list))]
    {:spec {:version 1}
     :datestamp created-date
     :updated-datestamp updated-date
     :total (count addon-list)
     :addon-summary-list addon-list}))

(defn-spec wowman-coercer (s/or :ok ::sp/catalogue, :empty nil?)
  "temporary pre-processor of wowman catalgoues until strongbox gets it's own"
  [catalogue-data (s/nilable map?)]
  (when-not (empty? catalogue-data)
    (let [row-coerce (fn [row]
                       (let [new-row (-> row
                                         (clojure.set/rename-keys {:uri :url})
                                         (dissoc :alt-name))

                             new-row (if (contains? new-row :description)
                                       (update-in new-row [:description] utils/safe-subs 255)
                                       new-row)

                             new-row (if (contains? new-row :category-list)
                                       (-> new-row
                                           (clojure.set/rename-keys {:category-list :tag-list})
                                           (update-in [:tag-list] utils/category-list-to-tag-list))
                                       new-row)]

                         new-row))]

      (update-in catalogue-data [:addon-summary-list] (partial mapv row-coerce)))))

(defn read-catalogue
  [catalogue-path & {:as opts}]
  ;; cheshire claims to be twice as fast: https://github.com/dakrone/cheshire#speed
  ;; consolidate catalogue access here
  (let [catalogue-data (apply utils/load-json-file-safely (apply concat [catalogue-path] opts))]
    (utils/nilable (wowman-coercer catalogue-data))))

(defn-spec write-catalogue ::sp/extant-file
  "write catalogue to given `output-file` as JSON. returns path to output file"
  [catalogue-data ::sp/catalogue, output-file ::sp/file]
  (utils/dump-json-file output-file catalogue-data)
  (info "wrote" output-file)
  output-file)

(defn-spec new-catalogue ::sp/catalogue
  [addon-list ::sp/addon-summary-list]
  (let [created (utils/datestamp-now-ymd)
        updated created]
    (format-catalogue-data addon-list created updated)))

(defn-spec write-empty-catalogue! ::sp/extant-file
  "writes a stub catalogue to the given `output-file`"
  [output-file ::sp/file]
  (let [created (utils/datestamp-now-ymd)
        updated created]
    (write-catalogue (new-catalogue []) output-file)))

;;

(defn-spec parse-user-string (s/or :ok ::sp/addon-summary, :error nil?)
  "given a string, figures out the addon source (github, etc) and dispatches accordingly."
  [uin string?]
  (let [dispatch-map {"github.com" github-api/parse-user-string
                      "www.github.com" github-api/parse-user-string ;; alias
                      }]
    (try
      (when-let [f (some->> uin utils/unmangle-https-url java.net.URL. .getHost (get dispatch-map))]
        (info "inspecting:" uin)
        (f uin))
      (catch java.net.MalformedURLException mue
        (debug "not a url")))))

(defn-spec merge-catalogues (s/or :ok ::sp/catalogue, :error nil?)
  "merges catalogue `cat-b` over catalogue `cat-a`.
  earliest creation date preserved.
  latest updated date preserved.
  addon-summary-list is unique by `:source` and `:source-id` with differing values replaced by those in `cat-b`"
  [cat-a (s/nilable ::sp/catalogue), cat-b (s/nilable ::sp/catalogue)]
  (let [matrix {;;[true true] ;; two non-empty catalogues, ideal case
                [true false] cat-a ;; cat-b empty, return cat-a
                [false true] cat-b ;; vice versa
                [false false] nil}
        not-empty? (complement empty?)
        key [(not-empty? cat-a) (not-empty? cat-b)]]
    (if (contains? matrix key)
      (get matrix key)
      (let [created-date (first (sort [(:datestamp cat-a) (:datestamp cat-b)])) ;; earliest
            updated-date (last (sort [(:updated-datestamp cat-a) (:updated-datestamp cat-b)])) ;; latest
            addons-a (:addon-summary-list cat-a)
            addons-b (:addon-summary-list cat-b)
            addon-summary-list (->> (concat addons-a addons-b) ;; join the two lists
                                    (group-by (juxt :source-id :source)) ;; group by the key
                                    vals ;; drop the map
                                    (map (partial apply merge))) ;; merge (not replace) the groups into single maps
            ]
        (format-catalogue-data addon-summary-list created-date updated-date)))))

;; 

(defn de-dupe-wowinterface
  "at time of writing, wowinterface has 5 pairs of duplicate addons with slightly different labels
  for each pair we'll pick the most recently updated.
  these pairs *may* get picked up and filtered out further down when comparing merged catalogues,
  depending on the time difference between the two updated dates"
  [addon-list]
  (let [de-duper (fn [[_ group-list]]
                   (if (> (count group-list) 1)
                     (do
                       (warn "wowinterface: multiple addons slugify to the same :name" (utils/pprint group-list))
                       (last (sort-by :updated-date group-list)))
                     (first group-list)))
        addon-groups (group-by :name addon-list)]
    (mapv de-duper addon-groups)))

;;

(defn-spec shorten-catalogue (s/or :ok ::sp/catalogue, :problem nil?)
  [full-catalogue-path ::sp/extant-file]
  (let [{:keys [addon-summary-list datestamp]}
        (utils/load-json-file-safely
         full-catalogue-path
         :no-file? #(error (format "catalogue '%s' could not be found" full-catalogue-path))
         :bad-data? #(error (format "catalogue '%s' is malformed and cannot be parsed" full-catalogue-path))
         :invalid-data? #(error (format "catalogue '%s' is incorrectly structured and will not be parsed" full-catalogue-path))
         :data-spec ::sp/catalogue)

        unmaintained? (fn [addon]
                        (let [dtobj (java-time/zoned-date-time (:updated-date addon))
                              release-of-previous-expansion (utils/todt "2016-08-30T00:00:00Z")]
                          (java-time/before? dtobj release-of-previous-expansion)))]
    (when addon-summary-list
      (format-catalogue-data (remove unmaintained? addon-summary-list) datestamp datestamp))))

;;

(st/instrument)
