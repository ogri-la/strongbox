(ns strongbox.core
  (:require
   [clojure.java.io]
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? ends-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error report spy]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.tufte :as tufte :refer [p]]
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
    [utils :as utils :refer [join nav-map nav-map-fn delete-many-files! expand-path if-let*]]
    [catalogue :as catalogue]
    [specs :as sp]
    [joblib :as joblib]])
  (:import
   [org.apache.commons.compress.compressors CompressorStreamFactory CompressorException]))

(def default-config-dir "~/.config/strongbox")
(def default-data-dir "~/.local/share/strongbox")

(def num-concurrent-downloads (-> (Runtime/getRuntime) .availableProcessors))

(defn-spec compressed-slurp (s/or :ok bytes?, :no-resource nil?)
  "returns the bz2 compressed bytes of the given resource file `resource`.
  returns `nil` if the file can't be found."
  [resource string?]
  (let [input-file (clojure.java.io/resource resource)]
    (when input-file
      (with-open [out (java.io.ByteArrayOutputStream.)]
        (with-open [cos (.createCompressorOutputStream (CompressorStreamFactory.) CompressorStreamFactory/BZIP2, out)]
          (clojure.java.io/copy (clojure.java.io/input-stream input-file) cos))
        ;; compressed output stream (cos) needs to be closed to flush any remaining bytes
        (.toByteArray out)))))

(defn-spec decompress-bytes (s/or :ok string?, :nil-or-empty-bytes nil?)
  "decompresses the given `bz2-bytes` as bz2, returning a string.
  if bytes are empty or `nil`, returns `nil`.
  if bytes are not bz2 compressed, throws an `IOException`."
  [bz2-bytes (s/nilable bytes?)]
  (when-not (empty? bz2-bytes)
    (with-open [is (clojure.java.io/input-stream bz2-bytes)]
      (try
        (with-open [cin (.createCompressorInputStream (CompressorStreamFactory.) CompressorStreamFactory/BZIP2, is)]
          (slurp cin))
        (catch CompressorException ce
          (throw (.getCause ce)))))))

(def static-catalogue
  "a bz2 compressed copy of the full catalogue used when the remote catalogue is unavailable or corrupt."
  (compressed-slurp "full-catalogue.json"))

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

                  ;; 2021-09-16: no longer used, exists only for cleanup and will be removed in the future.
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

(def -search-state-template
  {:term nil
   :filter-by {:source nil
               :tag #{}
               :user-catalogue false}
   :page 0
   :results []
   :selected-result-list []
   :results-per-page 60})

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
   ;; starts as parsed .toc file data
   ;; ... then updated with data from catalogue
   ;; ... then updated again with live data from addon hosts
   ;; see specs/toc-addon
   :installed-addon-list nil

   :etag-db {}

   ;; the list of addons from the catalogue
   :db nil

   ;; some generated stats about the db that are updated just once at load time.
   :db-stats {:known-host-list []}

   ;; the list of addons from the user-catalogue
   :user-catalogue nil

   ;; a set of maps with `:source` and `:source-id` keys from the user-catalogue
   :user-catalogue-idx #{}

   :log-lines []

   ;; a map of paths whose location may vary according to the cwd and envvars.
   :paths nil

   ;; a (stateful) ordered map of running jobs
   :job-queue (atom (joblib/make-queue))

   ;; ui

   ;; jfx ui showing?
   :gui-showing? false

   ;; log-level for the gui dedicated notice-logger
   ;; per-tab log-levels are attached to each tab in the `:tab-list`
   :gui-log-level :info

   ;; split the gui in two with the notice logger down the bottom
   :gui-split-pane false

   ;; addons in an unsteady state (data being updated, addon being installed, etc)
   ;; allows a UI to watch and update with progress
   :unsteady-addon-list #{}

   ;; a sublist of merged toc+addon that are selected
   :selected-addon-list []

   ;; dynamic tabs
   :tab-list []

   :search -search-state-template})

(def state (atom nil))

(defn started?
  "`true` if app has been started."
  []
  (-> @state nil? not))

(defn assert-started
  "raises a `RuntimeException` if app has not been started."
  []
  (when-not (started?)
    (throw (RuntimeException. "application must be `start`ed before state may be accessed."))))

(defn get-state
  "returns the state map of the value at the given path within the map, if path provided"
  [& path]
  (assert-started)
  (nav-map @state path))

(defn paths
  "like `get-in` and `get-state` but for the map of paths being used. requires running app"
  [& path]
  (nav-map (get-state :paths) path))

(def testing? false)

(defn-spec debug-mode? boolean?
  "debug mode is when the log level has been set to `:debug` and we're *not* running tests.
  the intent is to collect as much information around a problem as possible.
  the log level may be changed through REPL usage.
  the log level may be changed by using a `--verbosity` flag at runtime.
  `main/test` and `cloverage.clj` alter the `main/testing?` flag while running tests and resets it afterwards."
  []
  (and (-> timbre/*config* :min-level (= :debug))
       (not testing?)))

;;

(defn-spec find-installed-addon (s/or :match :addon/source-map, :no-match nil?)
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
  "data and a setter that gets bound to http/*cache* when caching http requests"
  []
  (if-not (started?)
    (warn "http cache disabled, app is not started")
    {;;:etag-db (get-state :etag-db) ;; don't do this. encourages stale reads of the etag-db
     :set-etag set-etag
     :get-etag #(get-state :etag-db %) ;; do this instead
     :cache-dir (paths :cache-dir)}))

(defn-spec add-cleanup-fn nil?
  "adds a function to a list of functions that are called without arguments when the application is stopped"
  [f fn?]
  (swap! state update-in [:cleanup] conj f)
  nil)

(defn-spec state-bind nil?
  "executes given callback function when value at path in state map changes. 
  trigger is discarded if old and new values are identical"
  [path ::sp/list-of-keywords, callback fn?]
  (let [has-changed (fn [old-state new-state]
                      (not= (get-in old-state path)
                            (get-in new-state path)))
        wid (keyword (gensym callback)) ;; :foo.bar$baz@123456789
        rmwatch #(remove-watch state wid)]

    (add-watch state wid
               (fn [_ _ old-state new-state] ;; key, atom, old-state, new-state
                 (when (has-changed old-state new-state)
                   ;; avoids infinite recursion
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
                       (error e "error caught in watch! your callback *must* be catching these or the thread dies silently:" path))))))
    (add-cleanup-fn rmwatch)
    nil))

;; addon dirs

(def default-game-track-strictness true) ;; strict

(defn-spec selected-addon-dir (s/or :ok ::sp/addon-dir, :no-selection nil?)
  "returns the currently selected addon directory or nil if no directories exist to select from"
  ([]
   (selected-addon-dir (get-state)))
  ([state map?]
   (-> state :cfg :selected-addon-dir)))

(defn-spec addon-dir-exists? boolean?
  ([addon-dir ::sp/addon-dir]
   (addon-dir-exists? addon-dir (get-state :cfg :addon-dir-list)))
  ([addon-dir ::sp/addon-dir, addon-dir-list ::sp/addon-dir-list]
   (->> addon-dir-list (map :addon-dir) (some #{addon-dir}) nil? not)))

(defn-spec add-addon-dir! nil?
  "creates and adds an addon directory entry in the user's `:addon-dir-list`, if it doesn't already exist."
  ([addon-dir ::sp/addon-dir, game-track ::sp/game-track]
   (add-addon-dir! addon-dir game-track default-game-track-strictness))
  ([addon-dir ::sp/addon-dir, game-track ::sp/game-track, strict? ::sp/strict?]
   (let [stub {:addon-dir addon-dir :game-track game-track :strict? strict?}]
     (when-not (addon-dir-exists? addon-dir)
       (swap! state update-in [:cfg :addon-dir-list] conj stub)))
   nil))

(defn-spec set-addon-dir! nil?
  "adds a new :addon-dir to :addon-dir-list (if it doesn't already exist) and marks it as selected"
  [addon-dir ::sp/addon-dir]
  (let [addon-dir (-> addon-dir fs/absolute fs/normalized str)
        ;; if '_classic_' is in given path, use the classic game track
        default-game-track (if (clojure.string/index-of addon-dir "_classic_") :classic :retail)]
    (dosync ;; necessary? makes me feel better
     (add-addon-dir! addon-dir default-game-track default-game-track-strictness)
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
   (assert-started)
   (addon-dir-map (selected-addon-dir)))
  ([addon-dir (s/nilable ::sp/addon-dir)]
   (let [addon-dir-list (get-state :cfg :addon-dir-list)]
     (when (and addon-dir
                (not (empty? addon-dir-list)))
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
  "returns the game track for the given `addon-dir`.
  uses the currently selected addon-dir if no `addon-dir` given."
  ([]
   (get-game-track (selected-addon-dir)))
  ([addon-dir (s/nilable ::sp/addon-dir)]
   (when addon-dir
     (-> addon-dir addon-dir-map :game-track))))

(defn-spec get-game-track-strictness (s/or :addon-dir ::sp/strict?, :no-addon-dir nil?)
  []
  (when-let [addon-dir-map (addon-dir-map)]
    (get addon-dir-map :strict? default-game-track-strictness)))

;; todo: this is almost identical with `get-game-track`.
;; come up with a better solution if we repeat ourselves again.
(defn-spec set-game-track-strictness! nil?
  "fetches the strictness level for the given `addon-dir` or the currently selected addon directory if not given."
  ([new-strictness-level ::sp/strict?]
   (when-let [addon-dir (selected-addon-dir)]
     (set-game-track-strictness! new-strictness-level addon-dir)))
  ([new-strictness-level ::sp/strict?, addon-dir ::sp/addon-dir]
   (let [tform (fn [addon-dir-map]
                 (if (= addon-dir (:addon-dir addon-dir-map))
                   (assoc addon-dir-map :strict? new-strictness-level)
                   addon-dir-map))
         new-addon-dir-map-list (mapv tform (get-state :cfg :addon-dir-list))]
     (swap! state update-in [:cfg] assoc :addon-dir-list new-addon-dir-map-list))
   nil))


;; stateful logging


(defn-spec -debug-logging nil?
  "if we're in debug mode, turn profiling on and write log to file"
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
   (reset-logging! testing? state (and @state (selected-addon-dir))))

  ([testing? boolean?, state-atm ::sp/atom, install-dir (s/nilable ::sp/install-dir)]
   ;; reset logging configuration to timbre's default.
   (timbre/swap-config! timbre/default-config)

   ;; layer in our own default config
   (timbre/merge-config! logging/-default-logging-config)

   ;; layer in any runtime config
   (when-let [user-level (some-> @state-atm :cli-opts :verbosity)]
     (timbre/merge-config! {:min-level user-level}))

   (-debug-logging state-atm)

   ;; ensure we're storing log lines in app state
   (add-cleanup-fn (logging/add-ui-appender! state-atm install-dir))

   ;; and finally, if we're running tests, drop the logging level to :debug and ensure nothing is emitting to a file
   (when testing?
     (timbre/merge-config! {:testing? true, :min-level :debug, :appenders {:spit nil}}))

   nil))

(defn-spec set-log-level! nil?
  "changes the effective log level from `logging/default-log-level` to `new-level`.
  The `:debug` log level outside of unit tests will write a log file to the data directory and 
  enables the profiling of certain sections of code."
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
    (when (contains? cli-opts :spec?)
      (utils/instrument (:spec? cli-opts))))
  nil)

;; utils

(defn-spec expanded? boolean?
  "returns true if an addon has found further details online"
  [addon map?]
  ;; nice and quick but essentially validating addon against `:addon/installable`
  (some? (:download-url addon)))

(defn-spec unsteady? boolean?
  "returns `true` if given `addon` is being updated"
  [addon-name ::sp/name]
  (if @state
    (clojure.set/subset? #{addon-name} (get-state :unsteady-addon-list))
    false))

(defn start-affecting-addon
  [addon]
  (dosync
   (if-not (unsteady? (:name addon))
     (do (swap! state update-in [:unsteady-addon-list] clojure.set/union #{(:name addon)})
         true)
     false)))

(defn stop-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addon-list] clojure.set/difference #{(:name addon)}))

(defn affects-addon-wrapper
  [wrapped-fn]
  (fn [addon & args]
    (assert-started)
    (let [applied? (start-affecting-addon addon)]
      (try
        (logging/with-addon addon
          (apply wrapped-fn addon args))
        (finally
          (when applied?
            (stop-affecting-addon addon)))))))

;; 


;; downloading and installing and updating

(defn-spec download-addon (s/or :ok ::sp/archive-file, :http-error :http/error, :error nil?)
  "downloads `addon` to the given `install-dir`.
  see `download-addon-guard` for a version with checks."
  [addon :addon/installable, install-dir ::sp/writeable-dir]
  (when (expanded? addon)
    (info (format "downloading '%s' version '%s'" (:label addon) (:version addon)))
    (let [output-fname (addon/downloaded-addon-fname (:name addon) (:version addon)) ;; addonname--1-2-3.zip
          output-path (join (fs/absolute install-dir) output-fname)] ;; /path/to/installed/addons/addonname--1.2.3.zip
      (binding [http/*cache* (cache)]
        (http/download-file (:download-url addon) output-path)))))

(def download-addon-affective
  (affects-addon-wrapper download-addon))

(defn-spec download-addon-guard (s/or :ok ::sp/archive-file, :error nil?)
  "downloads an addon, handling http and non-http errors, bad zip files, bad addons, bad directories."
  ([addon :addon/installable]
   (download-addon-guard addon (selected-addon-dir)))
  ([addon :addon/installable, install-dir ::sp/extant-dir]
   (cond
     ;; do some pre-installation checks
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

         (not (s/valid? ::sp/writeable-dir install-dir))
         (error (format "addon directory is not writable: %s" install-dir))

         (addon/overwrites-ignored? downloaded-file (get-state :installed-addon-list))
         (error "refusing to install addon that will overwrite an ignored addon.")

         (addon/overwrites-pinned? downloaded-file (get-state :installed-addon-list))
         (error "refusing to install addon that will overwrite a pinned addon.")

         :else downloaded-file)))))

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
       ;; todo: deprecate `test-only?`, logic has moved to `download-addon`
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

(defn-spec update-installed-addon! :addon/source-map
  "adds or updates an addon in the `:installed-addon-list` matching the `:source` and `:source-id` of the given `addon` with the given `addon`.
  order is not preserved.
  returns the given `addon`."
  [addon :addon/source-map]
  (update-installed-addon-list!
   (conj (utils/remove-items-matching (get-state :installed-addon-list)
                                      (juxt :source :source-id)
                                      addon)
         addon))
  addon)

(defn-spec load-installed-addon :addon/installed
  "loads the addon at the given `addon-dir` path, updating or adding it to the `:installed-addon-list`."
  [addon-dir ::sp/addon-dir]
  (update-installed-addon! (addon/load-installed-addon addon-dir (get-game-track))))

(defn-spec load-all-installed-addons nil?
  "guard function. offloads the hard work to `addon/load-all-installed-addons` then updates application state"
  []
  (if-let [addon-dir (selected-addon-dir)]
    (let [addon-list (addon/load-all-installed-addons addon-dir (get-game-track))]
      (info (format "loading (%s) installed addons in: %s" (count addon-list) addon-dir))
      (update-installed-addon-list! addon-list))

    ;; otherwise, ensure list of installed addons is cleared
    (update-installed-addon-list! [])))

;;
;; catalogue handling
;;

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

(defn-spec set-catalogue-location! nil?
  [catalogue-name keyword?]
  (if-let [catalogue (get-catalogue-location catalogue-name)]
    (swap! state assoc-in [:cfg :selected-catalogue] (:name catalogue))
    (warn "catalogue not found" catalogue-name))
  nil)

(defn-spec catalogue-local-path ::sp/file
  "given a catalogue-location, returns the local path to the catalogue."
  [catalogue-location :catalogue/location]
  ;; {:name :full, ...} => "/path/to/catalogue/dir/full-catalogue.json"
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
          message (format "downloading '%s' catalogue" (name (:name catalogue-location)))
          resp (http/download-file remote-catalogue local-catalogue message)]
      (when-not (http/http-error? resp)
        resp))))

(defn-spec download-current-catalogue (s/or :ok ::sp/extant-file, :error nil?)
  "downloads the currently selected (or default) catalogue. 
  issues a warning if no catalogue can be downloaded"
  []
  (if-let [catalogue-location (current-catalogue)]
    (download-catalogue catalogue-location)
    (warn "failed to find a downloadable catalogue")))

(defn-spec emergency-catalogue (s/or :static-catalogue :catalogue/catalogue, :unknown-catalogue nil?)
  "derives the requested catalogue from the static catalogue."
  [catalogue-location :catalogue/location]
  (let [opts {}
        catalogue (catalogue/read-catalogue (.getBytes (decompress-bytes static-catalogue)) opts)
        catalogue (assoc catalogue :emergency? true)]

    (warn (utils/message-list (format "the remote catalogue is unreachable or corrupt: %s" (:source catalogue-location))
                              [(str "backup catalogue generated: " (:datestamp catalogue))]))

    (case (:name catalogue-location)
      :full catalogue
      :short (catalogue/shorten-catalogue catalogue)
      ;;:curseforge (catalogue/filter-catalogue catalogue "curseforge")
      :wowinterface (catalogue/filter-catalogue catalogue "wowinterface")
      :tukui (catalogue/filter-catalogue catalogue "tukui")
      nil)))

;; --

(defn-spec moosh-addons :addon/toc+summary+match
  "merges the data from an installed addon with it's match in the catalogue"
  [installed-addon :addon/toc, db-catalogue-addon :addon/summary]
  (let [;; 2021-09: this may not be the case anymore.
        ;; nil fields are removed from the catalogue item because they might override good values in the .toc or .nfo
        db-catalogue-addon (utils/drop-nils db-catalogue-addon [:description])

        inst-source (:source installed-addon)
        dbc-source (:source db-catalogue-addon)
        source-mismatch (and inst-source
                             dbc-source
                             (not (= inst-source dbc-source)))]
    ;; merges left->right. catalogue-addon overwrites installed-addon, ':matched' overwrites catalogue-addon, etc
    (logging/addon-log installed-addon :info (format "found in catalogue with source \"%s\" and id \"%s\"" (:source db-catalogue-addon) (:source-id db-catalogue-addon)))
    (merge installed-addon
           db-catalogue-addon
           {:matched? true}
           ;; todo: I really want to disambiguate between where data is coming from.
           ;; it would mean carrying around the toc, nfo, catalogue, source updates and a merged set data.
           ;; all we have right now is the merged set (except this new `nfo/source` key)
           (when source-mismatch
             {:nfo/source (:source installed-addon)}))))

;;

(defn-spec write-user-catalogue! nil?
  "writes the `new-user-catalogue` to disk, using the current state of the `:user-catalogue` by default"
  ([]
   (write-user-catalogue! (catalogue/new-catalogue (get-state :user-catalogue :addon-summary-list))))
  ([new-user-catalogue :catalogue/catalogue]
   (catalogue/write-catalogue new-user-catalogue (paths :user-catalogue-file))
   nil))

(defn-spec get-create-user-catalogue (s/or :ok :catalogue/catalogue, :missing+no-create nil?)
  "returns the contents of the user catalogue at `user-catalogue-path`, creating one if `create?` is true (default)."
  ([]
   (get-create-user-catalogue (paths :user-catalogue-file) true))
  ([user-catalogue-path string?, create? boolean?]
   (when (and create?
              (not (fs/exists? user-catalogue-path)))
     (catalogue/write-empty-catalogue! user-catalogue-path))
   (let [catalogue (catalogue/read-catalogue user-catalogue-path {:bad-data? nil})
         curse? (fn [addon]
                  (-> addon :source (= "curseforge")))
         new-summary-list (->> catalogue :addon-summary-list (remove curse?) vec)]
     (when catalogue
       (merge catalogue {:addon-summary-list new-summary-list
                         :total (count new-summary-list)})))))

(defn-spec set-user-catalogue! nil?
  "given a catalogue, replaces the current user-catalogue in app state and generates a source-map index for faster lookups."
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
  [search-term cap filter-by]
  (let [args [(utils/nilable search-term) cap filter-by (get-state :user-catalogue-idx)]]
    (query-db :search args)))

(defn-spec empty-search-results nil?
  "empties the search state of results.
  this is to clear out anything between catalogue reloads."
  []
  (swap! state update-in [:search] merge (select-keys -search-state-template [:page :results :selected-results-list]))
  nil)

(defn-spec db-load-user-catalogue nil?
  "loads the user catalogue into state, but only if it hasn't already been loaded."
  []
  (when-not (get-state :user-catalogue)
    (let [create-user-catalogue? false
          path (paths :user-catalogue-file)]
      (set-user-catalogue! (get-create-user-catalogue path create-user-catalogue?))))
  nil)

(defn-spec load-current-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "merges the currently selected catalogue with the user-catalogue and returns the definitive list of addons 
  available to install. Handles malformed catalogue data by re-downloading catalogue."
  []
  (when-let [catalogue-location (current-catalogue)]
    (let [catalogue-path (catalogue-local-path catalogue-location)
          _ (info (format "loading '%s' catalogue" (name (:name catalogue-location))))

          ;; download from remote and try again when json can't be read
          bad-json-file-handler
          (fn []
            (warn "catalogue corrupted. re-downloading and trying again.")
            (fs/delete catalogue-path)
            (download-current-catalogue)
            (catalogue/read-catalogue
             catalogue-path
             {:bad-data? (fn []
                           (error (utils/reportable-error "remote catalogue failed to load and might be corrupt.")))}))

          catalogue-data (or (catalogue/read-catalogue catalogue-path {:bad-data? bad-json-file-handler})
                             (when-not testing?
                               (emergency-catalogue catalogue-location)))

          user-catalogue-data (get-state :user-catalogue)
          ;; 2021-06-30: merge order changed. catalogue data is now merged over the top of the user-catalogue.
          ;; this is because the user-catalogue may now contain addons from all hosts and is likely to be out of date.
          final-catalogue (p :p2/db:catalogue:merge-catalogues
                             (catalogue/merge-catalogues user-catalogue-data catalogue-data))]
      (-> final-catalogue :addon-summary-list count (str " addons in final catalogue") info)
      final-catalogue)))

(defn-spec db-load-catalogue nil?
  "loads the currently selected catalogue into the database, but only if we have a catalogue and it hasn't already 
  been loaded. Handles bad/invalid catalogues and merging the user catalogue"
  []
  (if (and (not (db-catalogue-loaded?))
           (current-catalogue))
    (let [final-catalogue (p :p2/db:catalogue
                             (load-current-catalogue))]
      (when-not (empty? final-catalogue)
        (p :p2/db:load
           (swap! state merge {:db (:addon-summary-list final-catalogue)
                               :db-stats {:num-addons (count (:addon-summary-list final-catalogue))
                                          :known-host-list (->> final-catalogue
                                                                :addon-summary-list
                                                                (map :source)
                                                                distinct
                                                                sort
                                                                vec)}}))))
    (debug "skipping db load. already loaded or no catalogue selected."))
  nil)

(defn-spec -match-installed-addon-list-with-catalogue :addon/installed-list
  "compare the list of addons installed with the database of known addons and try to match the two up.
  when a match is found (see `db/-db-match-installed-addon-list-with-catalogue`), merge it into the addon data."
  [database :addon/summary-list, installed-addon-list :addon/toc-list]
  (let [num-installed (count installed-addon-list)
        _ (when (> num-installed 0)
            (info (format "matching %s addons to catalogue" (count installed-addon-list))))
        match-results (db/-db-match-installed-addon-list-with-catalogue database installed-addon-list)
        [matched unmatched] (utils/split-filter :matched? match-results)

        ;; for those that *did* match, merge the installed addon data together with the catalogue data
        matched (mapv #(moosh-addons (:installed-addon %) (:catalogue-match %)) matched)
        ;; and then make them a single list of addons again

        ;; for those that failed to match but have nfo data we can fall back to that
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

    (when-not (= num-installed num-matched)
      (info (format "num installed %s, num matched %s" num-installed num-matched)))

    (when-not (empty? unmatched-names)
      (warn "you need to manually search for them and then re-install them")
      (warn (format "failed to find %s addons in the '%s' catalogue: %s"
                    (count unmatched-names)
                    (name (get-state :cfg :selected-catalogue))
                    (clojure.string/join ", " unmatched-names))))

    (when-not (empty? unmatched)
      (run! (fn [addon]
              (logging/with-addon addon
                (if (:ignore? addon)
                  (info "not matched to catalogue, addon is being ignored")
                  (warn (utils/message-list
                         (format "failed to find %s in the '%s' catalogue" (:dirname addon) (name (get-state :cfg :selected-catalogue)))
                         ["try searching for this addon by name or description"
                          "if this addon is part of a bundle, try \"File -> Re-install all\""])))))

            unmatched))

    expanded-installed-addon-list))

(defn-spec match-all-installed-addons-with-catalogue nil?
  "compare the list of addons installed with the database of known addons, match the two up, merge
  the two together and update the list of installed addons.
  Skipped when no catalogue loaded or no addon directory selected."
  []
  (when (and (db-catalogue-loaded?)
             (selected-addon-dir))
    (update-installed-addon-list!
     (-match-installed-addon-list-with-catalogue (get-state :db) (get-state :installed-addon-list)))))

(defn-spec match-installed-addon-with-catalogue (s/or :ok :addon/installed, :app-not-started nil?) ;; todo: revisit
  "compare the list of addons installed with the database of known addons, match the two up, merge
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
  [addon-summary]
  (binding [http/*cache* (cache)]
    (let [game-track (get-game-track)
          strict? (get-game-track-strictness)]
      (catalogue/expand-summary addon-summary game-track strict?))))

(defn-spec expandable? boolean?
  "returns `true` if the given addon in whatever form has the requisites to be 'expanded' (checked for updates from host)"
  [addon map?]
  (and (s/valid? :addon/expandable addon)
       (not (:ignore? addon))))

(defn-spec check-for-update :addon/toc
  "Returns given `addon` with source updates, if any, and sets an `update?` property if a different version is available.
  If addon is pinned to a specific version, `update?` will only be true if pinned version is different from installed version."
  [addon (s/or :unmatched :addon/toc
               :matched :addon/toc+summary+match)]
  (logging/with-addon addon
    (let [expanded-addon (when (expandable? addon)
                           (joblib/tick-delay 0.25)
                           (expand-summary-wrapper addon))
          addon (or expanded-addon addon) ;; expanded addon may still be nil
          has-update? (addon/updateable? addon)]
      (joblib/tick-delay 0.5)
      (when has-update?
        (info (format "update \"%s\" available from %s" (:version addon) (:source addon)))
        (when-not (= (get-game-track) (:game-track addon))
          (warn (format "update is for '%s' and the addon directory is set to '%s'"
                        (-> addon :game-track sp/game-track-labels-map)
                        (-> (get-game-track) sp/game-track-labels-map))))
        (when (:nfo/source addon)
          (warn (utils/message-list
                 (format "update is from a different host (%s) to the one it was installed from (%s)." (:source addon) (:nfo/source addon))
                 ["this happens when an exact match is not found in the selected catalogue."]))))

      (joblib/tick-delay 0.75)
      (assoc addon :update? has-update?))))

(def check-for-update-affective (affects-addon-wrapper check-for-update))

(defn-spec check-for-updates-serially nil?
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
  "downloads full details for all installed addons that can be found in summary list"
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
  "downloads full details for all installed addons that can be found in summary list"
  [addon (s/or :unmatched :addon/toc
               :matched :addon/toc+summary+match)]
  (when (selected-addon-dir)
    (update-installed-addon! (check-for-update addon))))


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

(defn-spec -latest-strongbox-release (s/or :ok string?, :failed? keyword)
  "returns the most recently released version of strongbox on github.
  returns `:failed` if an error occurred while downloading/decoding/extracting the version name, rather than `nil`.
  `nil` is used to mean 'not set' in the app state."
  []
  (binding [http/*cache* (cache)]
    (let [message "downloading strongbox version data"
          url "https://api.github.com/repos/ogri-la/strongbox/releases/latest"]
      (or (some-> url (http/download message) http/sink-error utils/from-json :tag_name)
          :failed))))

(defn-spec latest-strongbox-release (s/nilable string?)
  "returns the most recently released version of strongbox on github or `nil` if it can't."
  []
  (let [lsr (get-state :latest-strongbox-release)]
    (case lsr
      nil (let [lsr (-latest-strongbox-release)]
            (swap! state assoc :latest-strongbox-release lsr)
            (latest-strongbox-release)) ;; recurse
      :failed nil
      lsr)))

(defn-spec latest-strongbox-version? boolean?
  "returns true if the *running instance* of strongbox is the *most recent known* version of strongbox."
  []
  (let [version-running (strongbox-version)
        latest-release (or (latest-strongbox-release) version-running)
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
      (logging/with-label "export"
        (warn (format "Addon '%s' has no match in the catalogue and may be skipped during import. It's best all addons match before doing an export." (:name addon)))))

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
                 :interface-version 0
                 :toc/game-track :retail
                 :supported-game-tracks []
                 :installed-version "0"}
        addon-list (map #(merge padding %) addon-list)

        ;; todo: why aren't we just calling `expand-summary-wrapper` ?
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

        curse? (fn [addon]
                 (some-> addon :source (= "curseforge")))
        addon-list (remove curse? addon-list)

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

(defn-spec refresh-addon nil?
  "refreshes state of an individual addon.
  note! an addon may change it's set of directories between updates. this means the :dirname of the given `addon` may not represent the new :dirname, or that it's list of :grouped-addons are now stale.
  We could reload the addons using the set of old and new directories acquired, however that can lead to 'orphaned' addons where we unintentionally ensure old addons are propagated into new state using old data, despite the addon not existing on disk.
  We could do a lot more here but this operation already depends on re-reading the state of *all* addons from the filesystem for just a single addon update.
  The best thing to do is a full core/refresh after many individual core/refresh-addon."
  [addon :addon/installed]
  (->> addon
       :dirname
       (utils/join (selected-addon-dir))
       load-installed-addon
       match-installed-addon-with-catalogue
       check-addon-for-updates)
  nil)

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
  (p :p2/db (db-load-catalogue))

   ;; match installed addons to those in catalogue
  (match-all-installed-addons-with-catalogue)

   ;; for those addons that have matches, download their details
  (check-for-updates)

   ;; 2019-06-30, travis is failing with 403: Forbidden. Moved to gui init
   ;;(latest-strongbox-release) ;; check for updates after everything else is done 

   ;; seems like a good place to preserve the etag-db
  (save-settings!)

  nil)

(defn refresh-check
  "given a set of new directories, presumably introduced during an update, checks to see if new directories
  are present at all in the current set of known directories and does a `refresh` if not.
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
      (refresh))))

;; todo: move to ui.cli
(defn-spec remove-addon nil?
  "removes given installed addon"
  [installed-addon :addon/installed]
  (logging/with-addon installed-addon
    (addon/remove-addon (selected-addon-dir) installed-addon))
  (refresh))

;; todo: move to ui.cli
(defn-spec remove-many-addons nil?
  "deletes each of the addons in the given `toc-list` and then calls `refresh`"
  [installed-addon-list :addon/toc-list]
  (let [addon-dir (selected-addon-dir)]
    (doseq [installed-addon installed-addon-list]
      (logging/with-addon installed-addon
        (addon/remove-addon addon-dir installed-addon)))
    (refresh)))

;;

(defn-spec db-reload-catalogue nil?
  "unloads the database from state then calls `refresh` which will trigger a rebuild"
  []
  (swap! state assoc :db nil)
  (refresh))

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
  (let [useful-props
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
         ]

        adhoc-vars {:strongbox-version (strongbox-version)}
        vars (merge adhoc-vars (System/getProperties) @envvar.core/env)]
    (run! #(info (format "%s=%s" (name %) (get vars %))) (into useful-envvars useful-props))))

;;

(defn -start
  []
  (alter-var-root #'state (constantly (atom -state-template))))

(defn start
  [& [cli-opts]]
  (-start)
  (reset-logging!)
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
  (when (and @state (debug-mode?))
    (dump-useful-log-info)
    (info "wrote logs to:" (paths :log-file)))
  (reset! state nil))
