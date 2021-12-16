(ns strongbox.toc
  (:refer-clojure :rename {replace clj-replace})
  (:require
   [strongbox
    [constants :as constants]
    ;; I want to keep higher level logging concerns out of toc.clj and nfo.clj.
    ;; addon.clj and higher is ok.
    ;;[logging :as logging] 
    [specs :as sp]
    [utils :as utils]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [clojure.string :refer [lower-case ends-with?]])
  (:import
   [java.util.regex Pattern]))

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

(defn-spec parse-toc-file map?
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

(defn-spec read-toc-file (s/or :ok map?, :error nil?)
  "reads the contents of a *single* toc file into a map.
  returns a map of key-vals scraped from the .toc file in the given `addon-dir`."
  [path-to-toc ::sp/extant-file]
  (->> path-to-toc
       utils/de-bom-slurp
       parse-toc-file))

;; ---

(defn-spec find-toc-files (s/or :ok ::sp/list-of-lists, :error nil?)
  "returns a list of `[[game-track, filename.toc], ...]` where game-track is `nil` if it can't be guessed from the filename."
  [addon-dir ::sp/extant-dir]
  (let [pattern (Pattern/compile "(?u)^(.+?)(?:[\\-_]{1}(Mainline|Classic|Vanilla|TBC|BCC){1})?\\.toc$")
        matching-toc-pattern (fn [filename]
                               (let [toc-bname (str (fs/base-name filename))
                                     [toc-bname-match game-track-match] (rest (re-matches pattern toc-bname))]
                                 (when toc-bname-match
                                   [(utils/guess-game-track game-track-match) toc-bname])))
        result (->> addon-dir
                    fs/list-dir
                    (map str)
                    (map matching-toc-pattern)
                    (remove nil?)
                    (sort-by second) ;; filenames, alphabetically
                    vec
                    utils/nilable)]
    (if-not result
      (warn "failed to find any .toc files:" addon-dir)
      result)))

(defn-spec read-addon-dir (s/or :ok ::sp/list-of-maps, :error nil?)
  "reads the contents of *all* .toc files found in the given `addon-dir`.
  returns a list of [[guessed-game-track keyvals], ...]."
  [addon-dir ::sp/extant-dir]
  (let [toc-dir (-> addon-dir fs/absolute str)]
    (mapv (fn [[filename-game-track filename]]
            (merge (read-toc-file (utils/join toc-dir filename))
                   {:dirname (fs/base-name addon-dir) ;; /foo/bar/baz => baz
                    :-filename-game-track filename-game-track}))
          (find-toc-files toc-dir))))

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
  ([keyvals map?, addon-dir ::sp/dir]
   (parse-addon-toc (assoc keyvals :dirname (fs/base-name addon-dir))))
  ([keyvals map?]
   (let [dirname (:dirname keyvals)

         ;; https://github.com/ogri-la/strongbox/issues/47 - user encountered addon sans 'Title' attribute
         ;; if a match in the catalogue is found even after munging the title, it will overwrite this one
         no-label-label (str dirname " *") ;; "EveryAddon *"
         label (:title keyvals)
         label (if-not (empty? label) label no-label-label)
         _ (when (= label no-label-label)
             (warn "addon with no \"Title\" value found:" dirname))

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

         wago-source (when-let [x-wago-id (-> keyvals :x-wago-id)]
                       {:source "wago"
                        :source-id x-wago-id})

         source-map-list (when-let [items (->> [wowi-source curse-source tukui-source wago-source]
                                               utils/items
                                               utils/nilable)]
                           {:source-map-list items})

         ;; todo: add support for x-github
         ;; ## X-Github: https://github.com/teelolws/Altoholic-Retail => source "github", source-id "teelolws/Altoholic-Retail"

         ignore-flag (when (some->> keyvals :version (clojure.string/includes? "@project-version@"))
                       (warn (format "ignoring '%s': 'Version' field in .toc file is unrendered" dirname))
                       {:ignore? true})

         interface-version (or (some-> keyvals :interface utils/to-int)
                               constants/default-interface-version)

         game-track (utils/interface-version-to-game-track interface-version)

         _ (when (and (some? (:-filename-game-track keyvals))
                      (not= (:-filename-game-track keyvals) game-track))
             (warn (format "'%s' from filename does not match it's interface-version '%s'"
                           (:-filename-game-track keyvals) game-track)))

         addon {:name (normalise-name label)
                :dirname dirname
                :label label
                ;; :notes is preferred but we'll fall back to :description
                :description (or (:notes keyvals) (:description keyvals))
                :interface-version interface-version
                :-toc/game-track game-track

                ;; expanded upon in `parse-addon-toc-guard` when it knows about all available toc files
                :supported-game-tracks [game-track]

                :installed-version (:version keyvals)

                ;; toc file dependency values describe *load order*, not *packaging*
                ;;:dependencies (:dependencies keyvals)
                ;;:optional-dependencies (:optionaldependencies keyvals)
                ;;:required-dependencies (:requireddeps keyvals)
                }

         ;; yes, this prefers curseforge over wowinterface. and tukui over all others.
         ;; I need to figure out some way of supporting multiple hosts per-addon
         addon (merge addon alias wowi-source curse-source tukui-source ignore-flag source-map-list)]

     addon)))

(defn-spec blizzard-addon? boolean?
  "returns `true` if given path looks like an official Blizzard addon"
  [path ::sp/file]
  (-> path fs/base-name (.startsWith "Blizzard_")))

(defn-spec parse-addon-toc-guard (s/or :ok :addon/toc-list, :error nil?)
  "wraps the `parse-addon-toc` function, attaching the list of `:supported-game-tracks` and sinking any errors."
  [addon-dir ::sp/extant-dir]
  (when-not (blizzard-addon? addon-dir)
    (try
      (let [result (mapv parse-addon-toc (read-addon-dir addon-dir))
            supported-game-tracks (->> result (map :-toc/game-track) distinct sort vec)]
        (mapv #(assoc % :supported-game-tracks supported-game-tracks) result))
      (catch Exception e
        ;; this addon failed to parse somehow. don't propagate the exception, just report it and return `nil`.
        (error "please report this! https://github.com/ogri-la/strongbox/issues")
        (error e (format "unhandled error parsing addon in directory '%s': %s" addon-dir (.getMessage e)))))))
