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

(defn-spec parse-toc-file (s/or :ok map?, :empty-or-just-comments nil?)
  "parses the contents of a toc file into a map."
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
    (->> contents
         (filter interesting?)
         (map parse-comment)
         (reduce merge))))

(defn-spec read-toc-file (s/or :ok map?, :error nil?)
  "reads the contents of a *single* toc file into a map.
  returns a map of key-vals scraped from the .toc file in the given `addon-dir`."
  [path-to-toc ::sp/extant-file]
  (->> path-to-toc
       utils/de-bom-slurp
       parse-toc-file))

;; ---

(defn-spec find-toc-files (s/or :ok ::sp/list-of-lists, :error nil?)
  "returns a list of file names as `[[game-track, filename.toc], ...]` in the given `addon-dir`.
  `game-track` is `nil` if it can't be guessed from the filename (and not 'retail')."
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
  returns a list of `[[guessed-game-track keyvals], ...]`."
  [addon-dir ::sp/extant-dir]
  (let [toc-dir (-> addon-dir fs/absolute str)]
    (mapv (fn [[filename-game-track filename]]
            (merge (read-toc-file (utils/join toc-dir filename))
                   {:dirname (fs/base-name addon-dir) ;; /foo/bar/baz => baz
                    :dirsize (utils/folder-size-bytes addon-dir)
                    :-filename filename
                    :-filename-game-track filename-game-track}))
          (find-toc-files toc-dir))))

(defn-spec rm-trailing-version string?
  "strips any trailing version information from a string.
  for example, 'Some Title 1.2.3' => 'Some Title' and 'Some Title v1.2.3' => 'Some Title'"
  [str string?]
  (let [matching-suffix #" v?[\d\.]+$"
        nothing ""]
    (clojure.string/replace str matching-suffix nothing)))

(defn-spec normalise-name string?
  "convert the 'Title' attribute in toc file to a curseforge-style slug."
  [label string?]
  (-> label lower-case rm-trailing-version utils/slugify))

;;

(defn-spec parse-addon-toc (s/or :ok :addon/toc, :invalid nil?)
  "coerces raw `keyvals` map into a valid `:addon/toc` map or returns `nil` if toc data is invalid."
  ([keyvals map?, addon-dir ::sp/dir]
   (parse-addon-toc (assoc keyvals :dirname (fs/base-name addon-dir))))
  ([keyvals map?]
   (let [dirname (:dirname keyvals)
         no-label-label (str dirname " *") ;; "EveryAddon *"
         label (:title keyvals)
         label (if (empty? label)
                 (do (warn "addon with no \"Title\" value found:" dirname)
                     no-label-label)
                 label)

         wowi-source (when-let [x-wowi-id (-> keyvals :x-wowi-id utils/to-int)]
                       {:source "wowinterface"
                        :source-id x-wowi-id})

         ;;curse-source (when-let [x-curse-id (-> keyvals :x-curse-project-id utils/to-int)]
         ;;               {:source "curseforge"
         ;;                :source-id x-curse-id})

         tukui-source (when-let [x-tukui-id (-> keyvals :x-tukui-projectid utils/to-int)]
                        {:source "tukui"
                         :source-id x-tukui-id})

         github-source (when-let [x-github (-> keyvals :x-github)]
                         {:source "github"
                          :source-id (utils/github-url-to-source-id x-github)})

         source-map-list (when-let [items (->> [wowi-source tukui-source github-source
                                                ;;curse-source
                                                ]
                                               utils/items
                                               utils/nilable)]
                           {:source-map-list items})

         ignore-flag (when (some->> keyvals :version (clojure.string/includes? "@project-version@"))
                       (debug (format "ignoring '%s': 'Version' field in .toc file is unrendered" dirname))
                       {:ignore? true})

         ;; todo: warning when interface version not defined.
         interface-version (or (some-> keyvals :interface utils/to-int)
                               constants/default-interface-version)

         game-track (utils/interface-version-to-game-track interface-version)

         _ (when (and (some? (:-filename-game-track keyvals))
                      (not= (:-filename-game-track keyvals) game-track))
             (debug (format
                    ;; 'classic' in .toc filename does not match 'retail' derived from it's 'Interface' value of '90200'.
                    ;; see BigWigs_Classic for a false-positive
                     "'%s' in .toc filename does not match '%s' derived from it's 'Interface' value of '%s'."
                     (name (:-filename-game-track keyvals)) (name game-track) interface-version)))

         addon {:name (normalise-name label)
                :dirname dirname
                :label label
                ;; `:notes` is preferred but we'll fall back to `:description`
                :description (or (:notes keyvals) (:description keyvals))
                :interface-version interface-version
                :-toc/game-track game-track

                ;; expanded upon in `parse-addon-toc-guard` when it knows about *all* available toc files
                :supported-game-tracks [game-track]

                :installed-version (:version keyvals)

                ;; toc file dependency values describe *load order*, not *packaging*
                ;;:dependencies (:dependencies keyvals)
                ;;:optional-dependencies (:optionaldependencies keyvals)
                ;;:required-dependencies (:requireddeps keyvals)
                }

         addon (if-let [dirsize (:dirsize keyvals)]
                 (assoc addon :dirsize dirsize)
                 addon)

         ;; prefers tukui over wowi, wowi over github. I'd like to prefer github over wowi, but github
         ;; requires API calls to interact with and these are limited unless authenticated.
         addon (merge addon
                      github-source wowi-source tukui-source
                      ignore-flag source-map-list)]

     (if-not (s/valid? :addon/toc addon)
       (do (warn (utils/reportable-error
                  ;; "ignoring data in 'EveryAddon.toc', invalid values found."
                  (format "ignoring data in '%s', invalid values found." (:-filename keyvals))
                  "feel free to report this!"))
           (debug (s/explain :addon/toc addon)))
       addon))))

(defn-spec blizzard-addon? boolean?
  "returns `true` if given path looks like an official Blizzard addon"
  [path ::sp/file]
  (-> path fs/base-name (.startsWith "Blizzard_")))

(defn-spec parse-addon-toc-guard (s/or :ok :addon/toc-list, :error nil?)
  "wraps the `parse-addon-toc` function, attaching the list of `:supported-game-tracks` and sinking any errors."
  [addon-dir ::sp/extant-dir]
  (when-not (blizzard-addon? addon-dir)
    (try
      (let [result (->> addon-dir read-addon-dir (map parse-addon-toc) (remove nil?))
            supported-game-tracks (->> result (map :-toc/game-track) distinct sort vec)]
        (mapv #(assoc % :supported-game-tracks supported-game-tracks) result))
      (catch Exception e
        ;; this addon failed to parse somehow. don't propagate the exception, just report it and return `nil`.
        (error e (utils/reportable-error
                  ;; "unexpected error parsing addon in directory '/path/to/addon': Some obscure exception message."
                  (format "unexpected error parsing addon in directory '%s': %s" addon-dir (.getMessage e))))))))

;; --
