(ns strongbox.catalogue
  (:require
   [flatland.ordered.map :as omap]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [java-time]
   [strongbox
    [constants :as constants]
    [addon]
    [tags :as tags]
    [utils :as utils :refer [todt]]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [wowinterface-api :as wowinterface-api]
    [github-api :as github-api]]))

(defn-spec -expand-summary (s/or :ok :addon/expanded, :error nil?)
  "fetches updates from the addon host for the given `addon` and `game-track`.
  does *not* support compound game tracks or warning the user, see `expand-summary`.
  returns `nil` when release not found"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [dispatch-map {"curseforge" curseforge-api/expand-summary
                      "wowinterface" wowinterface-api/expand-summary
                      "github" github-api/expand-summary
                      "tukui" tukui-api/expand-summary
                      "tukui-classic" tukui-api/expand-summary
                      "tukui-classic-tbc" tukui-api/expand-summary
                      nil (fn [_ _] (error "malformed addon:" (utils/pprint addon)))}
        key (:source addon)]
    (try
      (if-not (contains? dispatch-map key)
        (error (format "addon '%s' is from source '%s' that is unsupported" (:label addon) key))
        (let [release-list ((get dispatch-map key) addon game-track)
              latest-release (first release-list)
              pinned-release (when (and release-list
                                        (contains? addon :pinned-version))
                               (strongbox.addon/find-pinned-release (assoc addon :release-list release-list)))
              source-updates (or pinned-release latest-release)]
          (when source-updates
            (-> addon
                (merge source-updates {:release-list release-list})
                (dissoc :release-label)))))
      (catch Exception e
        (error e "unhandled exception attempting to expand addon summary")
        (error "please report this! https://github.com/ogri-la/strongbox/issues")))))

(defn-spec expand-summary (s/or :ok :addon/expanded, :error nil?)
  "fetches updates from the addon host for the given `addon` and `game-track`.
  when `strict?` is `false` and an addon fails to match for the given `game-track`, other game tracks will be checked.
  emits warnings to user when no release found."
  [addon :addon/expandable, game-track :addon-dir/game-track, strict? ::sp/strict?]
  (if-let [source-updates
           (case [game-track strict?]
             [:retail true] (-expand-summary addon :retail)
             [:classic true] (-expand-summary addon :classic)
             [:classic-tbc true] (-expand-summary addon :classic-tbc)
             [:retail false]
             (or
              (-expand-summary addon :retail)
              (-expand-summary addon :classic)
              (-expand-summary addon :classic-tbc))

             [:classic false]
             (or
              (-expand-summary addon :classic)
              (-expand-summary addon :classic-tbc)
              (-expand-summary addon :retail))

             [:classic-tbc false]
             (or
              (-expand-summary addon :classic-tbc)
              (-expand-summary addon :classic)
              (-expand-summary addon :retail)))]

    source-updates

    (let [;; "no 'Retail' release found on github"
          ;; "no 'Classic' release found on wowinterface"
          ;; "no 'Classic (TBC)', 'Classic' or 'Retail' release found on curseforge"

          retail-lbl (sp/game-track-labels-map :retail)
          classic-lbl (sp/game-track-labels-map :classic)
          classic-tbc-lbl (sp/game-track-labels-map :classic-tbc)
          source (:source addon)

          single-template "no '%s' release found on %s."
          multi-template "no '%s', '%s' or '%s' release found on %s."

          msg (case [game-track strict?]
                [:retail true] (format single-template retail-lbl source)
                [:classic true] (format single-template classic-lbl source)
                [:classic-tbc true] (format single-template classic-tbc-lbl source)

                ;; these can happen after alpha/beta/no-lib releases have been excluded and no releases are left
                [:retail false] (format multi-template retail-lbl classic-lbl classic-tbc-lbl source)
                [:classic false] (format multi-template classic-lbl classic-tbc-lbl retail-lbl source)
                [:classic-tbc false] (format multi-template classic-tbc-lbl classic-lbl retail-lbl source))]
      (warn msg))))

;;

(defn-spec toc2summary (s/nilable :addon/summary)
  "accepts toc or toc+nfo data and emits a version of the data that validates as an `:addon/summary`"
  [toc (s/or :just-toc :addon/toc, :mixed :addon/toc+nfo)]
  (let [sink nil
        syn (-> toc
                (merge {:url (:group-id toc)
                        :tag-list []
                        :updated-date constants/fake-datetime
                        :download-count 0
                        :matched? false})
                (select-keys [:source :source-id :url :name :tag-list :label :updated-date :download-count]))

        syn (if (= (:source toc) "wowinterface")
              (cond
                (:installed-game-track toc) (assoc syn :game-track-list [(:installed-game-track toc)])
                (:interface-version toc) (assoc syn :game-track-list [(utils/interface-version-to-game-track (:interface-version toc))])
                :else sink)
              syn)

        ;; we might be able to recover from this.
        ;; wowi and github urls can be reconstructed
        syn (if (-> syn :url nil?)
              (case (:source toc)
                "wowinterface" (assoc syn :url (wowinterface/make-url toc))
                "tukui" (assoc syn :url (tukui-api/make-url toc))
                syn)
              syn)

        ;; url may still be nil at this point, just fail
        syn (if (-> syn :url nil?) sink syn)

        syn (if (-> syn :source nil?) sink syn)]
    syn))


;;


(defn-spec format-catalogue-data :catalogue/catalogue
  "returns a correctly formatted catalogue given a list of addons and a datestamp"
  [addon-list :addon/summary-list, datestamp ::sp/ymd-dt]
  (let [addon-list (mapv #(into (omap/ordered-map) (sort %))
                         (sort-by :name addon-list))]
    {:spec {:version 2}
     :datestamp datestamp
     :total (count addon-list)
     :addon-summary-list addon-list}))

(defn-spec catalogue-v1-coercer :catalogue/catalogue
  "converts wowman-era specification 1 catalogues, coercing them to specification version 2 catalogues"
  [catalogue-data map?]
  (let [row-coerce (fn [row]
                     (-> row
                         (assoc :tag-list (tags/category-list-to-tag-list (:source row) (:category-list row)))
                         (dissoc :category-list)))]
    (-> catalogue-data
        (update-in [:addon-summary-list] #(into [] (map row-coerce) %))
        (update-in [:spec :version] inc))))

(defn -read-catalogue-value-fn
  "used to transform catalogue values as the json is read. applies to both v1 and v2 catalogues."
  [key val]
  (case key
    :game-track-list (mapv keyword val)
    :tag-list (mapv keyword val)
    :description (utils/safe-subs val 255) ;; no database anymore, no hard failures on value length?

    ;; returning the function itself ensures element is removed from the result entirely
    :alt-name -read-catalogue-value-fn

    ;; catalogue-level in v1 catalogue, not the addon-level 'updated-date' that we should keep
    :updated-datestamp -read-catalogue-value-fn

    val))

(defn-spec -read-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "reads the catalogue of addon data at the given `catalogue-path`.
  supports reading legacy catalogues by dispatching on the `[:spec :version]` number."
  [catalogue-path ::sp/file, opts map?]
  (p :catalogue
     (let [key-fn (fn [k]
                    (case k
                      "uri" :url
                      ;;"category-list" :tag-list ;; pushed back into v1 post-processing
                      (keyword k)))
           value-fn -read-catalogue-value-fn ;; defined 'outside' so it can reference itself
           opts (merge opts {:key-fn key-fn :value-fn value-fn})
           catalogue-data (p :catalogue:load-json-file
                             (utils/load-json-file-safely catalogue-path opts))]

       (when-not (empty? catalogue-data)
         (if (-> catalogue-data :spec :version (= 1)) ;; if v1 catalogue, coerce
           (p :catalogue:v1-coercer
              (utils/nilable (catalogue-v1-coercer catalogue-data)))
           catalogue-data)))))

(defn validate
  "validates the given data as a `:catalogue/catalogue`, returning nil if data is invalid"
  [catalogue]
  (sp/valid-or-nil :catalogue/catalogue catalogue))

(defn read-catalogue
  "reads catalogue at given `path` and validates the result, regardless of spec instrumentation.
  returns `nil` if catalogue is invalid."
  ([path]
   (read-catalogue path {}))
  ([path opts]
   (-> path (-read-catalogue opts) validate)))

(defn-spec write-catalogue ::sp/extant-file
  "write catalogue to given `output-file` as JSON. returns path to output file"
  [catalogue-data :catalogue/catalogue, output-file ::sp/file]
  (if (some->> catalogue-data validate (utils/dump-json-file output-file))
    (do
      (info "wrote:" output-file)
      output-file)
    (error "catalogue data is invalid, refusing to write:" output-file)))

(defn-spec new-catalogue :catalogue/catalogue
  "convenience. returns a new catalogue with datestamp of 'now' given a list of addon summaries"
  [addon-list :addon/summary-list]
  (format-catalogue-data addon-list (utils/datestamp-now-ymd)))

(defn-spec write-empty-catalogue! ::sp/extant-file
  "writes a stub catalogue to the given `output-file`"
  [output-file ::sp/file]
  (write-catalogue (new-catalogue []) output-file))

;;

(defn-spec parse-user-string (s/or :by-source (s/keys :req-un [:addon/source :addon/source-id]),
                                   :by-url (s/keys :req-un [:addon/source ::sp/url])
                                   :error nil?)
  "given a string from the user, figures out the addon source (github, etc), calls the right module and returns a stub"
  [uin string?]
  (let [dispatch-map {"github" github-api/parse-user-string
                      "wowinterface" wowinterface-api/parse-user-string
                      "curseforge" curseforge-api/parse-user-string
                      "tukui" tukui-api/parse-user-string
                      "tukui-classic" tukui-api/parse-user-string
                      "tukui-classic-tbc" tukui-api/parse-user-string}
        url (some-> uin utils/unmangle-https-url java.net.URL. str)]
    (if-not url
      (error "bad url")
      (let [source (utils/url-to-addon-source url)]
        (if-let [f (get dispatch-map source)]
          (when-let [result (f url)]
            ;; special handling for curseforge, that returns a URL to be matched against catalogue.
            (merge {:source source} (if (= source "curseforge") {:url result} {:source-id result})))
          (warn "unsupported URL"))))))


;;


(defn-spec merge-catalogues (s/or :ok :catalogue/catalogue, :error nil?)
  "merges catalogue `cat-b` over catalogue `cat-a`.
  latest datestamp preserved.
  addon-summary-list is unique by `:source` and `:source-id` with differing values replaced by those in `cat-b`"
  [cat-a (s/nilable :catalogue/catalogue), cat-b (s/nilable :catalogue/catalogue)]
  (cond
    (and (empty? cat-a)
         (empty? cat-b)) nil
    (empty? cat-b) cat-a
    (empty? cat-a) cat-b
    :else
    (let [datestamp (last (sort [(:datestamp cat-a) (:datestamp cat-b)])) ;; latest wins
          addons-a (:addon-summary-list cat-a)
          addons-b (:addon-summary-list cat-b)
          addon-summary-list (->> (concat addons-a addons-b) ;; join the two lists
                                  (group-by (juxt :source-id :source)) ;; group by the key
                                  vals ;; drop the keys. we now have a list of lists.
                                  (map (partial apply merge)))] ;; merge the nested lists into single maps
      (format-catalogue-data addon-summary-list datestamp))))

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

(defn-spec shorten-catalogue (s/or :ok :catalogue/catalogue, :problem nil?)
  "reads the catalogue at the given `path` and returns a truncated version where all addons unmaintained addons are removed.
  an addon is considered unmaintained if it hasn't been updated since the beginning of the previous expansion"
  [full-catalogue-path ::sp/extant-file, cutoff ::sp/inst]
  (let [{:keys [addon-summary-list datestamp]} (read-catalogue full-catalogue-path)
        unmaintained? (fn [addon]
                        (let [dtobj (java-time/zoned-date-time (:updated-date addon))]
                          (java-time/before? dtobj (utils/todt cutoff))))]
    (when addon-summary-list
      (format-catalogue-data (remove unmaintained? addon-summary-list) datestamp))))
