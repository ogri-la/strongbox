(ns strongbox.toc
  (:refer-clojure :rename {replace clj-replace})
  (:require
   [strongbox
    [specs :as sp]
    [utils :as utils]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [clojure.string :refer [lower-case ends-with?]]))

;; interface version to use if .toc file is missing theirs
;; assume addon is compatible with the most recent version
(def default-interface-version 90100)

;; matches a tocfile's 'Title' (label) to a catalogue's name
;; aliases are maintained for the top-50 downloaded addons (ever) only, and only for those that need it
;; best and nicest way to avoid needing an alias is to have your .toc 'Title' attribute match your curseforge addon name
(def aliases1
  {"AtlasLoot |cFF22B14C[Core]|r" {:source "curseforge" :source-id 2134}
   "BigWigs [|cffeda55fCore|r]" {:source "curseforge" :source-id 2382}
   "|cffffd200Deadly Boss Mods|r |cff69ccf0Core|r" {:source "curseforge" :source-id 8814}
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Azeroth (Classic)|r" {:source "curseforge" :source-id 16442}
   "Raider.IO Mythic Plus and Raiding" {:source "curseforge" :source-id 279257}
   "HealBot" {:source "curseforge" :source-id 2743}
   "Auc-Auctioneer |cff774422(core)" {:source "curseforge" :source-id 7879}
   "Titan Panel |cff00aa005.17.1.80100|r" {:source "curseforge" :source-id 489}
   "BadBoy" {:source "curseforge" :source-id 5547}
   "Mik's Scrolling Battle Text" {:source "curseforge" :source-id 2450}
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Icecrown Citadel|r" {:source "curseforge" :source-id 43970}
   "Prat |cff8080ff3.0|r" {:source "curseforge" :source-id 10783}
   "Omen3" {:source "curseforge" :source-id 4963}
   "|cffffe00a<|r|cffff7d0aDBM|r|cffffe00a>|r |cff69ccf0Firelands|r" {:source "curseforge" :source-id 43971}
   "X-Perl UnitFrames by |cFFFF8080Zek|r" {:source "curseforge" :source-id 14911}})

;; 2020-12-13
;; took the top 50 downloaded addons from curseforge and the top 50 from wowinterface,
;; installed them, removed the nfo files, reconciled them and below are the ones that were not found.
;; when multiple sources available, the most recently updated one was picked.
(def aliases2
  {"Plater" {:source "curseforge" :source-id 100547}
   "|cff1784d1ElvUI|r |cff9482c9Shadow & Light|r" {:source "tukui" :source-id 38}
   "|cff00aeffCharacterStatsClassic|r" {:source "curseforge" :source-id 338856}
   "Adapt" {:source "wowinterface" :source-id 4729}
   "Dugi Questing Essential |cffffffff5.504|r" {:source "wowinterface" :source-id 20540}})

(def aliases (merge aliases1 aliases2))

(defn-spec -parse-toc-file map?
  [toc-contents string?]
  (let [comment? #(= (utils/safe-subs % 2) "##")
        comment-comment? #(= (utils/safe-subs % 4) "# ##")
        interesting? (some-fn comment-comment? comment?)

        parse-comment (fn [comment]
                        (let [[key value] (clojure.string/split comment #":" 2) ;; "##Interface: 70300" => ["##Interface" " 70300"]

                              key (if (comment-comment? comment)
                                    ;; handles "# ##Interface" as well as "# ## Interface"
                                    (->> (-> key (utils/ltrim "# ") lower-case) (str "#") keyword) ;; "# ## Title" => :#title
                                    ;; handles "##Interface" as well as "## Interface"
                                    (-> key (utils/ltrim "# ") lower-case keyword))] ;; "## Title" => :title    
                          (if-not value
                            (debug "cannot parse line, ignoring:" comment)
                            {key (clojure.string/trim value)})))
        contents (clojure.string/split-lines toc-contents)]
    (->> contents (filter interesting?) (map parse-comment) (reduce merge))))

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
                      (-> toc-file utils/de-bom-slurp -parse-toc-file))]
    (cond
      (fs/file? toc-file) (do-toc-file toc-file)

      ;; Archaelogy Helper
      (fs/file? alt-toc-file) (do-toc-file
                               alt-toc-file
                               (format "expecting '%s', found '%s'. filename should be case sensitive" toc-file alt-toc-file))

      ;; TradeSkillMaster_AuctioningScanSummary
      (not (nil? any-toc-file)) (do-toc-file
                                 any-toc-file
                                 (format "expecting '%s', found '%s' . please smack developer" toc-file any-toc-file)))))

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

(defn-spec normalise-name string?
  "convert the 'Title' attribute in toc file to a curseforge-style slug. this value is used to match against curseforge results"
  [label string?]
  ;; todo, replace this with a slugify fn
  (-> label lower-case rm-trailing-version (replace ":" "") (replace " " "-") (replace "\\?" "")))

;;

(defn-spec parse-addon-toc :addon/toc
  [addon-dir ::sp/extant-dir, keyvals map?]
  (let [dirname (fs/base-name addon-dir) ;; /foo/bar/baz => baz

        ;; https://github.com/ogri-la/strongbox/issues/47 - user encountered addon sans 'Title' attribute
        ;; if a match in the catalogue is found even after munging the title, it will overwrite this one
        no-label-label (str dirname " *") ;; "EveryAddon *"
        label (:title keyvals)
        label (if-not (empty? label) label no-label-label)
        _ (when (= label no-label-label)
            (warn "addon with no \"Title\" value found:" addon-dir))

        ;; if :title is present in list of aliases, add that alias to what we return
        alias (when (contains? aliases label)
                (get aliases label)) ;; `{:source "curseforge" :source-id 12345}`

        wowi-source (when-let [x-wowi-id (-> keyvals :x-wowi-id utils/to-int)]
                      {:source "wowinterface"
                       :source-id x-wowi-id})

        curse-source (when-let [x-curse-id (-> keyvals :x-curse-project-id utils/to-int)]
                       {:source "curseforge"
                        :source-id x-curse-id})

        tukui-source (when-let [x-tukui-id (-> keyvals :x-tukui-projectid utils/to-int)]
                       {:source "tukui"
                        :source-id x-tukui-id})

        ;; todo: add support for x-github
        ;; ## X-Github: https://github.com/teelolws/Altoholic-Retail => source "github", source-id "teelolws/Altoholic-Retail"

        ignore-flag (when (some->> keyvals :version (clojure.string/includes? "@project-version@"))
                      (warn (format "ignoring '%s': 'Version' field in .toc file is unrendered" dirname))
                      {:ignore? true})

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
               }

        ;; yes, this prefers curseforge over wowinterface. and tukui over all others.
        ;; I need to figure out some way of supporting multiple hosts per-addon
        addon (merge addon alias wowi-source curse-source tukui-source ignore-flag)]

    addon))

(defn-spec blizzard-addon? boolean?
  "returns `true` if given path looks like an official Blizzard addon"
  [path ::sp/file]
  (-> path fs/base-name (.startsWith "Blizzard_")))

(defn-spec parse-addon-toc-guard (s/or :ok :addon/toc, :error nil?)
  "wraps the `parse-addon-toc` function and ensures no unhandled exceptions cause a cascading failure"
  [addon-dir ::sp/extant-dir]
  (try
    (if-let [keyvals (read-addon-dir addon-dir)]
      ;; we found a .toc file, now parse it
      (parse-addon-toc addon-dir keyvals)
      ;; we didn't find a .toc file, but just ignore it if it looks like an official addon dir
      (when-not (blizzard-addon? addon-dir)
        ;; not an official addon and we didn't find a .toc file. warn the user
        (warn "failed to find .toc file:" addon-dir)))
    (catch Exception e
      ;; this addon failed somehow. don't propagate the exception, just report it and return nil
      (error "please report this! https://github.com/ogri-la/strongbox/issues")
      (error e (format "unhandled error parsing addon in directory '%s': %s" addon-dir (.getMessage e))))))

(defn-spec installed-addons (s/or :ok :addon/toc-list, :error nil?)
  "returns a list of addon data scraped from the .toc files of all addons in given `addon-dir`"
  [addon-dir ::sp/addon-dir]
  (let [addon-dir-list (->> addon-dir fs/list-dir (filter fs/directory?) (map str))
        addon-list (->> addon-dir-list (map parse-addon-toc-guard) (remove nil?) vec)]
    addon-list))
