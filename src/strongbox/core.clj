(ns strongbox.core
  (:require
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? ends-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error report spy]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [envvar.core :refer [env]]
   [strongbox
    [constants :as constants]
    [addon :as addon]
    [config :as config]
    [zip :as zip]
    [http :as http]
    [logging :as logging]
    [utils :as utils :refer [join nav-map delete-many-files! expand-path if-let* message-list]]
    [catalogue :as catalogue]
    [specs :as sp]
    [github-api :as github-api]
    [gitlab-api :as gitlab-api]
    [joblib :as joblib]])
  (:import
   [org.apache.commons.compress.compressors CompressorStreamFactory CompressorException]
   [java.util.regex Pattern]))

(def default-config-dir "~/.config/strongbox")
(def default-data-dir "~/.local/share/strongbox")
(def num-concurrent-downloads (-> (Runtime/getRuntime) .availableProcessors))
(def ^:dynamic *testing?* false)
(def default-game-track-strictness true) ;; strict

(def static-catalogue
  "a bz2 compressed copy of the full catalogue used when the remote catalogue is unavailable or corrupt.
  from this we can do per-host filtering as well as shortening to generate the other catalogues."
  (utils/compile-time-slurp "full-catalogue.json"))

(defn generate-path-map
  "filesystem paths whose location may vary based on the current working directory, environment variables, etc.
  this map of paths is generated during `start`, checked during `init-dirs` and then fixed in application state under `:paths`.
  during testing, ensure the correct environment variables and cwd are set prior to init for proper isolation."
  []
  (let [strongbox-suffix (fn [path]
                           (if-not (ends-with? path "/strongbox")
                             (join path "strongbox")
                             path))

        ;; XDG_DATA_HOME=/foo/bar => /foo/bar/strongbox
        ;; XDG_CONFIG_HOME=/baz/bup => /baz/bup/strongbox
        ;; - https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        ;; ignoring XDG_CONFIG_DIRS and XDG_DATA_DIRS for now

        config-dir (-> @env :xdg-config-home utils/nilable (or default-config-dir) expand-path strongbox-suffix)
        data-dir (-> @env :xdg-data-home utils/nilable (or default-data-dir) expand-path strongbox-suffix)

        log-dir (join data-dir "logs")

        ;; ensure path ends with `-file` or `-dir` or `-url`.
        ;; see `init-dirs`.
        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :catalogue-dir data-dir

                  ;; /home/$you/.local/share/strongbox/logs
                  :log-data-dir log-dir
                  :log-file (join log-dir "debug.log")

                  ;; /home/$you/.local/share/strongbox/cache
                  :cache-dir (join data-dir "cache")

                  ;; /home/$you/.config/strongbox/config.json
                  :cfg-file (join config-dir "config.json")

                  ;; /home/$you/.local/share/strongbox/etag-db.json
                  :etag-db-file (join data-dir "etag-db.json")

                  ;; 2020-05: moved to config dir
                  ;; /home/$you/.config/strongbox/user-catalogue.json
                  :user-catalogue-file (join config-dir "user-catalogue.json")}]
    path-map))

(def -search-state-template
  {:term nil
   :filter-by {:source nil
               :tag #{}
               :tag-membership "any of" ;; "all of"
               :user-catalogue false}
   :page 0
   :results []
   :selected-result-list []
   :results-per-page 60
   :sample? true})

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

   :latest-strongbox-version nil

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc and .strongbox.json data
   ;; ... then updated with data from catalogue when a match is made
   ;; ... then updated again with live data from addon hosts.
   :installed-addon-list nil

   :etag-db {}

   ;; the list of addons from the catalogue
   :db nil

   ;; some generated stats about the db that are updated just once at load time.
   :db-stats nil

   ;; the list of addons from the user-catalogue
   :user-catalogue nil

   ;; a set of maps of `:source` and `:source-id` keys from the user-catalogue.
   ;; added as a quick lookup of favourited addons
   :user-catalogue-idx #{}

   ;; the application log so we can see and filter log lines as part of the app.
   :log-lines []

   ;; a map of paths whose location may vary according to the cwd and envvars.
   ;; see `generate-path-map`
   :paths nil

   ;; a (stateful) ordered map of running jobs.
   :job-queue (atom (joblib/make-queue))

   ;; ui

   ;; jfx ui showing?
   :gui-showing? false

   ;; log-level for the gui's dedicated notice-logger.
   ;; per-tab log levels are attached to each tab in the `:tab-list`
   :gui-log-level :info

   ;; split the gui in two horizontally with the sub-pane on the bottom.
   :gui-split-pane false

   ;; default widget for the sub-pane
   :gui-sub-pane :notice-logger

   ;; addons in an 'unsteady' state (data being updated, addon being installed, etc)
   ;; allows a UI to watch and update the gui with progress.
   ;; also allows us to pause doing a thing until an addon is removed from the list.
   :unsteady-addon-list #{}

   ;; a subset of addons that are selected in the UI
   :selected-addon-list []

   ;; dynamic tabs (not the fixed tabs)
   :tab-list []

   :search -search-state-template})

(def state (atom nil))

(defn started?
  "returns `true` if app has been started."
  []
  (some? @state))

(defn assert-started
  "throws a `RuntimeException` if app has not been started."
  []
  (when-not (started?)
    (throw (RuntimeException. "application must be `start`ed before state may be accessed."))))

(defn get-state
  "returns the state map if `path` not provided, or just the state at the given `path`."
  [& path]
  (assert-started)
  (nav-map @state path))

(defn paths
  "returns the map of paths in application if `path` not provided, or just the given `path`."
  [& path]
  (nav-map (get-state :paths) path))

(defn-spec debug-mode? boolean?
  "debug mode is when the log level has been set to `:debug` and we're *not* running tests.
  the intent is to collect as much information around a problem as possible.
  the log level may be changed through REPL usage.
  the log level may be changed by using a `--verbosity` flag at runtime.
  `main/test` and `cloverage.clj` alter the `main/*testing?*` flag while running tests and resets it afterwards."
  []
  (and (-> timbre/*config* :min-level (= :debug))
       (not *testing?*)))

;;

#_(defn-spec find-installed-addon (s/or :match :addon/source-map, :no-match nil?)
    "returns first addon from the `:installed-addon-list` matching the `:source` and `:source-id` of the given `addon`."
    [addon :addon/source-map]
    (let [keyfn (juxt :source :source-id)
          key (keyfn addon)
          addon-list (get-state :installed-addon-list)]
      (first (filter (comp #(= key %) keyfn) addon-list))))

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
  "getter and setter that gets bound to `http/*cache*` when caching http requests"
  []
  (if-not (started?)
    (warn "http cache disabled, app is not started")
    {;;:etag-db (get-state :etag-db) ;; don't do this. encourages stale reads of the etag-db
     :set-etag set-etag
     :get-etag #(get-state :etag-db %) ;; do this instead
     :cache-dir (paths :cache-dir)}))

#_(defn simple-cache
    "a simplistic getter and setter bound to `http/*cache*` when caching http requests.
  ignores etags and data to be cached is written directly to the temp directory.
  can be used outside of an initialised app."
    []
    {:set-etag (constantly nil)
     :get-etag (constantly nil)
     :cache-dir (fs/tmpdir)})

(defn-spec add-cleanup-fn nil?
  "adds function `f` to a list of functions that are called without arguments when the application is stopped."
  [f fn?]
  (swap! state update-in [:cleanup] conj f)
  nil)

(defn-spec state-bind nil?
  "executes given `callback` when value at `path` in state map changes,
  unless both old and new values are identical."
  [path ::sp/list-of-keywords, callback fn?]
  (let [has-changed (fn [old-state new-state]
                      (not= (get-in old-state path)
                            (get-in new-state path)))
        wid (keyword (gensym callback)) ;; :foo.bar$baz@123456789
        rmwatch #(remove-watch state wid)]

    (add-watch state wid
               (fn [_ _ old-state new-state] ;; key, atom, old-state, new-state
                 (when (has-changed old-state new-state)
                   ;; ... now avoid infinite recursion
                   (when (and (= :debug (:min-level timbre/*config*))
                              (not (empty? path)))
                     ;; this would cause the gui to receive a :debug of "path [] triggered a ..."
                     ;; this would update the :log-lines in the state
                     ;; this would cause the gui to receive a :debug of "path [] triggered a ..." ...
                     ;;(debug (format "path %s triggered %s" path wid)))
                     ;; instead, if :debug is the log level, print this to stdout
                     (println (format "path %s triggered %s" path wid)))
                   (try
                     (callback new-state)
                     (catch Exception e
                       ;; todo: potential for infinite recursion here too?
                       ;; can the stacktrace be formatted and printed to stdout instead?
                       (error e "error caught in watch! the callback *must* be catching these or the thread dies silently:" path))))))
    (add-cleanup-fn rmwatch)
    nil))

;; addon dirs

(defn-spec selected-addon-dir (s/or :ok ::sp/addon-dir, :no-selection nil?)
  "returns the currently selected addon directory or `nil` if no directories exist to select from."
  ([]
   (selected-addon-dir (get-state)))
  ([state map?]
   (-> state :cfg :selected-addon-dir)))

(defn-spec addon-dir-exists? boolean?
  [addon-dir ::sp/addon-dir, addon-dir-list ::sp/addon-dir-list]
  (->> addon-dir-list (map :addon-dir) (some #{addon-dir}) nil? not))

(defn-spec add-addon-dir! nil?
  "creates and adds an addon directory entry in the user's `:addon-dir-list`, if it doesn't already exist."
  [addon-dir ::sp/addon-dir, game-track ::sp/game-track, strict? ::sp/strict?]
  (let [stub {:addon-dir addon-dir :game-track game-track :strict? strict?}]
    (when-not (addon-dir-exists? addon-dir (get-state :cfg :addon-dir-list))
      (swap! state update-in [:cfg :addon-dir-list] conj stub)))
  nil)

(defn-spec set-addon-dir! nil?
  "convenience. adds a new addon directory to the list of addon directories (if it doesn't already exist) and marks it as selected."
  [addon-dir ::sp/addon-dir]
  (let [addon-dir (-> addon-dir fs/absolute fs/normalized str)
        ;; if '_classic_' is in given path, use the classic game track
        default-game-track (if (clojure.string/index-of addon-dir "_classic_") :classic :retail)]
    (dosync ;; necessary? makes me feel better
     (add-addon-dir! addon-dir default-game-track default-game-track-strictness)
     ;; todo: break this down into two actions? adding a new directory + selecting the new one.
     ;; selecting the new addon dir will clear/reset any state like the selected addon list and tab list etc.
     (swap! state assoc :selected-addon-list [])
     (swap! state assoc-in [:cfg :selected-addon-dir] addon-dir)
     (swap! state assoc :tab-list []))) ;; todo: consider adding a watch for this as well
  nil)

(defn-spec remove-addon-dir! nil?
  "removes the directory from user configuration, does not alter the directory or it's contents at all"
  ([]
   (when-let [addon-dir (selected-addon-dir)]
     (remove-addon-dir! addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [matching #(= addon-dir (:addon-dir %))
         new-addon-dir-list (->> (get-state :cfg :addon-dir-list) (remove matching) vec)]
     ;; todo: again, two distinct actions here. removing an addon directory and selecting the new one.
     ;; selecting the new addon directory resets some state.
     (swap! state assoc-in [:cfg :addon-dir-list] new-addon-dir-list)
     ;; this may be nil if the new addon-dir-list is empty
     (swap! state assoc-in [:cfg :selected-addon-dir] (-> new-addon-dir-list first :addon-dir)))
   nil))

#_(defn available-addon-dirs
    []
    (mapv :addon-dir (get-state :cfg :addon-dir-list)))

(defn-spec addon-dir-map (s/or :ok ::sp/addon-dir-map, :missing nil?)
  "returns the addon-dir map for the given `addon-dir` or `nil` if it doesn't exist."
  [addon-dir (s/nilable ::sp/addon-dir)]
  (let [addon-dir-list (get-state :cfg :addon-dir-list)]
    (when (and addon-dir
               (not (empty? addon-dir-list)))
      (first (filter #(= addon-dir (:addon-dir %)) addon-dir-list)))))

(defn-spec set-game-track! nil?
  "changes the game track (retail or classic) for the given `addon-dir`.
  when called without args, changes the game track on the currently selected addon-dir"
  [game-track :addon-dir/game-track, addon-dir ::sp/addon-dir]
  (let [tform (fn [addon-dir-map]
                (if (= addon-dir (:addon-dir addon-dir-map))
                  (assoc addon-dir-map :game-track game-track)
                  addon-dir-map))
        new-addon-dir-map-list (mapv tform (get-state :cfg :addon-dir-list))]
    (swap! state update-in [:cfg] assoc :addon-dir-list new-addon-dir-map-list)
    nil))

(defn-spec get-game-track (s/or :ok :addon-dir/game-track, :missing nil?)
  "convenience. returns the game track for the currently selected addon-dir.
  returns `nil` if no addon directory is selected."
  []
  (when-let [addon-dir (selected-addon-dir)]
    (-> addon-dir addon-dir-map :game-track)))

(defn-spec get-game-track-strictness (s/or :addon-dir ::sp/strict?, :no-addon-dir nil?)
  "convenience. returns the game track strictness for the currently selected addon-dir.
  returns `nil` if no addon directory is selected."
  []
  (when-let [addon-dir-map (addon-dir-map (selected-addon-dir))]
    (get addon-dir-map :strict? default-game-track-strictness)))

(defn-spec set-game-track-strictness! nil?
  "fetches the strictness level for the given `addon-dir` or the currently selected addon directory if not given."
  [new-strictness-level ::sp/strict?]
  (when-let [addon-dir (selected-addon-dir)]
    (let [addon-dir-map (addon-dir-map addon-dir)
          pos (.indexOf (get-state :cfg :addon-dir-list) addon-dir-map)]
      (when (> pos -1)
        (swap! state assoc-in [:cfg :addon-dir-list pos :strict?] new-strictness-level))))
  nil)

;; stateful logging

(defn-spec -debug-logging nil?
  "log to a file if we're in debug mode."
  [state-atm ::sp/atom]
  (when (debug-mode?)
    (if-not @state-atm
      (warn "application has not been started, no location to write log data")
      (do
        (logging/add-file-appender! (paths :log-file))
        (info "writing logs to:" (paths :log-file))))))

(defn-spec reset-logging! nil?
  "the logging configuration in timbre may become unpredictable during testing and this resets it to what it should be."
  ([]
   (reset-logging! *testing?* state (and @state (selected-addon-dir))))

  ([testing? boolean?, state-atm ::sp/atom, install-dir (s/nilable ::sp/install-dir)]
   ;; reset logging configuration to timbre's default.
   (timbre/swap-config! timbre/default-config)

   ;; layer in our own default config
   (timbre/merge-config! logging/-default-logging-config)

   ;; layer in any runtime config
   (when-let [user-level (some-> @state-atm :cli-opts :verbosity)]
     (timbre/merge-config! {:min-level user-level}))
   (when (some-> @state-atm :env :no-color)
     (timbre/merge-config! {:output-opts {:stacktrace-fonts {}} ;; disable colours in stacktraces
                            :appenders {:println {:fn (logging/anon-println-appender {:colour-log-map {}})}}})) ;; disable coloured log lines

   ;; add a file appender if the user has set level `:debug`
   (-debug-logging state-atm)

   ;; ensure we're storing log lines in app state
   (add-cleanup-fn (logging/add-ui-appender! state-atm install-dir))

   ;; and finally, if we're running tests, drop the logging level to :debug and ensure nothing is emitting to a file
   (when testing?
     (timbre/merge-config! {:testing? true, :min-level :debug, :appenders {:spit nil}}))

   nil))

(defn-spec set-log-level! nil?
  "changes the effective log level from `logging/default-log-level` to `new-level`.
  The `:debug` log level outside of unit tests will write a log file to the `:log-file` path"
  [new-level keyword?]
  (timbre/merge-config! {:min-level new-level})
  (-debug-logging state))

;; settings

(defn-spec save-settings! nil?
  "writes user configuration to the filesystem"
  []
  ;; warning: this will preserve any once-off command line parameters as well
  ;; this might make sense within the gui but be careful about auto-saving elsewhere
  (when-let [cfg-file (paths :cfg-file)]
    (debug "saving settings to:" cfg-file)
    (utils/dump-json-file cfg-file (get-state :cfg)))
  (when-let [etag-db (paths :etag-db-file)]
    (debug "saving etag-db to:" etag-db)
    (utils/dump-json-file etag-db (get-state :etag-db)))
  nil)

(defn load-settings!
  "pulls together configuration from the fs and cli, merges it and sets application state"
  [cli-opts]
  (let [final-config (config/load-settings cli-opts (paths :cfg-file) (paths :etag-db-file))]
    (swap! state merge final-config)
    (reset-logging!)
    ;; erm, this is something I use while developing: (restart {:spec? false})
    ;; spec checking slows everything down so turning it off gives me a feel for actual performance.
    (when (contains? cli-opts :spec?)
      (utils/instrument (:spec? cli-opts))))
  nil)

;; utils

(defn-spec expanded? boolean?
  "returns `true` if given `addon` has found further details online."
  [addon map?]
  ;; nice and quick but essentially validating addon against `:addon/installable`
  (some? (:download-url addon)))

(defn-spec unsteady? boolean?
  "returns `true` if given `addon-name` or one of it's grouped addons is being updated/modified."
  [addon-name ::sp/name]
  (if @state
    (clojure.set/subset? #{addon-name} (get-state :unsteady-addon-list))
    false))

(defn-spec stop-affecting-addon nil?
  [addon map?]
  (swap! state update-in [:unsteady-addon-list] clojure.set/difference #{(:name addon)})
  nil)

(defn-spec start-affecting-addon fn?
  [addon map?]
  (if (unsteady? (:name addon))
    (constantly nil)
    (do (swap! state update-in [:unsteady-addon-list] conj (:name addon))
        (partial stop-affecting-addon addon))))

(defn affects-addon-wrapper
  [wrapped-fn]
  (fn [addon & args]
    (assert-started)
    (let [-stop-affecting-addon (start-affecting-addon addon)]
      (try
        (logging/with-addon addon
          (apply wrapped-fn addon args))
        (finally
          (-stop-affecting-addon))))))

;; downloading and installing and updating

(defn-spec download-addon (s/or :ok ::sp/archive-file, :http-error :http/error, :error nil?)
  "downloads `addon` to the given `install-dir`.
  see `download-addon-guard` for a version with checks."
  [addon :addon/installable, install-dir ::sp/writeable-dir]
  (when (expanded? addon)
    ;; "downloading 'EveryAddon' version '1.2.3'"
    (info (format "downloading '%s' version '%s'" (:label addon) (:version addon)))
    (let [output-fname (addon/downloaded-addon-fname (:name addon) (:version addon)) ;; "everyaddon--1-2-3.zip"
          output-path (join (fs/absolute install-dir) output-fname)] ;; "/path/to/addon/dir/everyaddon--1.2.3.zip"
      (binding [http/*cache* (cache)]
        (http/download-file (:download-url addon) output-path)))))

(defn-spec download-addon-guard (s/or :ok ::sp/archive-file, :error nil?)
  "downloads an addon, handling http and non-http errors, bad zip files, bad addons, bad directories."
  [addon :addon/installable, install-dir ::sp/extant-dir]
  (cond
    ;; pre-installation checks
    (:ignore? addon) (error "refusing to install addon, addon is being ignored:" (:name addon))
    (not (fs/writeable? install-dir)) (error "failed to install addon, directory not writeable:" install-dir)

    :else ;; attempt downloading and installing addon

    (let [;; todo: if -testing-zipfile, move zipfile into download dir
          ;; this will help the zipfile pruning tests
          downloaded-file (or (:-testing-zipfile addon) ;; don't download, install from this file (testing only right now)
                              (download-addon addon install-dir))]
      (cond
        (map? downloaded-file) (error "failed to download addon.")

        (nil? downloaded-file) (error "non-HTTP error downloading addon.") ;; I dunno. /shrug

        (not (zip/valid-zip-file? downloaded-file))
        (do (error "failed to read addon zip file, possibly corrupt or not a zip file.")
            (fs/delete downloaded-file)
            (warn "removed bad zip file."))

        (not (zip/valid-addon-zip-file? downloaded-file))
        (do (error "refusing to install, addon zip file contains top-level files or a top-level directory missing a .toc file.")
            (fs/delete downloaded-file)
            (warn "removed bad addon."))

        (addon/overwrites-ignored? downloaded-file (get-state :installed-addon-list))
        (error "refusing to install addon that will overwrite an ignored addon.")

        (addon/overwrites-pinned? downloaded-file (get-state :installed-addon-list))
        (error "refusing to install addon that will overwrite a pinned addon.")

        :else downloaded-file))))

(def download-addon-guard-affective
  (affects-addon-wrapper download-addon-guard))

(defn-spec install-addon (s/or :ok (s/coll-of ::sp/extant-file), :passed-tests true?, :error nil?)
  "downloads an addon and installs it, bypassing checks. see `install-addon-guard`."
  [addon :addon/installable, install-dir ::sp/extant-dir, downloaded-file (s/nilable ::sp/extant-archive-file)]
  (when downloaded-file
    (try
      (addon/install-addon addon install-dir downloaded-file)
      (finally
        (addon/post-install addon install-dir (get-state :cfg :preferences :addon-zips-to-keep))))))

(def install-addon-affective
  (affects-addon-wrapper install-addon))

(defn-spec install-addon-guard (s/or :ok (s/coll-of ::sp/extant-file), :passed-tests true?, :error nil?)
  "downloads an addon and installs it, handling http and non-http errors, bad zip files, bad addons, bad directories."
  ([addon :addon/installable]
   (install-addon-guard addon (selected-addon-dir) false))

  ([addon :addon/installable, install-dir ::sp/extant-dir]
   (install-addon-guard addon install-dir false))

  ([addon :addon/installable, install-dir ::sp/extant-dir, test-only? boolean?]
   (when-let [downloaded-file (download-addon-guard addon install-dir)]
     (if test-only?
       ;; addon was successfully downloaded and verified as being sound. stop here.
       true
       ;; else, install addon
       (install-addon addon install-dir downloaded-file)))))

(def install-addon-guard-affective
  (affects-addon-wrapper install-addon-guard))

(defn-spec update-installed-addon-list! nil?
  "replaces the list of installed addons with `installed-addon-list`"
  [installed-addon-list vector?]
  (let [asc compare
        ;; `vec` so we can use `update-in` and `assoc-in` on `:installed-addon-list`
        installed-addon-list (vec (sort-by :name asc installed-addon-list))]
    (swap! state assoc :installed-addon-list installed-addon-list)
    nil))

(defn-spec update-installed-addon! :addon/installed
  "adds or updates the given `addon`, removing any identical or grouped addons first.
  order is not preserved. returns the given `addon`."
  [addon :addon/installed]
  (let [f (fn [some-addon]
            ;; remove if group-id matches
            (or (= (:group-id addon) (:group-id some-addon))
                ;; or if the dirname matches
                (and (:dirname addon)
                     (= (:dirname addon) (:dirname some-addon)))))]
    (update-installed-addon-list!
     (conj (vec (remove f (get-state :installed-addon-list))) addon))
    addon))

(defn-spec load-installed-addon (s/or :ok :addon/installed, :error nil?)
  "loads the addon at the given `addon-dir` path, updating or adding it to the `:installed-addon-list`."
  [addon-path ::sp/addon-dir]
  (when-let [installed-addon (addon/load-installed-addon addon-path (get-game-track))]
    (update-installed-addon! installed-addon)))

(defn-spec load-all-installed-addons nil?
  "offloads the hard work to `addon/load-all-installed-addons` then updates application state"
  []
  (if-let [addon-dir (selected-addon-dir)]
    (let [addon-list (addon/load-all-installed-addons addon-dir (get-game-track))]
      (info (format "loading (%s) installed addons in: %s" (count addon-list) addon-dir))
      (update-installed-addon-list! addon-list))

    ;; if no addon directory selected, ensure list of installed addons is empty
    (update-installed-addon-list! [])))

;; catalogue handling

(defn-spec get-catalogue-location (s/or :ok :catalogue/location, :not-found nil?)
  "returns the catalogue-location map for the given `catalogue-name`.
  returns the catalogue-location map for the currently selected catalogue by default.
  returns `nil` if catalogue not found or no catalogue selected and no `catalogue-name` given."
  ([]
   (get-catalogue-location (get-state :cfg :selected-catalogue)))
  ([catalogue-name keyword?]
   (->> (get-state :cfg :catalogue-location-list)
        (filter #(-> % :name (= catalogue-name)))
        first)))

(defn-spec default-catalogue (s/or :ok :catalogue/location, :not-found nil?)
  "the 'default' catalogue is the first catalogue in the list of available catalogues.
  using the original set of catalogues that come with strongbox, this is the 'short' catalogue.
  user can specify their own (or no) catalogues however."
  []
  (-> (get-state :cfg :catalogue-location-list)
      first
      :name ;; `:short`, typically
      get-catalogue-location))

(defn-spec current-catalogue (s/or :ok :catalogue/location, :no-catalogues nil?)
  "returns the currently selected catalogue or the first catalogue it can find.
  returns `nil` if no catalogues selected or none available to choose from."
  []
  (or (get-catalogue-location) (default-catalogue)))

(defn-spec set-catalogue! nil?
  "change the selected catalogue using it's `catalogue-name`"
  [catalogue-name :catalogue/name]
  (if-let [catalogue (get-catalogue-location catalogue-name)]
    (swap! state assoc-in [:cfg :selected-catalogue] (:name catalogue))
    (warn "catalogue not found" catalogue-name))
  nil)

(defn-spec catalogue-local-path ::sp/file
  "returns the local path to the catalogue given a `catalogue-location`"
  [catalogue-name :catalogue/name]
  ;; :full => "/path/to/catalogue/dir/full-catalogue.json"
  (utils/join (paths :catalogue-dir) (-> catalogue-name name (str "-catalogue.json"))))

(defn-spec download-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads catalogue to expected location, nothing more"
  [catalogue-location :catalogue/location]
  (binding [http/*cache* (cache)]
    (let [remote-catalogue (:source catalogue-location)
          local-catalogue (catalogue-local-path (:name catalogue-location))
          message (format "downloading '%s' catalogue" (name (:name catalogue-location)))
          resp (http/download-file remote-catalogue local-catalogue message)]
      (when-not (http/http-error? resp)
        resp))))

(defn-spec download-current-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads the currently selected (or default) catalogue."
  []
  (if-let [catalogue-location (current-catalogue)]
    (download-catalogue catalogue-location)
    (warn "failed to find a downloadable catalogue")))

(defn-spec emergency-catalogue (s/or :static-catalogue :catalogue/catalogue, :unknown-catalogue nil?)
  "derives the requested catalogue from the static catalogue."
  [catalogue-location :catalogue/location]
  (let [opts {}
        catalogue (catalogue/read-catalogue (.getBytes static-catalogue) opts)
        catalogue (assoc catalogue :emergency? true)]

    (warn (utils/message-list (format "the remote catalogue is unreachable or corrupt: %s" (:source catalogue-location))
                              [(str "backup catalogue generated: " (:datestamp catalogue))]))

    (case (:name catalogue-location)
      :full catalogue
      :short (catalogue/shorten-catalogue catalogue)
      ;;:curseforge (catalogue/filter-catalogue catalogue "curseforge")
      :wowinterface (catalogue/filter-catalogue catalogue "wowinterface")
      ;;:tukui (catalogue/filter-catalogue catalogue "tukui")
      :github (catalogue/filter-catalogue catalogue "github") ;; todo: add a test for this. I expect all derivable catalogues to be available
      nil)))

;;

(defn-spec moosh-addons :addon/toc+summary+match
  "merges the data from an installed addon with it's match in the catalogue"
  [installed-addon :addon/toc, db-catalogue-addon :addon/summary]
  (let [;; 2021-09: this may not be the case anymore but it's still robust behaviour.
        ;; nil fields are removed from the catalogue item because they might override good values in the .toc or .nfo
        db-catalogue-addon (utils/drop-nils db-catalogue-addon [:description])
        inst-source (:source installed-addon)
        dbc-source (:source db-catalogue-addon)
        source-mismatch? (and inst-source
                              dbc-source
                              (not (= inst-source dbc-source)))]
    ;; merges left->right. catalogue-addon overwrites installed-addon, ':matched' overwrites catalogue-addon, etc
    (logging/addon-log installed-addon :info (format "found in catalogue with source \"%s\" and id \"%s\""
                                                     (:source db-catalogue-addon) (:source-id db-catalogue-addon)))
    (merge installed-addon
           db-catalogue-addon
           {:matched? true}
           ;; todo: I really want to disambiguate between where data is coming from.
           ;; it would mean carrying around the toc, nfo, catalogue, source updates and a merged set data.
           ;; all we have right now is the merged set (except this new `nfo/source` key)
           (when source-mismatch?
             {:nfo/source (:source installed-addon)}))))

;;

(defn-spec write-user-catalogue! nil?
  "writes the current state of the `:user-catalogue` to disk."
  []
  (catalogue/write-catalogue
   (catalogue/new-catalogue (or (get-state :user-catalogue :addon-summary-list) []))
   (paths :user-catalogue-file))
  nil)

(defn-spec get-user-catalogue (s/or :ok :catalogue/catalogue, :missing nil?)
  "returns the contents of the user catalogue or `nil` if it doesn't exist."
  []
  (let [catalogue (catalogue/read-catalogue (paths :user-catalogue-file) {:bad-data? nil})
        new-summary-list (->> catalogue :addon-summary-list (remove addon/host-disabled?) vec)]
    (when catalogue
      (catalogue/new-catalogue new-summary-list))))

(defn-spec set-user-catalogue! nil?
  "replaces the current user-catalogue in app state and generates a source-map index for faster lookups."
  [new-user-catalogue (s/nilable :catalogue/catalogue)]
  (let [user-catalogue-idx (some->> new-user-catalogue
                                    :addon-summary-list
                                    (map utils/source-map)
                                    set)]
    (swap! state merge {:user-catalogue new-user-catalogue
                        :user-catalogue-idx user-catalogue-idx}))
  nil)

(defn-spec add-user-addon-list! nil?
  "adds a list of addons to the user catalogue"
  [addon-summary-list :addon/summary-list]
  (let [user-catalogue (get-state :user-catalogue)
        tmp-catalogue (catalogue/new-catalogue addon-summary-list)
        new-user-catalogue (catalogue/merge-catalogues user-catalogue tmp-catalogue)]
    (set-user-catalogue! new-user-catalogue))
  nil)

(defn-spec add-user-addon! nil?
  "adds a single addon to the user catalogue"
  [addon-summary :addon/summary]
  (add-user-addon-list! [addon-summary]))

(defn-spec remove-user-addon! nil?
  "removes a single addon from the user-catalogue"
  [addon-summary :addon/summary]
  (let [addon-summary-idx (utils/source-map addon-summary)
        user-catalogue (->> (get-state :user-catalogue :addon-summary-list)
                            (remove (fn [row]
                                      (= (utils/source-map row) addon-summary-idx))))
        new-user-catalogue (catalogue/new-catalogue user-catalogue)]
    (set-user-catalogue! new-user-catalogue))
  nil)

;; catalogue db handling

(defn-spec -find-in-db :db/addon-catalogue-match
  "looks for `installed-addon` in the given `db`, matching `toc-key` to a `catalogue-key`.
  if a `toc-key` and `catalogue-key` are actually lists, then all the `toc-keys` must match the `catalogue-keys`"
  [db :addon/summary-list, installed-addon :db/installed-addon-or-import-stub, toc-keys :db/toc-keys, catalogue-keys :db/catalogue-keys]
  (let [;; [:source :source-id] => [:source :source-id], :name => [:name]
        catalogue-keys (if (vector? catalogue-keys) catalogue-keys [catalogue-keys])
        toc-keys (if (vector? toc-keys) toc-keys [toc-keys])

        ;; [:source :source-id] => ["curseforge" 12345], [:name] => ["foo"]
        arg-vals (mapv #(get installed-addon %) toc-keys)
        missing-args? (some nil? arg-vals)

        ;; there are cases where the installed-addon is missing an attribute to match on.
        ;; typically happened on the old `:alias` key that has since been replaced but we also have cases of missing `:title` values.
        _ (when missing-args?
            (debug "failed to find all values for db search, refusing to match against nil values. keys:" toc-keys "; vals:" arg-vals))

        ;; for each catalogue key, fetch the values and compare
        match? (fn [row]
                 (= arg-vals (mapv #(get row %) catalogue-keys)))

        results (if missing-args?
                  [] ;; don't look for 'nil', just skip with no results
                  (into [] (filter match?) db)) ;; todo: I think the `xform` parameter in this `into` form needs to be comped with `first`
        match (-> results first)]
    (when match
      {;; the relationship the match was made on: [[:source :source-id] [:source :source_id]]
       :idx [toc-keys catalogue-keys]
       ;; the values of the match: ["curseforge" "deadly-boss-mods"]
       :key arg-vals
       :installed-addon installed-addon
       :matched? (not (nil? match)) ;; todo: still used?
       :catalogue-match match})))

(defn-spec -find-first-in-db (s/or :match map?, :no-match nil?)
  "find a match for the given `installed-addon` in the database using a list of attributes in `match-on-list`.
  returns immediately when first match is found (does not check other joins in `match-on-list`)."
  [db :addon/summary-list, installed-addon :db/installed-addon-or-import-stub, match-on-list sequential?]
  (if (or (:ignore? installed-addon) ;; todo: should this check be done? db is a bit low level for this
          (empty? match-on-list))
    ;; either addon is being ignored, or,
    ;; we have exhausted all possibilities. not finding a match is ok.
    nil
    (let [[toc-keys catalogue-keys] (first match-on-list) ;; => [:name] or [:source-id :source]
          match (-find-in-db db installed-addon toc-keys catalogue-keys)]
      (if-not match
        (-find-first-in-db db installed-addon (rest match-on-list)) ;; recur
        match))))

(defn-spec db-match-installed-addon-list-with-catalogue (s/coll-of (s/or :match map? :no-match :addon/toc))
  "for each installed addon, search the catalogue across multiple joins until a match is found.
  addons with no match return themselves"
  [db :addon/summary-list, installed-addon-list :addon/installed-list]
  (let [;; toc-key -> db-catalogue-key
        ;; most -> least desirable match
        ;; nest to search across multiple parameters
        match-on-list [[[:source :source-id] [:source :source-id]] ;; source+source-id, perfect case
                       [:source :name] ;; source+name, we have a source but no source-id (nfo v1 files)
                       [:name :name]
                       [:label :label]
                       [:dirname :label]] ;; dirname = label, eg ./AdiBags = AdiBags
        ]
    (for [installed-addon installed-addon-list]
      (or (-find-first-in-db db installed-addon match-on-list)
          installed-addon))))

(defn-spec db-addon-by-source-and-source-id (s/nilable :addon/summary)
  "returns the first addon summary from `db` whose source and source-id exactly match the given `source` and `source-id`.
  there should only ever be one or zero such addons."
  [db :addon/summary-list, source :addon/source, source-id :addon/source-id]
  (let [xf (filter #(and (= source (:source %))
                         (= source-id (:source-id %))))]
    (first (into [] xf db))))

(defn-spec db-addon-by-source-and-name :addon/summary-list
  "returns a list of addon summaries from `db` whose source and name exactly match the given `source` and `name`."
  [db :addon/summary-list, source :addon/source, name ::sp/name]
  (let [xf (filter #(and (= source (:source %))
                         (= name (:name %))))]
    (into [] xf db)))

(defn-spec db-addon-by-name :addon/summary-list
  "returns a list of addon summaries from `db` whose name exactly matches the given `name`."
  [db :addon/summary-list, name ::sp/name]
  (let [xf (filter #(= name (:name %)))]
    (into [] xf db)))

(defn-spec db-search-sampling? boolean?
  "returns `true` if a database search should return a random sample of results.
  essentially, if nothing has been searched for and no filters have been set, we should take a random
  sample of the selected catalogue UNLESS something has explicitly flipped the `:sample?` boolean."
  [search-state map?]
  (let [filter-by (-> search-state :filter-by)]
    (and (:sample? search-state) ;; true by default, set to false to short circuit this logic.
         (empty? (-> search-state :term (or "") clojure.string/trim))
         (empty? (:tag filter-by))
         (not (:user-catalogue filter-by))
         (not (:source filter-by)))))

(defn db-search
  "returns a lazily fetched and paginated list of addon summaries.
  results are constructed using a `seque` that (somehow) bypasses chunking behaviour so 
  searching never takes more than `cap` results.
  matches on `uin` are case insensitive.
  if no user input, returns a list of randomly ordered ('sampled') results.
  `filter-by` filters are applied before searching for `uin`."
  ([search-term cap filter-by]
   (let [{:keys [db user-catalogue-idx search]} (get-state)]
     (db-search db (utils/nilable search-term) cap filter-by user-catalogue-idx (db-search-sampling? search))))

  ([db uin cap filter-by user-catalogue-idx random-sample?]
   (let [constantly-true (constantly true)

         user-catalogue-filter (if (:user-catalogue filter-by)
                                 (fn [row]
                                   (contains? user-catalogue-idx (utils/source-map row)))
                                 constantly-true)

         host-filter (if-let [source-list (:source filter-by)]
                       (fn [row]
                         (utils/in? (:source row) source-list))
                       constantly-true)

         selected-tag-set (:tag filter-by)
         tag-filter (if (empty? selected-tag-set)
                      constantly-true
                      (fn [addon]
                        (let [addon-tag-set (-> addon :tag-list set)
                              tag-membership (:tag-membership filter-by)]
                          (cond
                            ;; exclude addon if tags have been selected but it has no tags
                            (empty? addon-tag-set) false
                            ;; include addon if it contains *some* of the selected tags
                            (= tag-membership "any of") (some selected-tag-set addon-tag-set)
                            ;; include addon if it contains *all* of the selected tags
                            (= tag-membership "all of") (clojure.set/subset? selected-tag-set addon-tag-set)
                            :else false))))

         db (->> db
                 (filter user-catalogue-filter)
                 (filter host-filter)
                 (filter tag-filter))]

     ;; no/empty input, do a random sample
     (if random-sample?
       (let [pct (->> db count (max 1) (/ 100) (* 0.6))]
         ;; decrement cap here so navigation for random search results is disabled
         [(take (dec cap) (random-sample pct db))])

       ;; else, search by input
       (let [uin (clojure.string/trim (or uin ""))
             ;; implementation taken from here:
             ;; - https://www.baeldung.com/java-case-insensitive-string-matching
             regex (Pattern/compile (Pattern/quote uin) Pattern/CASE_INSENSITIVE)
             match-fn (fn [row]
                        (or
                         (.find (.matcher regex (or (:label row) "")))
                         (.find (.matcher regex (or (:description row) "")))))]
         (partition-all cap (seque 100 (filter match-fn db))))))))

(defn-spec empty-search-results nil?
  "empties app state of search *results* but not filters.
  this handles catalogue reloads but preserves user filtering."
  []
  (swap! state update-in [:search] merge (select-keys -search-state-template [:page :results :selected-results-list]))
  nil)

(defn-spec reset-search-navigation nil?
  "resets the search results to page 1"
  []
  (swap! state assoc-in [:search :page] 0)
  nil)

(defn-spec reset-search-state! nil?
  "replaces search state with default settings."
  []
  (swap! state update-in [:search] merge -search-state-template)
  nil)

(defn-spec db-load-user-catalogue nil?
  "loads the user catalogue into state, but only if it hasn't already been loaded."
  []
  (when-not (get-state :user-catalogue)
    (set-user-catalogue! (get-user-catalogue)))
  nil)

(defn-spec load-current-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "merges the currently selected catalogue with the user-catalogue and returns the definitive list of addons 
  available to install.
  Guard function, handles malformed catalogue data by re-downloading the catalogue."
  []
  (when-let [catalogue-location (current-catalogue)]
    (let [catalogue-path (catalogue-local-path (:name catalogue-location))
          catalogue-label (name (:name catalogue-location))
          catalogue-source (:source catalogue-location)
          ;; "loading 'full' catalogue"
          _ (info (format "loading '%s' catalogue." catalogue-label))

          ;; download from remote and try again when json can't be read
          bad-json-file-handler
          (fn []
            ;; "catalogue 'full' corrupted, attempting download again."
            (warn (format "catalogue '%s' corrupted, attempting download again." catalogue-label))
            (fs/delete catalogue-path)
            (download-current-catalogue) ;; todo: be explicit here
            (catalogue/read-catalogue
             catalogue-path
             {:bad-data? (fn []
                           ;; "catalogue 'full' failed to load again, it might be corrupt at it's source: https://path/to/online/catalogue.json"
                           (let [msg (format "catalogue '%s' failed to load again, it might be corrupt at it's source: %s" catalogue-label catalogue-source)]
                             (error (utils/reportable-error msg))))}))

          catalogue-data (or (catalogue/read-catalogue catalogue-path {:bad-data? bad-json-file-handler})
                             (when-not *testing?*
                               (emergency-catalogue catalogue-location)))

          user-catalogue-data (get-state :user-catalogue)
          ;; 2021-06-30: merge order changed. catalogue data is now merged over the top of the user-catalogue.
          ;; this is because the user-catalogue may now contain addons from all hosts and is likely to be out of date.
          final-catalogue (catalogue/merge-catalogues user-catalogue-data catalogue-data)]
      ;; "1024 addons in final catalogue." ;; todo: is this just noise?
      (info (-> final-catalogue :addon-summary-list count (str " addons in final catalogue.")))
      final-catalogue)))

(defn db-catalogue-loaded?
  "returns `true` if the database has a catalogue loaded.
  An empty database `[]` is distinct from an unloaded database (`nil`).
  A database may be empty only if the `addon-summary-list` key of a catalogue is empty.
  A database may be `nil` if it simply hasn't been loaded yet or we attempted to load it and it failed to load.
  A database may fail to load if it simply isn't there, can't be downloaded or, once downloaded, the data is invalid."
  []
  (some? (get-state :db)))

(defn-spec db-load-catalogue nil?
  "loads the currently selected catalogue into the database, but only if we have a catalogue and it hasn't already 
  been loaded.
  Handles bad/invalid catalogues and merging the user catalogue."
  []
  (if (or (db-catalogue-loaded?)
          (not (current-catalogue)))
    (debug "skipping catalogue load. already loaded or no catalogue selected.")

    (let [final-catalogue (load-current-catalogue)]
      (when-not (empty? final-catalogue)
        (swap! state assoc :db (:addon-summary-list final-catalogue)))))
  nil)

(defn-spec -match-installed-addon-list-with-catalogue :addon/installed-list
  "compare the list of given addons with the database of known addons and try to match the two up.
  when a match is found (see `db-match-installed-addon-list-with-catalogue`), merge it into the addon data."
  [database :addon/summary-list, installed-addon-list :addon/toc-list]
  (let [num-installed (count installed-addon-list)
        _ (when (> num-installed 0)
            (info (format "matching %s addons to catalogue" (count installed-addon-list))))
        match-results (db-match-installed-addon-list-with-catalogue database installed-addon-list)
        [matched unmatched] (utils/split-filter :matched? match-results)

        ;; for those that *did* match, merge the installed addon data together with the catalogue data
        matched (mapv #(moosh-addons (:installed-addon %) (:catalogue-match %)) matched)
        ;; and then make them a single list of addons again

        ;; for those that failed to match but have nfo data we can fall back to ...
        ;; 2022-09: I don't like this, it relies on source and source-id from the group-id, a URL, and addons can now switch sources.
        ;; group-id should be changed from a URL to something else to prevent this temptation.
        polyfilled (mapv (fn [addon]
                           (if-let [synthetic (catalogue/toc2summary addon)]
                             (moosh-addons addon synthetic)
                             addon))
                         unmatched)

        expanded-installed-addon-list (into matched polyfilled)

        ;; todo: metrics gathering is good, but this is a little adhoc. shift into parent wrapper somehow.
        ;; some metrics we'll emit for the user.
        num-matched (count matched)
        ;; we don't match ignored addons, we shouldn't report we couldn't find them either
        unmatched-names (->> unmatched (remove :ignore?) (map :name) set)]

    ;; todo: revisit this message.
    (when-not (= num-installed num-matched)
      (info (format "num installed %s, num matched %s" num-installed num-matched)))

    (when (and (not (empty? unmatched-names))
               (> 1 (count installed-addon-list))) ;; this is *probably* a per-addon install/refresh, it gives us just the addon in question
      (let [;; "failed to find 5 addons in the 'full' catalogue: foo, bar, baz, bup, boo"
            ;; "you need to manually search for them and then re-install them."
            msg (format "failed to find %s addons in the '%s' catalogue: %s"
                        (count unmatched-names)
                        (name (get-state :cfg :selected-catalogue))
                        (clojure.string/join ", " unmatched-names))
            suggestion "try searching for these addons by name or description in the search tab."]
        (warn (utils/message-list msg [suggestion]))))

    (when-not (empty? unmatched)
      (run! (fn [addon]
              (logging/with-addon addon
                (if (:ignore? addon)
                  (info "not matched to catalogue, addon is being ignored.")
                  (warn (utils/message-list
                         ;; "failed to find a match in the 'full' catalogue."
                         ;; "try searching for this addon name by or description in the search tab."
                         (format "failed to find a match in the '%s' catalogue." (name (get-state :cfg :selected-catalogue)))
                         ["try searching for this addon by name or description in the search tab."])))))
            unmatched))

    expanded-installed-addon-list))

(defn-spec match-all-installed-addons-with-catalogue nil?
  "compares the list of addons installed with the catalogue of known addons, match the two up, merge
  the two together and update the list of installed addons.
  Skipped when no catalogue loaded or no addon directory selected."
  []
  (when (and (db-catalogue-loaded?)
             (selected-addon-dir))
    (update-installed-addon-list!
     (-match-installed-addon-list-with-catalogue (get-state :db) (get-state :installed-addon-list)))))

(defn-spec match-installed-addon-with-catalogue (s/or :ok :addon/installed, :app-not-started nil?) ;; todo: revisit
  "compare the given `addon` with the catalogue of known addons, match the two up, merge
  the two together and update the list of installed addons.
  Skipped when no catalogue loaded or no addon directory selected."
  [addon :addon/installed]
  (when (and (db-catalogue-loaded?)
             (selected-addon-dir))
    (update-installed-addon!
     (first (-match-installed-addon-list-with-catalogue (get-state :db) [addon])))))

;;
;; addon summary and toc merging
;;

(defn expand-summary-wrapper
  "fetches updates for the given `addon-summary` from it's addon host.
  uses the current game track and strictness settings to pick the right release when multiple releases available."
  [addon-summary]
  (binding [http/*cache* (cache)]
    (catalogue/expand-summary addon-summary (get-game-track) (get-game-track-strictness))))

(defn-spec expandable? boolean?
  "returns `true` if the given `addon` (in whatever form) can be checked against an addon host for updates."
  [addon map?]
  (and (s/valid? :addon/expandable addon)
       (not (:ignore? addon))))

(defn-spec check-for-update :addon/toc
  "returns the given `addon` with source updates, if any, and sets an `update?` property if a different version is available.
  If addon is pinned to a specific version, `update?` will only be true if pinned version is different from installed version."
  [addon (s/or :unmatched :addon/toc
               :matched :addon/toc+summary+match)]
  (logging/with-addon addon
    (joblib/tick 0.25)
    (let [expanded-addon (when (expandable? addon)
                           (expand-summary-wrapper addon))
          addon (or expanded-addon addon) ;; expanded addon may still be nil
          has-update? (addon/updateable? addon)]
      (joblib/tick 0.5)
      (when has-update?
        ;; "update '1.2.3' available from github"
        (info (format "update '%s' available from %s" (:version addon) (:source addon)))
        (when-not (= (get-game-track) (:game-track addon))
          (warn (format "update is for '%s' and the addon directory is set to '%s'"
                        (-> addon :game-track sp/game-track-labels-map)
                        (-> (get-game-track) sp/game-track-labels-map))))

        (when (:nfo/source addon)
          (warn (utils/message-list
                 ;; "update is from a different host (github) to the one it was installed from (wowinterface)"
                 (format "update is from a different host (%s) to the one it was installed from (%s)." (:source addon) (:nfo/source addon))
                 ["this happens when an exact match is not found in the selected catalogue."]))))

      (joblib/tick 0.75)
      (assoc addon :update? has-update?))))

(def check-for-update-affective (affects-addon-wrapper check-for-update))

#_(defn-spec check-for-updates-serially nil?
    "downloads full details for all installed addons that can be found in summary list"
    []
    (when (selected-addon-dir)
      (let [installed-addon-list (get-state :installed-addon-list)
            num-installed (count installed-addon-list)]
        (when (> num-installed 0)
          (info "checking for updates")
          (let [improved-addon-list (mapv check-for-update installed-addon-list)
                num-matched (->> improved-addon-list (filterv :matched?) count)
                num-updates (->> improved-addon-list (filterv :update?) count)]
            (update-installed-addon-list! improved-addon-list)
            (info (format "%s addons checked, %s updates available" num-matched num-updates)))))))

(defn-spec check-for-updates-in-parallel nil?
  "fetches updates for all installed addons from addon hosts, in parallel."
  []
  (when (selected-addon-dir)
    (let [installed-addon-list (get-state :installed-addon-list)]
      (info "checking for updates")
      (let [queue-atm (get-state :job-queue)
            update-jobs (fn [installed-addon]
                          (joblib/create-addon-job! queue-atm, installed-addon, check-for-update-affective))
            _ (run! update-jobs installed-addon-list)

            expanded-addon-list (joblib/run-jobs! queue-atm num-concurrent-downloads)

            num-matched (->> expanded-addon-list (filterv :matched?) count)
            num-updates (->> expanded-addon-list (filterv :update?) count)]

        (update-installed-addon-list! expanded-addon-list)
        (info (format "%s addons checked, %s updates available" num-matched num-updates))))))

(def check-for-updates check-for-updates-in-parallel)

(defn-spec check-addon-for-updates (s/or :ok :addon/installed, :app-not-started nil?)
  "fetches updates for given `addon` from it's addon host."
  [addon (s/or :unmatched :addon/toc
               :matched :addon/toc+summary+match)]
  (when (selected-addon-dir)
    (update-installed-addon! (check-for-update addon))))

;;

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  "given a URL of a supported addon host, parses it, looks for it in the catalogue, expands addon and attempts to install a dry run installation.
  if successful, returns the addon-summary.
  dry-run installation attempt can be skipped by setting `attempt-dry-run` to false."
  [addon-url string?, attempt-dry-run? boolean?]
  (binding [http/*cache* (cache)]
    (if-let* [addon-summary-stub (catalogue/parse-user-string addon-url)
              source (:source addon-summary-stub)
              match-on-list [[[:source :url] [:source :url]]
                             [[:source :source-id] [:source :source-id]]]
              addon-summary (cond
                              (= source "github")
                              (or (github-api/find-addon (:source-id addon-summary-stub))
                                  (error (message-list
                                          "Failed. URL must be:"
                                          ["valid"
                                           "originate from github.com"
                                           "addon uses 'releases'"
                                           "latest release has a packaged 'asset'"
                                           "asset must be a .zip file"
                                           "zip file must be structured like an addon"])))

                              (= source "gitlab")
                              (or (gitlab-api/find-addon (:source-id addon-summary-stub))
                                  (error (message-list
                                          "Failed. URL must be:"
                                          ["valid"
                                           "originate from gitlab.com"
                                           "addon uses releases"
                                           "latest release has a custom asset with a 'link'"
                                           "link type must be either a 'package' or 'other'"])))

                              (= source "curseforge")
                              (error (str "addon host 'curseforge' was disabled " constants/curseforge-cutoff-label "."))

                              (utils/in? source sp/tukui-source-list)
                              (error (str "addon host 'tukui' was disabled " constants/tukui-cutoff-label "."))

                              :else
                              ;; look in the current catalogue. emit an error if we fail
                              (or (:catalogue-match (-find-first-in-db (or (get-state :db) []) addon-summary-stub match-on-list))
                                  (error (format "couldn't find addon in catalogue '%s'"
                                                 (name (get-state :cfg :selected-catalogue))))))

              ;; game track doesn't matter when adding it to the user catalogue.
              ;; prefer retail though (it's the most common) and `strict` here is `false`
              addon (or (catalogue/expand-summary addon-summary :retail false)
                        (error "failed to fetch details of addon"))

              ;; a dry-run is good when importing an addon for the first time but
              ;; not necessary when updating the user-catalogue.
              _ (if-not attempt-dry-run?
                  true
                  (or (install-addon-guard addon (selected-addon-dir) true)
                      (error "failed dry-run installation")))]

             ;; if-let* was successful!
             addon-summary

             ;; failed if-let* :(
             nil)))

(defn-spec refresh-user-catalogue-item nil?
  "refresh the details of an individual `addon` in the user catalogue, optionally writing the updated catalogue to file."
  [addon :addon/summary, db :addon/summary-list]
  (logging/with-addon addon
    (info "refreshing user-catalogue entry")
    (try
      (let [{:keys [source source-id url]} addon
            refreshed-addon (db-addon-by-source-and-source-id db source source-id)
            attempt-dry-run? false
            refreshed-addon (or refreshed-addon
                                (find-addon url attempt-dry-run?))]
        (if-not refreshed-addon
          (warn "failed to refresh user-catalogue entry as the addon was not found in the catalogue or online")
          (add-user-addon! refreshed-addon)))

      (catch Exception e
        (error (format "an unexpected error happened while refreshing the user-catalogue entry: %s" (.getMessage e)))))))

(defn-spec refresh-user-catalogue nil?
  "refresh the details of all addons in the user catalogue, writing the updated catalogue to file once."
  []
  (binding [http/*cache* (cache)]
    (let [path (fs/base-name (paths :user-catalogue-file)) ;; "user-catalogue.json"
          ;; we can't assume the full-catalogue is available.
          _ (download-catalogue (get-catalogue-location :full))
          db (catalogue/read-catalogue (catalogue-local-path :full))
          full-catalogue (or (:addon-summary-list db) [])]
      (info (format "refreshing \"%s\", this may take a minute ..." path))
      (doseq [user-addon (get-state :user-catalogue :addon-summary-list)]
        (refresh-user-catalogue-item user-addon full-catalogue))
      (write-user-catalogue!)
      (info (format "\"%s\" has been refreshed" path))))
  nil)

(defn-spec refresh-user-catalogue? boolean?
  "predicate, returns `true` if the user-catalogue needs a refresh."
  [keep-user-catalogue-updated? boolean?, catalogue-datestamp (s/nilable ::sp/inst)]
  (and keep-user-catalogue-updated?
       catalogue-datestamp
       (utils/older-than? catalogue-datestamp constants/max-user-catalogue-age :days)))

(defn-spec scheduled-user-catalogue-refresh nil?
  "checks the loaded database and calls `refresh-user-catalogue` if it's considered too old."
  []
  (when (refresh-user-catalogue? (get-state :cfg :preferences :keep-user-catalogue-updated)
                                 (get-state :user-catalogue :datestamp))
    (info (format "user-catalogue not updated in the last %s days, automatic refresh triggered." constants/max-user-catalogue-age))
    (refresh-user-catalogue)))

;;

(defn-spec init-dirs nil?
  "ensure all directories in `generate-path-map` exist and are writable, creating them if necessary.
  this logic depends on paths that are not generated until the application has been started."
  []
  ;; data directory doesn't exist and parent directory isn't writable.
  ;; nowhere to create data dir, nowhere to store download catalogue. non-starter.
  (when (and
         (not (fs/exists? (paths :data-dir))) ;; doesn't exist and ..
         (not (utils/last-writeable-dir (paths :data-dir)))) ;; .. no writeable parent
    (throw (RuntimeException. (str "Data directory doesn't exist and it cannot be created: " (paths :data-dir)))))

  ;; state directory *does* exist but isn't writeable.
  ;; another non-starter.
  (when (and (fs/exists? (paths :data-dir))
             (not (fs/writeable? (paths :data-dir))))
    (throw (RuntimeException. (str "Data directory isn't writeable:" (paths :data-dir)))))

  ;; ensure all '-dir' suffixed paths exist, creating them if necessary.
  (doseq [[path val] (paths)]
    (when (-> path name (clojure.string/ends-with? "-dir"))
      ;; "creating 'config-dir' directory: /path/to/config/dir"
      (debug (format "creating '%s' directory: %s" path val))
      (fs/mkdirs val)))

  nil)

(defn-spec delete-log-files! nil?
  "empties the 'logs' directory.
  files are written here when the log level is set to `:debug` (and we're not testing)."
  []
  (warn "deleting logs")
  (fs/delete-dir (paths :log-data-dir))
  (init-dirs))

(defn-spec prune-http-cache! nil?
  "deletes html/json files from the 'cache' directory that are older than a certain age."
  []
  (debug "pruning http cache")
  (http/prune-cache-dir (paths :cache-dir)))

(defn-spec delete-http-cache! nil?
  "deletes the 'cache' directory that contains html/json files and the etag db file.
  this is something the user initiates and not done automatically."
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
  "deletes all downloaded catalogues from the `:data-dir`.
  the user-catalogue is deliberately ignored. It also lives in the `:config-dir`."
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

(def download-version-lock (Object.))

(defn-spec -download-strongbox-release (s/or :ok string?, :failed? keyword)
  "returns the most recently released version of strongbox on github.
  returns `:failed` if an error occurred while downloading/decoding/extracting the version name, rather than `nil`.
  `nil` is used to mean 'not set (yet)' in the app state."
  []
  (locking download-version-lock
    (binding [http/*cache* (cache)]
      (let [message "downloading strongbox version data"
            url "https://api.github.com/repos/ogri-la/strongbox/releases/latest"]
        (or (some-> url (http/download message) http/sink-error utils/from-json :tag_name)
            :failed)))))

(defn-spec latest-strongbox-version? boolean?
  "returns `true` if the given `latest-release` is the *most recent known* version of strongbox.
  when called with no parameters the `latest-release` is pulled from application state."
  ([]
   (latest-strongbox-version? (get-state :latest-strongbox-release)))
  ([latest-release ::sp/latest-strongbox-release]
   (case latest-release
     nil true ;; we haven't looked yet, so yes, we're the latest :)
     :failed true ;; we've already looked and failed, so as far as we know we're the latest.
     (let [version-running (strongbox-version)
           sorted-asc (utils/sort-semver-strings [latest-release version-running])]
       (= version-running (last sorted-asc))))))

(defn-spec latest-strongbox-release! ::sp/latest-strongbox-release
  "downloads and sets the most recently released version of strongbox on github, returning the found version or `:failed` on error."
  []
  (let [lsr (get-state :latest-strongbox-release)
        check-for-update? (get-state :cfg :preferences :check-for-update)]
    (when check-for-update?
      (case lsr
        ;; we haven't looked yet, so look.
        nil (let [lsr (-download-strongbox-release)]
              (swap! state assoc :latest-strongbox-release lsr)
              lsr)
        ;; we've already looked and failed, don't try again this session.
        :failed nil
        ;; we've already looked, return what we found
        lsr))))

;; stats

(defn-spec github-stats! (s/nilable :github/requests-stats)
  "returns a map of github request rate limiting stats by querying the `/rate_limit` API endpoint.
  doesn't make a Github `/rate_limit` request more often than once a minute."
  []
  (when (and (started?)
             (not *testing?*))
    (binding [http/*cache* (cache)
              http/*expiry-offset-minutes* 1]
      (when-let [resp (some-> "https://api.github.com/rate_limit" http/download http/sink-error utils/from-json :resources :core)]
        {:github/token-set? (not (nil? (System/getenv "GITHUB_TOKEN")))
         :github/requests-limit (:limit resp)
         :github/requests-limit-reset-minutes (-> resp :reset utils/unix-time-to-datetime utils/minutes-from-now)
         :github/requests-remaining (:remaining resp)
         :github/requests-used (:used resp)}))))

(defn-spec app-stats map?
  "summarises application state and returns a map of stats"
  [state map?]
  (let [num-addons (-> state :db count)
        known-host-list (->> state :db (map :source) distinct sort vec)
        num-addons-starred (-> state :user-catalogue-idx count)

        num-addons-installed (-> state :installed-addon-list count)
        num-addons-installed-matched (->> state :installed-addon-list (filter :matched?) count)
        num-addons-installed-ignored (->> state :installed-addon-list (filter addon/ignored?) count)
        num-addons-installed-bytes (->> state :installed-addon-list (map :dirsize) (remove nil?) (reduce +))

        num-addons-reducer (fn [acc addon]
                             (update acc (-> addon :source (or "none")) (fnil inc 0)))
        num-addons-per-host (reduce num-addons-reducer {} (-> state :db))
        num-addons-installed-per-host (reduce num-addons-reducer {} (-> state :installed-addon-list))]

    (merge
     {:addons/total num-addons
      :addons/known-host-list known-host-list
      :addons/num-by-host num-addons-per-host
      :addons/total-starred num-addons-starred

      :installed-addons/total num-addons-installed
      :installed-addons/num-matched num-addons-installed-matched
      :installed-addons/num-by-host num-addons-installed-per-host
      :installed-addons/num-ignored num-addons-installed-ignored
      :installed-addons/total-bytes num-addons-installed-bytes}

     (github-stats!))))

(defn-spec update-stats! nil?
  "summarises application state and updates a map of stats"
  []
  (swap! state assoc :db-stats (app-stats (get-state)))
  nil)

(defn-spec watch-stats! nil?
  "attaches a listener to sections of the state so stats are updated as they happen"
  []
  (let [watch-these-paths [[:db]
                           [:installed-addon-list]
                           [:user-catalogue-idx]]]
    (doseq [path watch-these-paths]
      (state-bind path (fn [_]
                         (try
                           (update-stats!)
                           (catch Exception e
                             (error e "uncaught exception updating stats"))))))))

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
      (logging/with-label "export"
        (warn (format "Addon '%s' has no match in the catalogue and may be skipped during import. It's best all addons match before doing an export." (:name addon)))))

    (utils/dump-json-file output-file export)
    (info "wrote:" output-file)
    output-file))

(defn-spec export-catalogue-addon-list ::sp/export-record-list
  "given a catalogue of addons, generates a list of 'export-records' from the list of addon summaries"
  [catalogue :catalogue/catalogue]
  (export-installed-addon-list (:addon-summary-list catalogue)))

(defn-spec export-user-catalogue-addon-list-safely ::sp/extant-file
  "generates a list of 'export-records' from the addon summaries in the user catalogue and writes them to the given `output-file`"
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str (utils/replace-file-ext ".json"))
        catalogue (get-state :user-catalogue)
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
                              (and source name) (first (db-addon-by-source-and-name (get-state :db) source name))

                              ;; first addon by given name. potentially multiple results
                              name (first (db-addon-by-name (get-state :db) name))

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
    (run! install-addon-guard matching-addon-list)))

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
                 ;; todo: why is dirname needed here?
                 :dirname addon/dummy-dirname
                 :interface-version constants/default-interface-version
                 :toc/game-track :retail
                 :supported-game-tracks []
                 :installed-version "0"}
        addon-list (map #(merge padding %) addon-list)

        ;; todo: why aren't we just calling `expand-summary-wrapper` directly?

        ;; match each of these padded addon maps to entries in the catalogue database
        ;; afterwards this will call `update-installed-addon-list!` that will trigger a refresh in the gui
        matching-addon-list (-match-installed-addon-list-with-catalogue (get-state :db) addon-list)

        ;; this is what v1 does, but it's hidden away in `expand-summary-wrapper`
        ;;default-game-track (get-game-track)

        ;; when no game-track is present in the export record, use the more lenient
        ;; version of the currently selected game track.
        ;; it's better to have an addon installed with the incorrect game track then missing addons.
        default-game-track (get-game-track)
        strict? false]

    (binding [http/*cache* (cache)]
      (doseq [addon matching-addon-list
              :let [game-track (get addon :game-track default-game-track)]]
        (when-let [expanded-addon (catalogue/expand-summary addon game-track strict?)]
          (install-addon-guard expanded-addon))))))

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

        addon-list (remove addon/host-disabled? addon-list)

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

(defn-spec refresh-addon* nil?
  "refreshes state of an individual addon using a filesystem path.
  note! an addon may change it's set of directories between updates, or be completely overwritten by the same addon being installed without the context of a catalogue.
  This means the `:dirname` of the *given* `addon-path` may not represent the *new* `:dirname` and that it's list of `:grouped-addons` are now stale.
  We could reload the addons using the set of old and new directories acquired (see `refresh-check`), however that also leads to 'orphaned' addons,
  where a grouped addon that used to belong but no longer does.
  It's messy. A full refresh is best but that's noisy/ugly :("
  [addon-path ::sp/addon-dir]
  (some->> addon-path
           load-installed-addon
           match-installed-addon-with-catalogue
           check-addon-for-updates)
  nil)

(defn-spec refresh-addon nil?
  "refreshes state of an individual `addon`."
  [addon :addon/installed]
  (logging/with-addon addon
    (some->> addon
             :dirname
             (utils/join (selected-addon-dir))
             refresh-addon*)))

(defn-spec refresh nil?
  []
  (report "refresh")

   ;; parse toc files in install-dir. do this first so we see *something* while catalogue downloads (next)
  (load-all-installed-addons)

   ;; downloads the big long list of addon information stored on github
  (download-current-catalogue)

  ;; load the user-catalogue. `db-load-catalogue` will incorporate this if it's found.
  (db-load-user-catalogue)

  ;; load the contents of the selected catalogue and the user catalogue
  (db-load-catalogue)

  ;; match installed addons to those in catalogue
  (match-all-installed-addons-with-catalogue)

  ;; for those addons that have matches, download their details
  (check-for-updates)

  ;; 2019-06-30, travis is failing with 403: Forbidden. Moved to gui init
  ;;(latest-strongbox-release) ;; check for updates after everything else is done 

  ;; seems like a good place to preserve the etag-db
  (save-settings!)

  (scheduled-user-catalogue-refresh)

  nil)

(defn-spec hard-refresh nil?
  "unlike `refresh`, `hard-refresh` clears the http cache before checking for addon updates."
  []
  ;; why can't we be more specific, like just the addons for the current addon-dir?
  ;; the url used to 'expand' an addon from the catalogue isn't preserved.
  ;; it may also change with the game track (tukui, historically) or not even exist (tukui, currently).
  ;; a thorough inspection would be too much code.
  ;; this also removes the etag cache. the etag db only applies to catalogues and downloaded zip files.
  (delete-http-cache!)
  (refresh))

;; move to core.clj?
(defn-spec half-refresh nil?
  "like `refresh` but excludes reloading catalogues, focusing on re-reading installed addons,
  matching them to the catalogue and reapplying host updates."
  []
  (report "refresh")
  (load-all-installed-addons)
  (match-all-installed-addons-with-catalogue)
  (check-for-updates)
  (save-settings!))

(defn refresh-check
  "given a set of directories, presumably new ones introduced during an update, checks to see if any are
  present at all in the current set of known directories and does a `refresh` if not.
  this should solve the problem with 'stuck' addons, where the addon changed it's primary directory name during update."
  [new-dirs]
  (let [existing-dirs (->> (get-state :installed-addon-list)
                           (mapv addon/flatten-addon)
                           flatten
                           (mapv :dirname)
                           set)
        diff (clojure.set/difference new-dirs existing-dirs)]
    (when-not (empty? diff)
      (debug "diff found between new and old, full refresh required:" diff)
      ;; todo: could this be a half-refresh instead?
      (refresh))))

;; todo: move to ui.cli
#_(defn-spec remove-addon nil?
    "removes given installed addon"
    [installed-addon :addon/installed]
    (logging/with-addon installed-addon
      (addon/remove-addon! (selected-addon-dir) installed-addon))
    (refresh))

;; todo: move to ui.cli
(defn-spec remove-many-addons nil?
  "deletes each of the addons in the given `toc-list` and then calls `refresh`"
  [installed-addon-list :addon/toc-list]
  (let [addon-dir (selected-addon-dir)]
    (doseq [installed-addon installed-addon-list]
      (logging/with-addon installed-addon
        (addon/remove-addon! addon-dir installed-addon)))
    (refresh)))

;;

(defn-spec db-reload-catalogue nil?
  "unloads the database from state then calls `refresh`, triggering a rebuild."
  []
  (swap! state assoc :db nil)
  (refresh))

;; init

(defn-spec set-paths! nil?
  []
  (swap! state assoc :paths (generate-path-map))
  nil)

(defn-spec detect-repl! nil?
  "if we're working from the REPL, we don't want the gui closing the session when it's window closes."
  []
  (swap! state assoc :in-repl? (utils/in-repl?))
  nil)

(defn-spec dump-useful-log-info nil?
  "writes selected system properties to the log.
  mostly concerned with OS, Java and JavaFX versions."
  []
  (let [state-map (if (some? @state) @state {})

        useful-state
        [[:state :paths :config-dir]
         [:state :paths :data-dir]]

        useful-props
        ["javafx.version" ;; "15.0.1"
         "javafx.runtime.version" ;; "15.0.1+1"
         ]

        useful-envvars
        [:strongbox-version ;; "4.0.0"
         :os-name ;; "Linux"
         :os-version ;; "5.11.6-arch1-1"
         :os-arch ;; "amd64"
         :java-runtime-name ;; "OpenJDK Runtime Environment"
         :java-vm-name ;; "OpenJDK 64-Bit Server VM"
         :java-version ;; "11.0.10"
         :java-vendor-url ;; "https://openjdk.java.net/"
         :java-version-date ;; "2021-01-19"
         :java-awt-graphicsenv ;; "sun.awt.X11GraphicsEnvironment"
         :gtk-modules ;; "canberra-gtk-module"
         :xdg-session-desktop ;; "notion"
         :flatpak-id ;; "la.ogri.strongbox" when running within a flatpak
         ]

        adhoc-vars {:strongbox-version (strongbox-version)}
        available-vars (merge adhoc-vars (System/getProperties) @envvar.core/env {:state state-map})
        nm (partial utils/nav-map available-vars)
        key-list (-> []
                     (into useful-state)
                     (into useful-props)
                     (into useful-envvars))]
    (doseq [key key-list]
      (if (sequential? key)
        (let [val (nm key)]
          (info (format "%s=%s" (->> key (map name) (clojure.string/join ".")) val)))
        (info (format "%s=%s" (name key) (get available-vars key)))))))

;;

(defn -start
  []
  (alter-var-root #'state (constantly (atom -state-template))))

(defn start
  [& [cli-opts]]
  (-start)
  (reset-logging!)
  (debug "starting app")
  (set-paths!)
  (detect-repl!)
  (init-dirs)
  (prune-http-cache!)
  (load-settings! cli-opts)
  (watch-stats!)

  state)

(defn stop
  [state]
  (debug "stopping app")
  ;; traverse cleanup list and call them
  (doseq [f (:cleanup @state)]
    (debug "calling" f)
    (f))
  (when (and @state (debug-mode?))
    (dump-useful-log-info)
    (info "wrote debug log to:" (paths :log-file)))
  (reset! state nil))
