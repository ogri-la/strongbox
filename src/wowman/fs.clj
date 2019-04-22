(ns wowman.fs
  (:refer-clojure :rename {replace clj-replace})
  (:require
   [wowman
    [nfo :as nfo]
    [specs :as sp]
    [utils :as utils]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [clojure.string :refer [lower-case ends-with?]]))

;; matches a tocfile's 'Title' (label) to a catalog's name
;; aliases are maintained for the top-N downloaded addons only
;; best way to avoid needing an alias is to have your .toc 'Title' attribute match your curseforge addon name
(def aliases
  {"|cffffd200Deadly Boss Mods|r |cff69ccf0Core|r" "deadly-boss-mods"
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Azeroth (Classic)|r" "dbm-bc"
   "AtlasLoot |cFF22B14C[Core]|r" "atlasloot-enhanced"
   "Auc-Auctioneer |cff774422(core)" "auctioneer"
   "HealBot" "heal-bot-continued"})

(defn-spec -read-toc-file map?
  [toc-contents string?]
  (let [comment? #(clojure.string/starts-with? % "##")
        parse-comment (fn [comment]
                        (let [[key value] (clojure.string/split comment #":" 2) ;; "##Interface: 70300" => ["##Interface" " 70300"]
                              ;; handles "##Interface" as well as "## Interface"
                              key (-> key (utils/ltrim "# ") lower-case keyword)] ;; "## Title" => :title
                          (if-not value
                            (debug "cannot parse line, ignoring:" comment)
                            {key (clojure.string/trim value)})))
        contents (clojure.string/split-lines toc-contents)
        filtered (filterv comment? contents)
        parsed (map parse-comment filtered)] ;; list of maps
    (reduce merge parsed))) ;; single map

(defn-spec read-addon-dir (s/or :ok map?, :error nil?)
  "returns a map of key-vals scraped from the .toc file in given directory"
  [addon ::sp/extant-dir]
  (let [toc-dir (fs/absolute addon)
        toc-bname (str (fs/base-name addon))

        ;; the ideal, perfect case
        toc-file (clojure.java.io/file toc-dir (str toc-bname ".toc")) ;; /foo/Addon/Addon.toc

        ;; less than ideal, probably case-insensitive filesystem + lazy dev
        alt-toc-file (clojure.java.io/file toc-dir (-> toc-bname lower-case (str ".toc"))) ;; /foo/Addon/addon.toc

        ;; fubar case, toc file doesn't match dir
        any-toc-file (first (filter #(-> % lower-case (ends-with? ".toc")) (fs/list-dir toc-dir)))

        do-toc-file (fn [toc-file & [warning]]
                      (when warning
                        (debug warning))
                      (-> toc-file utils/de-bom-slurp -read-toc-file))]
    (cond
      (.exists toc-file) (do-toc-file toc-file)

      ;; Archaelogy Helper
      (.exists alt-toc-file) (do-toc-file
                              alt-toc-file
                              (format "expecting '%s', found '%s'. filename should be case sensitive" toc-file alt-toc-file))

      ;; TradeSkillMaster_AuctioningScanSummary
      (not (nil? any-toc-file)) (do-toc-file
                                 any-toc-file
                                 (format "expecting %s, found %s . please smack developer" toc-file any-toc-file)))))

(defn-spec rm-trailing-version string?
  "'foo 1.2.3' => 'foo', 'foo 1"
  [str string?]
  (let [matching-suffix #"[ \d\.]+$"
        nothing ""]
    (clojure.string/replace str matching-suffix nothing)))

(defn-spec replace string?
  "simple find-replace"
  [str string? matching string? replacement string?]
  (clojure.string/replace str (re-pattern matching) replacement))

;; TODO: slugify?
(defn-spec normalise-name string?
  "convert the 'Title' attribute in toc file to a curseforge-style slug. this value is used to match against curseforge results"
  [label string?]
  ;; todo, replace this with a slugify fn
  (-> label lower-case rm-trailing-version (replace ":" "") (replace " " "-") (replace "\\?" "")))

;; TODO: test this nil? error
(defn-spec parse-addon-toc (s/or :ok ::sp/toc, :error nil?)
  [addon-dir ::sp/extant-dir]
  (if-let [keyvals (read-addon-dir addon-dir)]
    (let [;;decoded-title (:title keyvals) ;; TODO: title may be encoded: https://wow.gamepedia.com/UI_escape_sequences
          dirname (fs/base-name addon-dir) ;; /foo/bar/baz => baz
          install-dir (str (fs/parent addon-dir)) ;; /foo/bar/baz => /foo/bar
          nfo-contents (nfo/read-nfo install-dir dirname)
          label (:title keyvals)
          alias (when (contains? aliases label) ;; ensure :alias is absent if not present
                  {:alias (get aliases label)})
          addon {:name (normalise-name (:title keyvals))
                 :dirname dirname
                 :label label
                 :description (or (:notes keyvals) (:description keyvals)) ;; :notes is preferred but we'll fall back to :description
                 :interface-version (-> keyvals :interface utils/to-int)
                 :installed-version (:version keyvals)

                 ;; toc file dependency values describe *load order*, not *packaging*
                 ;;:dependencies (:dependencies keyvals)
                 ;;:optional-dependencies (:optionaldependencies keyvals)
                 ;;:required-dependencies (:requireddeps keyvals)
                 }]
      (merge alias addon nfo-contents))

    ;; ignore failures to parse Blizzard addons
    (when-not (.startsWith (fs/base-name addon-dir) "Blizzard_")
      (warn "failed to find .toc file:" addon-dir))))

;; TODO: test this nil? error
(defn-spec installed-addons (s/or :ok ::sp/toc-list, :error nil?)
  [addons-dir ::sp/extant-dir]
  (let [addon-dir-list (filter fs/directory? (fs/list-dir addons-dir))
        addon-list (remove nil? (mapv (comp parse-addon-toc str) addon-dir-list))

        ;; an addon may actually be many addons bundled together in a single download
        ;; wowman tags the bundled addons as they are unzipped and tries to determine the primary one
        addon-groups (group-by :group-id addon-list)

        ;; remove those addons without a group, we'll conj them in later
        unknown-grouping (get addon-groups nil)
        addon-groups (dissoc addon-groups nil)

        expand (fn [[group-id addons]]
                 (if (= 1 (count addons))
                   ;; don't attempt to group lone addons, it's unnecessary
                   (first addons)

                   ;; multiple addons in group
                   (let [_ (debug (format "grouping '%s', %s addons in group" group-id (count addons)))
                         primary (first (filter :primary? addons))
                         next-best (first addons)
                         new-data {:group-addons addons
                                   :group-addon-count (count addons)}
                         next-best-label (-> next-best :group-id fs/base-name)]
                     (if primary
                       ;; best, easiest case
                       (merge primary new-data)
                       ;; when we can't determine the primary addon, add a shitty synthetic one
                       ;; TODO: should I dissoc :dirname? it could be misleading..
                       (merge next-best new-data {:label (format "%s (group)" next-best-label)
                                                  :description (format "group record for the %s addon" next-best-label)})))))

        ;; expand the grouped addons and join with the unknowns
        addon-groups (apply conj (mapv expand addon-groups) unknown-grouping)]
    addon-groups))

(st/instrument)
