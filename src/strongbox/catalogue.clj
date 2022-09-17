(ns strongbox.catalogue
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [taoensso.tufte :as tufte :refer [p]]
   [java-time]
   [strongbox
    [constants :as constants]
    [addon]
    [utils :as utils :refer [todt]]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [curseforge-api :as curseforge-api]
    [wowinterface-api :as wowinterface-api]
    [gitlab-api :as gitlab-api]
    [github-api :as github-api]]))

(defn-spec host-disabled? boolean?
  "returns `true` if the addon host has been disabled"
  [addon map?]
  (-> addon :source (= "curseforge")))

(defn-spec -expand-summary (s/or :ok :addon/expanded, :error nil?)
  "fetches updates from the addon host for the given `addon` and `game-track`.
  does *not* support multiple game tracks or warning the user, see `expand-summary`.
  does *not* support ignoring disabled hosts, see `expand-summary`.
  returns `nil` when no release found."
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [dispatch-map {"curse" curseforge-api/expand-summary
                      "wowin" wowinterface-api/expand-summary
                      "gitla" gitlab-api/expand-summary
                      "githu" github-api/expand-summary
                      "tukui" tukui-api/expand-summary
                      nil (fn [_ _] (error "malformed addon:" (utils/pprint addon)))}
        key (utils/safe-subs (:source addon) 5)]
    (try
      (if-not (contains? dispatch-map key)
        (error (format "addon '%s' for %s is from an unsupported source '%s'." (:label addon) (sp/game-track-labels-map game-track) (:source addon)))
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
        (error e (utils/reportable-error "unexpected error attempting to expand addon summary"))))))

(defn-spec expand-summary (s/or :ok :addon/expanded, :error nil?)
  "fetches updates from the addon host for the given `addon` and `game-track`.
  when `strict?` is `false` and an addon fails to match for the given `game-track`, other game tracks will be checked.
  emits warnings to user when no release found."
  [addon :addon/expandable, game-track :addon-dir/game-track, strict? ::sp/strict?]
  (let [strict? (boolean strict?)
        track-map {:retail [:retail :classic :classic-tbc :classic-wotlk]
                   :classic [:classic :classic-tbc :classic-wotlk :retail]
                   :classic-tbc [:classic-tbc :classic-wotlk :classic :retail]
                   :classic-wotlk [:classic-wotlk :classic-tbc :classic :retail]}
        game-track* game-track
        game-track (some #{game-track} sp/game-tracks)] ;; :retail => :retail, :unknown-game-track => nil
    (cond
      (not game-track) (error (format "unsupported game track '%s'." (str game-track*)))
      (host-disabled? addon) (warn (utils/message-list (str "addon host 'curseforge' was disabled " constants/curseforge-cutoff-label ".")
                                                       ["use 'Source' and 'Find similar' from the addon context menu for alternatives."]))
      :else (if-let [source-updates (if strict?
                                      (-expand-summary addon game-track)
                                      (utils/first-nn (partial -expand-summary addon) (get track-map game-track)))]
              source-updates

              ;; "no 'Retail' release found on github"
              ;; "no 'Classic' release found on wowinterface"
              ;; "no 'Classic (TBC)', 'Classic' or 'Retail' release found on github"
              (let [single-template "no '%s' release found on %s."
                    multi-template "no '%s', '%s', '%s' or '%s' release found on %s."
                    msg (if strict?
                          (format single-template (sp/game-track-labels-map game-track) (:source addon))
                          (apply format multi-template (conj (mapv #(sp/game-track-labels-map %) (get track-map game-track))
                                                             (:source addon))))]
                (warn msg))))))

;;

(defn-spec toc2summary (s/nilable :addon/summary)
  "accepts toc or toc+nfo data and emits a version of the data that validates as an `:addon/summary`"
  [toc (s/or :just-toc :addon/toc, :mixed :addon/toc+nfo)]
  (when-not (:ignore? toc)
    (let [sink nil
          syn (-> toc
                  (merge {;; :url (:group-id toc) ;; 2021-12-30: can't rely on `url` being consistent with `source` anymore.
                          :url nil ;; attempt to reconstruct below
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
          ;; all urls except curseforge can be reconstructed
          syn (if (-> syn :url nil?)
                (case (:source toc)
                  ;; "curseforge" ... ;; addon page URL can't be reconstructed, so clickable links break.
                  "wowinterface" (assoc syn :url (wowinterface-api/make-url toc))
                  "tukui" (assoc syn :url (tukui-api/make-url toc))
                  "github" (assoc syn :url (github-api/make-url toc))
                  ;; gitlab addons only appear in the user catalogue so will only be 'polyfilled' here if
                  ;; they remain installed but removed from the user-catalogue.
                  "gitlab" (assoc syn :url (gitlab-api/make-url toc))
                  syn)
                syn)

          ;; url may still be nil at this point, just fail
          syn (if (-> syn :url nil?) sink syn)

          syn (if (-> syn :source nil?) sink syn)]
      syn)))

;;

(defn-spec format-catalogue-data :catalogue/catalogue
  "returns a correctly formatted, ordered, catalogue given a list of addons and a datestamp"
  [addon-list :addon/summary-list, datestamp ::sp/ymd-dt]
  (let [addon-list (p :cat/sort-addons
                      (sort-by :name addon-list))]
    {:spec {:version 2}
     :datestamp datestamp
     :total (count addon-list)
     :addon-summary-list addon-list}))

(defn -read-catalogue-value-fn
  "used to transform catalogue values as the json is read. applies to both v1 and v2 catalogues."
  [key val]
  (case key
    :game-track-list (mapv keyword val)
    :tag-list (mapv keyword val)
    :description (utils/safe-subs val 255) ;; no database anymore, no hard failures on value length?
    val))

(defn-spec read-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "reads the catalogue of addon data at the given `catalogue-path`."
  ([catalogue-path (s/or :file ::sp/file, :bytes bytes?)]
   (read-catalogue catalogue-path {}))
  ([catalogue-path (s/or :file ::sp/file, :bytes bytes?), opts map?]
   (let [value-fn -read-catalogue-value-fn ;; defined 'outside' so it can reference itself
         opts (merge opts {:key-fn keyword :value-fn value-fn})
         catalogue-data (utils/load-json-file-safely catalogue-path opts)]
     (when-not (empty? catalogue-data)
       catalogue-data))))

(defn validate
  "validates the given data as a `:catalogue/catalogue`, returning nil if data is invalid"
  [catalogue]
  (p :catalogue:validate
     (sp/valid-or-nil :catalogue/catalogue catalogue)))

(defn-spec write-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "write catalogue to given `output-file` as JSON. returns path to output file"
  [catalogue-data :catalogue/catalogue, output-file ::sp/file]
  (locking output-file
    (if (some->> catalogue-data validate (utils/dump-json-file output-file))
      output-file
      (error "catalogue data is invalid, refusing to write:" output-file))))

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
  (let [dispatch-map {"githu" github-api/parse-user-string
                      "gitla" gitlab-api/parse-user-string
                      "wowin" wowinterface-api/parse-user-string
                      "curse" curseforge-api/parse-user-string
                      "tukui" tukui-api/parse-user-string}
        url (some-> uin utils/unmangle-https-url java.net.URL. str)]
    (if-not url
      (error "bad url")
      (let [source (utils/url-to-addon-source url)]
        (if-let [f (dispatch-map (utils/safe-subs source 5))]
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

(defn-spec shorten-catalogue (s/or :ok :catalogue/catalogue, :problem nil?)
  "returns a truncated version of `catalogue` where all addons considered unmaintained are removed.
  an addon is considered unmaintained if it hasn't been updated since before the given `cutoff` date."
  ([catalogue :catalogue/catalogue]
   (shorten-catalogue catalogue constants/release-of-previous-expansion))
  ([catalogue :catalogue/catalogue, cutoff ::sp/inst]
   (let [{:keys [addon-summary-list datestamp]} catalogue
         unmaintained? (fn [addon]
                         (let [dtobj (java-time/zoned-date-time (:updated-date addon))]
                           (java-time/before? dtobj (utils/todt cutoff))))]
     (when addon-summary-list
       (format-catalogue-data (remove unmaintained? addon-summary-list) datestamp)))))

(defn-spec filter-catalogue :catalogue/catalogue
  "returns a catalogue whose `addon-summary-list` has been filtered against the given `source`."
  [catalogue :catalogue/catalogue, source :addon/source]
  (let [new-addon-summary-list (filterv #(= source (:source %)) (:addon-summary-list catalogue))]
    (-> catalogue
        (assoc :addon-summary-list new-addon-summary-list)
        (assoc :total (count new-addon-summary-list)))))
