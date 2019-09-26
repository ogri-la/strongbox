(ns wowman.core
  (:require
   [clojure.set :refer [rename-keys]]
   [clojure.string :refer [lower-case starts-with? trim]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [spec-tools.core :as spec-tools]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [envvar.core :refer [env]]
   [next.jdbc :as jdbc]
   [next.jdbc
    [sql :as sql]
    [result-set :as rs]]
   [wowman
    [zip :as zip]
    [http :as http]
    [logging :as logging]
    [nfo :as nfo]
    [utils :as utils :refer [join not-empty? false-if-nil nav-map nav-map-fn]]
    [catalog :as catalog]
    [toc]
    [specs :as sp]]))

(def game-tracks ["retail" "classic"])

(def -colour-map
  {:notice/error :tomato
   :notice/warning :lemonchiffon
   ;;:installed/unmatched :tomato
   :installed/needs-updating :lemonchiffon
   :installed/hovering "#e6e6e6"
   :search/already-installed "#99bc6b"} ;; greenish
  )

(def colours (utils/nav-map-fn -colour-map))

;; not in `paths` because it's not configurable
;; deprecated
(def remote-catalog "https://github.com/ogri-la/wowman-data/releases/download/daily/catalog.json")

(defn paths
  "returns a map of paths whose location may vary depending on the location of the current working directory"
  [& path]
  (let [;; https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        ;; ignoring XDG_CONFIG_DIRS and XDG_DATA_DIRS for now
        config-dir (or (:xdg-config-home @env) "~/.config/wowman")
        config-dir (-> config-dir fs/expand-home fs/normalized fs/absolute str)

        data-dir (or (:xdg-data-home @env) "~/.local/share/wowman")
        data-dir (-> data-dir fs/expand-home fs/normalized fs/absolute str)

        ;; cache files are deleted regularly. even if you fuck up with "XDG_DATA_HOME=/" then the worse that
        ;; can happen, even if you run wowman as root, is you get /cache/ or /home/$you/cache/ created and
        ;; files within it deleted. /cache and /home/$you/cache are not common directories
        cache-dir (join data-dir "cache") ;; /home/you/.local/share/wowman/cache

        cfg-file (join config-dir "config.json") ;; /home/$you/.config/wowman/config.json
        etag-db-file (join data-dir "etag-db.json") ;; /home/$you/.local/share/wowman/etag-db.json

        ;;curseforge-catalog (join data-dir "curseforge.json") ;; /home/$you/.local/share/wowman/cache/curseforge.json
        ;;curseforge-catalog-updates (join data-dir "curseforge-updates.json") ;; todo: remove this intermediate file
        ;;wowinterface-catalog (join data-dir "wowinterface.json")

        ;;catalog (join data-dir "catalog.json")
        ;;catalog-short (join data-dir "catalog-short.json")

        ;; ensure path ends with `-file` or `-dir` or `-uri`
        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :cache-dir cache-dir
                  :cfg-file cfg-file
                  :etag-db-file etag-db-file

                  :catalog-dir data-dir

                  ;;:catalog-file catalog
                  ;;:catalog-file-short catalog-short

                  ;;:curseforge-catalog-file curseforge-catalog
                  ;;:curseforge-catalog-updates-file curseforge-catalog-updates ;; todo, remove
                  ;;:wowinterface-catalog-file wowinterface-catalog
                  }]
    (nav-map path-map path)))

(def -state-template
  {:cleanup []

   :file-opts {} ;; options parsed from config file
   :cli-opts {} ;; options passed in on the command line

   ;; final config, result of merging :file-opts and :cli-opts
   :cfg {:addon-dir-list []
         :debug? false ;; todo, remove
         :selected-catalog :short}

   :catalog-source-list [{:name :short :label "Short (default)" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/catalog-short.json"}
                         ;; todo: change the name of the release from 'daily' to something else
                         {:name :full :label "Full" :source remote-catalog}

                         {:name :curseforge-catalog-file :label "Curseforge" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/curseforge.json"}
                         {:name :wowinterface-catalog-file :label "WoWInterface" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/wowinterface.json"}]

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc file data
   ;; ... then updated with data from catalog
   ;; ... then updated again with live data from curseforge
   ;; see specs/toc-addon


   :installed-addon-list nil

   :etag-db {}

   :db nil
   :catalog-size nil ;; used to trigger those waiting for the catalog to become available

   ;; ui

   ;; the root swing window
   :gui nil

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

(defn as-unqualified-hyphenated-maps
  "used to coerce keys in each row of resultset
  adapted from https://github.com/seancorfield/next-jdbc/blob/master/doc/result-set-builders.md#rowbuilder-and-resultsetbuilder"
  [rs opts]
  (let [xform #(-> % name lower-case (clojure.string/replace #"_" "-"))
        unqualified (constantly nil)]
    (rs/as-modified-maps rs (assoc opts :qualifier-fn unqualified :label-fn xform))))

(defn db-query
  [query & {:keys [arg-list opts]}]
  (jdbc/execute! (get-state :db) (into [query] arg-list)
                 (merge {:builder-fn as-unqualified-hyphenated-maps} opts)))

(def select-*-catalog (str (slurp "resources/query--all-catalog.sql") " ")) ;; trailing space is important

(defn-spec db-split-category-list vector?
  "converts a pipe-separated list of categories into a vector"
  [category-list-str (s/nilable string?)]
  (if (empty? category-list-str)
    []
    (clojure.string/split category-list-str #"\|")))

(defn db-gen-game-track-list
  "converts the 'retail_track' and 'vanilla_track' boolean values into a list of strings"
  [row]
  (let [track-list (vec (remove nil? [(when (:retail-track row) "retail")
                                      (when (:vanilla-track row) "classic")]))
        row (dissoc row :retail-track :vanilla-track)]
    (if (empty? track-list)
      row
      (assoc row :game-track-list track-list))))

(defn db-coerce-catalog-values
  "further per-row processing of catalog data after retrieving it from the database"
  [row]
  (when row
    (->> row
         (utils/coerce-map-values {:category-list db-split-category-list})
         (db-gen-game-track-list))))

(defn db-search
  "searches database for addons whose name or description contains given user input.
  if no user input, returns a list of randomly ordered results"
  ([]
   ;; random list of addons, no preference
   (db-query (str select-*-catalog "order by RAND() limit ?") :arg-list [(get-state :search-results-cap)]))
  ([uin]
   (let [uin% (str uin "%")
         %uin% (str "%" uin "%")]
     (mapv db-coerce-catalog-values
           (db-query (str select-*-catalog "where label ilike ? or description ilike ?")
                     :arg-list [uin% %uin%]
                     :opts {:max-rows (get-state :search-results-cap)})))))

(defn get-db
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
                       (error e "error caught in watch! your callback *must* be catching these or the thread dies silently! rethrowing")
                       (throw e))))))

    (swap! state update-in [:cleanup] conj rmwatch)
    nil))

(defn-spec debugging? boolean?
  "true, if we we're in 'debug' mode"
  []
  (false-if-nil (get-state :cfg :debug?)))

;; addon dirs

(defn-spec addon-dir-exists? boolean?
  ([addon-dir ::sp/addon-dir]
   (addon-dir-exists? addon-dir (get-state :cfg :addon-dir-list)))
  ([addon-dir ::sp/addon-dir, addon-dir-list ::sp/addon-dir-list]
   (not (nil? (some #{addon-dir} (mapv :addon-dir addon-dir-list))))))

(defn-spec add-addon-dir! nil?
  [addon-dir ::sp/addon-dir, game-track ::sp/game-track]
  (let [stub {:addon-dir addon-dir :game-track game-track}]
    (when-not (addon-dir-exists? addon-dir)
      (swap! state update-in [:cfg :addon-dir-list] conj stub))
    nil))

(defn-spec set-addon-dir! nil?
  "adds a new :addon-dir to :addon-dir-list (if it doesn't already exist) and marks it as selected"
  [addon-dir ::sp/addon-dir]
  (let [addon-dir (-> addon-dir fs/absolute fs/normalized str)]
    (add-addon-dir! addon-dir "retail")
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


(defn handle-legacy-install-dir
  [cfg]
  (let [install-dir (:install-dir cfg)
        stub {:addon-dir install-dir :game-track "retail"}
        ;; add stub to addon-dir-list if install-dir isn't nil and doesn't match anything already present
        cfg (if (and install-dir
                     (not (addon-dir-exists? install-dir (:addon-dir-list cfg))))
              (update-in cfg [:addon-dir-list] conj stub)
              cfg)]
      ;; finally, ensure :install-dir is absent from whatever we return
    (dissoc cfg :install-dir)))

(defn-spec configure ::sp/user-config
  "handles the user configurable bit of the app. command line args override args from from the config file."
  [file-opts map?, cli-opts map?]
  (debug "loading file config:" file-opts)
  (let [default-cfg (:cfg -state-template)

        ;; todo: this sucks,fix
        file-opts (if (contains? file-opts :selected-catalog)
                    (update-in file-opts [:selected-catalog] keyword)
                    file-opts)

        cfg (merge default-cfg file-opts)
        cfg (handle-legacy-install-dir cfg)
        ;; doesn't support optional :opt keysets
        cfg (spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer)
        valid? (s/valid? ::sp/user-config cfg)]
    (when-not valid?
      (warn "configuration from saved settings is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

    (debug "loading runtime config:" cli-opts)
    (let [cfg (if valid? cfg default-cfg)
          final-cfg (merge cfg cli-opts)
          ;; :install-dir may be re-introduced at this point. handle it exactly as we did above
          final-cfg (handle-legacy-install-dir final-cfg)
          final-cfg (spec-tools/coerce ::sp/user-config final-cfg spec-tools/strip-extra-keys-transformer)
          valid? (s/valid? ::sp/user-config cfg)]
      (when-not valid?
        (warn "configuration from command line args is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

      (if valid? final-cfg cfg))))

(defn save-settings
  []
  ;; warning: this will preserve any once-off command line parameters as well
  ;; this might make sense within the gui but be careful about auto-saving elsewhere
  (debug "saving settings to:" (paths :cfg-file))
  (utils/dump-json-file (paths :cfg-file) (get-state :cfg))
  (debug "saving etag-db to:" (paths :etag-db-file))
  (utils/dump-json-file (paths :etag-db-file) (get-state :etag-db)))

(defn load-settings
  ([]
   ;; load settings with previous cli-opts, if they exist, else no opts
   (load-settings (or (get-state :cli-opts) {})))
  ([cli-opts]
   (when-not (fs/exists? (paths :cfg-file))
     (warn "configuration file not found: " (paths :cfg-file)))

   (let [file-opts (utils/load-json-file-safely (paths :cfg-file) :no-file? {} :bad-data? {})
         cfg (configure file-opts cli-opts)
         etag-db (utils/load-json-file-safely (paths :etag-db-file) :no-file? {} :bad-data? {})
         new-state {:cfg cfg, :cli-opts cli-opts, :file-opts file-opts, :etag-db etag-db
                    :selected-addon-dir (-> cfg :addon-dir-list first :addon-dir)
                    :db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (utils/uuid)})}]
     (swap! state merge new-state)
     (when (:verbosity cli-opts)
       (logging/change-log-level (:verbosity cli-opts))))))


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

(defn-spec install-addon-guard (s/or :ok (s/coll-of ::sp/extant-file), :error nil?)
  "downloads an addon and installs it. handles http and non-http errors, bad zip files, bad addons"
  [addon ::sp/addon-or-toc-addon, install-dir ::sp/extant-dir]
  (if (not (fs/writeable? install-dir))
    (error "failed to install addon, directory not writeable" install-dir)
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
        :else (-install-addon addon install-dir downloaded-file)))))

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

(defn-spec get-catalog-source (s/nilable ::sp/catalog-source)
  ([]
   (get-catalog-source (get-state :cfg :selected-catalog)))
  ([catalog-name keyword?]
   (->> (get-state :catalog-source-list) (filter #(= catalog-name (:name %))) first)))

(defn-spec set-catalog-source! nil?
  [catalog-name keyword?]
  (if-let [catalog (get-catalog-source catalog-name)]
    (do
      (swap! state assoc-in [:cfg :selected-catalog] (:name catalog))
      (save-settings))
    (warn "catalog not found" catalog-name))
  nil)

(defn-spec catalog-local-path ::sp/file
  "given a catalog-source map, returns the local path to the catalog."
  [catalog-source ::sp/catalog-source]
  ;; {:name :full ...} => "/path/to/catalog/dir/full-catalog.json"
  (utils/join (paths :catalog-dir) (-> catalog-source :name name (str "-catalog.json"))))

(defn-spec find-catalog-local-path ::sp/file
  "convenience wrapper around `catalog-local-path`"
  [catalog-name keyword?]
  (catalog-local-path (get-catalog-source catalog-name)))

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

(defn moosh-addons
  [installed-addon catalog-addon]
  ;; merges left->right. catalog-addon overwrites installed-addon, ':matched' overwrites catalog-addon, etc
  (merge installed-addon catalog-addon {:matched? true}))

(defn source-from-group-id
  [addon-summary]
  (when-let [uri (:group-id addon-summary)]
    (-> uri java.net.URI. .getHost (clojure.string/split #"\.") second)))


;;


(defn find-in-db [installed-addon toc-keys catalog-keys]
  (let [catalog-keys (if (vector? catalog-keys) catalog-keys [catalog-keys]) ;; ["source" "source_id"] => ["source" "source_id"], "name" => ["name"]
        sql-arg-template (clojure.string/join " AND " (mapv #(format "%s = ?" %) catalog-keys)) ;; "source = ? AND source_id = ?", "name = ?"

        toc-keys (if (vector? toc-keys) toc-keys [toc-keys])
        sql-arg-vals (mapv #(get installed-addon %) toc-keys) ;; [:source :source-id] => ["curseforge" 12345], [:name] => ["foo"]

        _ (when (some nil? sql-arg-vals)
            (warn "(debug) failed to find all values for sql query. keys:" toc-keys "vals:" sql-arg-vals))

        sql (str select-*-catalog "where " sql-arg-template)
        ;;_ (info sql-arg-vals) ;; there are cases where an arg is nil. should we still query if we don't have all the facts?
        results (db-query sql :arg-list sql-arg-vals)
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
  [installed-addon-list catalog]
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
    (info "matching installed addons to online addons")
    (let [inst-addons (get-state :installed-addon-list)
          catalog (get-db :addon-summary-list)

          match-results (-db-match-installed-addons-with-catalog inst-addons catalog)
          [matched unmatched] (utils/split-filter #(contains? % :final) match-results)

          matched (mapv :final matched)
          unmatched-names (set (map :name unmatched))

          expanded-installed-addon-list (into matched unmatched)

          [num-installed num-matched] [(count inst-addons) (count matched)]]

      (when-not (= num-installed num-matched)
        (info "num installed" num-installed ", num matched" num-matched))

      (when-not (empty? unmatched)
        (warn "you need to manually search for them and then re-install them")
        (warn (format "failed to find %s installed addons in the catalog: %s" (count unmatched) (clojure.string/join ", " unmatched-names))))

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
  (jdbc/execute! (get-state :db) [(slurp "resources/table--catalog.sql")])
  (debug "creating category tables")
  (jdbc/execute! (get-state :db) [(slurp "resources/table--category.sql")])
  (swap! state update-in [:cleanup] conj db-shutdown)
  nil)

(defn db-catalog-loaded?
  []
  (> (get-db :catalog-size) 0))

(defn-spec db-load-catalog nil?
  []
  (when-not (db-catalog-loaded?)
    (let [ds (get-state :db)
          catalog-source (get-catalog-source)
          catalog-path (catalog-local-path catalog-source)
          _ (debug "loading addon summaries from catalog into database:" catalog-path)

          ;; total hack and to be removed once curseforge/wowinterface catalogs have a :source field
          missing-source (if (= (:name catalog-source) :curseforge-catalog-file)
                           "curseforge"
                           "wowinterface")

          {:keys [addon-summary-list]} (utils/load-json-file catalog-path)

          addon-categories (mapv (fn [{:keys [source-id source category-list]}]
                                   (mapv (fn [category]
                                           [source-id (or source missing-source) category]) category-list)) addon-summary-list)
          ;; todo: we have addons in multiple identical categories. fix this in catalog.clj
          ;; see curseforge:319346
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
                                 :vanilla_track (utils/in? "classic" (:game-track-list row))}

                            absent-source {:source (or (:source row) missing-source)}]
                        (-> row (utils/dissoc-all ignored) (rename-keys mapping) (merge new) (merge absent-source))))]
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
                  (sql/insert! ds :addon_category {:addon_source (or source missing-source)
                                                   :addon_source_id source-id
                                                   :category_id (get category-map category)})))))

      (swap! state assoc :catalog-size (get-db :catalog-size))
      nil)))

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

;;
;; ui interface
;; 


(defn-spec delete-cache nil?
  "deletes the 'cache' directory that contains scraped html files, etag files, the catalog, etc. 
  nothing that isn't regenerated when missing."
  []
  (warn "deleting cache")
  (fs/delete-dir (paths :cache-dir))
  ;; todo: this and `init-dirs` needs revisiting
  (fs/mkdirs (paths :cache-dir))
  nil)

(defn-spec list-downloaded-addon-zips (s/coll-of ::sp/extant-file)
  [dir ::sp/extant-dir]
  (mapv str (fs/find-files dir #".+\-\-.+\.zip")))

;; todo: these are all variations on a theme. consider something generic

(defn-spec delete-downloaded-addon-zips nil?
  "deletes all of the addon zip files downloaded to '/path/to/Addons/'"
  []
  (when-let [addon-dir (get-state :selected-addon-dir)]
    (let [zip-files (list-downloaded-addon-zips addon-dir)
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s downloaded addon zip files" (count zip-files)))
      (dorun (map (juxt alert fs/delete) zip-files)))))

(defn delete-wowman-json-files
  []
  (when-let [addon-dir (get-state :selected-addon-dir)]
    (let [wowman-json #(fs/find-files % #"\.wowman\.json$")
          subdirs (filter fs/directory? (fs/list-dir addon-dir))
          wowman-files (flatten (map wowman-json subdirs))
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s .wowman.json files" (count wowman-files)))
      (dorun (vec (map (juxt alert fs/delete) wowman-files))))))

(defn delete-wowmatrix-dat-files
  []
  (when-let [addon-dir (get-state :selected-addon-dir)]
    (let [wowman-json #(fs/find-files % #"WowMatrix.dat$")
          subdirs (filter fs/directory? (fs/list-dir addon-dir))
          wowman-files (flatten (map wowman-json subdirs))
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s WowMatrix.dat files" (count wowman-files)))
      (dorun (vec (map (juxt alert fs/delete) wowman-files))))))

(defn-spec clear-all-temp-files nil?
  []
  (delete-downloaded-addon-zips)
  (delete-cache))

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

(defn-spec export-installed-addon-list nil?
  [output-file ::sp/file, addon-list ::sp/toc-list]
  (let [addon-list (map #(select-keys % [:name :source]) addon-list)]
    (utils/dump-json-file output-file addon-list)
    (info "wrote:" output-file)))

(defn-spec export-installed-addon-list-safely nil?
  [output-file ::sp/file]
  (let [output-file (-> output-file fs/absolute str)
        output-file (utils/replace-file-ext output-file ".json")
        addon-list (get-state :installed-addon-list)
        unmatched-addon-list (remove :source addon-list)]
    (doseq [addon unmatched-addon-list]
      (warn (format "Addon '%s' has no match in the catalog and will be skipped during import. Ensure all addons match before doing an export." (:name addon))))
    (export-installed-addon-list output-file addon-list)))

;; created to investigate some performance issues, seems sensible to keep it separate
(defn -mk-import-idx
  [addon-list]
  (let [key-fn #(select-keys % [:source :name])
        addon-summary-list (get-db :addon-summary-list)
        ;; todo: this sucks. use a database
        catalog-idx (group-by key-fn addon-summary-list)
        find-expand (fn [addon]
                      (when-let [matching-addon (first (get catalog-idx (key-fn addon)))]
                        (expand-summary-wrapper matching-addon)))
        matching-addon-list (->> addon-list (map find-expand) (remove nil?) vec)]
    matching-addon-list))

(defn import-addon-list
  "caveats: imports the *latest* version of the addon, if addon found"
  [addon-list] ;; todo: spec
  (info "attempting to import" addon-list)
  (let [matching-addon-list (-mk-import-idx addon-list)
        addon-dir (get-state :selected-addon-dir)]
    (doseq [addon matching-addon-list]
      (install-addon addon addon-dir))))

(defn-spec import-exported-file nil?
  [path ::sp/extant-file]
  (info "importing exports file:" path)
  (let [nil-me (constantly nil)
        invalid-warn #(warn "invalid!")
        addon-list (utils/load-json-file-safely path
                                                :bad-data? nil-me
                                                :data-spec ::sp/export-record-list
                                                :invalid-data? nil-me)]
    (when-not (empty? addon-list)
      (import-addon-list addon-list))))

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
  (state-bind [:cfg :selected-catalog] (fn [state]
                                         (db-shutdown)
                                         (refresh))))

(defn-spec init-dirs nil?
  []
  (doseq [[path val] (paths)]
    (when (-> path name (clojure.string/ends-with? "-dir"))
      (debug (format "creating '%s' directory: %s" path val))
      (fs/mkdirs val)))
  (http/prune-cache-dir (paths :cache-dir))
  nil)

(defn -start
  []
  (alter-var-root #'state (constantly (atom -state-template))))

(defn start
  [& [cli-opts]]
  (-start)
  (info "starting app")
  (init-dirs)
  (load-settings cli-opts)
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

(st/instrument)
