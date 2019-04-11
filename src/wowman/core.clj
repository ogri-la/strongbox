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
   [wowman
    [logging :as logging]
    [nfo :as nfo]
    [utils :as utils :refer [join not-empty?]]
    [curseforge :as curseforge]
    [fs]
    [specs :as sp]]))

;; TODO: allow override in user config
(def remote-addon-summary-file "https://raw.githubusercontent.com/ogri-la/wowman-data/master/curseforge.json")

(defn paths
  "returns a map of paths whose location may vary depending on the location of the current working directory"
  [& path]
  (let [state-dir (join fs/*cwd* "state") ;; /path/to/current-dir/state
        cache-dir (join state-dir "cache")
        cfg-file (join state-dir "config.json")

        addon-summary-file (join state-dir "curseforge.json")
        addon-summary-updates-file (join state-dir "curseforge-updates.json")

        path-map {:state-dir state-dir
                  :cache-dir cache-dir
                  :cfg-file cfg-file
                  :addon-summary-file addon-summary-file
                  :addon-summary-updates-file addon-summary-updates-file}]
    (if-not (empty? path)
      (get-in path-map path)
      path-map)))

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

   ;; same as above but a map indexed by :name. purely for convenience
   :installed-addon-idx nil

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
  "state accessor, really should be using this"
  [& path]
  (let [state @state]
    (if (nil? state)
      (throw (RuntimeException. "application must be `start`ed before state may be accessed."))
      (if-not (empty? path)
        (get-in state path)
        state))))

(defn-spec state-bind nil?
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

(defn-spec state-binds nil?
  [path-list ::sp/list-of-list-of-keywords, callback fn?]
  (doseq [path path-list]
    (state-bind path callback)))

(defn-spec debugging? boolean?
  "'debug mode'"
  []
  (get-state :cfg :debug?))

;; settings

(defn-spec configure ::sp/user-config
  "handles the user configurable bit of the app. command line args override args from from the config file."
  [file-opts map?, cli-opts map?]
  (debug "loading config from file:" file-opts)
  (let [default-cfg (:cfg -state-template)
        cfg (merge default-cfg file-opts)
        cfg (spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer)
        valid? (s/valid? ::sp/user-config cfg)]
    (when-not valid?
      (warn "configuration from file is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

    (debug "loading config from runtime args:" cli-opts)
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
  (info "saving settings to " (paths :cfg-file))
  (utils/dump-json-file (paths :cfg-file) (get-state :cfg)))

(defn load-settings
  ([]
   ;; load settings with previous cli-opts, if they exist, else no opts
   (load-settings (or (get-state :cli-opts) {})))
  ([cli-opts]
   (when-not (fs/exists? (paths :cfg-file))
     (warn "configuration file not found: " (paths :cfg-file)))

   (let [file-opts (if (fs/exists? (paths :cfg-file)) (utils/load-json-file (paths :cfg-file)) {})
         cfg (configure file-opts cli-opts)]
     (dosync
      (swap! state assoc :cfg cfg)

      ;; possibly not necessary, we'll see
      (swap! state assoc :cli-opts cli-opts)
      (swap! state assoc :file-opts file-opts)

      (when (:verbosity cli-opts) (logging/change-log-level (:verbosity cli-opts)))))))

(defn-spec set-install-dir! nil?
  [install-dir ::sp/install-dir]
  (swap! state assoc-in [:cfg :install-dir] (-> install-dir fs/absolute fs/normalized str))
  nil)

;; utils

(defn start-affecting-addon
  [addon]
  (swap! state update-in [:unsteady-addons] clojure.set/union #{(:name addon)})) ;; TLC, set?

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

(defn-spec downloaded-addon-fname string?
  [name string? version string?]
  (format "%s--%s.zip" name (utils/slugify version))) ;; addonname--1-2-3.zip

(defn-spec download-addon (s/or :ok ::sp/archive-file, :http-error ::sp/http-error, :error nil?)
  [addon ::sp/addon-or-toc-addon download-dir ::sp/writeable-dir]
  (info "downloading" (:label addon) "...")
  (when-let [download-uri (:download-uri addon)]
    (let [output-fname (downloaded-addon-fname (:name addon) (:version addon)) ;; addonname--1-2-3.zip
          output-path (join (fs/absolute download-dir) output-fname)] ;; /path/to/installed/addons/addonname--1.2.3.zip
      (binding [utils/*cache-dir* (paths :cache-dir)]
        (utils/download-file download-uri output-path :overwrite? false)))))

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
    ;; do people care if SlideBar and Stubby are being installed when they install things like Auctioneer?

    (cond
      (= 1 (count toplevel-dirs)) ;; single dir, perfect case
      first-toplevel-dir

      (and
       ;; multiple dirs, one shorter than all others
       (not= (first dirname-lengths) (second dirname-lengths))
       ;; all dirs are prefixed with the name of the first toplevel dir
       (every? #(clojure.string/starts-with? (path-val %) (path-val first-toplevel-dir)) toplevel-dirs))
      first-toplevel-dir

      ;; todo: option to do a lookup in a static map here

      ;; couldn't reasonably determine the primary directory
      :else nil)))

(defn-spec -install-addon (s/or :ok (s/coll-of ::sp/extant-file), :error ::sp/empty-coll)
  "installs an addon given an addon description, a place to install the addon and the addon zip file itself"
  [addon ::sp/addon-or-toc-addon, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file]
  (let [toplevel-entries (utils/zipfile-toplevel-entries downloaded-file)
        [toplevel-dirs, toplevel-files] (utils/split-filter :dir? toplevel-entries)]
    (if (> (count toplevel-files) 0)
      (do
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
        (nil? downloaded-file) (error "non-http error downloading addon, could not install" (:name addon))
        (map? downloaded-file) (error "failed to download addon, could not install" (:name addon))
        (not (utils/valid-zip-file? downloaded-file)) (error (format "failed to read zip file '%s', could not install %s" downloaded-file (:name addon)))
        :else (-install-addon addon install-dir downloaded-file)))))

(def install-addon
  (affects-addon-wrapper install-addon-guard))

(defn update-installed-addon-list!
  [installed-addon-list]
  (let [installed-addon-idx (utils/idx installed-addon-list :name)
        asc compare
        installed-addon-list (sort-by :name asc installed-addon-list)]
    (swap! state merge {:installed-addon-list installed-addon-list
                        :installed-addon-idx installed-addon-idx})
    nil))

(defn-spec load-installed-addons nil?
  []
  (when-let [install-dir (get-state :cfg :install-dir)]
    (info "(re)loading installed addons:" install-dir)
    (update-installed-addon-list! (wowman.fs/installed-addons install-dir))))

(defn-spec download-addon-summary-file ::sp/extant-file
  "downloads addon summary file to expected location, nothing more"
  []
  (binding [utils/*cache-dir* (paths :cache-dir)]
    (utils/download-file remote-addon-summary-file (paths :addon-summary-file))))

(defn-spec load-addon-summaries nil?
  []
  (when-not (fs/exists? (paths :addon-summary-file)) ;; temporary check until header caching in
    ;; what happens if we have no addon-summary-file?
    ;; we have nothing to search, which is ok if temporary
    ;; if we installed the addon via wowman then the :group-id in nfo file can be used as a fall back
    (download-addon-summary-file))
  (info "loading addon summary list from:" (paths :addon-summary-file))
  (let [{:keys [addon-summary-list]} (utils/load-json-file (paths :addon-summary-file))]
    (swap! state assoc :addon-summary-list addon-summary-list)
    nil))

(defn-spec match-installed-addons-with-online-addons nil?
  "when we have a list of installed addons as well as the addon list,
   merge what we can into ::specs/addon-toc records and update state.
   any installed addon not found in :addon-idx has a mapping problem"
  []
  (info "matching installed addons to online addons")
  (let [inst-addons (get-state :installed-addon-list)
        ia-idx (get-state :installed-addon-idx)
        matcher (fn [available-addon]
                  (let [{:keys [name alt-name]} available-addon
                        ia-by-name (get ia-idx name)
                        ia-by-alt-name (get ia-idx alt-name)
                        installed-addon (or ia-by-name ia-by-alt-name)]
                    (when installed-addon
                      (when-not ia-by-name
                          ;; we matched, but under less than ideal circumstances
                        (warn (format "matched installed addon '%s' by the :alt-name '%s'" (:name installed-addon) (:alt-name available-addon))))
                        ;;(merge-addons installed-addon available-addon))))
                      (merge {:matched? true} available-addon installed-addon))))

        matched (vec (remove nil? (map matcher (get-state :addon-summary-list))))
        unmatched (clojure.set/difference (set (keys ia-idx)) (mapv :name matched))

        expanded-installed-addon-list (utils/merge-lists :name (get-state :installed-addon-list) matched)]

    (info "num installed" (count inst-addons) ", num matched" (count matched))

    (when-not (empty? unmatched)
      (warn "failed to match the following addons to an addon online:" (clojure.string/join ", " unmatched)))

    (update-installed-addon-list! expanded-installed-addon-list)))

(defn-spec merge-addons ::sp/toc-addon
  [toc ::sp/toc, addon ::sp/addon]
  (let [toc-addon (merge toc addon)
        update? (not= (:installed-version toc-addon) (:version toc-addon))]
    (if update?
      (assoc toc-addon :update? update?)
      toc-addon)))

(defn expand-summary-wrapper
  [addon-summary]
  (binding [utils/*cache-dir* (paths :cache-dir)]
    (let [wrapper (affects-addon-wrapper curseforge/expand-summary)]
      (wrapper addon-summary))))

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
                                          (merge-addons ia (expand-summary-wrapper ia))
                                          (assoc ia :update? false))) ;;hack
                                      (get-state :installed-addon-list)))
  (info "done checking for updates"))

(defn-spec refresh nil?
  []
  (load-addon-summaries)  ;; load the contents of the curseforge.json file
  (load-installed-addons) ;; parse toc files in install-dir
  (match-installed-addons-with-online-addons) ;; match installed addons to those in curseforge.json
  (check-for-updates)     ;; for those addons that have matches, download their full details from curseforge

  nil)

(defn-spec -install-update-these nil?
  [updateable-toc-addons (s/coll-of ::sp/addon-or-toc-addon)]
  (doseq [toc-addon updateable-toc-addons]
    (install-addon toc-addon (get-state :cfg :install-dir))))

(defn updateable?
  [rows]
  (filterv :update? rows))

(defn re-install-selected
  []
  (-> (get-state) :selected-installed -install-update-these)
  (refresh))

(defn re-install-all
  []
  (-> (get-state) :installed-addon-list -install-update-these)
  (refresh))

(defn install-update-selected
  []
  (-> (get-state) :selected-installed updateable? -install-update-these)
  (refresh))

(defn-spec install-update-all nil?
  []
  (-> (get-state) :installed-addon-list updateable? -install-update-these)
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
  (info (format "creating directories %s and %s" (paths :state-dir) (paths :cache-dir)))
  (fs/mkdirs (paths :state-dir))
  (fs/mkdirs (paths :cache-dir))
  nil)

;;
;;
;;

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
