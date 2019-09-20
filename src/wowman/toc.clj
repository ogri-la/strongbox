(ns wowman.toc
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

;; interface version to use if .toc file is missing theirs
;; assume addon is compatible with the most recent version
(def default-interface-version 80200)

;; matches a tocfile's 'Title' (label) to a catalog's name
;; aliases are maintained for the top-50 downloaded addons (ever) only, and only for those that need it
;; best and nicest way to avoid needing an alias is to have your .toc 'Title' attribute match your curseforge addon name
(def aliases
  {"AtlasLoot |cFF22B14C[Core]|r" "atlasloot-enhanced"
   "BigWigs [|cffeda55fCore|r]" "big-wigs"
   "|cffffd200Deadly Boss Mods|r |cff69ccf0Core|r" "deadly-boss-mods"
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Azeroth (Classic)|r" "dbm-bc"
   "Raider.IO Mythic Plus and Raiding" "raiderio"
   "HealBot" "heal-bot-continued"
   "Auc-Auctioneer |cff774422(core)" "auctioneer"
   "Titan Panel |cff00aa005.17.1.80100|r" "titan-panel"
   "BadBoy" "bad-boy"
   "Mik's Scrolling Battle Text" "mik-scrolling-battle-text"
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Icecrown Citadel|r" "deadly-boss-mods-wotlk"
   "Prat |cff8080ff3.0|r" "prat-3-0"
   "Omen3" "omen-threat-meter"
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Firelands|r" "deadly-boss-mods-cataclysm-mods"
   "X-Perl UnitFrames by |cFFFF8080Zek|r" "xperl"})

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
        toc-file (utils/join toc-dir (str toc-bname ".toc")) ;; /foo/Addon/Addon.toc

        ;; less than ideal, probably case-insensitive filesystem + lazy dev
        alt-toc-file (utils/join toc-dir (-> toc-bname lower-case (str ".toc"))) ;; /foo/Addon/addon.toc

        ;; fubar case, toc file doesn't match dir
        any-toc-file (first (filter #(-> % lower-case (ends-with? ".toc")) (fs/list-dir toc-dir)))

        do-toc-file (fn [toc-file & [warning]]
                      (when warning
                        (debug warning))
                      (-> toc-file utils/de-bom-slurp -read-toc-file))]
    (cond
      (fs/file? toc-file) (do-toc-file toc-file)

      ;; Archaelogy Helper
      (fs/file? alt-toc-file) (do-toc-file
                               alt-toc-file
                               (format "expecting '%s', found '%s'. filename should be case sensitive" toc-file alt-toc-file))

      ;; TradeSkillMaster_AuctioningScanSummary
      (not (nil? any-toc-file)) (do-toc-file
                                 any-toc-file
                                 (format "expecting %s, found %s . please smack developer" toc-file any-toc-file)))))

(defn-spec rm-trailing-version string?
  "'foo 1.2.3' => 'foo', 'foo 1"
  [str string?]
  (let [matching-suffix #" v?[\d\.]+$"
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

(defn-spec parse-addon-toc ::sp/toc
  [addon-dir ::sp/extant-dir, keyvals map?]
  (let [dirname (fs/base-name addon-dir) ;; /foo/bar/baz => baz
        install-dir (str (fs/parent addon-dir)) ;; /foo/bar/baz => /foo/bar
        nfo-contents (nfo/read-nfo install-dir dirname)

        ;; https://github.com/ogri-la/wowman/issues/47 - user encountered addon sans 'Title' attribute
        ;; if a match in the catalog is found even after munging the title, it will overwrite this one
        no-label-label (str dirname " *") ;; "EveryAddon *"
        label (:title keyvals)
        label (if-not (empty? label) label no-label-label)
        _ (when (= label no-label-label)
            (warn "addon with no \"Title\" value found:" addon-dir))

        ;; if :title is present in list of aliases, add that alias to what we return
        alias (when (contains? aliases label)
                {:alias (get aliases label)})

        addon {:name (normalise-name label)
               :dirname dirname
               :label label
               ;; :notes is preferred but we'll fall back to :description
               :description (or (:notes keyvals) (:description keyvals))
               :interface-version (or (some-> keyvals :interface utils/to-int)
                                      default-interface-version)
               :installed-version (:version keyvals)

               ;; toc file dependency values describe *load order*, not *packaging*
               ;;:dependencies (:dependencies keyvals)
               ;;:optional-dependencies (:optionaldependencies keyvals)
               ;;:required-dependencies (:requireddeps keyvals)
               }]
    (merge alias addon nfo-contents)))

(defn-spec parse-addon-toc-guard (s/or :ok ::sp/toc, :error nil?)
  "wraps the `parse-addon-toc` function and ensures no unhandled exceptions cause a cascading failure"
  [addon-dir ::sp/extant-dir]
  (try
    (if-let [keyvals (read-addon-dir addon-dir)]
      ;; we found a .toc file, now parse it
      (parse-addon-toc addon-dir keyvals)
      ;; we didn't find a .toc file, but just ignore it if it looks like an official addon dir
      (when-not (.startsWith (fs/base-name addon-dir) "Blizzard_")
        ;; not an official addon and we didn't find a .toc file. warn the user
        (warn "failed to find .toc file:" addon-dir)))
    (catch Exception e
      ;; this addon failed somehow. don't propagate the exception, just report it and return nil
      (error "please report this! https://github.com/ogri-la/wowman/issues")
      (error e (format "unhandled error parsing addon in directory '%s': %s" addon-dir (.getMessage e))))))

;; TODO: test this nil? error
(defn-spec installed-addons (s/or :ok ::sp/toc-list, :error nil?)
  [addons-dir ::sp/extant-dir]
  (let [addon-dir-list (filter fs/directory? (fs/list-dir addons-dir))
        addon-list (remove nil? (mapv (comp parse-addon-toc-guard str) addon-dir-list))

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
