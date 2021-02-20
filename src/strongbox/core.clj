(ns strongbox.core
  (:require
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? ends-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [envvar.core :refer [env]]
   [strongbox
    [addon :as addon]
    [db :as db]
    [config :as config]
    [zip :as zip]
    [http :as http]
    [logging :as logging]
    [utils :as utils :refer [join nav-map nav-map-fn delete-many-files! static-slurp expand-path if-let*]]
    [catalogue :as catalogue]
    [specs :as sp]]))

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

        ;; for migration tasks
        old-config-dir (clojure.string/replace config-dir "strongbox" "wowman")
        old-data-dir (clojure.string/replace data-dir "strongbox" "wowman")

        log-dir (join data-dir "logs")

        ;; ensure path ends with `-file` or `-dir` or `-url`.
        ;; see `init-dirs`.
        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :catalogue-dir data-dir

                  ;; /home/$you/.local/share/strongbox/profile-data
                  :profile-data-dir (join data-dir "profile-data")

                  ;; /home/$you/.local/share/strongbox/logs
                  :log-data-dir log-dir
                  :log-file (join log-dir "debug.log")

                  ;; /home/$you/.local/share/strongbox/cache
                  :cache-dir (join data-dir "cache")

                  ;; /home/$you/.config/strongbox/config.json
                  :cfg-file (join config-dir "config.json")

                  ;; /home/$you/.config/wowman/config.json
                  :old-cfg-file (join old-config-dir "config.json")

                  ;; /home/$you/.local/share/strongbox/etag-db.json
                  :etag-db-file (join data-dir "etag-db.json")

                  ;; 2020-05: moved to config dir
                  ;; /home/$you/.config/strongbox/user-catalogue.json
                  :user-catalogue-file (join config-dir "user-catalogue.json")

                  ;; /home/$you/.local/share/wowman/user-catalog.json
                  :old-user-catalogue-file (join old-data-dir "user-catalog.json")}]
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
   ;;:catalogue-source-list [] ;; moved to config.clj and [:cfg :catalogue-location-list]

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc file data
   ;; ... then updated with data from catalogue
   ;; ... then updated again with live data from curseforge
   ;; see specs/toc-addon
   :installed-addon-list nil

   :etag-db {}

   ;; the list of addons from the catalogue
   :db nil

   ;; a map of paths whose location may vary according to the cwd and envvars.
   :paths nil

   ;; ui

   ;; jfx ui showing?
   :gui-showing? false

   ;; addons in an unsteady state (data being updated, addon being installed, etc)
   ;; allows a UI to watch and update with progress
   :unsteady-addon-list #{}

   ;; a sublist of merged toc+addon that are selected
   :selected-addon-list []

   :search {:term nil
            :page 0
            :results []
            :selected-result-list []
            :results-per-page 60}})

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

(defn-spec add-cleanup-fn nil?
  "adds a function to a list of functions that are called without arguments when the application is stopped"
  [f fn?]
  (swap! state update-in [:cleanup] conj f)
  nil)

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
    (add-cleanup-fn rmwatch)
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

;; see also: strongbox.ui.cli/set-addon-dir! 
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
  "returns the currently selected addon directory or nil if no directories exist to select from"
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
  "returns the addon-dir map for the given `addon-dir`, if it exists in the map.
  when called without args, returns the addon-dir map for the currently selected addon-dir."
  ([]
   (when-let [addon-dir (selected-addon-dir)]
     (addon-dir-map addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [addon-dir-list (get-state :cfg :addon-dir-list)]
     (when-not (empty? addon-dir-list)
       (first (filter #(= addon-dir (:addon-dir %)) addon-dir-list))))))

(defn-spec set-game-track! nil?
  "changes the game track (retail or classic) for the given `addon-dir`.
  when called without args, changes the game track on the currently selected addon-dir"
  ([game-track :addon-dir/game-track]
   (when-let [addon-dir (selected-addon-dir)]
     (set-game-track! game-track addon-dir)))
  ([game-track :addon-dir/game-track, addon-dir ::sp/addon-dir]
   (let [tform (fn [addon-dir-map]
                 (if (= addon-dir (:addon-dir addon-dir-map))
                   (assoc addon-dir-map :game-track game-track)
                   addon-dir-map))
         new-addon-dir-map-list (mapv tform (get-state :cfg :addon-dir-list))]
     (swap! state update-in [:cfg] assoc :addon-dir-list new-addon-dir-map-list)
     nil)))

(defn-spec get-game-track (s/or :ok :addon-dir/game-track, :missing nil?)
  "returns the game track for the given `addon-dir` or the currently selected addon-dir if no `addon-dir` given"
  ([]
   (get-game-track (selected-addon-dir)))
  ([addon-dir (s/nilable ::sp/addon-dir)]
   (when addon-dir
     (-> addon-dir addon-dir-map :game-track))))

(defn-spec get-lenient-game-track ::sp/lenient-game-track
  "returns the lenient/compound version of the currently selected game track. 
  if `:retail` then `:retail-classic`, etc"
  []
  (case (get-game-track)
    :classic-retail :classic-retail
    :classic :classic-retail
    :retail-classic))

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
        (logging/add-file-appender! (paths :log-file))
        (info "writing logs to:" (paths :log-file)))))
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
    (change-log-level! (or (:verbosity cli-opts) logging/default-log-level))
    (when (contains? cli-opts :profile?)
      (swap! state assoc :profile? (:profile? cli-opts)))
    (when (contains? cli-opts :spec?)
      (utils/instrument (:spec? cli-opts))))
  nil)

;; utils

(defn-spec expanded? boolean?
  "returns true if an addon has found further details online"
  [addon map?]
  ;; nice and quick but essentially validating addon against `:addon/installable`
  (some? (:download-url addon)))

(defn start-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addon-list] clojure.set/union #{(:name addon)}))

(defn stop-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addon-list] clojure.set/difference #{(:name addon)}))

(defn-spec unsteady? boolean?
  "returns `true` if given `addon` is being updated"
  [addon-name ::sp/name]
  (utils/in? addon-name (get-state :unsteady-addon-list)))

(defn affects-addon-wrapper
  [wrapped-fn]
  (fn [addon & args]
    (try
      (start-affecting-addon addon)
      (apply wrapped-fn addon args)
      (finally
        (stop-affecting-addon addon)))))

;; downloading and installing and updating

(defn-spec download-addon (s/or :ok ::sp/archive-file, :http-error :http/error, :error nil?)
  [addon :addon/installable, download-dir ::sp/writeable-dir]
  (info (format "downloading '%s' version '%s'" (:label addon) (:version addon)))
  (when (expanded? addon)
    (let [output-fname (addon/downloaded-addon-fname (:name addon) (:version addon)) ;; addonname--1-2-3.zip
          output-path (join (fs/absolute download-dir) output-fname)] ;; /path/to/installed/addons/addonname--1.2.3.zip
      (binding [http/*cache* (cache)]
        (http/download-file (:download-url addon) output-path)))))

;; don't do this. `download-addon` is wrapped by `install-addon` that is already affecting the addon
;;(def download-addon
;;  (affects-addon-wrapper download-addon))

(defn-spec install-addon-guard (s/or :ok (s/coll-of ::sp/extant-file), :passed-tests true?, :error nil?)
  "downloads an addon and installs it. 
  handles http and non-http errors, bad zip files, bad addons, bad directories."
  ([addon :addon/installable]
   (install-addon-guard addon (selected-addon-dir)))
  ([addon :addon/installable, install-dir ::sp/extant-dir]
   (install-addon-guard addon install-dir false))
  ([addon :addon/installable, install-dir ::sp/extant-dir, test-only? boolean?]
   (cond
     ;; do some pre-installation checks
     (:ignore? addon) (error "refusing to install addon, addon is being ignored:" (:name addon))
     (not (fs/writeable? install-dir)) (error "refusing to install addon, directory not writeable:" install-dir)

     :else ;; attempt downloading and installing addon

     (let [;; todo: if -testing-zipfile, move zipfile into download dir
           ;; this will help the zipfile pruning tests
           downloaded-file (or (:-testing-zipfile addon) ;; don't download, install from this file (testing only right now)
                               (download-addon addon install-dir))
           bad-zipfile-msg (format "failed to read zip file '%s', could not install %s" downloaded-file (:name addon))
           bad-addon-msg (format "refusing to install '%s'. It contains top-level files or top-level directories missing .toc files."  (:name addon))]
       (cond
         (map? downloaded-file) (error "failed to download addon, could not install" (:name addon))

         (nil? downloaded-file) (error "non-http error downloading addon, could not install" (:name addon)) ;; I dunno. /shrug

         (not (zip/valid-zip-file? downloaded-file))
         (do
           (error bad-zipfile-msg)
           (fs/delete downloaded-file)
           (warn "removed bad zip file" downloaded-file))

         (not (zip/valid-addon-zip-file? downloaded-file))
         (do
           (error bad-addon-msg)
           (fs/delete downloaded-file) ;; I could be more lenient
           (warn "removed bad addon" downloaded-file))

         (not (s/valid? ::sp/writeable-dir install-dir))
         (error (format "addon directory is not writable: %s" install-dir))

         (addon/overwrites-ignored? downloaded-file (get-state :installed-addon-list))
         (error "refusing to install addon that will overwrite an ignored addon")

         (addon/overwrites-pinned? downloaded-file (get-state :installed-addon-list))
         (error "refusing to install addon that will overwrite a pinned addon")

         test-only? true ;; addon was successfully downloaded and verified as being sound

         :else (let [result (addon/install-addon addon install-dir downloaded-file)]
                 (addon/post-install addon install-dir (get-state :cfg :preferences :addon-zips-to-keep))
                 result))))))

(def install-addon
  (affects-addon-wrapper install-addon-guard))

(defn update-installed-addon-list!
  [installed-addon-list]
  (let [asc compare
        installed-addon-list (sort-by :name asc installed-addon-list)]
    (swap! state assoc :installed-addon-list installed-addon-list)
    nil))

(defn-spec load-installed-addons nil?
  "guard function. offloads the hard work to `-load-installed-addons` then updates application state"
  []
  (if-let [addon-dir (selected-addon-dir)]
    (let [addon-list (addon/load-installed-addons addon-dir)]
      (info "loading installed addons:" addon-dir)
      (update-installed-addon-list! addon-list))

    ;; otherwise, ensure list of installed addons is cleared
    (update-installed-addon-list! [])))

;;
;; catalogue handling
;;

(defn-spec get-catalogue-location (s/or :ok :catalogue/location, :not-found nil?)
  ([]
   (get-catalogue-location (get-state :cfg :selected-catalogue)))
  ([catalogue-name keyword?]
   (->> (get-state :cfg :catalogue-location-list) (filter #(= catalogue-name (:name %))) first)))

(defn-spec current-catalogue (s/or :ok :catalogue/location, :no-catalogues nil?)
  "returns the currently selected catalogue or the first catalogue it can find.
  returns `nil` if no catalogues available to choose from."
  []
  (if-let* [;; there may be nothing selected
            catalogue (get-catalogue-location (get-state :cfg :selected-catalogue))
            ;; there may be no default catalogue available
            default-catalogue (get-catalogue-location (-> (get-state :cfg :catalogue-location-list) first :name))]
           (or catalogue default-catalogue)
           nil))

(defn-spec set-catalogue-location! nil?
  [catalogue-name keyword?]
  (if-let [catalogue (get-catalogue-location catalogue-name)]
    (swap! state assoc-in [:cfg :selected-catalogue] (:name catalogue))
    (warn "catalogue not found" catalogue-name))
  nil)

(defn-spec catalogue-local-path ::sp/file
  "given a catalogue-location, returns the local path to the catalogue."
  [catalogue-location :catalogue/location]
  ;; {:name :full ...} => "/path/to/catalogue/dir/full-catalogue.json"
  (utils/join (paths :catalogue-dir) (-> catalogue-location :name name (str "-catalogue.json"))))

(defn-spec find-catalogue-local-path (s/or :ok ::sp/file, :not-found nil?)
  "convenience wrapper around `catalogue-local-path`"
  [catalogue-name keyword?]
  (some-> catalogue-name get-catalogue-location catalogue-local-path))

(defn-spec download-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads catalogue to expected location, nothing more"
  [catalogue-location :catalogue/location]
  (binding [http/*cache* (cache)]
    (let [remote-catalogue (:source catalogue-location)
          local-catalogue (catalogue-local-path catalogue-location)
          message (format "downloading catalogue: %s" (name (:name catalogue-location)))
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

(defn-spec moosh-addons :addon/toc+summary+match
  "merges the data from an installed addon with it's match in the catalogue"
  [installed-addon :addon/toc, db-catalogue-addon :addon/summary]
  (let [;; nil fields are removed from the catalogue item because they might override good values in the .toc or .nfo
        db-catalogue-addon (utils/drop-nils db-catalogue-addon [:description])]
    ;; merges left->right. catalogue-addon overwrites installed-addon, ':matched' overwrites catalogue-addon, etc
    (merge installed-addon db-catalogue-addon {:matched? true})))

;;

(defn-spec get-create-user-catalogue :catalogue/catalogue
  "returns the contents of the user catalogue, creating one if necessary"
  []
  (let [user-catalogue-path (paths :user-catalogue-file)]
    (catalogue/read-catalogue
     (if (fs/exists? user-catalogue-path)
       user-catalogue-path
       (catalogue/write-empty-catalogue! user-catalogue-path)))))

(defn-spec add-user-addon! nil?
  "adds one or many addons to the user catalogue"
  [addon-summary (s/or :single :addon/summary, :many :addon/summary-list)]
  (let [addon-summary-list (if (sequential? addon-summary)
                             addon-summary
                             [addon-summary])
        user-catalogue-path (paths :user-catalogue-file)
        user-catalogue (get-create-user-catalogue)
        tmp-catalogue (catalogue/new-catalogue addon-summary-list)
        new-user-catalogue (catalogue/merge-catalogues user-catalogue tmp-catalogue)]
    (catalogue/write-catalogue new-user-catalogue user-catalogue-path))
  nil)

;; catalogue db handling

(defn query-db
  "uses keywords to do predefined queries. see `db/stored-query`"
  [query-kw & [arg-list]]
  (when-let [db (get-state :db)]
    (db/stored-query db query-kw arg-list)))

(defn db-catalogue-loaded?
  "returns `true` if the database has a catalogue loaded.
  An empty database `[]` is distinct from an unloaded database (`nil`).
  A database may be empty only if the `addon-summary-list` key of a catalogue is empty.
  A database may be `nil` if it simply hasn't been loaded yet or we attempted to load it and it failed to load.
  A database may fail to load if it simply isn't there, can't be downloaded or, once downloaded, the data is invalid."
  []
  (-> (get-state :db) nil? not))

(defn db-search
  "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
  [search-term]
  (let [args [(utils/nilable search-term) (get-state :search :results-per-page)]]
    (query-db :search args)))

(defn-spec load-current-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "merges the currently selected catalogue with the user-catalogue and returns the definitive list of addons 
  available to install. Handles malformed catalogue data by re-downloading catalogue."
  []
  (when-let [catalogue-location (current-catalogue)]
    (let [catalogue-path (catalogue-local-path catalogue-location)
          _ (info "loading catalogue:" (name (:name catalogue-location)))

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
      (-> final-catalogue :addon-summary-list count (str " addons in final catalogue") info)
      final-catalogue)))

(defn-spec db-load-catalogue nil?
  "loads the currently selected catalogue into the database, but only if we have a catalogue and it hasn't already 
  been loaded. Handles bad/invalid catalogues and merging the user catalogue"
  []
  (if (and (not (db-catalogue-loaded?))
           (current-catalogue))
    (let [final-catalogue (p :p2/db:catalogue (load-current-catalogue))]
      (when-not (empty? final-catalogue)
        (p :p2/db:load
           (swap! state assoc :db
                  (db/put-many [] (:addon-summary-list final-catalogue))))))
    (debug "skipping db load. already loaded or no catalogue selected."))
  nil)

(defn-spec -match-installed-addons-with-catalogue :addon/installed-list
  "compare the list of addons installed with the database of known addons and try to match the two up.
  when a match is found (see `db/-db-match-installed-addons-with-catalogue`), merge it into the addon data."
  [database :addon/summary-list, installed-addon-list :addon/toc-list]
  (info (format "matching %s addons to catalogue" (count installed-addon-list)))
  (let [match-results (db/-db-match-installed-addons-with-catalogue database installed-addon-list)
        [matched unmatched] (utils/split-filter :matched? match-results)

        ;; for those that *did* match, merge the installed addon data together with the catalogue data
        matched (mapv #(moosh-addons (:installed-addon %) (:catalogue-match %)) matched)
        ;; and then make them a single list of addons again
        expanded-installed-addon-list (into matched unmatched)

        ;; todo: metrics gathering is good, but this is a little adhoc. shift into parent wrapper somehow.
        ;; some metrics we'll emit for the user.
        [num-installed num-matched] [(count installed-addon-list) (count matched)]
        ;; we don't match ignored addons, we shouldn't report we couldn't find them either
        unmatched-names (->> unmatched (remove :ignore?) (map :name) set)]

    (when-not (= num-installed num-matched)
      (info "num installed" num-installed ", num matched" num-matched))

    (when-not (empty? unmatched-names)
      (warn "you need to manually search for them and then re-install them")
      (warn (format "failed to find %s addons in the '%s' catalogue: %s"
                    (count unmatched-names)
                    (name (get-state :cfg :selected-catalogue))
                    (clojure.string/join ", " unmatched-names))))

    expanded-installed-addon-list))

(defn-spec match-installed-addons-with-catalogue nil?
  "compare the list of addons installed with the database of known addons, match the two up, merge
  the two together and update the list of installed addons.
  Skipped when no catalogue loaded or no addon directory selected."
  []
  (when (and (db-catalogue-loaded?)
             (selected-addon-dir))
    (update-installed-addon-list!
     (-match-installed-addons-with-catalogue (get-state :db) (get-state :installed-addon-list)))))


;;


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

(defn-spec check-for-update :addon/toc
  "Returns given `addon` with source updates, if any, and sets an `update?` property if a different version is available.
  If addon is pinned to a specific version, `update?` will only be true if pinned version is different from installed version."
  [addon (s/or :unmatched :addon/toc
               :matched :addon/toc+summary+match)]
  (let [expanded-addon (when (:matched? addon)
                         (expand-summary-wrapper addon))
        addon (or expanded-addon addon)] ;; expanded addon may still be nil
    (assoc addon :update? (addon/updateable? addon))))

(defn-spec check-for-updates nil?
  "downloads full details for all installed addons that can be found in summary list"
  []
  (when (selected-addon-dir)
    (info "checking for updates")
    (let [improved-addon-list (mapv check-for-update (get-state :installed-addon-list))
          num-matched (->> improved-addon-list (filterv :matched?) count)
          num-updates (->> improved-addon-list (filterv :update?) count)]
      (update-installed-addon-list! improved-addon-list)
      (info (format "%s addons checked, %s updates available" num-matched num-updates)))))

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

(defn-spec delete-wowman-json-files! nil?
  []
  (delete-many-files! (selected-addon-dir) #"\.wowman\.json$" ".wowman.json"))

(defn-spec delete-wowmatrix-dat-files! nil?
  []
  (delete-many-files! (selected-addon-dir) #"(?i)WowMatrix.dat$" "WowMatrix.dat"))

(defn-spec delete-catalogue-files! nil?
  []
  ;; the user catalogue is deliberately ignored here.
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
          url "https://api.github.com/repos/ogri-la/strongbox/releases/latest"
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
  [addon (s/or :catalogue :addon/summary, :installed :addon/toc)]
  (let [stub (select-keys addon [:name :source :source-id])
        game-track (when-let [game-track (:installed-game-track addon)]
                     {:game-track game-track})]
    (merge stub game-track)))

(defn-spec export-installed-addon-list ::sp/export-record-list
  "derives an 'export-record' from a list of either addon summaries from a catalogue or .toc file data from installed addons"
  [addon-list (s/or :catalogue :addon/summary-list, :installed :addon/toc-list)]
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
  [catalogue :catalogue/catalogue]
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
  (let [matching-addon-list (-import-addon-list-v1 addon-list)]
    (run! install-addon matching-addon-list)))

;; v2 uses the same mechanism to match addons as the rest of the app does
(defn import-addon-list-v2
  "handles exports with full information (name and source and source-id) from strongbox >=0.10.1.
  this style of export allows us to make fast and unambiguous matches against the database."
  [addon-list]
  (let [;; in order to get these bare maps playing nicely with the rest of the system we need to
        ;; gussy them up a bit so it looks like an `::sp/installed-addon-summary`
        padding {:label ""
                 :description ""
                 ;; 2020-06: dirname must be a non-empty string
                 :dirname addon/dummy-dirname
                 :interface-version 0
                 :installed-version "0"}
        addon-list (map #(merge padding %) addon-list)

        ;; match each of these padded addon maps to entries in the catalogue database
        ;; afterwards this will call `update-installed-addon-list!` that will trigger a refresh in the gui
        matching-addon-list (-match-installed-addons-with-catalogue (get-state :db) addon-list)

        ;; this is what v1 does, but it's hidden away in `expand-summary-wrapper`
        ;;default-game-track (get-game-track)

        ;; when no game-track is present in the export record, use the more lenient
        ;; version of the currently selected game track.
        ;; it's better to have an addon installed with the incorrect game track then missing addons.
        default-game-track (get-lenient-game-track)]

    (binding [http/*cache* (cache)]
      (doseq [addon matching-addon-list
              :let [game-track (get addon :game-track default-game-track)]]
        (when-let [expanded-addon (catalogue/expand-summary addon game-track)]
          (install-addon expanded-addon))))))

(defn-spec import-exported-file nil?
  "imports a file at given `path` created with the export function.
  supports exports created with older versions of the app using partial data."
  [path ::sp/extant-file]
  (info "importing file:" path)
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
      (debug "v1 import (only partial data available)" partial-data)
      (import-addon-list-v1 partial-data))
    (when-not (empty? full-data)
      (debug "v2 import")
      (import-addon-list-v2 full-data))))

;;

(defn-spec refresh nil?
  []
  (profile
   {:when (get-state :profile?)}

   ;; parse toc files in install-dir. do this first so we see *something* while catalogue downloads (next)
   (load-installed-addons)

   ;; downloads the big long list of addon information stored on github
   (download-current-catalogue)

   ;; load the contents of the catalogue into the database
   (p :p2/db (db-load-catalogue))

   ;; match installed addons to those in catalogue
   (match-installed-addons-with-catalogue)

   ;; for those addons that have matches, download their details
   (check-for-updates)

   ;; 2019-06-30, travis is failing with 403: Forbidden. Moved to gui init
   ;;(latest-strongbox-release) ;; check for updates after everything else is done 

   ;; seems like a good place to preserve the etag-db
   (save-settings)

   nil))

(defn-spec remove-many-addons nil?
  "deletes each of the addons in the given `toc-list` and then calls `refresh`"
  [installed-addon-list :addon/toc-list]
  (let [addon-dir (selected-addon-dir)]
    (doseq [installed-addon installed-addon-list]
      (addon/remove-addon addon-dir installed-addon))
    (refresh)))

(defn-spec remove-addon nil?
  "removes given installed addon"
  [installed-addon :addon/installed]
  (addon/remove-addon (selected-addon-dir) installed-addon)
  (refresh))

;;

(defn-spec db-reload-catalogue nil?
  "unloads the database from state then calls `refresh` which will trigger a rebuild"
  []
  (swap! state assoc :db nil)
  (refresh))

;; installing addons from strings

(defn-spec add+install-user-addon! (s/or :ok :addon/addon, :less-ok :addon/summary, :failed nil?)
  "convenience. parses string, adds to user catalogue, installs addon then reloads database.
  relies on UI to call refresh (or not)"
  [addon-url string?]
  (binding [http/*cache* (cache)]
    (if-let* [addon-summary (catalogue/parse-user-string addon-url)
              ;; game track doesn't matter when adding it to the user catalogue. prefer retail though.
              addon (catalogue/expand-summary addon-summary :retail-classic)
              test-only? true
              _ (install-addon-guard addon (selected-addon-dir) test-only?)]

             ;; ... but does matter when installing it in to the current addon directory
             (let [addon (expand-summary-wrapper addon-summary)]

               (add-user-addon! addon-summary)

               (when addon
                 (install-addon addon (selected-addon-dir))
                 (db-reload-catalogue)
                 addon)

               ;; failed to expand summary, probably because of selected game track.
               ;; gui depends on difference between an addon and addon summary to know
               ;; what error message to display.
               (or addon addon-summary))

             ;; failed to parse url, or expand summary, or trial installation
             nil)))

;; init

(defn-spec set-paths! nil?
  []
  (swap! state assoc :paths (generate-path-map))
  nil)

(defn-spec detect-repl! nil?
  "if we're working from the REPL, we don't want the gui closing the session"
  []
  (swap! state assoc :in-repl? (utils/in-repl?))
  nil)

(defn-spec dump-useful-log-info nil?
  "writes selected system properties to the log.
  mostly concerned with OS, Java and JavaFX versions."
  []
  (let [useful-keys ["strongbox.version"
                     "os.name"
                     "os.version"
                     "os.arch"
                     "java.runtime.name"
                     "java.vm.name"
                     "java.version"
                     "java.runtime.version"
                     "java.vendor.url"
                     "java.version.date"
                     "java.awt.graphicsenv"
                     "javafx.version"
                     "javafx.runtime.version"]
        props (System/getProperties)]
    (run! #(info (format "%s=%s" % (get props %))) useful-keys)))

;;

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
  (prune-http-cache!)
  (load-settings! cli-opts)

  state)

(defn stop
  [state]
  (info "stopping app")
  ;; traverse cleanup list and call them
  (doseq [f (:cleanup @state)]
    (debug "calling" f)
    (f))
  (when (and @state
             (logging/debug-mode?))
    (dump-useful-log-info)
    (info "wrote logs to:" (paths :log-file)))
  (reset! state nil))
