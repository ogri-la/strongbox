(ns strongbox.core
  (:require
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? ends-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [envvar.core :refer [env]]
   [strongbox
    [db :as db]
    [config :as config]
    [zip :as zip]
    [http :as http]
    [logging :as logging]
    [nfo :as nfo]
    [utils :as utils :refer [join not-empty? false-if-nil nav-map nav-map-fn delete-many-files! static-slurp expand-path if-let*]]
    [catalogue :as catalogue]
    [toc]
    [specs :as sp]]))

(def game-tracks [:retail :classic])

(def -colour-map
  {:notice/error :tomato
   :notice/warning :lemonchiffon
   ;;:installed/unmatched :tomato
   :installed/ignored-bg nil
   :installed/ignored-fg :darkgray
   :installed/needs-updating :lemonchiffon
   :installed/hovering "#e6e6e6" ;; light grey
   :search/already-installed "#99bc6b" ;; greenish
   :hyperlink :blue})

;; inverse colours of -colour-map
(def -dark-colour-map
  {:notice/error "#009CB8"
   :notice/warning "#000532"
   ;;:installed/unmatched :tomato
   :installed/ignored-bg nil
   :installed/ignored-fg "#666666"
   :installed/needs-updating "#000532"
   :installed/hovering "#191919"
   :search/already-installed "#664394"
   :hyperlink :yellow})

(def themes
  {:light -colour-map
   :dark -dark-colour-map})

(def default-config-dir "~/.config/strongbox")
(def default-data-dir "~/.local/share/strongbox")

(defn generate-path-map
  "generates filesystem paths whose location may vary based on the current working directory and environment variables.
  this map of paths is generated during `init-dirs` and is then fixed in application state.
  ensure the correct environment variables and cwd are set prior to init for proper isolation during tests."
  []
  (let [strongbox-suffix (fn [path]
                           (if-not (ends-with? path "/strongbox")
                             (join path "strongbox")
                             path))

        ;; XDG_DATA_HOME=/foo/bar => /foo/bar/strongbox
        ;; XDG_CONFIG_HOME=/baz/bup => /baz/bup/strongbox

        ;; https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        ;; ignoring XDG_CONFIG_DIRS and XDG_DATA_DIRS for now
        config-dir (-> @env :xdg-config-home utils/nilable (or default-config-dir) expand-path strongbox-suffix)
        data-dir (-> @env :xdg-data-home utils/nilable (or default-data-dir) expand-path strongbox-suffix)

        ;; ensure path ends with `-file` or `-dir` or `-url`.
        ;; see `init-dirs`.
        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :catalogue-dir data-dir

                  ;; /home/$you/.local/share/strongbox/profile-data
                  :profile-data-dir (join data-dir "profile-data")

                  ;; /home/$you/.local/share/strongbox/logs
                  :log-data-dir (join data-dir "logs")

                  ;; /home/$you/.local/share/strongbox/cache
                  :cache-dir (join data-dir "cache")

                  ;; /home/$you/.config/strongbox/config.json
                  :cfg-file (join config-dir "config.json")

                  ;; /home/$you/.local/share/strongbox/etag-db.json
                  :etag-db-file (join data-dir "etag-db.json")

                  ;; /home/$you/.local/share/strongbox/user-catalogue.json
                  :user-catalogue-file (join data-dir "user-catalogue.json")}]
    path-map))

(def -state-template
  {:cleanup []

   ;; set once per application instance
   :in-repl? false

   :file-opts {} ;; options parsed from config file
   :cli-opts {} ;; options passed in on the command line

   ;; final config, result of merging :file-opts and :cli-opts
   ;;:cfg {:addon-dir-list []
   ;;      :selected-catalogue :short}
   :cfg nil ;; see config.clj
   ;;:catalogue-source-list [] ;; moved to config.clj and [:cfg :catalogue-source-list]

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc file data
   ;; ... then updated with data from catalogue
   ;; ... then updated again with live data from curseforge
   ;; see specs/toc-addon


   :installed-addon-list nil

   :etag-db {}

   :db nil

   ;; a map of paths whose location may vary according to the cwd and envvars.
   :paths nil

   ;; ui

   ;; the root swing window
   :gui nil

   ;; set to anything other than `nil` to have `main.clj` restart the gui
   :gui-restart-flag nil

   ;; which of the addon directories is currently selected
   ;;:selected-addon-dir nil ;; moved to [:cfg :selected-addon-dir]

   ;; addons in an unsteady state (data being updated, addon being installed, etc)
   ;; allows a UI to watch and update with progress
   :unsteady-addons #{}

   ;; a sublist of merged toc+addon that are selected
   :selected-installed []

   :search-field-input nil
   :selected-search []
   ;; number of results to display in search results pane.
   ;; used to be 250 but with better searching there is less scrolling
   :search-results-cap 150})

(def state (atom nil))

(defn get-state
  "returns the state map of the value at the given path within the map, if path provided"
  [& path]
  (if-let [state @state]
    (nav-map state path)
    (throw (RuntimeException. "application must be `start`ed before state may be accessed."))))

(defn paths
  "like `get-in` and `get-state` but for the map of paths being used. requires running app"
  [& path]
  (nav-map (get-state :paths) path))

(defn colours
  "like `get-in` but for the currently selected colour theme. requires running app"
  [& path]
  (nav-map (get themes (get-state :cfg :gui-theme)) path))

;;

(defn set-etag
  "convenient wrapper around adding and removing etag values from state map"
  ([filename]
   (swap! state update-in [:etag-db] dissoc filename)
   nil)
  ([filename etag]
   (swap! state assoc-in [:etag-db filename] etag)
   nil))

(defn cache
  "data and a setter that gets bound to http/*cache* when caching http requests"
  []
  {;;:etag-db (get-state :etag-db) ;; don't do this. encourages stale reads of the etag-db
   :set-etag set-etag
   :get-etag #(get-state :etag-db %) ;; do this instead
   :cache-dir (paths :cache-dir)})

(defn-spec state-bind nil?
  "executes given callback function when value at path in state map changes. 
  trigger is discarded if old and new values are identical"
  [path ::sp/list-of-keywords, callback fn?]
  (let [prefn identity
        has-changed (fn [old-state new-state]
                      (not= (prefn (get-in old-state path))
                            (prefn (get-in new-state path))))
        wid (keyword (gensym callback)) ;; :foo.bar$baz@123456789
        rmwatch #(remove-watch state wid)]

    (add-watch state wid
               (fn [_ _ old-state new-state] ;; key, atom, old-state, new-state
                 (when (has-changed old-state new-state)
                   (debug (format "path %s triggered %s" path wid))
                   (try
                     (callback new-state)
                     (catch Exception e
                       (error e "error caught in watch! your callback *must* be catching these or the thread dies silently:" path))))))

    (swap! state update-in [:cleanup] conj rmwatch)
    nil))

;; addon dirs

(defn-spec addon-dir-exists? boolean?
  ([addon-dir ::sp/addon-dir]
   (addon-dir-exists? addon-dir (get-state :cfg :addon-dir-list)))
  ([addon-dir ::sp/addon-dir, addon-dir-list ::sp/addon-dir-list]
   (->> addon-dir-list (map :addon-dir) (some #{addon-dir}) nil? not)))

(defn-spec add-addon-dir! nil?
  [addon-dir ::sp/addon-dir, game-track ::sp/game-track]
  (let [stub {:addon-dir addon-dir :game-track game-track}]
    (when-not (addon-dir-exists? addon-dir)
      (swap! state update-in [:cfg :addon-dir-list] conj stub))
    nil))

(defn-spec set-addon-dir! nil?
  "adds a new :addon-dir to :addon-dir-list (if it doesn't already exist) and marks it as selected"
  [addon-dir ::sp/addon-dir]
  (let [addon-dir (-> addon-dir fs/absolute fs/normalized str)
        ;; if '_classic_' is in given path, use the classic game track
        default-game-track (if (clojure.string/index-of addon-dir "_classic_") :classic :retail)]
    (add-addon-dir! addon-dir default-game-track)
    (swap! state assoc-in [:cfg :selected-addon-dir] addon-dir))
  nil)

(defn-spec selected-addon-dir (s/or :ok ::sp/addon-dir, :no-selection nil?)
  "returns the currently selected addon or nil if no directories exist to select from"
  []
  (get-state :cfg :selected-addon-dir))

(defn-spec remove-addon-dir! nil?
  "removes the directory from user configuration, does not alter the directory or it's contents at all"
  ([]
   (when-let [addon-dir (selected-addon-dir)]
     (remove-addon-dir! addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [matching #(= addon-dir (:addon-dir %))
         new-addon-dir-list (->> (get-state :cfg :addon-dir-list) (remove matching) vec)]
     (swap! state assoc-in [:cfg :addon-dir-list] new-addon-dir-list)
     ;; this may be nil if the new addon-dir-list is empty
     (swap! state assoc-in [:cfg :selected-addon-dir] (-> new-addon-dir-list first :addon-dir)))
   nil))

(defn available-addon-dirs
  []
  (mapv :addon-dir (get-state :cfg :addon-dir-list)))

(defn-spec addon-dir-map (s/or :ok ::sp/addon-dir-map, :missing nil?)
  ([]
   (addon-dir-map (selected-addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [addon-dir-list (get-state :cfg :addon-dir-list)]
     (when-not (empty? addon-dir-list)
       (first (filter #(= addon-dir (:addon-dir %)) addon-dir-list))))))

(defn-spec set-game-track! nil?
  ([game-track ::sp/game-track]
   (set-game-track! game-track (selected-addon-dir)))
  ([game-track ::sp/game-track, addon-dir ::sp/addon-dir]
   (let [tform (fn [addon-dir-map]
                 (if (= addon-dir (:addon-dir addon-dir-map))
                   (assoc addon-dir-map :game-track game-track)
                   addon-dir-map))
         new-addon-dir-map-list (mapv tform (get-state :cfg :addon-dir-list))]
     (swap! state update-in [:cfg] assoc :addon-dir-list new-addon-dir-map-list)
     nil)))

(defn-spec get-game-track (s/or :ok ::sp/game-track, :missing nil?)
  ([]
   (when-let [addon-dir (selected-addon-dir)]
     (get-game-track addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (-> addon-dir addon-dir-map :game-track)))

;; settings

(defn-spec change-log-level! nil?
  "changes the effective log level from `logging/default-log-level` to `new-level`.
  The `:debug` log level outside of unit tests will write a log file to the data directory and 
  enables the profiling of certain sections of code."
  [new-level keyword?]
  (timbre/merge-config! {:level new-level})
  (when (logging/debug-mode?) ;; debug level + not-testing
    (if-not @state
      (warn "application has not been started, no location to write log or profile data")
      (do
        (logging/add-profiling-handler! (paths :profile-data-dir))
        (logging/add-file-appender! (paths :log-data-dir)))))
  nil)

(defn save-settings
  "writes user configuration to the filesystem"
  []
  ;; warning: this will preserve any once-off command line parameters as well
  ;; this might make sense within the gui but be careful about auto-saving elsewhere
  (debug "saving settings to:" (paths :cfg-file))
  (utils/dump-json-file (paths :cfg-file) (get-state :cfg))
  (debug "saving etag-db to:" (paths :etag-db-file))
  (utils/dump-json-file (paths :etag-db-file) (get-state :etag-db)))

(defn load-settings!
  "pulls together configuration from the fs and cli, merges it and sets application state"
  [cli-opts]
  (let [final-config (config/load-settings cli-opts (paths :cfg-file) (paths :etag-db-file))]
    (swap! state merge final-config)
    (change-log-level! (or (:verbosity cli-opts) logging/default-log-level)))
  nil)


;;
;; utils
;;


(defn-spec expanded? boolean?
  "returns true if an addon has found further details online"
  [addon map?]
  (some? (:download-url addon)))

(defn start-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addons] clojure.set/union #{(:name addon)}))

(defn stop-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addons] clojure.set/difference #{(:name addon)}))

(defn affects-addon-wrapper
  [wrapped-fn]
  (fn [addon & args]
    (try
      (start-affecting-addon addon)
      (apply wrapped-fn addon args)
      (finally
        (stop-affecting-addon addon)))))

;;
;; downloading and installing and updating
;;

(defn-spec downloaded-addon-fname string?
  [name string?, version string?]
  (format "%s--%s.zip" name (utils/slugify version))) ;; addonname--1-2-3.zip

(defn-spec download-addon (s/or :ok ::sp/archive-file, :http-error ::sp/http-error, :error nil?)
  [addon ::sp/addon-or-toc-addon, download-dir ::sp/writeable-dir]
  (info "downloading" (:label addon) "...")
  (when (expanded? addon)
    (let [output-fname (downloaded-addon-fname (:name addon) (:version addon)) ;; addonname--1-2-3.zip
          output-path (join (fs/absolute download-dir) output-fname)] ;; /path/to/installed/addons/addonname--1.2.3.zip
      (binding [http/*cache* (cache)]
        (http/download-file (:download-url addon) output-path)))))

;; don't do this. `download-addon` is wrapped by `install-addon` that is already affecting the addon
;;(def download-addon
;;  (affects-addon-wrapper download-addon))

(defn-spec determine-primary-subdir (s/or :found map?, :not-found nil?)
  "if an addon unpacks to multiple directories, which is the 'main' addon?
   a common convention looks like 'Addon[seperator]Subname', for example:
       'Healbot' and 'Healbot_de' or 
       'MogIt' and 'MogIt_Artifact'
   DBM is one exception to this as the 'main' addon is 'DBM-Core' (I think, it's definitely the largest)
   'MasterPlan' and 'MasterPlanA' is another exception
   these exceptions to the rule are easily handled. the rule is:
       1. if multiple directories,
       2. assume dir with shortest name is the main addon
       3. but only if it's a prefix of all other directories
       4. if case doesn't hold, do nothing and accept we have no 'main' addon"
  [toplevel-dirs ::sp/list-of-maps]
  (let [path-val #(-> % :path (utils/rtrim "\\/")) ;; strips trailing line endings, they mess with comparison
        path-len (comp count :path)
        toplevel-dirs (remove #(empty? (:path %)) toplevel-dirs) ;; remove anything we can't compare
        toplevel-dirs (vec (vals (utils/idx toplevel-dirs :path))) ;; remove duplicate paths
        toplevel-dirs (sort-by path-len toplevel-dirs)
        dirname-lengths (mapv path-len toplevel-dirs)
        first-toplevel-dir (first toplevel-dirs)]

    (cond
      (= 1 (count toplevel-dirs)) ;; single dir, perfect case
      first-toplevel-dir

      (and
       ;; multiple dirs, one shorter than all others
       (not= (first dirname-lengths) (second dirname-lengths))
       ;; all dirs are prefixed with the name of the first toplevel dir
       (every? #(clojure.string/starts-with? (path-val %) (path-val first-toplevel-dir)) toplevel-dirs))
      first-toplevel-dir

      ;; couldn't reasonably determine the primary directory
      :else nil)))

(defn-spec guess-game-track ::sp/game-track
  "given a map of addon data, attempts to guess the most likely game track it belongs to"
  [install-dir ::sp/extant-dir, addon map?]
  (or (:game-track addon) ;; from reading an export record. most of the time this value won't be here
      (:installed-game-track addon) ;; re-use the value we have if updating an existing addon
      (cond
        ;; addon has been successfully expanded, current game track is being used.
        (expanded? addon) (get-game-track install-dir)

        ;; the interface version is set in the .toc file but is also part of 'expanding' an addon.
        ;; prefer current game track over this as the real value may be overridden.
        (some? (:interface-version addon)) (utils/interface-version-to-game-track (:interface-version addon)))

      ;; very last here is for testing only
      :retail))

(defn-spec -install-addon (s/or :ok (s/coll-of ::sp/extant-file), :error ::sp/empty-coll)
  "installs an addon given an addon description, a place to install the addon and the addon zip file itself"
  [addon ::sp/addon-or-toc-addon, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file]
  ;; TODO: this function is becoming a mess. clean it up
  (let [zipfile-entries (zip/zipfile-normal-entries downloaded-file)
        sus-addons (zip/inconsistently-prefixed zipfile-entries)

        ;; one single line message or multi-line?
        msg "%s will install inconsistently prefixed addons: %s"
        _ (when sus-addons
            (warn (format msg (:label addon) (clojure.string/join ", " sus-addons))))

        _ (zip/unzip-file downloaded-file install-dir)
        toplevel-dirs (filter (every-pred :dir? :toplevel?) zipfile-entries)
        primary-dirname (determine-primary-subdir toplevel-dirs)
        game-track (or (:game-track addon) ;; from reading an export record. most of the time this value won't be here
                       (get-game-track install-dir)

                       ;; very last here is for testing only
                       :retail)

        ;; an addon may unzip to many directories, each directory needs the nfo file
        update-nfo-fn (fn [zipentry]
                        (let [addon-dirname (:path zipentry)
                              primary? (= addon-dirname (:path primary-dirname))]
                          (nfo/write-nfo install-dir addon addon-dirname primary? game-track)))

        ;; write the nfo files, return a list of all nfo files written
        retval (mapv update-nfo-fn toplevel-dirs)]
    (info (:label addon) "installed.")
    retval))

(defn-spec install-addon-guard (s/or :ok (s/coll-of ::sp/extant-file), :passed-tests true?, :error nil?)
  "downloads an addon and installs it. handles http and non-http errors, bad zip files, bad addons"
  ([addon ::sp/addon-or-toc-addon, install-dir ::sp/extant-dir]
   (install-addon-guard addon install-dir false))
  ([addon ::sp/addon-or-toc-addon, install-dir ::sp/extant-dir, test-only? boolean?]
   (cond
     ;; do some pre-installation checks
     (:ignore? addon) (warn "failing to install addon, addon is being ignored:" install-dir)
     (not (fs/writeable? install-dir)) (error "failing to install addon, directory not writeable:" install-dir)

     :else ;; attempt downloading and installing addon

     (let [downloaded-file (download-addon addon install-dir)
           bad-zipfile-msg (format "failed to read zip file '%s', could not install %s" downloaded-file (:name addon))
           bad-addon-msg (format "refusing to install '%s'. It contains top-level files or top-level directories missing .toc files."  (:name addon))]
       (info "installing" (:label addon) "...")
       (cond
         (map? downloaded-file) (error "failed to download addon, could not install" (:name addon))
         (nil? downloaded-file) (error "non-http error downloading addon, could not install" (:name addon)) ;; I dunno. /shrug
         (not (zip/valid-zip-file? downloaded-file)) (do
                                                       (error bad-zipfile-msg)
                                                       (fs/delete downloaded-file)
                                                       (warn "removed bad zip file" downloaded-file))
         (not (zip/valid-addon-zip-file? downloaded-file)) (do
                                                             (error bad-addon-msg)
                                                             (fs/delete downloaded-file) ;; I could be more lenient
                                                             (warn "removed bad addon" downloaded-file))

         test-only? true ;; addon was successfully downloaded and verified as being sound

         :else (-install-addon addon install-dir downloaded-file))))))

(def install-addon
  (affects-addon-wrapper install-addon-guard))

(defn update-installed-addon-list!
  [installed-addon-list]
  (let [asc compare
        installed-addon-list (sort-by :name asc installed-addon-list)]
    (swap! state assoc :installed-addon-list installed-addon-list)
    nil))

(defn-spec group-addons ::sp/toc-list
  "an addon may actually be many addons bundled together in a single download.
  strongbox tags the bundled addons as they are unzipped and tries to determine the primary one.
  after we've loaded the addons and merged their nfo data, we can then group them"
  [addon-list ::sp/toc-list]
  (let [;; group-id comes from the nfo file
        addon-groups (group-by :group-id addon-list)

        ;; remove those addons without a group, we'll conj them in later
        unknown-grouping (get addon-groups nil)
        addon-groups (dissoc addon-groups nil)

        expand (fn [[group-id addons]]
                 (if (= 1 (count addons))
                   ;; perfect case, no grouping.
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

        ;; this flattens the newly grouped addons from a map into a list and joins the unknowns
        addon-list (apply conj (mapv expand addon-groups) unknown-grouping)]
    addon-list))

(defn-spec -load-installed-addons ::sp/toc-list
  "reads the .toc files from the given addon dir, reads any nfo data for 
  these addons, groups them, returns the mooshed data"
  [addon-dir ::sp/addon-dir]
  (let [addon-list (strongbox.toc/installed-addons addon-dir)

        ;; at this point we have a list of the 'top level' addons, with
        ;; any bundled addons grouped within each one.

        ;; each addon now needs to be merged with the 'nfo' data, the additional
        ;; data we store alongside each addon when it is installed/updated

        merge-nfo-data (fn [addon]
                         (let [nfo-data (nfo/read-nfo addon-dir (:dirname addon))]
                           ;; merge the addon with the nfo data.
                           ;; when `ignore?` flag in addon is `true` but `false` in nfo-data, nfo-data will take precedence.
                           (merge addon nfo-data)))

        addon-list (mapv merge-nfo-data addon-list)]

    (group-addons addon-list)))

(defn-spec load-installed-addons nil?
  "guard function. offloads the hard work to `-load-installed-addons` then updates application state"
  []
  (if-let [addon-dir (selected-addon-dir)]
    (let [addon-list (-load-installed-addons addon-dir)]
      (info "(re)loading installed addons:" addon-dir)
      (update-installed-addon-list! addon-list))

    ;; otherwise, ensure list of installed addons is cleared
    (update-installed-addon-list! [])))

;;
;; catalogue handling
;;

(defn-spec get-catalogue-source (s/or :ok ::sp/catalogue-source-map, :not-found nil?)
  ([]
   (get-catalogue-source (get-state :cfg :selected-catalogue)))
  ([catalogue-name keyword?]
   (->> (get-state :cfg :catalogue-source-list) (filter #(= catalogue-name (:name %))) first)))

(defn-spec current-catalogue (s/or :ok ::sp/catalogue-source-map, :no-catalogues nil?)
  "returns the currently selected catalogue or the first catalogue it can find.
  returns `nil` if no catalogues available to choose from."
  []
  (if-let* [;; there may be nothing selected
            catalogue (get-catalogue-source (get-state :cfg :selected-catalogue))
            ;; there may be no default catalogue available
            default-catalogue (get-catalogue-source (-> (get-state :cfg :catalogue-source-list) first :name))]
           (or catalogue default-catalogue)
           nil))

(defn-spec set-catalogue-source! nil?
  [catalogue-name keyword?]
  (if-let [catalogue (get-catalogue-source catalogue-name)]
    (swap! state assoc-in [:cfg :selected-catalogue] (:name catalogue))
    (warn "catalogue not found" catalogue-name))
  nil)

(defn-spec catalogue-local-path ::sp/file
  "given a catalogue-source map, returns the local path to the catalogue."
  [catalogue-source ::sp/catalogue-source-map]
  ;; {:name :full ...} => "/path/to/catalogue/dir/full-catalogue.json"
  (utils/join (paths :catalogue-dir) (-> catalogue-source :name name (str "-catalogue.json"))))

(defn-spec find-catalogue-local-path (s/or :ok ::sp/file, :not-found nil?)
  "convenience wrapper around `catalogue-local-path`"
  [catalogue-name keyword?]
  (some-> catalogue-name get-catalogue-source catalogue-local-path))

(defn-spec download-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads catalogue to expected location, nothing more"
  [catalogue-source ::sp/catalogue-source-map]
  (binding [http/*cache* (cache)]
    (let [remote-catalogue (:source catalogue-source)
          local-catalogue (catalogue-local-path catalogue-source)
          message (format "downloading catalogue '%s'" (:label catalogue-source))
          resp (http/download-file remote-catalogue local-catalogue message)]
      (when-not (http/http-error? resp)
        resp))))

(defn-spec download-current-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads the currently selected (or default) catalogue. 
  issues a warning if no catalogue can be downloaded"
  []
  (if-let [catalogue (current-catalogue)]
    (download-catalogue catalogue)
    (warn "failed to find a downloadable catalogue")))

(defn-spec moosh-addons ::sp/toc-addon-summary
  "merges the data from an installed addon with it's match in the catalogue"
  [installed-addon ::sp/toc, db-catalogue-addon ::sp/addon-summary]
  (let [;; nil fields are removed from the catalogue item because they might override good values in the .toc or .nfo
        db-catalogue-addon (utils/drop-nils db-catalogue-addon [:description])]
    ;; merges left->right. catalogue-addon overwrites installed-addon, ':matched' overwrites catalogue-addon, etc
    (merge installed-addon db-catalogue-addon {:matched? true})))

;;


(defn-spec get-create-user-catalogue ::sp/catalogue
  "returns the contents of the user catalogue, creating one if necessary"
  []
  (let [user-catalogue-path (paths :user-catalogue-file)]
    (catalogue/read-catalogue
     (if (fs/exists? user-catalogue-path)
       user-catalogue-path
       (catalogue/write-empty-catalogue! user-catalogue-path)))))

(defn-spec add-user-addon! nil?
  "adds one or many addons to the user catalogue"
  [addon-summary (s/or :single ::sp/addon-summary, :many ::sp/addon-summary-list)]
  (let [addon-summary-list (if (sequential? addon-summary)
                             addon-summary
                             [addon-summary])
        user-catalogue-path (paths :user-catalogue-file)
        user-catalogue (get-create-user-catalogue)
        tmp-catalogue (catalogue/new-catalogue addon-summary-list)
        new-user-catalogue (catalogue/merge-catalogues user-catalogue tmp-catalogue)]
    (catalogue/write-catalogue new-user-catalogue user-catalogue-path))
  nil)

;;

(defn match-installed-addons-with-catalogue
  "when we have a list of installed addons as well as the addon list,
   merge what we can into ::specs/addon-toc records and update state.
   any installed addon not found in :addon-idx has a mapping problem"
  ([]
   (when (selected-addon-dir) ;; don't even bother if we have nothing to match it to
     (match-installed-addons-with-catalogue (get-state :db) (get-state :installed-addon-list))))
  ([database installed-addon-list]
   (info "matching installed addons to catalogue")
   (let [match-results (db/-db-match-installed-addons-with-catalogue (get-state :db) installed-addon-list)
         [matched unmatched] (utils/split-filter :matched? match-results)

         ;; for those that *did* match, merge the installed addon data together with the catalogue data
         matched (mapv #(moosh-addons (:installed-addon %) (:catalogue-match %)) matched)
         ;; and then make them a single list of addons again
         expanded-installed-addon-list (into matched unmatched)

         ;; todo: metrics gathering is good, but this is a little adhoc.
         ;; some metrics we'll emit for the user
         [num-installed num-matched] [(count installed-addon-list) (count matched)]
         unmatched-names (set (map :name unmatched))]

     (when-not (= num-installed num-matched)
       (info "num installed" num-installed ", num matched" num-matched))

     (when-not (empty? unmatched)
       (warn "you need to manually search for them and then re-install them")
       (warn (format "failed to find %s installed addons in the '%s' catalogue: %s"
                     (count unmatched)
                     (name (get-state :cfg :selected-catalogue))
                     (clojure.string/join ", " unmatched-names))))

     (update-installed-addon-list! expanded-installed-addon-list))))

;; catalogue db handling

(defn query-db
  [query-kw & [arg-list]]
  (db/stored-query (get-state :db) query-kw arg-list))

(defn db-init
  "loads any previous database instance"
  []
  (swap! state assoc :db (db/start))
  nil)

;; todo: should I distinguish between not-set (nil) and empty ([])?
;; it is possible to have an empty catalogue or no catalogue ...
(defn db-catalogue-loaded?
  []
  (-> (get-state :db) empty? not))

(defn db-search
  "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
  ([]
   ;; random list of addons, no preference
   (db-search nil))
  ([uin]
   (query-db :search uin)))

;; 100ms penalty for spec checking, disabling for now.
;; catalogue data check should be shifted to load-catalogue
;;(defn-spec -db-load-catalogue nil?
;;  [catalogue-data ::sp/catalogue]
(defn -db-load-catalogue
  [catalogue-data]
  (p :p2/db:load:insert-addon-list
     (swap! state assoc :db (db/put-many (get-state :db) (:addon-summary-list catalogue-data))))
  nil)

(defn-spec load-current-catalogue (s/or :ok ::sp/catalogue, :error nil?)
  []
  (when-let [catalogue-source (current-catalogue)]
    (let [catalogue-path (catalogue-local-path catalogue-source)
          _ (info (format "loading catalogue '%s'" (name (:name catalogue-source))))

          ;; download from remote and try again when json can't be read
          bad-json-file-handler
          (fn []
            (warn "catalogue corrupted. re-downloading and trying again.")
            (fs/delete catalogue-path)
            (download-current-catalogue)
            (catalogue/read-catalogue
             catalogue-path
             {:bad-data? (fn []
                           (error "please report this! https://github.com/ogri-la/strongbox/issues")
                           (error "catalogue *still* corrupted and cannot be loaded. try another catalogue from the 'catalogue' menu"))}))

          catalogue-data (p :p2/db:catalogue:read-catalogue (catalogue/read-catalogue catalogue-path {:bad-data? bad-json-file-handler}))
          user-catalogue-data (p :p2/db:catalogue:read-user-catalogue (catalogue/read-catalogue (paths :user-catalogue-file) {:bad-data? nil}))
          final-catalogue (p :p2/db:catalogue:merge-catalogues (catalogue/merge-catalogues catalogue-data user-catalogue-data))]
      final-catalogue)))

(defn db-load-catalogue
  "loads the currently selected catalgoue into the database if hasn't already been loaded.
  handles bad/invalid catalgoues and merging the user catalogue"
  []
  (when (and (not (db-catalogue-loaded?))
             (current-catalogue))
    (let [final-catalogue (p :p2/db:catalogue (load-current-catalogue))]
      (when-not (empty? final-catalogue)
        (p :p2/db:load (-db-load-catalogue final-catalogue))))))

(defn-spec refresh-user-catalogue nil?
  "re-fetch each item in user catalogue using the URI and replace old entry with any updated details"
  []
  (binding [http/*cache* (cache)]
    (info "refreshing \"user-catalogue.json\", this may take a minute ...")
    (->> (get-create-user-catalogue)
         :addon-summary-list
         (map :url)
         (map catalogue/parse-user-string)
         add-user-addon!)))

;;
;; addon summary and toc merging
;;

(defn expand-summary-wrapper
  [addon-summary]
  (binding [http/*cache* (cache)]
    (let [game-track (get-game-track) ;; scope creep, but it fits so nicely
          wrapper (affects-addon-wrapper catalogue/expand-summary)]
      (wrapper addon-summary game-track))))

(defn-spec check-for-update ::sp/toc
  [toc ::sp/toc]
  (if-let [addon (when (:matched? toc)
                   (expand-summary-wrapper toc))]
    ;; we have a match and were successful in expanding the summary
    (let [toc-addon (merge toc addon)
          {:keys [installed-version version]} toc-addon
          ;; update only if we have a new version and it's different from the installed version
          update? (and version (not= installed-version version))]
      (assoc toc-addon :update? update?))

    ;; failed to match against catalogue or expand-summary returned nil (couldn't expand for whatever reason)
    ;; in this case, we set a flag saying this addon shouldn't be updated
    (assoc toc :update? false)))

(defn-spec check-for-updates nil?
  "downloads full details for all installed addons that can be found in summary list"
  []
  (when (selected-addon-dir)
    (info "checking for updates")
    (update-installed-addon-list! (mapv check-for-update (get-state :installed-addon-list)))
    (info "done checking for updates")))

;; ui interface

(defn-spec init-dirs nil?
  "ensures all directories in `generate-path-map` exist and are writable, creating them if necessary.
  this logic depends on paths that are not generated until the application has been started."
  []
  ;; data directory doesn't exist and parent directory isn't writable
  ;; nowhere to create data dir, nowhere to store download catalogue. non-starter
  (when (and
         (not (fs/exists? (paths :data-dir))) ;; doesn't exist and ..
         (not (utils/last-writeable-dir (paths :data-dir)))) ;; .. no writeable parent
    (throw (RuntimeException. (str "Data directory doesn't exist and it cannot be created: " (paths :data-dir)))))

  ;; state directory *does* exist but isn't writeable
  ;; another non-starter
  (when (and (fs/exists? (paths :data-dir))
             (not (fs/writeable? (paths :data-dir))))
    (throw (RuntimeException. (str "Data directory isn't writeable:" (paths :data-dir)))))

  ;; ensure all '-dir' suffixed paths exist, creating them if necessary
  (doseq [[path val] (paths)]
    (when (-> path name (clojure.string/ends-with? "-dir"))
      (debug (format "creating '%s' directory: %s" path val))
      (fs/mkdirs val)))

  nil)

(defn-spec delete-log-files! nil?
  "Deletes the 'logs' and 'profile-data' directories.
  Files are written here when the log level is :debug (and we're not testing)."
  []
  (warn "deleting logs")
  (fs/delete-dir (paths :log-data-dir))
  (fs/delete-dir (paths :profile-data-dir))
  (init-dirs))

(defn-spec prune-http-cache! nil?
  "deletes html/json files from the 'cache' directory that are older than a certain age."
  []
  (info "pruning http cache")
  (http/prune-cache-dir (paths :cache-dir)))

(defn-spec delete-http-cache! nil?
  "Deletes the 'cache' directory that contains html/json files and the etag db file."
  []
  (warn "deleting http cache")
  (fs/delete-dir (paths :cache-dir))
  (fs/delete (paths :etag-db-file))
  (init-dirs))

(defn-spec delete-downloaded-addon-zips! nil?
  []
  (delete-many-files! (selected-addon-dir) #".+\-\-.+\.zip$" "downloaded addon zip"))

(defn-spec delete-strongbox-json-files! nil?
  []
  (delete-many-files! (selected-addon-dir) #"\.strongbox\.json$" ".strongbox.json"))

(defn-spec delete-wowmatrix-dat-files! nil?
  []
  (delete-many-files! (selected-addon-dir) #"(?i)WowMatrix.dat$" "WowMatrix.dat"))

(defn-spec delete-catalogue-files! nil?
  []
  (delete-many-files! (paths :data-dir) #".+\-catalogue\.json$" "catalogue"))

(defn-spec clear-all-temp-files! nil?
  "deletes all log files, downloaded addon zip files, catalogues and the http cache, including the etag db"
  []
  (delete-log-files!)
  (delete-downloaded-addon-zips!)
  (delete-catalogue-files!)
  (delete-http-cache!))

;; version checking

(defn-spec strongbox-version string?
  "returns this version of strongbox"
  []
  (versioneer/get-version "ogri-la" "strongbox"))

(defn-spec latest-strongbox-release string?
  "returns the most recently released version of strongbox it can find"
  []
  (binding [http/*cache* (cache)]
    (let [message "downloading strongbox version data"
          url "https://api.github.com/repos/ogri-la/wowman/releases/latest"
          resp (utils/from-json (http/download url message))]
      (-> resp :tag_name))))

(defn-spec latest-strongbox-version? boolean?
  "returns true if the *running instance* of strongbox is the *most recent known* version of strongbox."
  []
  (let [latest-release (latest-strongbox-release)
        version-running (strongbox-version)
        sorted-asc (utils/sort-semver-strings [latest-release version-running])]
    (= version-running (last sorted-asc))))

;; import/export

(defn-spec export-installed-addon ::sp/export-record
  "given an addon summary from a catalogue or .toc file data, derive an 'export-record' that can be used to import an addon later"
  [addon (s/or :catalogue ::sp/addon-summary, :installed ::sp/toc)]
  (let [stub (select-keys addon [:name :source :source-id])
        game-track (when-let [game-track (:installed-game-track addon)]
                     {:game-track game-track})]
    (merge stub game-track)))

(defn-spec export-installed-addon-list ::sp/export-record-list
  "derives an 'export-record' from a list of either addon summaries from a catalogue or .toc file data from installed addons"
  [addon-list (s/or :catalogue ::sp/addon-summary-list, :installed ::sp/toc-list)]
  (->> addon-list (remove :ignore?) (map export-installed-addon) vec))

(defn-spec export-installed-addon-list-safely ::sp/extant-file
  "writes the name, source, source-id and current game track to a json file for each installed addon in the currently selected addon directory"
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str)
        output-file (utils/replace-file-ext output-file ".json")
        addon-list (get-state :installed-addon-list)
        export (export-installed-addon-list addon-list)]

    ;; target any unmatched addons with no `:source` from the addon list and emit a warning
    (doseq [addon (remove :source addon-list)]
      (warn (format "Addon '%s' has no match in the catalogue and may be skipped durlng import. It's best all addons match before doing an export." (:name addon))))

    (utils/dump-json-file output-file export)
    (info "wrote:" output-file)
    output-file))

(defn-spec export-catalogue-addon-list ::sp/export-record-list
  "given a catalogue of addons, generates a list of 'export-records' from the list of addon summaries"
  [catalogue ::sp/catalogue]
  (let [addon-list (:addon-summary-list catalogue)]
    (export-installed-addon-list addon-list)))

(defn-spec export-user-catalogue-addon-list-safely ::sp/extant-file
  "generates a list of 'export-records' from the addon summaries in the user catalogue and writes them to the given `output-file`"
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str (utils/replace-file-ext ".json"))
        catalogue (get-create-user-catalogue)
        export (export-catalogue-addon-list catalogue)]
    (utils/dump-json-file output-file export)
    (info "wrote:" output-file)
    output-file))

(defn -import-addon-list-v1
  "finds matches in the database for the given list of partial addon data (name, name+source) and then expands them."
  [addon-list]
  (let [find-expand (fn [addon]
                      (let [{:keys [source name]} addon
                            matching-addon
                            (cond
                              ;; first addon by given name and source. hopefully 0 or 1 results
                              (and source name) (first (query-db :addon-by-source-and-name [source name]))

                              ;; first addon by given name. potentially multiple results
                              name (first (query-db :addon-by-name [name]))

                              ;; we have nothing to query on :(                          
                              :else nil)]
                        (when matching-addon
                          (expand-summary-wrapper matching-addon))))
        matching-addon-list (->> addon-list (map find-expand) (remove nil?) vec)]
    matching-addon-list))

(defn import-addon-list-v1
  "handles exports with partial information (name, or name and source) from <=0.10.0 versions of strongbox."
  [addon-list] ;; todo: spec
  (info (format "attempting to import %s addons. this may take a minute" (count addon-list)))
  (let [matching-addon-list (-import-addon-list-v1 addon-list)
        addon-dir (selected-addon-dir)]
    (doseq [addon matching-addon-list]
      (install-addon addon addon-dir))))

;; v2 uses the same mechanism to match addons as the rest of the app does
(defn import-addon-list-v2
  "handles exports with full information (name and source and source-id) from strongbox >=0.10.1.
  this style of export allows us to make fast and unambiguous matches against the database."
  [addon-list]
  (let [;; in order to get these bare maps playing nicely with the rest of the system we need to
        ;; gussy them up a bit so it looks like an `::sp/installed-addon-summary`
        padding {:label ""
                 :description ""
                 :dirname ""
                 :interface-version 0
                 :installed-version "0"}
        addon-list (map #(merge padding %) addon-list)

        ;; match each of these padded addon maps to entries in the catalogue database
        ;; afterwards this will call `update-installed-addon-list!` that will trigger a refresh in the gui
        _ (match-installed-addons-with-catalogue (get-state :db) addon-list)

        addon-dir (selected-addon-dir)

        ;; this is what v1 does, but it's hidden away in `expand-summary-wrapper`
        default-game-track (get-game-track)]

    (doseq [addon (get-state :installed-addon-list)
            :let [game-track (get addon :game-track default-game-track)]]
      (when-let [expanded-addon (catalogue/expand-summary addon game-track)]
        (install-addon expanded-addon addon-dir)))))

(defn-spec import-exported-file nil?
  [path ::sp/extant-file]
  (info "importing exports file:" path)
  (let [nil-me (constantly nil)
        addon-list (utils/load-json-file-safely path
                                                {:bad-data? nil-me
                                                 :data-spec ::sp/export-record-list
                                                 :invalid-data? nil-me
                                                 :transform-map {:game-track keyword}})
        full-data? (fn [addon]
                     (utils/all (mapv #(contains? addon %) [:source :source-id :name])))
        [full-data, partial-data] (utils/split-filter full-data? addon-list)]
    (when-not (empty? partial-data)
      (debug "partial data, v1 import:" partial-data)
      (import-addon-list-v1 partial-data))
    (when-not (empty? full-data)
      (debug "full data, v2 import:" full-data)
      (import-addon-list-v2 full-data))))

;;

(defn-spec -upgrade-nfo nil?
  "given an addon, upgrades it's nfo file to the current nfo spec."
  [install-dir ::sp/extant-dir, addon (s/keys :req-un [::sp/dirname])]
  (info "upgrading nfo file:" (:dirname addon))
  (let [;; best guess of what the installed game track is
        ;; TODO: remove this in 0.14.0 and delete invalid nfo-v2 files
        ;; it's reasonable to assume nfo files will consistently have a :game-track by then
        game-track (guess-game-track install-dir addon)
        addon (merge addon {;; double handling of `:version` here with `nfo/update-nfo`
                            ;; the new minimum spec to derive nfo data requires a `:version` key.
                            ;; addons that are not expanded yet do not have this key.
                            ;; which is beside the point, because we don't want to use the `:version` key anyway
                            :version (:installed-version addon)
                            :game-track game-track})
        nfo-file (nfo/nfo-path install-dir (:dirname addon))]
    (if (s/valid? ::sp/nfo-input-minimum addon)
      (nfo/upgrade-nfo install-dir addon)
      (do
        (warn (format "failed to upgrade file, removing: %s" nfo-file))
        (nfo/rm-nfo nfo-file))))
  nil)

(defn-spec upgrade-nfo-files nil?
  "upgrade the nfo files for all addons in the selected addon-dir.
  new data may have been introduced since addon was installed"
  []
  (let [install-dir (selected-addon-dir)
        has-nfo-file? (partial nfo/has-nfo-file? install-dir)
        has-valid-nfo-file? (partial nfo/has-valid-nfo-file? install-dir)
        upgrade-nfo (partial -upgrade-nfo install-dir)]
    (->> (get-state)
         :installed-addon-list
         (filter has-nfo-file?) ;; only upgrade addons that have nfo files
         (remove has-valid-nfo-file?) ;; skip good nfo files
         (mapv upgrade-nfo)))
  nil)

;; 

(defn refresh
  [& _]
  (profile
   ;; enable profiling when log level is 'debug' and we're not testing
   {:when (logging/debug-mode?)}

   ;; todo: why do we need to download and rebuild the database on each refresh?
   ;; downloads the big long list of addon information stored on github
   (download-current-catalogue)

   ;; parse toc files in install-dir. do this first so we see *something* while catalogue downloads (next)
   (load-installed-addons)

   ;; creates a 'database' (empty list of addons available to install)
   (db-init)

   ;; load the contents of the catalogue into the database
   (p :p2/db (db-load-catalogue))

   ;; match installed addons to those in catalogue
   (match-installed-addons-with-catalogue)

   ;; for those addons that have matches, download their details
   (check-for-updates)

   ;; 2019-06-30, travis is failing with 403: Forbidden. Moved to gui init
   ;;(latest-strongbox-release) ;; check for updates after everything else is done 

   ;; otherwise nfo data is only updated when an addon is installed or updated
   (upgrade-nfo-files)

   ;; seems like a good place to preserve the etag-db
   (save-settings)
   nil))

(defn-spec -install-update-these nil?
  [updateable-toc-addons (s/coll-of ::sp/addon-or-toc-addon)]
  (doseq [toc-addon updateable-toc-addons]
    (install-addon toc-addon (selected-addon-dir))))

(defn -updateable?
  [rows]
  (filterv :update? rows))

(defn -re-installable?
  "an addon can only be re-installed if it's been matched to an addon in the catalogue and a release available to download"
  [rows]
  (filterv expanded? rows))

(defn re-install-selected
  []
  (-> (get-state) :selected-installed -re-installable? -install-update-these)
  (refresh))

(defn re-install-all
  []
  (-> (get-state) :installed-addon-list -re-installable? -install-update-these)
  (refresh))

(defn install-update-selected
  []
  (-> (get-state) :selected-installed -updateable? -install-update-these)
  (refresh))

(defn-spec install-update-all nil?
  []
  (-> (get-state) :installed-addon-list -updateable? -install-update-these)
  (refresh))

(defn -remove-addon
  [addon-dirname]
  (let [addon-dir (selected-addon-dir)
        addon-path (fs/file addon-dir addon-dirname) ;; todo: perhaps this (addon-dir (base-name addon-dirname)) is safer
        addon-path (-> addon-path fs/absolute fs/normalized)]
    ;; if after resolving the given addon dir it's still within the install-dir, remove it
    (if (and
         (fs/directory? addon-path)
         (clojure.string/starts-with? addon-path addon-dir)) ;; don't delete anything outside of install dir!
      (do
        (fs/delete-dir addon-path)
        (warn (format "removed '%s'" addon-path))
        nil)

      (error (format "directory '%s' is outside the current installation dir of '%s', not removing" addon-path addon-dir)))))

(defn-spec remove-addon nil?
  "removes the given addon. if addon is part of a group, all addons in group are removed"
  [toc ::sp/toc]
  (if (contains? toc :group-addons)
    (doseq [subtoc (:group-addons toc)]
      (-remove-addon (:dirname subtoc))) ;; top-level toc is contained in the :group-addons list
    (-remove-addon (:dirname toc))))

(defn-spec remove-many-addons nil?
  [toc-list ::sp/toc-list]
  (doseq [toc toc-list]
    (remove-addon toc))
  (refresh))

(defn-spec remove-selected nil?
  []
  (-> (get-state) :selected-installed vec remove-many-addons)
  nil)

;; installing addons from strings

(defn-spec add+install-user-addon! (s/or :ok ::sp/addon, :less-ok ::sp/addon-summary, :failed nil?)
  "convenience. parses string, adds to user catalogue, installs addon then reloads database.
  relies on UI to call refresh (or not)"
  [addon-url string?]
  (binding [http/*cache* (cache)]
    (if-let* [addon-summary (catalogue/parse-user-string addon-url)
              ;; game track doesn't matter when adding it to the user catalogue ...
              addon (or
                     (catalogue/expand-summary addon-summary :retail)
                     (catalogue/expand-summary addon-summary :classic))
              test-only? true
              _ (install-addon-guard addon (selected-addon-dir) test-only?)]

             ;; ... but does matter when installing it in to the current addon directory
             (let [addon (expand-summary-wrapper addon-summary)]

               (add-user-addon! addon-summary)

               (when addon
                 (install-addon addon (selected-addon-dir))
                 (refresh)
                 addon)

               ;; failed to expand summary, probably because of selected game track.
               ;; gui depends on difference between an addon and addon summary to know
               ;; what error message to display.
               (or addon addon-summary))

             ;; failed to parse url, or expand summary, or trial installation
             nil)))

;; init

(defn watch-for-addon-dir-change
  "when the addon directory changes, the list of installed addons should be re-read"
  []
  (let [state-atm state
        reset-state-fn (fn [state]
                         ;; TODO: revisit this
                         ;; remove :cfg because it's controlled by user
                         (let [default-state (dissoc state :cfg)]
                           (swap! state-atm merge default-state)
                           (refresh)))]
    (state-bind [:cfg :selected-addon-dir] reset-state-fn)))

(defn watch-for-catalogue-change
  "when the catalogue changes, the list of available addons should be re-read"
  []
  (state-bind [:cfg :selected-catalogue] (fn [_] (refresh))))

(defn-spec set-paths! nil?
  []
  (swap! state assoc :paths (generate-path-map))
  nil)

(defn-spec detect-repl! nil?
  "if we're working from the REPL, we don't want the gui closing the session"
  []
  (swap! state assoc :in-repl? (utils/in-repl?))
  nil)

(defn -start
  []
  (alter-var-root #'state (constantly (atom -state-template))))

(defn start
  [& [cli-opts]]
  (-start)
  (info "starting app")
  (set-paths!)
  (detect-repl!)
  (init-dirs)
  (prune-http-cache!) ;; 2020-04: used to be part of init-dirs
  (load-settings! cli-opts)
  (watch-for-addon-dir-change)
  (watch-for-catalogue-change)

  state)

(defn stop
  [state]
  (info "stopping app")
  ;; traverse cleanup list and call them
  (doseq [f (:cleanup @state)]
    (debug "calling" f)
    (f))
  (reset! state nil))

;;

(st/instrument)
