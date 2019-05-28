(ns wowman.catalog
  (:require
   [flatland.ordered.map :as omap]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]
   [wowman
    ;;[core :as core]
    [utils :as utils]
    [specs :as sp]
    [curseforge :as curseforge]
    [wowinterface :as wowinterface]]))

(defn todt
  [dt]
  (java-time/zoned-date-time (get java-time.format/predefined-formatters "iso-zoned-date-time") dt))

(defn utcnow
  []
  (java-time/zoned-date-time (java-time/local-date-time) "UTC"))

;;

;; ... this feels like boilerplate. better way?
;; curseforge/wowinterface won't be able to 'reach back' into catalog.clj ...

(defmulti expand-summary (comp keyword :source))

(defmethod expand-summary :curseforge
  [addon-summary]
  (curseforge/expand-summary addon-summary))

(defmethod expand-summary :wowinterface
  [addon-summary]
  (warn "summary expansion not implemented for wowinterface yet!")
  addon-summary)

(defmethod expand-summary :default
  [addon-summary]
  (error "malformed addon-summary:" (utils/pprint addon-summary)))

;;

(defn-spec write-addon-file ::sp/extant-file
  [output-file ::sp/file, addon-list ::sp/addon-summary-list, created-date ::sp/catalog-created-date, updated-date ::sp/catalog-updated-date]
  (let [addon-list (mapv #(into (omap/ordered-map) (sort %))
                         (sort-by :name addon-list))]
    (spit output-file (utils/to-json {:spec {:version 1}
                                      :datestamp created-date
                                      :updated-datestamp updated-date
                                      :total (count addon-list)
                                      :addon-summary-list addon-list}))
    output-file))

;;

;; todo: test this logic. it feels sound but also a quiet place for logic bugs to lurk
(defn-spec merge-catalogs ::sp/extant-file
  [output-file ::sp/file, catalog-a ::sp/extant-file, catalog-b ::sp/extant-file]
  (let [aa (utils/load-json-file catalog-a)
        ab (utils/load-json-file catalog-b)

        ;; should these two dates belong to the catalog proper or derived from the component catalogs?
        ;; lets go with derived for now
        created-date (first (sort [(:datestamp aa) (:datestamp ab)])) ;; earliest of the two catalogs
        updated-date (last (sort [(:updated-datestamp aa) (:updated-datestamp ab)])) ;; most recent of the two catalogs

        addon-list (into (:addon-summary-list aa)
                         (:addon-summary-list ab))
        _ (info "total addons:" (count addon-list))

        addon-groups (group-by :name addon-list)
        _ (info "total unique addons:" (count addon-groups))

        ;; addons that appear in both catalogs
        multiple-sources (filter (fn [[name group-list]]
                                   (> (count group-list) 1)) addon-groups)
        _ (info "total overlap:" (count multiple-sources) "(addons)" (count (flatten (vals multiple-sources))) "(entries)")

        ;; drop those addons that appear in multiple catalogs
        ;; they get some additional filtering
        multiple-sources-key-set (set (keys multiple-sources))
        ;; (this takes ages, there must be a faster way to remove ~1k rows from ~10k results)
        addon-list (remove #(some #{(:name %)} multiple-sources-key-set) addon-list)
        ;;_ (info "addons sans multiples:" (count addon-list))

        ;; when there is a very large gap between updated-dates (2yrs), drop one addon in favour of the other
        ;; at time of writing:
        ;; - filtering for > 2 years removes  231 of the 2356 addons overlapping, leaving 2125 addons appearing in both catalogs
        ;; - filtering for > 1 year removes   338 of the 2356 addons overlapping, leaving 2018 addons appearing in both catalogs
        ;; - filtering for > 6 months removes 389 of the 2356 addons overlapping, leaving 1967 addons appearing in both catalogs
        ;; - filtering for > 1 month removes  471 of the 2356 addons overlapping, leaving 1885 addons appearing in both catalogs
        drop-some-addons (mapv (fn [[name group-list]]

                                 ;; sanity check. it's definitely possible for an addon to appear more than twice
                                 (when (> (count group-list) 2)
                                   ;; huh, was expecting more than none
                                   ;; there should have been groupings *within* wowinterface ...
                                   ;; but if those groupings don't appear in curseforge, then that would make sense.
                                   ;; update: definitely the case after checking the duplicates within wowinterface.
                                   (error "addon has more than 2 entries in group:" (:name (first group-list)) (count group-list)))

                                 (let [[aa ab] (take 2 (sort-by #(-> % :description count) group-list))
                                       adt (todt (:updated-date aa)) ;; wowinterface
                                       bdt (todt (:updated-date ab)) ;; curseforge (probably)
                                       diff (java-time/duration adt bdt)
                                       neg? (java-time/negative? diff)
                                       diff-days (java-time/as diff :days)
                                       diff-days (if neg? (- diff-days) diff-days)
                                       ;;threshold (* 365 2) ;; two years
                                       ;;threshold (/ 366 2) ;; six-ish months
                                       threshold 28 ;; 1 moonth. if an addon author hasn't updated both hosts within four weeks, then ...
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
                       (let [source (if-not (contains? a :description) :wowinterface :curseforge)
                             ;; an even more slugified label with hyphens and underscores removed
                             alt-name (-> a :label (slugify ""))
                             dtobj (java-time/zoned-date-time (:updated-date a))
                             age-in-days (java-time/as (java-time/duration dtobj today) :days)
                             version-age (condp #(< %2 %1) age-in-days
                                           7 :brand-new ;; < 1 week old
                                           14 :new      ;; 1-2 weeks old
                                           42 :recent   ;; 2-6 weeks old
                                           168 :mature  ;; 6 weeks-6 months old (28*6)
                                           504 :aging   ;; 6-18 months old (28*18)
                                           :ancient)
                             ]
                         (merge a {:source source ;; json serialisation will stringify this :(
                                   :alt-name alt-name
                                   :age version-age
                                   })))

        addon-list (mapv update-addon addon-list)]

    (write-addon-file output-file, addon-list, created-date, updated-date)))

;;

(st/instrument)
