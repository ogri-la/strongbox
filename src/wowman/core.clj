(ns wowman.core
  (:require
   [clojure.set]
   [clojure.string]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [spec-tools.core :as spec-tools]
   [me.raynes.fs :as fs]
   [trptcolin.versioneer.core :as versioneer]
   [wowman
    [http :as http]
    [logging :as logging]
    [nfo :as nfo]
    [utils :as utils :refer [join not-empty? false-if-nil]]
    [catalog :as catalog]
    [toc]
    [specs :as sp]]))

(defn colours
  [& path]
  (let [colour-map {:notice/error :tomato
                    :notice/warning :lemonchiffon
                    ;;:installed/unmatched :tomato
                    :installed/needs-updating :lemonchiffon
                    :installed/hovering "#e6e6e6"
                    :search/already-installed "#99bc6b"}] ;; greenish
    (if-not (empty? path)
      (get-in colour-map path)
      colour-map)))

(defn paths
  "returns a map of paths whose location may vary depending on the location of the current working directory"
  [& path]
  (let [;; https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        ;; ignoring XDG_CONFIG_DIRS and XDG_DATA_DIRS for now
        config-dir (or (System/getenv "XDG_CONFIG_HOME") "~/.config/wowman")
        config-dir (-> config-dir fs/expand-home fs/normalized fs/absolute str)

        data-dir (or (System/getenv "XDG_DATA_HOME") "~/.local/share/wowman")
        data-dir (-> data-dir fs/expand-home fs/normalized fs/absolute str)

        ;; cache files are deleted regularly. even if you fuck up with "XDG_DATA_HOME=/" then the worse that
        ;; can happen, even if you run wowman as root, is you get /cache/ or /home/$you/cache/ created and
        ;; files within it deleted. /cache and /home/$you/cache are not common directories
        cache-dir (join data-dir "cache") ;; /home/you/.local/share/wowman/cache

        cfg-file (join config-dir "config.json") ;; /home/$you/.config/wowman/config.json
        etag-db-file (join data-dir "etag-db.json") ;; /home/$you/.local/share/wowman/etag-db.json

        curseforge-catalog (join data-dir "curseforge.json") ;; /home/$you/.local/share/wowman/cache/curseforge.json
        curseforge-catalog-updates (join data-dir "curseforge-updates.json") ;; todo: remove this intermediate file
        wowinterface-catalog (join data-dir "wowinterface.json")

        catalog (join data-dir "catalog.json")

        path-map {:config-dir config-dir
                  :data-dir data-dir
                  :cache-dir cache-dir
                  :cfg-file cfg-file
                  :etag-db-file etag-db-file

                  :catalog catalog
                  :remote-catalog "https://raw.githubusercontent.com/ogri-la/wowman-data/master/catalog.json"

                  :curseforge-catalog curseforge-catalog
                  :curseforge-catalog-updates curseforge-catalog-updates
                  :wowinterface-catalog wowinterface-catalog}]

    (if (empty? path) path-map (get-in path-map path))))

(def -state-template
  {:cleanup []

   :file-opts {} ;; options parsed from config file
   :cli-opts {} ;; options passed in on the command line

   ;; final config, result of merging :file-opts and :cli-opts
   :cfg {:install-dir nil
         :debug? false}

   ;; summary data about ALL available addons, scraped from listing pages.
   :addon-summary-list nil

   ;; subset of possible data about all INSTALLED addons
   ;; starts as parsed .toc file data
   ;; ... then updated with data from :addon-summary-list above
   ;; ... then updated again with live data from curseforge
   ;; see specs/toc-addon
   :installed-addon-list nil

   :etag-db {}

   ;; ui

   ;; the root swing window
   :gui nil

   ;; addons in an unsteady state (data being updated, addon being installed, etc)
   ;; allows a UI to watch and update with progress
   :unsteady-addons #{}

   ;; a sublist of merged toc+addon that are selected
   :selected-installed []

   :search-field-input nil
   :selected-search []})

(def state (atom nil))

(defn get-state
  "returns the state map of the value at the given path within the map, if path provided"
  [& path]
  (if-let [state @state]
    (if (empty? path) state (get-in state path))
    (AssertionError. "application must be `start`ed before state may be accessed.")))

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
  {:etag-db (get-state :etag-db)
   :set-etag set-etag
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
                   (callback new-state))))

    (swap! state update-in [:cleanup] conj rmwatch)
    nil))

(defn-spec debugging? boolean?
  "true, if we we're in 'debug' mode"
  []
  (false-if-nil (get-state :cfg :debug?)))

;;
;; settings
;;

(defn-spec configure ::sp/user-config
  "handles the user configurable bit of the app. command line args override args from from the config file."
  [file-opts map?, cli-opts map?]
  (debug "loading file config:" file-opts)
  (let [default-cfg (:cfg -state-template)
        cfg (merge default-cfg file-opts)
        cfg (spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer)
        valid? (s/valid? ::sp/user-config cfg)]
    (when-not valid?
      (warn "configuration from file is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

    (debug "loading runtime config:" cli-opts)
    (let [cfg (if valid? cfg default-cfg)
          final-cfg (merge cfg cli-opts)
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

   (let [file-opts (if (fs/exists? (paths :cfg-file)) (utils/load-json-file (paths :cfg-file)) {})
         cfg (configure file-opts cli-opts)
         etag-db (if (fs/exists? (paths :etag-db-file)) (utils/load-json-file (paths :etag-db-file)) {})
         new-state {:cfg cfg, :cli-opts cli-opts, :file-opts file-opts, :etag-db etag-db}]
     (swap! state merge new-state)
     (when (:verbosity cli-opts)
       (logging/change-log-level (:verbosity cli-opts))))))

(defn-spec set-install-dir! nil?
  [install-dir ::sp/install-dir]
  (swap! state assoc-in [:cfg :install-dir] (-> install-dir fs/absolute fs/normalized str))
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

    ;; todo: issue a warning if all subfolders don't share a common prefix?
    ;; do people care if SlideBar and Stubby are being installed when they install things like Auctioneer/Healbot?

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
  (let [toplevel-entries (utils/zipfile-toplevel-entries downloaded-file)
        [toplevel-dirs, toplevel-files] (utils/split-filter :dir? toplevel-entries)]
    (if (> (count toplevel-files) 0)
      (do
        ;; todo: shift this (somehow) into `install-addon-guard`
        (error "refusing to unzip addon, it contains top-level *files*:" toplevel-files)
        [])
      (let [_ (utils/unzip-file downloaded-file install-dir)
            primary-dirname (determine-primary-subdir toplevel-dirs)

            ;; an addon may unzip to many directories, each directory needs the nfo file
            update-nfo-fn (fn [zipentry]
                            (let [addon-dirname (:path zipentry)
                                  primary? (= addon-dirname (:path primary-dirname))]
                              (nfo/write-nfo install-dir addon addon-dirname primary?)))

            ;; write the nfo files, return a list of all nfo files written
            retval (mapv update-nfo-fn toplevel-dirs)]
        (info (:label addon) "installed.")
        retval))))

(defn-spec install-addon-guard (s/or :ok (s/coll-of ::sp/extant-file), :error nil?)
  "downloads an addon and installs it. handles http and non-http errors"
  [addon ::sp/addon-or-toc-addon, install-dir ::sp/extant-dir]
  (if (not (fs/writeable? install-dir))
    (error "failed to install addon, directory not writeable" install-dir)
    (let [downloaded-file (download-addon addon install-dir)]
      (info "installing" (:label addon) "...")
      (cond
        (map? downloaded-file) (error "failed to download addon, could not install" (:name addon))
        (not (utils/valid-zip-file? downloaded-file)) (do
                                                        (error (format "failed to read zip file '%s', could not install %s" downloaded-file (:name addon)))
                                                        (fs/delete downloaded-file)
                                                        (warn "removed bad zipfile" downloaded-file))
        (nil? downloaded-file) (error "non-http error downloading addon, could not install" (:name addon)) ;; I dunno. /shrug
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
  (when-let [install-dir (get-state :cfg :install-dir)]
    (info "(re)loading installed addons:" install-dir)
    (update-installed-addon-list! (wowman.toc/installed-addons install-dir))))

;;
;; addon summaries
;;

(defn-spec download-catalog ::sp/extant-file
  "downloads catalog to expected location, nothing more"
  []
  (binding [http/*cache* (cache)]
    (http/download-file (paths :remote-catalog) (paths :catalog))))

(defn-spec load-addon-summaries nil?
  []
  (download-catalog)
  (info "loading addon summaries from catalog:" (paths :catalog))
  (let [{:keys [addon-summary-list]} (utils/load-json-file (paths :catalog))]
    (swap! state assoc :addon-summary-list addon-summary-list)
    nil))

(defn moosh-addons
  [installed-addon catalog-addon]
  ;; merges left->right. catalog-addon overwrites installed-addon, ':matched' overwrites catalog-addon, etc
  (merge installed-addon catalog-addon {:matched? true}))

(defn source-from-group-id
  [addon-summary]
  (when-let [uri (:group-id addon-summary)]
    (-> uri java.net.URI. .getHost (clojure.string/split #"\.") second)))

(defn -match-installed-addons-with-catalog
  "for each installed addon, search the catalog across multiple joins until a match is found."
  [installed-addon-list catalog]
  (let [;; [[toc-key catalog-key], ...]
        ;; most -> least desirable match
        match-on [[:alias :name] [:name :name] [:name :alt-name] [:label :label] [:dirname :label]]

        ;; split the catalog into it's source parts (curseforge, wowinterface)
        catalog-source-idx (group-by :source catalog)

        ;; split each source catalog into multiple indicies (:name, :alt-name, etc)
        idx-idx-fn (fn [source catalog-source]
                     (debug "building index for" source)
                     (into {} (mapv (fn [[toc-key catalog-key]]
                                      {catalog-key (utils/idx catalog-source catalog-key)}) match-on)))

        ;; idx-idx-idx is a structure that resembles this:
        ;; {"curseforge" {:name {"addon-name-1" {:name "addon-name-1" ...}
        ;;                       "addon-name-2" {:name "addon-name-2" ...}
        ;;                       ...}
        ;;                :alt-name {"addonname1" {:name "addon-name-1" ...}
        ;;                           "addonname2" {:name "addon-name-2" ...}
        ;;                           ...}
        ;;                :label {"Addon Name 1" {:name "addon-name-1" ...}
        ;;                        "Addon Name 2" {:name "addon-name-2" ...}
        ;;                        ...}}
        ;; "wowinterface" {...}}
        idx-idx-idx (utils/fmap idx-idx-fn catalog-source-idx)

        finder (fn [installed-addon]
                 (let [;; figure out where this addon was installed from
                       source (or
                               (:source installed-addon) ;; perfect case
                               (source-from-group-id installed-addon))

                       ;; if we can't figure out where the addon came from, search all sources for a match
                       source-list (sort ;; alphabetically, curseforge will come first
                                    (if (nil? source)
                                      (keys idx-idx-idx)
                                      [source]))

                       -finder (fn [source]
                                 (mapv (fn [[toc-key catalog-key]]
                                         (let [catalog (get idx-idx-idx source)
                                               idx (get catalog catalog-key)
                                               key (get installed-addon toc-key)
                                               match (get idx key)]
                                           ;; "checking wowinterface :alias=>:name for AdiBags"
                                           (debug (format "checking %s %s=>%s for %s" source toc-key catalog-key (:name installed-addon)))

                                           (when match
                                             ;; {:idx [:name :alt-name], :key "deadly-boss-mods", :match {...}, ...}
                                             {:idx [toc-key catalog-key]
                                              :key key
                                              :installed-addon installed-addon
                                              :match match
                                              :final (moosh-addons installed-addon match)}))) match-on))

                       ;; clojure has peculiar laziness rules and this will actually visit *all* of the `match-on` pairs
                       ;; see chunking: http://clojure-doc.org/articles/language/laziness.html

                       match (first (remove nil? (flatten (map -finder source-list))))]

                   (if match match {:installed-addon installed-addon})))]

    (mapv finder installed-addon-list)))

(defn -match-installed-addons-with-catalog-debug
  "good for when you have addons that don't have a match"
  []
  (-match-installed-addons-with-catalog (get-state :installed-addon-list) (get-state :addon-summary-list)))

(defn-spec match-installed-addons-with-online-addons nil?
  "when we have a list of installed addons as well as the addon list,
   merge what we can into ::specs/addon-toc records and update state.
   any installed addon not found in :addon-idx has a mapping problem"
  []
  (info "matching installed addons to online addons")
  (let [inst-addons (get-state :installed-addon-list)
        catalog (get-state :addon-summary-list)

        match-results (-match-installed-addons-with-catalog inst-addons catalog)
        [matched unmatched] (utils/split-filter #(contains? % :final) match-results)

        matched (mapv :final matched)
        unmatched (mapv :installed-addon unmatched)
        unmatched-names (set (map :name unmatched))

        expanded-installed-addon-list (into matched unmatched)
        ;;expanded-installed-addon-list (utils/merge-lists :name (get-state :installed-addon-list) matched)
        ]

    (info "num installed" (count inst-addons) ", num matched" (count matched))

    (when-not (empty? unmatched)
      (warn "you need to manually search for them and then re-install them")
      (warn (format "failed to match %s installed addons to online addons: %s" (count unmatched) (clojure.string/join ", " unmatched-names))))

    (update-installed-addon-list! expanded-installed-addon-list)))

;;
;; addon summary and toc merging
;;

(defn-spec merge-addons ::sp/toc-addon
  [toc ::sp/toc, addon ::sp/addon]
  (let [toc-addon (merge toc addon)
        {:keys [installed-version version]} toc-addon
        ;; update only if we have a new version and it's different from the installed version
        update? (and version (not= installed-version version))]
    (if update?
      (assoc toc-addon :update? update?)
      toc-addon)))

(defn expand-summary-wrapper
  [addon-summary]
  (binding [http/*cache* (cache)]
    (let [wrapper (affects-addon-wrapper catalog/expand-summary)]
      (wrapper addon-summary))))

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
  (when-let [install-dir (get-state :cfg :install-dir)]
    (let [zip-files (list-downloaded-addon-zips install-dir)
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s downloaded addon zip files" (count zip-files)))
      (dorun (map (juxt alert fs/delete) zip-files)))))

(defn delete-wowman-json-files
  []
  (when-let [install-dir (get-state :cfg :install-dir)]
    (let [wowman-json #(fs/find-files % #"\.wowman\.json$")
          subdirs (filter fs/directory? (fs/list-dir install-dir))
          wowman-files (flatten (map wowman-json subdirs))
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s .wowman.json files" (count wowman-files)))
      (dorun (vec (map (juxt alert fs/delete) wowman-files))))))

(defn delete-wowmatrix-dat-files
  []
  (when-let [install-dir (get-state :cfg :install-dir)]
    (let [wowman-json #(fs/find-files % #"WowMatrix.dat$")
          subdirs (filter fs/directory? (fs/list-dir install-dir))
          wowman-files (flatten (map wowman-json subdirs))
          alert #(warn "deleting file " %)]
      (warn (format "deleting %s WowMatrix.dat files" (count wowman-files)))
      (dorun (vec (map (juxt alert fs/delete) wowman-files))))))

(defn-spec clear-all-temp-files nil?
  []
  (delete-downloaded-addon-zips)
  (delete-cache))

(defn-spec check-for-updates nil?
  "downloads full details for all installed addons that can be found in summary list"
  []
  (info "checking for updates")
  (update-installed-addon-list! (mapv (fn [ia]
                                        (if (and
                                             (:matched? ia)
                                             ;; don't expand if we have a dummy uri.
                                             ;; this isn't the right place for test code, but eh
                                             (nil? (clojure.string/index-of (:uri ia) "example.org")))
                                          ;; we have a match!
                                          (merge-addons ia (expand-summary-wrapper ia))
                                          ;; no match, can't update
                                          (assoc ia :update? false))) ;; hack. this whole bit needs looking at
                                      (get-state :installed-addon-list)))
  (info "done checking for updates"))

(defn refresh
  [& _]
  (load-addon-summaries)  ;; load the contents of the curseforge.json file
  (load-installed-addons) ;; parse toc files in install-dir
  (match-installed-addons-with-online-addons) ;; match installed addons to those in curseforge.json
  (check-for-updates)     ;; for those addons that have matches, download their full details from curseforge
  (save-settings)         ;; seems like a good place to preserve the etag-db
  nil)

(defn-spec -install-update-these nil?
  [updateable-toc-addons (s/coll-of ::sp/addon-or-toc-addon)]
  (doseq [toc-addon updateable-toc-addons]
    (install-addon toc-addon (get-state :cfg :install-dir))))

(defn -updateable?
  [rows]
  (filterv :update? rows))

(defn -re-installable?
  "an addon can only be re-installed if it's been matched to an addon in the catalog"
  [rows]
  (filterv :uri rows)) ;; :uri is only present in addons that have a match

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
  [addon-dir]
  (warn "attempting to remove" addon-dir)
  (let [install-dir (get-state :cfg :install-dir)
        addon-dir (fs/file install-dir addon-dir)
        addon-dir (-> addon-dir fs/absolute fs/normalized)]
    ;; if after resolving the given addon dir it's still within the install-dir, remove it
    (if (and
         (fs/directory? addon-dir)
         (clojure.string/starts-with? addon-dir install-dir)) ;; don't delete anything outside of install dir!
      (do
        (warn "removing" addon-dir)
        (fs/delete-dir addon-dir)
        nil)

      (warn (format "addon-dir '%s' is outside the current installation dir of '%s'. not removing." addon-dir install-dir)))))

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

(defn remove-selected
  []
  (-> (get-state) :selected-installed vec remove-many-addons))

;; version checking

(defn-spec wowman-version string?
  "returns this version of wowman"
  []
  (versioneer/get-version "ogri-la" "wowman"))

(defn-spec latest-wowman-release string?
  "returns the most recently released version of wowman it can find"
  []
  (binding [http/*cache* (cache)]
    (let [resp (utils/from-json (http/download "https://api.github.com/repos/ogri-la/wowman/releases/latest"))]
      (-> resp :tag_name))))

(defn-spec latest-wowman-version? boolean?
  "returns true if the *running instance* of wowman is the *most recent known* version of wowman."
  []
  (let [latest-release (latest-wowman-release)
        version-running (wowman-version)
        sorted-asc (utils/sort-semver-strings [latest-release version-running])]
    (= version-running (last sorted-asc))))

(defn alias-wrangling
  "temporary code until it finds a better home. downloads the top-50 addons and prints out the addon's and subaddon's labels. see `fs/aliases`"
  []
  (let [top-50 (take 50 (sort-by :download-count > (get-state :addon-summary-list)))
        _ (mapv #(-> % expand-summary-wrapper (install-addon-guard (get-state :cfg :install-dir))) top-50)
        ia (wowman.toc/installed-addons (get-state :cfg :install-dir))]
    (mapv (fn [r] {(:name r) (if (:group-addons r) (mapv :label (:group-addons r)) [(:label r)])}) ia)))

;;
;; init
;;

(defn watch-for-install-dir-change
  "when the install directory changes, the list of installed addons should be re-read"
  []
  (let [state-atm state
        reset-state-fn (fn [state]
                         ;; TODO: revisit this
                         ;; remove :cfg because it's controlled by user
                         ;; remove :addon-summary-list because there is no need to load it multiple times
                         (let [default-state (dissoc state :cfg :addon-summary-list)]
                           (swap! state-atm merge default-state)
                           (refresh)))]
    (state-bind [:cfg :install-dir] reset-state-fn)))

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
  ;;(load-addon-summaries)  ;; load the contents of the curseforge.json file. defer to ui
  (watch-for-install-dir-change)

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
