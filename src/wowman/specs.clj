(ns wowman.specs
  (:require
   [java-time]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]))

(s/def ::list-of-strings (s/coll-of string?))
(s/def ::list-of-maps (s/coll-of map?))
(s/def ::list-of-keywords (s/coll-of keyword?))
(s/def ::list-of-list-of-keywords (s/coll-of ::list-of-keywords))

(s/def ::regex #(instance? java.util.regex.Pattern %))
(s/def ::short-string #(<= (count %) 80))

(defn-spec has-ext boolean?
  [path string?, ext-list ::list-of-strings]
  (some #{(fs/extension path)} ext-list))

;; addon data that can be scraped from the listing pages
(s/def ::addon-summary
  (s/keys :req-un [::uri ::name ::label ::category-list ::updated-date ::download-count ::source ::source-id]
          :opt [::description ;; wowinterface summaries have no description
                ::created-date ;; wowinterface summaries have no created date
                ::game-track-list ;; more of a set, really
                ]))

;; 'expanded' addon summary, everything we need in order to download an addon
;; see catalog/expand-addon-summary
;; todo: rename '::expanded-addon' or similar
(s/def ::addon
  (s/merge ::addon-summary (s/keys :req-un [::version ::download-uri]
                                   :opt [::donation-uri ::interface-version])))

;; .toc files live in the root of an addon and include the author's metadata about the addon
;; minimum needed to be scraped from a toc file
(s/def ::toc
  (s/keys :req-un [::name ::label ::description ::dirname ::interface-version ::installed-version]
          :opt [::group-id ::primary? ::group-addons]))

;; the result of merging an installed addon (toc) with an installable addon
;; this is very much a utility-type shape for convenience over purity
;; todo: renamed '::matched-addon' or similar
(s/def ::toc-addon
  (s/merge ::toc ::addon (s/keys :opt [::update?])))

;; one or the other, it's all good
(s/def ::addon-or-toc-addon (s/or :addon? ::addon, :toc-addon? ::toc-addon))

(s/def ::toc-list (s/coll-of ::toc))
(s/def ::addon-list (s/coll-of ::addon))
(s/def ::addon-summary-list (s/coll-of ::addon-summary))

(s/def ::uri (s/and string?
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
(s/def ::download-uri ::uri)
(s/def ::name string?) ;; normalised name of the addon, shared between toc file and curseforge
(s/def ::label string?) ;; name of the addon without normalisation
(s/def ::dirname string?)
(s/def ::description (s/nilable string?))

(s/def ::group-id string?)
(s/def ::primary boolean?)
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
(s/def ::catalog-created-date ::ymd-dt)
(s/def ::catalog-updated-date ::ymd-dt)
(s/def ::catalog-source #{"curseforge" "wowinterface" "github"})
(s/def ::zoned-dt-obj #(instance? java.time.ZonedDateTime %))
(s/def ::download-count (s/and int? #(>= % 0)))
(s/def ::donation-uri (s/nilable ::uri))
(s/def ::json string?)
(s/def ::html string?)

(s/def ::string-pair (s/and (s/coll-of string?) #(= (count %) 2)))
(s/def ::list-of-string-pairs (s/coll-of ::string-pair))

(s/def ::nfo map?) ;; todo: not cool

;; orphaned
(s/def ::file-byte-array-pair (s/cat :file ::file
                                     :file-contents bytes?))

(s/def ::gui-event #(instance? java.util.EventObject %))

(s/def ::install-dir (s/nilable ::extant-dir))
(s/def ::debug? boolean?)
(s/def ::game-track #{"retail" "classic"})
(s/def ::game-track-list ::game-track) ;; just an alias for the catalog, consistent with category-list (also a set)
(s/def ::addon-dir ::extant-dir)
(s/def ::selected? boolean?)
(s/def ::addon-dir-map (s/keys :req-un [::addon-dir ::game-track]))
(s/def ::addon-dir-list (s/coll-of ::addon-dir-map))
(s/def ::selected-catalog keyword?)
(s/def ::user-config (s/keys :req-un [::addon-dir-list ::debug? ::selected-catalog]))

(s/def ::reason-phrase (s/and string? #(<= (count %) 50)))
(s/def ::status int?) ;; a little too general but ok for now
(s/def ::http-error (s/keys :req-un [::reason-phrase ::status]))
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
(s/def ::catalog (s/keys :req-un [::spec ::datestamp ::updated-datestamp ::total ::addon-summary-list]))

;;

(s/def ::export-type #{:json :edn})
(s/def ::source ::catalog-source) ;; alias :(
(s/def ::export-record (s/keys :req-un [::name]
                               :opt [::source]))
(s/def ::export-record-list (s/coll-of ::export-record))

;;

(s/def ::catalog-source-map map?)
