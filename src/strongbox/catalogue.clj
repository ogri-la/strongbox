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
    ;;[core :as core]
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
  "formats given catalogue data"
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
                                         (dissoc :alt-name))]
                         (if (contains? new-row :description)
                           (update-in new-row [:description] utils/safe-subs 255)
                           new-row)))]
      (update-in catalogue-data [:addon-summary-list] (partial mapv row-coerce)))))

(defn read-catalogue
  [catalogue-path & {:as opts}]
  ;; cheshire claims to be twice as fast: https://github.com/dakrone/cheshire#speed
  ;; consolidate catalogue access here
  (let [catalogue-data (spy :info (apply utils/load-json-file-safely (apply concat [catalogue-path] opts)))]
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

(defn-spec -merge-curse-wowi-catalogues ::sp/catalogue
  [aa ::sp/catalogue, ab ::sp/catalogue]
  (let [;; this is 80% sanity check, 20% correctness
        ab (assoc ab :addon-summary-list (de-dupe-wowinterface (:addon-summary-list ab)))

        addon-list (into (:addon-summary-list aa)
                         (:addon-summary-list ab))
        _ (info "total addons:" (count addon-list))

        addon-groups (group-by :name addon-list)
        _ (info "total unique addons:" (count addon-groups))

        ;; addons that appear in both catalogues
        multiple-sources (filter (fn [[_ group-list]]
                                   (> (count group-list) 1)) addon-groups)
        _ (info "total overlap:" (count multiple-sources) "(addons)" (count (flatten (vals multiple-sources))) "(entries)")

        ;; drop those addons that appear in multiple catalogues
        ;; they get some additional filtering
        multiple-sources-key-set (set (keys multiple-sources))
        ;; (this takes ages, there must be a faster way to remove ~1k rows from ~10k results)
        addon-list (remove #(some #{(:name %)} multiple-sources-key-set) addon-list)
        ;;_ (info "addons sans multiples:" (count addon-list))

        ;; todo: move this to shorten-catalogue
        ;; when there is a very large gap between updated-dates, drop one addon in favour of the other
        ;; at time of writing:
        ;; - filtering for > 2 years removes  231 of the 2356 addons overlapping, leaving 2125 addons appearing in both catalogues
        ;; - filtering for > 1 year removes   338 of the 2356 addons overlapping, leaving 2018 addons appearing in both catalogues
        ;; - filtering for > 6 months removes 389 of the 2356 addons overlapping, leaving 1967 addons appearing in both catalogues
        ;; - filtering for > 1 month removes  471 of the 2356 addons overlapping, leaving 1885 addons appearing in both catalogues
        drop-some-addons (mapv (fn [[_ group-list]]

                                 ;; sanity check. it's definitely possible for an addon to appear more than twice
                                 (when (> (count group-list) 2)
                                   ;; huh, was expecting more than none
                                   ;; there should have been groupings *within* wowinterface ...
                                   ;; but if those groupings don't appear in curseforge, then that would make sense.
                                   ;; update: definitely the case after checking the duplicates within wowinterface.
                                   (error "addon has more than 2 entries in group:" (:name (first group-list)) (count group-list)))

                                 (let [[aa ab] (take 2 (sort-by #(-> % :description count) group-list)) ;; :description is nil for wowinterface
                                       adt (todt (:updated-date aa)) ;; wowinterface
                                       bdt (todt (:updated-date ab)) ;; curseforge (probably)
                                       diff (java-time/duration adt bdt)
                                       neg? (java-time/negative? diff)
                                       diff-days (java-time/as diff :days)
                                       diff-days (if neg? (- diff-days) diff-days)
                                       ;;threshold (* 365 2) ;; two years
                                       ;;threshold (/ 366 2) ;; six-ish months
                                       threshold 28 ;; 1 moonth. if an addon author hasn't updated both hosts within four weeks, then one is preferred over the other
                                       ]
                                   (if (> diff-days threshold)
                                     ;; if the diff is negative, that means B (curseforge) is older than A (wowinterface)
                                     (if neg?
                                       [aa]
                                       [ab])
                                     [aa ab])))
                               multiple-sources)
        filtered-group-addons (flatten drop-some-addons)
        _ (info "duplicate addons pruned after filtering for age:" (- (count (flatten (vals multiple-sources)))
                                                                      (count filtered-group-addons)))

        addon-list (into addon-list filtered-group-addons)
        _ (info "final addon count" (count addon-list) (format "(%s survived duplicate pruning)" (count filtered-group-addons)))

        today (utcnow)
        update-addon (fn [a]
                       (let [dtobj (java-time/zoned-date-time (:updated-date a))
                             age-in-days (java-time/as (java-time/duration dtobj today) :days)
                             version-age (condp #(< %2 %1) age-in-days
                                           7 :brand-new ;; < 1 week old
                                           42 :new      ;; 1-6 weeks old
                                           168 :recent  ;; 6 weeks-6 months old (28*6)
                                           504 :aging   ;; 6-18 months old (28*18)
                                           :ancient)

                             ;; todo: normalise categories here
                             ]
                         (merge a {:age version-age})))
        addon-list (mapv update-addon addon-list)

        ;; should these two dates belong to the catalogue proper or derived from the component catalogues?
        ;; lets go with derived for now
        created-date (first (sort [(:datestamp aa) (:datestamp ab)])) ;; earliest of the two catalogues
        updated-date (last (sort [(:updated-datestamp aa) (:updated-datestamp ab)]))] ;; most recent of the two catalogues

    (format-catalogue-data addon-list created-date updated-date)))

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

(defn-spec merge-curse-wowi-catalogues ::sp/catalogue
  [curseforge-catalogue ::sp/extant-file, wowinterface-catalogue ::sp/extant-file]
  (let [aa (utils/load-json-file curseforge-catalogue)
        ab (utils/load-json-file wowinterface-catalogue)]
    (-merge-curse-wowi-catalogues aa ab)))

;;

(st/instrument)
