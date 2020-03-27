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

(s/def ::string255 (s/and string? (between 0 256)))

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
(s/def ::tag-list (s/coll-of ::tag))

;; addon data that comes from the catalogue


(s/def ::addon-summary
  (s/keys :req-un [::url ::name ::label ::category-list ::updated-date ::download-count ::source ::source-id]
          :opt [::description ;; wowinterface summaries have no description
                ::created-date ;; wowinterface summaries have no created date
                ::game-track-list ;; more of a set, really
                ]))

;; 'expanded' addon summary, everything we need in order to download an addon
;; see catalogue/expand-addon-summary
;; todo: rename '::expanded-addon' or similar
(s/def ::addon
  (s/merge ::addon-summary (s/keys :req-un [::version ::download-url]
                                   :opt [::interface-version])))

;; .toc files live in the root of an addon and include the author's metadata about the addon
;; minimum needed to be scraped from a toc file
;; this seems to have a bit of nfo stuff in it ...
(s/def ::toc
  (s/keys :req-un [::name ::label ::description ::dirname ::interface-version ::installed-version]
          :opt [::group-id ::primary? ::group-addons ::source ::source-id]))

;; the result of merging an installed addon (toc) with an installable addon from the catalogue
(s/def ::toc-addon-summary
  (s/merge ::toc ::addon-summary (s/keys :opt [::matched?])))

;; the result of 'expanding' an ::toc-addon-summary (matched addon) with further fields from addon host
(s/def ::toc-addon
  (s/merge ::toc ::addon (s/keys :opt [::update?])))

;; one or the other, it's all good
(s/def ::addon-or-toc-addon (s/or :addon? ::addon, :toc-addon? ::toc-addon))

(s/def ::toc-list (s/coll-of ::toc))
(s/def ::addon-list (s/coll-of ::addon))
(s/def ::addon-summary-list (s/coll-of ::addon-summary))

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
(s/def ::group-addons ::toc-list)
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
(s/def ::catalogue-created-date ::ymd-dt)
(s/def ::catalogue-updated-date ::ymd-dt)
;;(def catalogue-sources #{"curseforge" "wowinterface" "github" "tukui" "tukui-classic"})
;;(s/def ::catalogue-source catalogue-sources)
;;(s/def ::catalogue-source string?) ;; ::string255)
(s/def ::catalogue-source ::string255)
(s/def ::catalogue-source-id (s/or ::integer-id? int? ;; tukui has negative ids
                                   ::string-id? string?))
(s/def ::zoned-dt-obj #(instance? java.time.ZonedDateTime %))
(s/def ::download-count (s/and int? #(>= % 0)))
(s/def ::json string?)
(s/def ::html string?)

(s/def ::string-pair (s/and (s/coll-of string?) #(= (count %) 2)))
(s/def ::list-of-string-pairs (s/coll-of ::string-pair))

;;

(s/def ::ignore-flag (s/keys :req-un [::ignore?]))

;;(s/def ::nfo-v1 map?)

;; this is what is needed to be passed in, at a minium, to generate a nfo file
(s/def ::nfo-input-minimum (s/keys :req-un [::version ::name ::url ::source ::source-id]))


;; ignored nfo file may simply be the json '{"ignore?": true}'


(s/def ::nfo-v2 (s/or :ignored-addon ::ignore-flag
                      :ok (s/keys :req-un [::installed-version ::name ::group-id ::primary? ::source
                                           ::installed-game-track ::source-id]
                                  :opt [::ignore?])))

;; orphaned
(s/def ::file-byte-array-pair (s/cat :file ::file
                                     :file-contents bytes?))

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
(s/def ::user-config (s/keys :req-un [::addon-dir-list ::selected-addon-dir
                                      ::catalogue-source-list ::selected-catalogue
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

(s/def ::spec map?) ;; grr. ::version conflicts with above
(s/def ::datestamp ::inst)
(s/def ::updated-datestamp ::inst)
(s/def ::total int?)
(s/def ::catalogue (s/keys :req-un [::spec ::datestamp ::updated-datestamp ::total ::addon-summary-list]))

;;

(s/def ::export-type #{:json :edn})
(s/def ::source ::catalogue-source) ;; alias :(
(s/def ::source-id ::catalogue-source-id) ;; alias :(

(s/def ::export-record-v1 (s/keys :req-un [::name]
                                  :opt [::source ::source-id]))

(s/def ::export-record-v2 (s/keys :req-un [::name ::source ::source-id]
                                  :opt [::game-track ;; optional because we also support exporting catalogue items that have no game track
                                        ]))

(s/def ::export-record (s/or :v1 ::export-record-v1, :v2 ::export-record-v2))

(s/def ::export-record-list (s/coll-of ::export-record))

;;

(s/def :catalogue/label ::label)
(s/def :catalogue/name keyword?)
(s/def :catalogue/source ::url)
(s/def ::catalogue-source-map (s/keys :req-un [:catalogue/name ::label :catalogue/source]))
(s/def ::catalogue-source-list (s/or :ok (s/coll-of ::catalogue-source-map)
                                     :empty ::empty-coll))
