(ns strongbox.specs
  (:require
   [java-time]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]))

(defn conform-or-nil
  "returns `nil` instead of `:clojure.spec.alpha/invalid` when given data `x` is invalid"
  [spec x]
  (let [result (s/conform spec x)]
    (when-not (= result :clojure.spec.alpha/invalid)
      result)))

(defn-spec between fn?
  "returns a function that will return true if it's `count` is between (not inclusive) `n` and `m`"
  [min int?, max int?]
  (fn [x]
    (let [c (if (int? x) x (count x))]
      (and (< c max)
           (> c min)))))

(s/def ::list-of-strings (s/coll-of string?))
(s/def ::list-of-maps (s/coll-of map?))
(s/def ::list-of-keywords (s/coll-of keyword?))
(s/def ::list-of-list-of-keywords (s/coll-of ::list-of-keywords))

(s/def ::regex #(instance? java.util.regex.Pattern %))
(s/def ::short-string #(<= (count %) 80))

(defn-spec has-ext boolean?
  "returns true if given `path` is suffixed with one of the extensions in `ext-list`"
  [path string?, ext-list ::list-of-strings]
  (some #{(fs/extension path)} ext-list))


;;


(s/def ::tag keyword?)
(s/def ::tag-list (s/or :ok (s/coll-of ::tag)
                        :empty ::empty-coll))

(s/def ::url (s/and string?
                    #(try (instance? java.net.URL (java.net.URL. %))
                          (catch java.net.MalformedURLException e
                            false))))

(s/def ::file (s/and string?
                     #(try (and % (java.io.File. %))
                           (catch java.lang.IllegalArgumentException e
                             false))))
(s/def ::extant-file (s/and ::file fs/exists?))
(s/def ::archive-file (s/and ::file #(has-ext % [".zip"])))
(s/def ::extant-archive-file (s/and ::extant-file ::archive-file))
(s/def ::list-of-files (s/coll-of ::file))
(s/def ::anything (complement nil?)) ;; like `any?` but nil is considered false
(s/def ::dir ::file) ;; directory must also be a string and a valid File object, but not necessarily exist (yet)
(s/def ::extant-dir (s/and ::dir fs/directory?))
(s/def ::writeable-dir (s/and ::extant-dir fs/writeable?))
(s/def ::download-url ::url)
(s/def ::name string?) ;; normalised name of the addon, shared between toc file and curseforge
(s/def ::label string?) ;; name of the addon without normalisation
(s/def ::dirname string?)
(s/def ::description (s/nilable string?))
(s/def ::matched? boolean?)
(s/def ::group-id string?)
(s/def ::primary? boolean?)
(s/def ::version string?)
(s/def ::installed-version (s/nilable ::version))
(s/def ::update? boolean?)
(s/def ::interface-version int?)
(s/def ::category string?)
(s/def ::category-list (s/coll-of ::category))

(s/def ::inst (s/and string? #(try
                                (clojure.instant/read-instant-date %)
                                (catch RuntimeException e
                                  false))))
(s/def ::ymd-dt (s/and string? #(try
                                  (java-time/local-date %)
                                  (catch RuntimeException e
                                    false))))
(s/def ::created-date ::inst)
(s/def ::updated-date ::inst)

(s/def ::zoned-dt-obj #(instance? java.time.ZonedDateTime %))
(s/def ::download-count (s/and int? #(>= % 0)))
(s/def ::json string?)
(s/def ::html string?)

(s/def ::string-pair (s/and (s/coll-of string?) #(= (count %) 2)))
(s/def ::list-of-string-pairs (s/coll-of ::string-pair))

;;

(s/def ::ignore-flag (s/keys :req-un [::ignore?]))

(s/def ::gui-event #(instance? java.util.EventObject %))

(s/def ::install-dir (s/nilable ::extant-dir))
(s/def ::game-track #{:retail :classic})
(s/def ::installed-game-track ::game-track) ;; alias
(s/def ::game-track-list (s/coll-of ::game-track :kind vector? :distinct true))
(s/def ::addon-dir ::extant-dir)
(s/def ::selected? boolean?)
(s/def ::addon-dir-map (s/keys :req-un [::addon-dir ::game-track]))
(s/def ::addon-dir-list (s/coll-of ::addon-dir-map))
(s/def ::selected-catalogue keyword?)
(s/def ::gui-theme #{:light :dark})

;; todo: rename 'catalogue-source' to ':catalogue/location-map'
(s/def ::user-config (s/keys :req-un [::addon-dir-list ::selected-addon-dir
                                      ::catalogue-location-list ::selected-catalogue
                                      ::gui-theme]))
(s/def ::ignore? boolean?)

(s/def ::reason-phrase (s/and string? #(<= (count %) 50)))
(s/def ::status int?) ;; a little too general but ok for now
(s/def ::host string?)
(s/def ::http-error (s/keys :req-un [::reason-phrase ::status ::host]))
(s/def ::body any?) ;; even a nil body is allowed (304 Not Modified)
(s/def ::http-resp (s/keys :req-un [::status ::body])) ;; *at least* these keys, it will definitely have others

(s/def ::empty-coll (s/and coll? #(empty? %)))

;;

(s/def ::dir? boolean?)
(s/def ::level pos-int?)
(s/def ::toplevel? boolean?)
(s/def ::path string?)

(s/def ::zipfile-entry (s/keys :req-un [::path ::toplevel? ::level ::dir?]))
(s/def ::zipfile-entries (s/coll-of ::zipfile-entry))

;;

;; todo: this is the catalogue/spec key that just has a version
(s/def ::spec map?) ;; grr. ::version conflicts with above
(s/def ::datestamp ::inst)
(s/def ::total int?)

;;

(s/def ::export-type #{:json :edn})
(s/def ::source (s/or :known #{"curseforge" "wowinterface" "github" "tukui" "tukui-classic"}
                      :unknown string?))
(s/def ::source-id (s/or ::integer-id? int? ;; tukui has negative ids
                         ::string-id? string?))

(s/def ::export-record-v1 (s/keys :req-un [::name]
                                  :opt [::source ::source-id]))

(s/def ::export-record-v2 (s/keys :req-un [::name ::source ::source-id]
                                  :opt [::game-track ;; optional because we also support exporting catalogue items that have no game track
                                        ]))

(s/def ::export-record (s/or :v1 ::export-record-v1, :v2 ::export-record-v2))

(s/def ::export-record-list (s/coll-of ::export-record))



;; addons
;; -----------------------------------------


(s/def :addon/toc
  (s/keys :req-un [::name ::label ::description ::dirname ::interface-version ::installed-version]
          :opt [::group-id ::primary? ::group-addons ::source ::source-id]))
(s/def :addon/toc-list (s/coll-of :addon/toc))

;; circular dependency? :addon/toc has an optional ::group-addons and ::group-addons is a list of :addon/toc ? oof
(s/def ::group-addons :addon/toc-list)

;; 'nfo' files contain extra per-addon data written to addon directories as .strongbox.json
(s/def :addon/nfo (s/or :ignored ::ignore-flag
                        :ok (s/keys :req-un [::installed-version ::name ::group-id ::primary? ::source
                                             ::installed-game-track ::source-id]
                                    :opt [::ignore?])))

;; minimum amount of data required to create a nfo file. the rest is derived.
(s/def :addon/nfo-input-minimum (s/keys :req-un [::version ::name ::url ::source ::source-id]))

(s/def :addon/summary
  (s/keys :req-un [::url ::name ::label ::tag-list ::updated-date ::download-count ::source ::source-id]
          :opt [::description ;; wowinterface summaries have no description
                ::created-date ;; wowinterface summaries have no created date
                ::game-track-list ;; more of a set, really
                ]))
(s/def :addon/summary-list (s/coll-of :addon/summary))

;; introduced after finding addon in the catalogue
(s/def :addon/match (s/keys :req-un [::matched?]))

(s/def :addon/source-updates
  (s/keys :req-un [::version ::download-url]
          :opt [::interface-version]))

;;

;; bare minimum required to install an addon
(s/def :addon/installable (s/merge :addon/source-updates
                                   (s/keys :req-un [::name ::label]
                                           :opt [::game-track])))

(s/def :addon/installable-list (s/coll-of :addon/installable))

;; addon has nfo data
(s/def :addon/toc+nfo (s/merge :addon/toc :addon/nfo))

;; addon has been run against the catalogue and a match was *not* found
(s/def :addon/toc+match (s/merge :addon/toc :addon/match))

;; addon with nfo data has been run against the catalogue and a match was *not* found
(s/def :addon/toc+nfo+match (s/merge :addon/toc+nfo :addon/match))

;; addon has been run against the catalogue and a match was found
(s/def :addon/toc+summary+match (s/merge :addon/toc :addon/summary :addon/match))

;; addon with nfo data has been run against the catalogue and a match was found
(s/def :addon/toc+nfo+summary+match (s/merge :addon/toc+nfo :addon/summary :addon/match))

;; addon with *no* nfo data has been matched against the catalogue and found online at it's source
(s/def :addon/toc+summary+match+source-updates (s/merge :addon/toc+summary+match :addon/source-updates))

;; addon with nfo data has been matched against the catalogue and found online at it's source
;; this is a strongbox-installed addon!
(s/def :addon/toc+nfo+summary+match+source-updates (s/merge :addon/toc :addon/nfo :addon/summary :addon/match :addon/source-updates))

;; addon (with or without nfo data) has been matched against the catalogue and found online at it's source
;; this is an ideal state.
(s/def :addon/addon (s/or :installed :addon/toc+summary+match+source-updates
                          :strongbox-installed :addon/toc+nfo+summary+match+source-updates))

(s/def :addon/addon-list (s/coll-of :addon/addon))


;; catalogues
;; -----------------------------------------


(s/def :catalogue/name keyword?)

(s/def :catalogue/addon-summary-list :addon/summary-list) ;; alias :(


;; todo: rename 'addon-summary-list' to just 'summary-list' and remove the alias?
(s/def :catalogue/catalogue (s/keys :req-un [::spec ::datestamp ::total :catalogue/addon-summary-list]))

;; a source-map is a description of a catalogue and where to find it,
;; not the catalogue itself.
;; todo: 'source-map' is a bad name. perhaps ':catalogue/location-map' and ':catalogue/location-map-list'
;; catalogue/loc-map and catalogue/loc-map-list ?
;; catalogue/origin ? catalogue/address ?

(s/def :catalogue/location (s/keys :req-un [:catalogue/name ::label ::source]))
(s/def :catalogue/location-list (s/or :ok (s/coll-of :catalogue/location)
                                      :empty ::empty-coll))

(s/def ::catalogue-location-list :catalogue/location-list) ;; alias :(
