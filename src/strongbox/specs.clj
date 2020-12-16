(ns strongbox.specs
  (:require
   [java-time]
   [clojure.set :refer [map-invert]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]))

(def placeholder "even qualified specs still require `specs.clj` to be included for linting and uberjar")

(defn valid-or-nil
  "returns `nil` instead of `false` when given data `x` is invalid"
  [spec x]
  (when (s/valid? spec x)
    x))

(s/def ::list-of-strings (s/coll-of string?))
(s/def ::list-of-maps (s/coll-of map?))
(s/def ::list-of-keywords (s/coll-of keyword?))
(s/def ::list-of-list-of-keywords (s/coll-of ::list-of-keywords))

(s/def ::map-or-list-of-maps (s/or :map map? :list (s/coll-of map?)))

(s/def ::regex #(instance? java.util.regex.Pattern %))
(s/def ::short-string #(<= (count %) 80))

(defn-spec has-ext boolean?
  "returns true if given `path` is suffixed with one of the extensions in `ext-list`"
  [path string?, ext-list ::list-of-strings]
  (some #{(fs/extension path)} ext-list))

(s/def ::url (s/and string?
                    #(try (instance? java.net.URL (java.net.URL. %))
                          (catch java.net.MalformedURLException e
                            false))))

(s/def ::file (s/and string?
                     #(try (and % (java.io.File. %))
                           (catch java.lang.IllegalArgumentException e
                             false))))
(s/def ::extant-file (s/and ::file fs/exists?))
(s/def ::anything (complement nil?)) ;; like `any?` but nil is considered false
(s/def ::dir ::file) ;; directory must also be a string and a valid File object, but not necessarily exist (yet)
(s/def ::extant-dir (s/and ::dir fs/directory?))
(s/def ::writeable-dir (s/and ::extant-dir fs/writeable?))
(s/def ::empty-coll (s/and coll? #(empty? %)))
(s/def ::gui-event #(instance? java.util.EventObject %))
(s/def ::install-dir (s/nilable ::extant-dir))
(s/def ::selected? boolean?)
(s/def ::gui-theme #{:light :dark})

;; preserve order, used in GUI
(def game-track-labels [[:retail "retail"]
                        [:classic "classic"]])
(def selectable-game-track-labels (into game-track-labels
                                        [[:retail-classic "any, prefer retail"]
                                         [:classic-retail "any, prefer classic"]]))

(def selectable-game-track-labels-map (into {} selectable-game-track-labels))
(def selectable-game-track-labels-map-inv (map-invert selectable-game-track-labels))

(def game-tracks (->> game-track-labels (into {}) keys set))
(def selectable-game-tracks (->> selectable-game-track-labels (into {}) keys set))
(def lenient-game-tracks #{:retail-classic :classic-retail})

(s/def ::game-track game-tracks)
(s/def ::installed-game-track ::game-track) ;; alias
(s/def ::lenient-game-track lenient-game-tracks)
(s/def ::game-track-list (s/coll-of ::game-track :kind vector? :distinct true))
(s/def ::download-count (s/and int? #(>= % 0)))
(s/def ::ignore? boolean?)
(s/def ::ignore-flag (s/keys :req-un [::ignore?]))
(s/def ::download-url ::url)
(s/def ::dirname (s/and string? #(not (empty? %))))
(s/def ::description (s/nilable string?))
(s/def ::matched? boolean?)
(s/def ::group-id string?)
(s/def ::primary? boolean?)
(s/def ::version string?)
(s/def ::installed-version (s/nilable ::version))
(s/def ::update? boolean?)
(s/def ::interface-version int?)
(s/def ::name string?) ;; normalised name of the addon, shared between toc file and curseforge
(s/def ::label string?) ;; name of the addon without normalisation

;; dates and times

(s/def ::inst (s/and string? #(try
                                (clojure.instant/read-instant-date %)
                                (catch RuntimeException e
                                  false))))
(s/def ::ymd-dt (s/and string? #(try
                                  (java-time/local-date %)
                                  (catch RuntimeException e
                                    false))))

(s/def ::zoned-dt-obj #(instance? java.time.ZonedDateTime %))

;; user config

;; the game tracks *selectable by the user* are mapped to a simpler set internally
(s/def :addon-dir/game-track selectable-game-tracks)
(s/def ::addon-dir ::extant-dir)
(s/def ::addon-dir-map (s/keys :req-un [::addon-dir :addon-dir/game-track]))
(s/def ::addon-dir-list (s/coll-of ::addon-dir-map))
(s/def ::selected-catalogue keyword?)

(s/def :config/addon-zips-to-keep (s/nilable int?))
(s/def :config/preferences (s/keys :req-un [:config/addon-zips-to-keep]))

(s/def ::user-config (s/keys :req-un [::addon-dir-list ::selected-addon-dir
                                      ::catalogue-location-list ::selected-catalogue
                                      ::gui-theme
                                      :config/preferences]))

;; http

(s/def :http/reason-phrase (s/and string? #(<= (count %) 50)))
(s/def :http/status int?) ;; a little too general but ok for now
(s/def :http/host string?)
(s/def :http/error (s/keys :req-un [:http/reason-phrase :http/status :http/host]))
(s/def :http/body any?) ;; even a nil body is allowed (304 Not Modified)
(s/def :http/resp (s/keys :req-un [:http/status :http/body])) ;; *at least* these keys, it will definitely have others

;; zipfiles

(s/def ::archive-file (s/and ::file #(has-ext % [".zip"])))
(s/def ::extant-archive-file (s/and ::extant-file ::archive-file))

(s/def :zipfile/dir? boolean?)
(s/def :zipfile/level pos-int?)
(s/def :zipfile/toplevel? boolean?)
(s/def :zipfile/path string?)

(s/def :zipfile/entry (s/keys :req-un [:zipfile/path :zipfile/toplevel? :zipfile/level :zipfile/dir?]))
(s/def :zipfile/entry-list (s/coll-of :zipfile/entry))

;; export records

(s/def ::export-type #{:json :edn})

(s/def ::export-record-v1 (s/keys :req-un [::name]
                                  :opt-un [:addon/source :addon/source-id]))

(s/def ::export-record-v2 (s/keys :req-un [::name :addon/source :addon/source-id]
                                  :opt-un [::game-track ;; optional because we also support exporting catalogue items that have no game track
                                           ]))

(s/def ::export-record (s/or :v1 ::export-record-v1, :v2 ::export-record-v2))

(s/def ::export-record-list (s/coll-of ::export-record))

;; addons

(s/def :addon/tag keyword?)
(s/def :addon/tag-list (s/or :ok (s/coll-of :addon/tag)
                             :empty ::empty-coll))
(s/def :addon/category string?)
(s/def :addon/category-list (s/coll-of :addon/category))

(s/def :addon/source (s/or :known #{"curseforge" "wowinterface" "github" "tukui" "tukui-classic"}
                           :unknown string?))
(s/def :addon/source-id (s/or ::integer-id? int? ;; tukui has negative ids
                              ::string-id? string?))
(s/def :addon/created-date ::inst)
(s/def :addon/updated-date ::inst)

(s/def :addon/toc
  (s/keys :req-un [::name ::label ::description ::dirname ::interface-version ::installed-version]
          ;; todo: revisit all of these
          ;;:opt-un [::group-id ::primary? ::group-addons :addon/source :addon/source-id]
          ))
(s/def :addon/toc-list (s/coll-of :addon/toc))

;; circular dependency? :addon/toc has an optional ::group-addons and ::group-addons is a list of :addon/toc ? oof
(s/def ::group-addons :addon/toc-list)

;; 'nfo' files contain extra per-addon data written to addon directories as .strongbox.json
(s/def :addon/-nfo (s/keys :req-un [::installed-version
                                    ::name
                                    ::group-id
                                    ::primary?
                                    :addon/source
                                    ::installed-game-track
                                    :addon/source-id]
                           :opt-un [::ignore?]))

(s/def :addon/nfo (s/or :ignored ::ignore-flag
                        :ok :addon/-nfo
                        :mutual-depedency (s/coll-of :addon/-nfo :kind vector?)))

;; intermediate spec. minimum amount of data required to create a nfo file. the rest is derived.
(s/def :addon/nfo-input-minimum (s/keys :req-un [::version
                                                 ::name
                                                 ::game-track
                                                 ::url ;; becomes the `group-id`
                                                 :addon/source
                                                 :addon/source-id]))

;; a catalogue entry, essentially
(s/def :addon/summary
  (s/keys :req-un [::url
                   ::name
                   ::label
                   :addon/tag-list
                   :addon/updated-date
                   ::download-count
                   :addon/source
                   :addon/source-id]
          ;; todo: bug here, `:opt` should be `:opt-un`
          :opt [::description ;; wowinterface summaries have no description
                :addon/created-date ;; wowinterface summaries have no created date
                ::game-track-list ;; more of a set, really
                ]))
(s/def :addon/summary-list (s/coll-of :addon/summary))

;; introduced after finding addon in the catalogue
;; `update?` is set at a slightly different time, but it's convenient to slip it in here
(s/def :addon/match (s/keys :req-un [::matched?]
                            :opt-un [::update?]))

;; bare minimum required to find and 'expand' an addon summary
(s/def :addon/expandable
  (s/keys :req-un [::name
                   ::label
                   :addon/source ;; for host resolver dispatch
                   :addon/source-id ;; unique identifier for host resolver
                   ]
          :opt-un [::game-track-list ;; wowinterface only
                   ]))

;; the set of per-addon values provided by the remote host on each check
(s/def :addon/source-updates
  (s/keys :req-un [::version
                   ::download-url
                   ::game-track]
          :opt-un [::interface-version]))

;; result of expanding an addon
(s/def :addon/expanded (s/merge :addon/expandable :addon/source-updates))

;; bare minimum required to install an addon summary
(s/def :addon/installable (s/merge
                           :addon/expanded
                           :addon/nfo-input-minimum
                           (s/keys :opt-un [;; used if present
                                            ::ignore?])))
(s/def :addon/installable-list (s/coll-of :addon/installable))

;; addon has nfo data
(s/def :addon/toc+nfo (s/merge :addon/toc :addon/nfo))

;; addon is installed
(s/def :addon/installed (s/or :installed :addon/toc
                              :strongbox-installed :addon/toc+nfo))
(s/def :addon/installed-list (s/coll-of :addon/installed))

;; addon has been run against the catalogue and a match was *not* found.
;; unused.
;;(s/def :addon/toc+match (s/merge :addon/toc :addon/match))

;; addon with nfo data has been run against the catalogue and a match was *not* found.
;; unused.
;;(s/def :addon/toc+nfo+match (s/merge :addon/toc+nfo :addon/match))

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

(s/def :catalogue/version int?)
(s/def :catalogue/spec (s/keys :req-un [:catalogue/version]))
(s/def :catalogue/datestamp ::inst)
(s/def :catalogue/total int?)
(s/def :catalogue/addon-summary-list :addon/summary-list)

(s/def :catalogue/catalogue (s/and (s/keys :req-un [:catalogue/spec :catalogue/datestamp :catalogue/total :catalogue/addon-summary-list])
                                   (fn [data]
                                     (= (:total data) (count (:addon-summary-list data))))))

;; catalogue locations

(s/def :catalogue/name keyword?)

;; a `catalogue/location` is a description of a catalogue and where to find it, not the catalogue itself.
(s/def :catalogue/location (s/keys :req-un [:catalogue/name ::label :addon/source]))
(s/def :catalogue/location-list (s/or :ok (s/coll-of :catalogue/location)
                                      :empty ::empty-coll))

(s/def ::catalogue-location-list :catalogue/location-list) ;; alias for user config

;; db

(s/def :db/toc-keys (s/or :keyword keyword? :list-of-keywords ::list-of-keywords))
(s/def :db/catalogue-keys :db/toc-keys)

(s/def :db/idx (s/coll-of keyword?))
(s/def :db/key vector?) ;; coll of any
(s/def :db/catalogue-match :addon/summary)
(s/def :db/installed-addon :addon/installed) ;; alias :(
(s/def :db/addon-catalogue-match (s/or :no-match nil?
                                       :match (s/keys :req-un [:db/idx :db/key :db/installed-addon ::matched? :db/catalogue-match])))

;; javafx, cljfx, gui2
;; no references to cljfx or javafx please!
;; requiring cljfx or anything in javafx.scene.control starts the javafx application thread

(s/def :gui/text string?)
(s/def :gui/cell-value-factory ifn?)
(s/def :gui/style-class (s/coll-of string? :kind vector?))
(s/def :gui/column-data (s/keys :opt-un [:gui/text :gui/cell-value-factory :gui/style-class]))
