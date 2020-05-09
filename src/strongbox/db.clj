(ns strongbox.db
  (:require
   ;;[clojure.spec.alpha :as s]
   ;;[orchestra.core :refer [defn-spec]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   ;;[strongbox.specs :as sp]]
   ))

(defn put-many
  [db doc-list]
  (into db (vec doc-list)))

(defn query
  [db query]
  nil)

(defn stored-query
  "common queries we can call by keyword"
  [db query-kw & [arg-list]]
  (case query-kw
    :addon-summary-list db
    :addon-by-source-and-name (let [[source name] arg-list
                                    xf (filter #(and (= source (:source %))
                                                     (= name (:name %))))]
                                (into [] xf db))
    :addon-by-name (let [[name] arg-list
                         xf (filter #(= name (:name %)))]
                     (into [] xf db))

    :search (let [[uin cap] arg-list]
              (if (nil? uin)
                (take cap (random-sample 0.1 db))
                (let [label-regex (re-pattern (str "(?i)^" uin ".*"))
                      desc-regex (re-pattern (str "(?i).*" uin ".*"))
                      ;; a little slow and naive, but ok for now
                      xf (filter (fn [row]
                                   (or
                                    (re-find label-regex (:label row))
                                    (re-find desc-regex (get row :description "")))))]
                  (into [] (comp xf (take cap)) db))))
    nil))

(defn start
  "initialises the database, returning something that can be used to access it later"
  []
  (let [db []]
    db))

;;;

(defn find-in-db
  "looks for `installed-addon` in the given `db`, matching `toc-key` to a `catalogue-key`.
  if a `toc-key` and `catalogue-key` are actually lists, then all the `toc-keys` must match the `catalogue-keys`"
  [db installed-addon toc-keys catalogue-keys]
  (let [;; ["source" "source_id"] => ["source" "source_id"], "name" => ["name"]
        catalogue-keys (if (vector? catalogue-keys) catalogue-keys [catalogue-keys])
        toc-keys (if (vector? toc-keys) toc-keys [toc-keys])

        ;; [:source :source-id] => ["curseforge" 12345], [:name] => ["foo"]
        arg-vals (mapv #(get installed-addon %) toc-keys)
        missing-args? (some nil? arg-vals)

        ;; there are cases where the installed-addon is missing an attribute to match on. typically happens on :alias
        _ (when missing-args?
            (debug "failed to find all values for db search, refusing to match against nil values. keys:" toc-keys "; vals:" arg-vals))

        func (fn [row]
               (= arg-vals (mapv #(get row %) catalogue-keys)))

        results (if missing-args?
                  [] ;; don't look for 'nil', just skip with no results
                  (into [] (filter func) db))
        match (-> results first)]
    (when match
      ;; {:idx [[:source :source-id] [:source :source_id]], :key "deadly-boss-mods", :match {...}, ...}
      {:idx [toc-keys catalogue-keys]
       :key arg-vals
       :installed-addon installed-addon
       :matched? (not (nil? match))
       :catalogue-match match

       ;; :final (moosh-addons installed-addon match) ;; mooshing moved back into core
       })))

(defn -find-first-in-db
  "given an `installed-addon`, try to find a match in the catalogue using a list of attributes in `match-on-list`"
  [db installed-addon match-on-list]
  (if (empty? match-on-list) ;; we may have exhausted all possibilities. not finding a match is ok
    installed-addon
    (let [[toc-keys catalogue-keys] (first match-on-list) ;; => [:name] or [:source-id :source]
          match (find-in-db db installed-addon toc-keys catalogue-keys)]
      (if-not match ;; recur
        (-find-first-in-db db installed-addon (rest match-on-list))
        match))))

(defn -db-match-installed-addons-with-catalogue
  "for each installed addon, search the catalogue across multiple joins until a match is found. returns immediately when first match is found"
  [db installed-addon-list]
  (let [;; toc-key -> db-catalogue-key
        ;; most -> least desirable match
        ;; nest to search across multiple parameters
        match-on-list [[[:source :source-id] [:source :source-id]] ;; source+source-id, perfect case
                       [:alias :name] ;; alias = name, popular addon's hardcoded name to a catalogue item
                       [:source :name] ;; source+name, we have a source but no source-id (nfo-v1 files)
                       [:name :name]
                       [:label :label]
                       [:dirname :label]] ;; dirname = label, eg ./AdiBags = AdiBags
        ]
    (for [installed-addon installed-addon-list]
      (-find-first-in-db db installed-addon match-on-list))))

;;


(comment
  (defn sqldb-search
    "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
    ([]
   ;; random list of addons, no preference
     (mapv sqldb-coerce-catalogue-values
           (sqldb-query (str select-*-catalogue "order by RAND() limit ?") :arg-list [(get-state :search-results-cap)])))
    ([uin]
     (let [uin% (str uin "%")
           %uin% (str "%" uin "%")]
       (mapv sqldb-coerce-catalogue-values
             (sqldb-query (str select-*-catalogue "where label ilike ? or description ilike ?")
                          :arg-list [uin% %uin%]
                          :opts {:max-rows (get-state :search-results-cap)})))))

  (defn query-sqldb
    "like `get-state`, uses 'paths' (keywords) to do predefined queries"
    [kw & [arg-list]]
    (case kw
      :addon-summary-list (->> (sqldb-query select-*-catalogue) (mapv sqldb-coerce-catalogue-values))
      :addon-by-source-and-name (as-> select-*-catalogue q,
                                  (str q "WHERE source = ? AND name = ?")
                                  (sqldb-query q :arg-list arg-list)
                                  (mapv sqldb-coerce-catalogue-values q))
      :addon-by-name (as-> select-*-catalogue $,
                       (str $ "WHERE name = ?")
                       (sqldb-query $ :arg-list arg-list)
                       (mapv sqldb-coerce-catalogue-values $))

      nil))

  (defn db-search
    "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
    ([]
   ;; random list of addons, no preference
     (mapv sqldb-coerce-catalogue-values
           (sqldb-query (str select-*-catalogue "order by RAND() limit ?") :arg-list [(get-state :search-results-cap)])))
    ([uin]
     (let [uin% (str uin "%")
           %uin% (str "%" uin "%")]
       (mapv sqldb-coerce-catalogue-values
             (sqldb-query (str select-*-catalogue "where label ilike ? or description ilike ?")
                          :arg-list [uin% %uin%]
                          :opts {:max-rows (get-state :search-results-cap)}))))))
