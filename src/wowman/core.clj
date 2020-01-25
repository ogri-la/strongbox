(ns wowman.core
  (:require
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? ends-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [envvar.core :refer [env]]
   [next.jdbc :as jdbc]
   [next.jdbc
    [sql :as sql]
    [result-set :as rs]]
   [wowman
    [config :as config]
    [zip :as zip]
    [http :as http]
    [logging :as logging]
    [nfo :as nfo]
    [utils :as utils :refer [join not-empty? false-if-nil nav-map nav-map-fn delete-many-files! static-slurp expand-path if-let*]]
    [catalog :as catalog]
    [toc]
    [specs :as sp]]))

;; acquired when switching between catalogs so the old database is shutdown
;; properly in one thread before being recreated in another
(def db-lock (Object.))

(def game-tracks ["retail" "classic"])

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

(def default-config-dir "~/.config/wowman")
(def default-data-dir "~/.local/share/wowman")

(defn generate-path-map
  "generates filesystem paths whose location may vary based on the current working directory and environment variables.
  this map of paths is generated during init and is then fixed in application state.
  ensure the correct environment variables and cwd are set prior to init for proper isolation during tests"
  []
  (let [wowman-suffix (fn [path]
                        (if-not (ends-with? path "/wowman")
                          (join path "wowman")
                          path))

        ;; https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        ;; ignoring XDG_CONFIG_DIRS and XDG_DATA_DIRS for now
        config-dir (-> @env :xdg-config-home utils/nilable (or default-config-dir) expand-path wowman-suffix)
        data-dir (-> @env :xdg-data-home utils/nilable (or default-data-dir) expand-path wowman-suffix)

        cache-dir (join data-dir "cache") ;; /home/you/.local/share/wowman/cache

        cfg-file (join config-dir "config.json") ;; /home/$you/.config/wowman/config.json
        etag-db-file (join data-dir "etag-db.json") ;; /home/$you/.local/share/wowman/etag-db.json

        user-catalog (join data-dir "user-catalog.json")

        ;; ensure path ends with `-file` or `-dir` or `-uri`
        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :cache-dir cache-dir
                  :cfg-file cfg-file
                  :etag-db-file etag-db-file

                  :user-catalog-file user-catalog
                  :catalog-dir data-dir}]
    path-map))

(def -state-template
  {:cleanup []

   ;; set once per application instance
   :in-repl? false

   :file-opts {} ;; options parsed from config file
   :cli-opts {} ;; options passed in on the command line

   ;; final config, result of merging :file-opts and :cli-opts
   ;;:cfg {:addon-dir-list []
   ;;      :debug? false ;; todo, remove
   ;;      :selected-catalog :short}
   :cfg nil ;; see config.clj
   :catalog-source-list [{:name :short :label "Short (default)" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"}
                         {:name :full :label "Full" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/full-catalog.json"}

                         {:name :tukui :label "Tukui" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/tukui-catalog.json"}
                         {:name :curseforge :label "Curseforge" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/curseforge-catalog.json"}
                         {:name :wowinterface :label "WoWInterface" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/wowinterface-catalog.json"}]

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc file data
   ;; ... then updated with data from catalog
   ;; ... then updated again with live data from curseforge
   ;; see specs/toc-addon


   :installed-addon-list nil

   :etag-db {}

   :db nil ;; see get-db
   :catalog-size nil ;; used to trigger those waiting for the catalog to become available

   ;; a map of paths whose location may vary according to the cwd and envvars.
   :paths nil

   ;; ui

   ;; the root swing window
   :gui nil

   ;; set to anything other than `nil` to have `main.clj` restart the gui
   :gui-restart-flag nil

   ;; which of the addon directories is currently selected
   :selected-addon-dir nil

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

(defn as-unqualified-hyphenated-maps
  "used to coerce keys in each row of resultset
  adapted from https://github.com/seancorfield/next-jdbc/blob/master/doc/result-set-builders.md#rowbuilder-and-resultsetbuilder"
  [rs opts]
  (let [xform #(-> % name lower-case (clojure.string/replace #"_" "-"))
        unqualified (constantly nil)]
    (rs/as-modified-maps rs (assoc opts :qualifier-fn unqualified :label-fn xform))))

(defn get-db
  "returns the database connection if it exists, else creates and sets a new one"
  []
  (if-let [db-conn (get-state :db)]
    ;; connection already exists, return that
    db-conn
    (do
      ;; else, create one, then return that.
      (swap! state merge {:db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (utils/uuid)})})
      (get-state :db))))

(defn db-query
  [query & {:keys [arg-list opts]}]
  (jdbc/execute! (get-db) (into [query] arg-list)
                 (merge {:builder-fn as-unqualified-hyphenated-maps} opts)))

(def select-*-catalog (str (static-slurp "resources/query--all-catalog.sql") " ")) ;; trailing space is important

(defn-spec db-split-category-list vector?
  "converts a pipe-separated list of categories into a vector"
  [category-list-str (s/nilable string?)]
  (if (empty? category-list-str)
    []
    (clojure.string/split category-list-str #"\|")))

(defn db-gen-game-track-list
  "converts the 'retail_track' and 'classic_track' boolean values in db into a list of strings"
  [row]
  (let [track-list (vec (remove nil? [(when (:retail-track row) "retail")
                                      (when (:classic-track row) "classic")]))
        row (dissoc row :retail-track :classic-track)]
    (if (empty? track-list)
      row
      (assoc row :game-track-list track-list))))

(defn db-preserve-integer-source-id-if-possible
  "source-id for 99.9999% of addons is an integer.
  this doesn't hold true for addons on Github or for other hosts in the future, so the database
  needs to store the value as a string. When coming out of the database, it's also a string - it's type has been lost.
  for no good reason, try to preserve it here"
  [source-id]
  (try
    (Integer. source-id)
    (catch Exception e
      source-id)))

(defn db-coerce-catalog-values
  "further per-row processing of catalog data after retrieving it from the database"
  [row]
  (when row
    (->> row
         (utils/coerce-map-values {:source-id db-preserve-integer-source-id-if-possible
                                   :category-list db-split-category-list})
         (db-gen-game-track-list))))

(defn db-search
  "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
  ([]
   ;; random list of addons, no preference
   (mapv db-coerce-catalog-values
         (db-query (str select-*-catalog "order by RAND() limit ?") :arg-list [(get-state :search-results-cap)])))
  ([uin]
   (let [uin% (str uin "%")
         %uin% (str "%" uin "%")]
     (mapv db-coerce-catalog-values
           (db-query (str select-*-catalog "where label ilike ? or description ilike ?")
                     :arg-list [uin% %uin%]
                     :opts {:max-rows (get-state :search-results-cap)})))))

(defn query-db
  "like `get-state`, uses 'paths' (keywords) to do predefined queries"
  [kw]
  (case kw
    :addon-summary-list (->> (db-query select-*-catalog) (mapv db-coerce-catalog-values))
    :catalog-size (-> "select count(*) as num from catalog" db-query first :num)

    nil))

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
        default-game-track (if (clojure.string/index-of addon-dir "_classic_") "classic" "retail")]
    (add-addon-dir! addon-dir default-game-track)
    (swap! state assoc :selected-addon-dir addon-dir))
  nil)

(defn-spec remove-addon-dir! nil?
  ([]
   (when-let [addon-dir (get-state :selected-addon-dir)]
     (remove-addon-dir! addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [matching #(= addon-dir (:addon-dir %))
         new-addon-dir-list (->> (get-state :cfg :addon-dir-list) (remove matching) vec)]
     (swap! state assoc-in [:cfg :addon-dir-list] new-addon-dir-list)
     ;; this may be nil if the new addon-dir-list is empty
     (swap! state assoc :selected-addon-dir (-> new-addon-dir-list first :addon-dir)))
   nil))

(defn available-addon-dirs
  []
  (mapv :addon-dir (get-state :cfg :addon-dir-list)))

(defn-spec addon-dir-map (s/or :ok ::sp/addon-dir-map, :missing nil?)
  ([]
   (addon-dir-map (get-state :selected-addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (let [addon-dir-list (get-state :cfg :addon-dir-list)]
     (when-not (empty? addon-dir-list)
       (first (filter #(= addon-dir (:addon-dir %)) addon-dir-list))))))

(defn-spec set-game-track! nil?
  ([game-track ::sp/game-track]
   (set-game-track! game-track (get-state :selected-addon-dir)))
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
   (when-let [addon-dir (get-state :selected-addon-dir)]
     (get-game-track addon-dir)))
  ([addon-dir ::sp/addon-dir]
   (-> addon-dir addon-dir-map :game-track)))

;; settings

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
    (when (:verbosity cli-opts)
      (logging/change-log-level (:verbosity cli-opts)))
    (swap! state merge final-config))
  nil)


;;
;; utils
;;


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
  (when-let [download-uri (:download-uri addon)]
    (let [output-fname (downloaded-addon-fname (:name addon) (:version addon)) ;; addonname--1-2-3.zip
          output-path (join (fs/absolute download-dir) output-fname)] ;; /path/to/installed/addons/addonname--1.2.3.zip
      (binding [http/*cache* (cache)]
        (http/download-file download-uri output-path)))))

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

        ;; an addon may unzip to many directories, each directory needs the nfo file
        update-nfo-fn (fn [zipentry]
                        (let [addon-dirname (:path zipentry)
                              primary? (= addon-dirname (:path primary-dirname))]
                          (nfo/write-nfo install-dir addon addon-dirname primary?)))

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

(defn-spec load-installed-addons nil?
  []
  (if-let [addon-dir (get-state :selected-addon-dir)]
    (do
      (info "(re)loading installed addons:" addon-dir)
      (update-installed-addon-list! (wowman.toc/installed-addons addon-dir)))
    ;; ensure the previous list of addon dirs are cleared if :selected-addon-dir is unset
    (update-installed-addon-list! [])))

;;
;; catalog handling
;;

(defn-spec get-catalog-source (s/or :ok ::sp/catalog-source-map, :not-found nil?)
  ([]
   (get-catalog-source (get-state :cfg :selected-catalog)))
  ([catalog-name keyword?]
   (->> (get-state :catalog-source-list) (filter #(= catalog-name (:name %))) first)))

(defn-spec set-catalog-source! nil?
  [catalog-name keyword?]
  (if-let [catalog (get-catalog-source catalog-name)]
    (swap! state assoc-in [:cfg :selected-catalog] (:name catalog))
    (warn "catalog not found" catalog-name))
  nil)

(defn-spec catalog-local-path ::sp/file
  "given a catalog-source map, returns the local path to the catalog."
  [catalog-source ::sp/catalog-source-map]
  ;; {:name :full ...} => "/path/to/catalog/dir/full-catalog.json"
  (utils/join (paths :catalog-dir) (-> catalog-source :name name (str "-catalog.json"))))

(defn-spec find-catalog-local-path (s/or :ok ::sp/file, :not-found nil?)
  "convenience wrapper around `catalog-local-path`"
  [catalog-name keyword?]
  (some-> catalog-name get-catalog-source catalog-local-path))

(defn-spec download-catalog (s/or :ok ::sp/extant-file, :error nil?)
  "downloads catalog to expected location, nothing more"
  [& [catalog] (s/* keyword?)]
  (binding [http/*cache* (cache)]
    (if-let [current-catalog (get-state :cfg :selected-catalog)]
      (let [catalog-source (get-catalog-source (or catalog current-catalog)) ;; {:name :full :label "Full" :source "https://..."}
            remote-catalog (:source catalog-source)
            local-catalog (catalog-local-path catalog-source)]
        (http/download-file remote-catalog local-catalog :message (format "downloading catalog '%s'" (:label catalog-source))))
      (error "failed to find catalog:" catalog))))

(defn-spec moosh-addons ::sp/toc-addon-summary
  "merges the data from an installed addon with it's match in the catalog"
  [installed-addon ::sp/toc, db-catalog-addon ::sp/addon-summary]
  (let [;; nil fields are removed from the catalog item because they might override good values in the .toc or .nfo
        db-catalog-addon (utils/drop-nils db-catalog-addon [:description])]
    ;; merges left->right. catalog-addon overwrites installed-addon, ':matched' overwrites catalog-addon, etc
    (merge installed-addon db-catalog-addon {:matched? true})))


;;


(defn-spec get-create-user-catalog ::sp/catalog
  "returns the contents of the user catalog, creating one if necessary"
  []
  (let [user-catalog-path (paths :user-catalog-file)]
    (catalog/read-catalog
     (if (fs/exists? user-catalog-path)
       user-catalog-path
       (catalog/write-empty-catalog! user-catalog-path)))))

(defn-spec add-user-addon! nil?
  "adds one or many addons to the user catalog"
  [addon-summary (s/or :single ::sp/addon-summary, :many ::sp/addon-summary-list)]
  (let [addon-summary-list (if (sequential? addon-summary)
                             addon-summary
                             [addon-summary])
        user-catalog-path (paths :user-catalog-file)
        user-catalog (get-create-user-catalog)
        tmp-catalog (catalog/new-catalog addon-summary-list)
        new-user-catalog (catalog/merge-catalogs user-catalog tmp-catalog)]
    (catalog/write-catalog new-user-catalog user-catalog-path))
  nil)

;;

(defn find-in-db
  "looks for `installed-addon` in the database, matching `toc-key` to a `catalog-key`.
  if a `toc-key` and `catalog-key` are actually lists, then all the `toc-keys` must match the `catalog-keys`"
  [installed-addon toc-keys catalog-keys]
  (let [catalog-keys (if (vector? catalog-keys) catalog-keys [catalog-keys]) ;; ["source" "source_id"] => ["source" "source_id"], "name" => ["name"]
        sql-arg-template (clojure.string/join " AND " (mapv #(format "%s = ?" %) catalog-keys)) ;; "source = ? AND source_id = ?", "name = ?"

        toc-keys (if (vector? toc-keys) toc-keys [toc-keys])
        sql-arg-vals (mapv #(get installed-addon %) toc-keys) ;; [:source :source-id] => ["curseforge" 12345], [:name] => ["foo"]

        missing-args? (some nil? sql-arg-vals)

        ;; there are cases where the installed-addon is missing an attribute to match on. typically happens on :alias
        _ (when missing-args?
            (debug "(debug) failed to find all values for sql query, refusing to match on nil. keys:" toc-keys "vals:" sql-arg-vals))

        sql (str select-*-catalog "where " sql-arg-template)
        results (if missing-args?
                  [] ;; don't look for 'nil', just skip with no results
                  (db-query sql :arg-list sql-arg-vals))
        match (-> results first db-coerce-catalog-values)]
    (when match
      ;; {:idx [:name :alt-name], :key "deadly-boss-mods", :match {...}, ...}
      {:idx [toc-keys catalog-keys]
       :key sql-arg-vals
       :installed-addon installed-addon
       :match match
       :final (moosh-addons installed-addon match)})))

(defn find-first-in-db
  [installed-addon match-on-list]
  (if (empty? match-on-list) ;; we may have exhausted all possibilities. not finding a match is ok
    installed-addon
    (let [[toc-keys catalog-keys] (first match-on-list)
          match (find-in-db installed-addon toc-keys catalog-keys)]
      (if-not match ;; recur
        (find-first-in-db installed-addon (rest match-on-list))
        match))))

(defn -db-match-installed-addons-with-catalog
  "for each installed addon, search the catalog across multiple joins until a match is found. returns immediately when first match is found"
  [installed-addon-list]
  (let [;; toc-key -> catalog-key
        ;; most -> least desirable match
        match-on-list [[[:source :source-id]  ["source" "source_id"]] ;; nest to search across multiple parameters
                       [:alias "name"]
                       [:name "name"] [:name "alt_name"] [:label "label"] [:dirname "label"]]]
    (for [installed-addon installed-addon-list]
      (find-first-in-db installed-addon match-on-list))))

;;

(defn-spec match-installed-addons-with-catalog nil?
  "when we have a list of installed addons as well as the addon list,
   merge what we can into ::specs/addon-toc records and update state.
   any installed addon not found in :addon-idx has a mapping problem"
  []
  (when (get-state :selected-addon-dir) ;; don't even bother if we have nothing to match it to
    (info "matching installed addons to catalog")
    (let [inst-addons (get-state :installed-addon-list)
          catalog (query-db :addon-summary-list)

          match-results (-db-match-installed-addons-with-catalog inst-addons)
          [matched unmatched] (utils/split-filter #(contains? % :final) match-results)

          matched (mapv :final matched)

          unmatched-names (set (map :name unmatched))

          expanded-installed-addon-list (into matched unmatched)

          [num-installed num-matched] [(count inst-addons) (count matched)]]

      (when-not (= num-installed num-matched)
        (info "num installed" num-installed ", num matched" num-matched))

      (when-not (empty? unmatched)
        (warn "you need to manually search for them and then re-install them")
        (warn (format "failed to find %s installed addons in the '%s' catalog: %s"
                      (count unmatched)
                      (name (get-state :cfg :selected-catalog))
                      (clojure.string/join ", " unmatched-names))))

      (update-installed-addon-list! expanded-installed-addon-list))))

;; catalog db handling

(defn db-shutdown
  []
  (try
    (db-query "shutdown")
    (catch org.h2.jdbc.JdbcSQLNonTransientConnectionException e
      ;; "Database is already closed" (it's not)
      (debug (str e)))))

(defn-spec db-init nil?
  []
  (debug "creating 'catalog' table")
  (jdbc/execute! (get-db) [(static-slurp "resources/table--catalog.sql")])
  (debug "creating category tables")
  (jdbc/execute! (get-db) [(static-slurp "resources/table--category.sql")])
  (swap! state update-in [:cleanup] conj db-shutdown)
  nil)

(defn db-catalog-loaded?
  []
  (> (query-db :catalog-size) 0))

(defn-spec -db-load-catalog nil?
  [catalog-data ::sp/catalog]
  (let [ds (get-db)
        {:keys [addon-summary-list]} catalog-data

        ;; filter out items from unsupported sources
        addon-summary-list (filterv #(utils/in? (:source %) sp/catalog-sources) addon-summary-list)

        addon-categories (mapv (fn [{:keys [source-id source category-list]}]
                                 (mapv (fn [category]
                                         [source-id source category]) category-list)) addon-summary-list)

        ;; using `set` was a symptom of a problem with duplicate categories affecting curseforge
        ;; I think it's safest to leave it in for now
        addon-categories (->> addon-categories utils/shallow-flatten set vec)

        ;; distinct list of :categories
        category-list (->> addon-categories (mapv rest) set vec)

        xform-row (fn [row]
                    (let [ignored [:category-list :age :game-track-list :created-date]
                          mapping {:source-id :source_id
                                   :alt-name :alt_name
                                   :download-count :download_count
                                   ;;:created-date :created_date ;; curseforge only and unused
                                   :updated-date :updated_date}
                          new {:retail_track (utils/in? "retail" (:game-track-list row))
                               :classic_track (utils/in? "classic" (:game-track-list row))}]

                      (-> row (utils/dissoc-all ignored) (rename-keys mapping) (merge new))))]

    (jdbc/with-transaction [tx ds]
      ;;    1.703391 msec
      ;; ~100 items
      (time (sql/insert-multi! ds :category [:source :name] category-list))
      ;;  871.427154 msec
      ;; ~10k items
      (time (doseq [row addon-summary-list]
              (sql/insert! ds :catalog (xform-row row))))

      ;; 1337.340542 msec
      ;; ~20k items (avg 2 categories per addon)
      (time (let [category-map (db-query "select name, id from category")
                  category-map (into {} (mapv (fn [{:keys [name id]}]
                                                {name id}) category-map))]

              (doseq [[source-id source category] addon-categories]
                (sql/insert! ds :addon_category {:addon_source source
                                                 :addon_source_id source-id
                                                 :category_id (get category-map category)})))))

    (swap! state assoc :catalog-size (query-db :catalog-size)))
  nil)

(defn-spec db-load-catalog nil?
  []
  (when-not (db-catalog-loaded?)
    (let [catalog-source (get-catalog-source)
          catalog-path (catalog-local-path catalog-source)
          _ (info (format "loading catalog '%s'" (name (get-state :cfg :selected-catalog))))
          _ (debug "loading addon summaries from catalog into database:" catalog-path)

          ;; download from remote and try again when json can't be read
          bad-json-file-handler (fn []
                                  (warn "catalog corrupted. re-downloading and trying again.")
                                  (fs/delete catalog-path)
                                  (download-catalog)
                                  (catalog/read-catalog
                                   catalog-path
                                   :bad-data? (fn []
                                                (error "please report this! https://github.com/ogri-la/wowman/issues")
                                                (error "catalog *still* corrupted and cannot be loaded. try another catalog from the 'catalog' menu"))))

          catalog-data (utils/nilable
                        (catalog/read-catalog catalog-path :bad-data? bad-json-file-handler))
          user-catalog-data (utils/nilable
                             (catalog/read-catalog (paths :user-catalog-file) :bad-data? nil))
          final-catalog (catalog/merge-catalogs catalog-data user-catalog-data)]
      (when-not (empty? final-catalog)
        (-db-load-catalog final-catalog)))))

(defn-spec refresh-user-catalog nil?
  "re-fetch each item in user catalog using the URI and replace old entry with any updated details"
  []
  (binding [http/*cache* (cache)]
    (info "refreshing \"user-catalog.json\", this may take a minute ...")
    (->> (get-create-user-catalog)
         :addon-summary-list
         (map :uri)
         (map catalog/parse-user-string)
         add-user-addon!)))

;;
;; addon summary and toc merging
;;

(defn expand-summary-wrapper
  [addon-summary]
  (binding [http/*cache* (cache)]
    (let [game-track (get-game-track) ;; scope creep, but it fits so nicely
          wrapper (affects-addon-wrapper catalog/expand-summary)]
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

    ;; failed to match against catalog or expand-summary returned nil (couldn't expand for whatever reason)
    ;; in this case, we set a flag saying this addon shouldn't be updated
    (assoc toc :update? false)))

(defn-spec check-for-updates nil?
  "downloads full details for all installed addons that can be found in summary list"
  []
  (when (get-state :selected-addon-dir)
    (info "checking for updates")
    (update-installed-addon-list! (mapv check-for-update (get-state :installed-addon-list)))
    (info "done checking for updates")))

;; ui interface

(defn-spec delete-cache! nil?
  "deletes the 'cache' directory that contains scraped html files and the etag db file.
  these are regenerated when missing"
  []
  (warn "deleting cache")
  (fs/delete-dir (paths :cache-dir))
  (fs/delete (paths :etag-db-file))

  (fs/mkdirs (paths :cache-dir)) ;; todo: this and `init-dirs` needs revisiting
  nil)

(defn-spec delete-downloaded-addon-zips! nil?
  []
  (delete-many-files! (get-state :selected-addon-dir) #".+\-\-.+\.zip$" "downloaded addon zip"))

(defn-spec delete-wowman-json-files! nil?
  []
  (delete-many-files! (get-state :selected-addon-dir) #"\.wowman\.json$" ".wowman.json"))

(defn-spec delete-wowmatrix-dat-files! nil?
  []
  (delete-many-files! (get-state :selected-addon-dir) #"(?i)WowMatrix.dat$" "WowMatrix.dat"))

(defn-spec delete-catalog-files! nil?
  []
  (delete-many-files! (paths :data-dir) #".+\-catalog\.json$" "catalog"))

(defn-spec clear-all-temp-files! nil?
  []
  (delete-downloaded-addon-zips!)
  (delete-catalog-files!)
  (delete-cache!))

;; version checking

(defn-spec wowman-version string?
  "returns this version of wowman"
  []
  (versioneer/get-version "ogri-la" "wowman"))

(defn-spec latest-wowman-release string?
  "returns the most recently released version of wowman it can find"
  []
  (binding [http/*cache* (cache)]
    (let [message "downloading wowman version data"
          url "https://api.github.com/repos/ogri-la/wowman/releases/latest"
          resp (utils/from-json (http/download url :message message))]
      (-> resp :tag_name))))

(defn-spec latest-wowman-version? boolean?
  "returns true if the *running instance* of wowman is the *most recent known* version of wowman."
  []
  (let [latest-release (latest-wowman-release)
        version-running (wowman-version)
        sorted-asc (utils/sort-semver-strings [latest-release version-running])]
    (= version-running (last sorted-asc))))

;; import/export

(defn-spec export-installed-addon ::sp/export-record
  "given an addon summary from a catalogue or .toc file data, derive an 'export-record' that can be used to import addon later"
  [addon (s/or :catalog ::sp/addon-summary, :installed ::sp/toc), game-track (s/nilable ::sp/game-track)]
  (let [stub (select-keys addon [:name :source :source-id])
        ;; when there is a catalog match, attach the game track as well
        game-track (when (and (:source stub) game-track) {:game-track game-track})]
    (merge stub game-track)))

(defn-spec export-installed-addon-list ::sp/export-record-list
  "derives an 'export-record' from a list of either addon summaries from a catalog or .toc file data from installed addons"
  [addon-list (s/or :catalog ::sp/addon-summary-list, :installed ::sp/toc-list), game-track (s/nilable ::sp/game-track)]
  (mapv #(export-installed-addon % game-track) addon-list))

(defn-spec export-installed-addon-list-safely ::sp/extant-file
  "writes the name, source, source-id and current game track to a json file for each installed addon in the currently selected addon directory"
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str)
        output-file (utils/replace-file-ext output-file ".json")
        addon-list (get-state :installed-addon-list)
        export (export-installed-addon-list addon-list (get-game-track))]

    ;; target any unmatched addons with no `:source` from the addon list and emit a warning
    (doseq [addon (remove :source addon-list)]
      (warn (format "Addon '%s' has no match in the catalog and may be skipped during import. It's best all addons match before doing an export." (:name addon))))

    (utils/dump-json-file output-file export)
    (info "wrote:" output-file)
    output-file))

(defn-spec export-catalog-addon-list ::sp/export-record-list
  "given a catalogue of addons, generates a list of 'export-records' from the list of addon summaries"
  [catalog ::sp/catalog]
  (let [;; exporting installed addons feels like it's for personal use whereas exporting the user catalogue feels like
        ;; it's for sharing with others for that reason alone I'm skipping the game track in this export.
        game-track nil
        addon-list (:addon-summary-list catalog)]
    (export-installed-addon-list addon-list game-track)))

(defn-spec export-user-catalog-addon-list-safely ::sp/extant-file
  "generates a list of 'export-records' from the addon summaries in the user catalogue and writes them to the given `output-file`"
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str (utils/replace-file-ext ".json"))
        catalog (get-create-user-catalog)
        export (export-catalog-addon-list catalog)]
    (utils/dump-json-file output-file export)
    (info "wrote:" output-file)
    output-file))

;; created to investigate some performance issues, seems sensible to keep it separate
(defn -mk-import-idx
  [addon-list]
  (let [key-fn #(select-keys % [:source :name])
        addon-summary-list (query-db :addon-summary-list)
        ;; todo: this sucks. use a database
        catalog-idx (group-by key-fn addon-summary-list)
        find-expand (fn [addon]
                      (when-let [matching-addon (first (get catalog-idx (key-fn addon)))]
                        (expand-summary-wrapper matching-addon)))
        matching-addon-list (->> addon-list (map find-expand) (remove nil?) vec)]
    matching-addon-list))

;; v1 takes 13.7 seconds to build an index using the 'short' catalog
;; this function can still be improved
(defn import-addon-list-v1
  "handles exports with partial information (name, or name and source) from <=0.10.0 versions of wowman."
  [addon-list] ;; todo: spec
  (info (format "attempting to import %s addons. this may take a minute" (count addon-list)))
  (let [matching-addon-list (-mk-import-idx addon-list)
        addon-dir (get-state :selected-addon-dir)]
    (doseq [addon matching-addon-list]
      (install-addon addon addon-dir))))

;; v2 uses the same mechanism to match addons as the rest of the app does
(defn import-addon-list-v2
  "handles exports with full information (name and source and source-id) from wowman >=0.10.1.
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

        match-results (-db-match-installed-addons-with-catalog addon-list)
        [matched unmatched] (utils/split-filter #(contains? % :final) match-results)
        addon-dir (get-state :selected-addon-dir)

        ;; this is what v1 does, but it's hidden away in `expand-summary-wrapper`
        default-game-track (get-game-track)]

    (doseq [db-match match-results
            :let [addon (:installed-addon db-match) ;; ignore 'installed' bit
                  db-entry (:final db-match)
                  game-track (get addon :game-track default-game-track)]]
      (when-let [expanded-addon (catalog/expand-summary db-entry game-track)]
        (install-addon expanded-addon addon-dir)))))

(defn-spec import-exported-file nil?
  [path ::sp/extant-file]
  (info "importing exports file:" path)
  (let [nil-me (constantly nil)
        addon-list (utils/load-json-file-safely path
                                                :bad-data? nil-me
                                                :data-spec ::sp/export-record-list
                                                :invalid-data? nil-me)
        full-data? (fn [addon]
                     (utils/all (mapv #(contains? addon %) [:source :source-id :name])))
        [full-data, partial-data] (utils/split-filter full-data? addon-list)]
    (when-not (empty? partial-data)
      (import-addon-list-v1 partial-data))
    (when-not (empty? full-data)
      (import-addon-list-v2 full-data))))

;; 

(defn refresh
  [& _]
  (download-catalog)      ;; downloads the big long list of addon information stored on github

  (db-init)               ;; creates an in-memory database and some empty tables

  (load-installed-addons) ;; parse toc files in install-dir. do this first so we see *something* while catalog downloads (next)

  (db-load-catalog)       ;; load the contents of the catalog into the database

  (match-installed-addons-with-catalog) ;; match installed addons to those in catalog

  (check-for-updates)     ;; for those addons that have matches, download their details

  ;;(latest-wowman-release) ;; check for updates after everything else is done ;; 2019-06-30, travis is failing with 403: Forbidden. Moved to gui init

  (save-settings)         ;; seems like a good place to preserve the etag-db
  nil)

(defn-spec -install-update-these nil?
  [updateable-toc-addons (s/coll-of ::sp/addon-or-toc-addon)]
  (doseq [toc-addon updateable-toc-addons]
    (install-addon toc-addon (get-state :selected-addon-dir))))

(defn -updateable?
  [rows]
  (filterv :update? rows))

(defn -re-installable?
  "an addon can only be re-installed if it's been matched to an addon in the catalog"
  [rows]
  ;; no longer true in 0.9.0. an addon may be found in the catalog but may not match the selected game track
  ;;(filterv :uri rows)) ;; :uri is only present in addons that have a match

  ;; todo: this indirect logic smells. something like this should be done instead:
  ;; (filterv (comp :release-available? :matched?) rows)
  (filterv :download-uri rows))

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
  (let [addon-dir (get-state :selected-addon-dir)
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

(defn-spec db-reload-catalog nil?
  []
  (locking db-lock
    (db-shutdown)
    (refresh)))

;; installing addons from strings

(defn-spec add+install-user-addon! (s/or :ok ::sp/addon, :less-ok ::sp/addon-summary, :failed nil?)
  "convenience. parses string, adds to user catalog, installs addon then reloads database.
  relies on UI to call refresh (or not)"
  [addon-url string?]
  (binding [http/*cache* (cache)]
    (if-let* [addon-summary (catalog/parse-user-string addon-url)
              ;; game track doesn't matter when adding it to the user catalogue ...
              addon (or
                     (catalog/expand-summary addon-summary "retail")
                     (catalog/expand-summary addon-summary "classic"))
              test-only? true
              _ (install-addon-guard addon (get-state :selected-addon-dir) test-only?)]

             ;; ... but does matter when installing it in to the current addon directory
             (let [addon (expand-summary-wrapper addon-summary)]

               (add-user-addon! addon-summary)

               (when addon
                 (install-addon addon (get-state :selected-addon-dir))
                 (db-reload-catalog)
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
    (state-bind [:selected-addon-dir] reset-state-fn)))

(defn watch-for-catalog-change
  "when the catalog changes, the list of available addons should be re-read"
  []
  (state-bind [:cfg :selected-catalog] (fn [_] (db-reload-catalog))))

(defn-spec init-dirs nil?
  []
  ;; 2019-10-13: transplanted from `main/validate`
  ;; this validation depends on paths that are not generated until application init

  ;; data directory doesn't exist and parent directory isn't writable
  ;; nowhere to create data dir, nowhere to store download catalog. non-starter

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
  (http/prune-cache-dir (paths :cache-dir))
  nil)

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
  (load-settings! cli-opts)
  (watch-for-addon-dir-change)
  (watch-for-catalog-change)

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
