(ns strongbox.db
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [strongbox.specs :as sp]))

(defn-spec put-many :addon/summary-list
  "adds all of the items from `doc-list` into the given `db`.
  this is a leftover from when the database was using the H2 rdbms but kept because I 
  like the separation it provides"
  [db vector?, doc-list :addon/summary-list]
  (into db (vec doc-list)))

;; matching

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

        ;; for each catalogue key, fetch the values and compare
        match? (fn [row]
                 (= arg-vals (mapv #(get row %) catalogue-keys)))

        results (if missing-args?
                  [] ;; don't look for 'nil', just skip with no results
                  (into [] (filter match?) db))
        match (-> results first)]
    (when match
      {;; the relationship the match was made on: [[:source :source-id] [:source :source_id]]
       :idx [toc-keys catalogue-keys]
       ;; the values of the match: ["curseforge" "deadly-boss-mods"]
       :key arg-vals
       :installed-addon installed-addon
       :matched? (not (nil? match))
       :catalogue-match match})))

;; todo: can we do better than 'map?' now?
(defn-spec -find-first-in-db (s/or :match map?, :no-match nil?)
  "find a match for the given `installed-addon` in the database using a list of attributes in `match-on-list`.
  returns immediately when first match is found (does not check other joins in `match-on-list`)."
  [db :addon/summary-list, installed-addon :addon/toc, match-on-list vector?]
  (if (empty? match-on-list)
    nil ;; we may have exhausted all possibilities. not finding a match is ok.
    (let [[toc-keys catalogue-keys] (first match-on-list) ;; => [:name] or [:source-id :source]
          match (find-in-db db installed-addon toc-keys catalogue-keys)]
      (if-not match ;; recur
        (-find-first-in-db db installed-addon (rest match-on-list))
        match))))

;; todo: flesh out the 'match' and match-on-list specs.
(defn-spec -db-match-installed-addons-with-catalogue (s/coll-of (s/or :match map? :no-match :addon/toc))
  "for each installed addon, search the catalogue across multiple joins until a match is found.
  addons with no match return themselves"
  [db :addon/summary-list, installed-addon-list :addon/toc-list]
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
      (or (-find-first-in-db db installed-addon match-on-list)
          installed-addon))))

;; querying

(defn-spec -addon-by-source-and-name :addon/summary-list
  "returns a list of addon summaries whose source and name match (exactly) the given `source` and `name`"
  [db :addon/summary-list, source :addon/source, name ::sp/name]
  (let [xf (filter #(and (= source (:source %))
                         (= name (:name %))))]
    (into [] xf db)))

(defn-spec -addon-by-name :addon/summary-list
  "returns a list of addon summaries whose name matches (exactly) the given `name`"
  [db :addon/summary-list, name ::sp/name]
  (let [xf (filter #(= name (:name %)))]
    (into [] xf db)))

(defn-spec -search :addon/summary-list
  "returns a list of addon summaries whose label or description matches the given user input `uin`.
  matches are case insensitive.
  label matching matches from the beginning of the label.
  description matching matches any substring within description"
  [db :addon/summary-list, uin (s/nilable string?), cap int?]
  (if (nil? uin)
    (take cap (random-sample 0.005 db))
    (let [label-regex (re-pattern (str "(?i)^" uin ".*"))
          desc-regex (re-pattern (str "(?i).*" uin ".*"))
          ;; a little slow and naive, but ok for now
          xf (filter (fn [row]
                       (or
                        (re-find label-regex (:label row))
                        (re-find desc-regex (get row :description "")))))]
      (into [] (comp xf (take cap)) db))))

;; not specced because the results and argument lists may vary greatly
(defn stored-query
  "common queries we can call by keyword"
  [db query-kw & [arg-list]]
  (case query-kw
    :addon-by-source-and-name (-addon-by-source-and-name db (first arg-list) (second arg-list))
    :addon-by-name (-addon-by-name db (first arg-list))
    :search (-search db (first arg-list) (second arg-list))
    nil))
