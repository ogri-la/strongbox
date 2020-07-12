(ns strongbox.addon
  (:require
   [clojure.string :refer [lower-case starts-with?]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [strongbox
    [toc :as toc]
    [utils :as utils]
    [nfo :as nfo]
    [zip :as zip]
    [specs :as sp]]))

;;(defn-spec -remove-addon nil?
;;  [addon-dir ::sp/addon-dir, addon-dirname ::sp/dirname]
(defn -remove-addon
  [addon-dir addon-dirname]
  (info (format "addon dir '%s' addon dirname '%s'" addon-dir addon-dirname))
  (let [addon-path (fs/file addon-dir addon-dirname) ;; todo: perhaps this (addon-dir (base-name addon-dirname)) is safer
        addon-path (-> addon-path fs/absolute fs/normalized)]
    ;; if after resolving the given addon dir it's still within the install-dir, remove it
    (if (and
         (fs/directory? addon-path)
         (starts-with? addon-path addon-dir) ;; don't delete anything outside of install dir!
         (not (= addon-path addon-dir)))
      (do
        (fs/delete-dir addon-path)
        (warn (format "removed '%s'" addon-path)))

      (error (format "directory '%s' is outside the current installation dir of '%s', not removing" addon-path addon-dir)))))

;; todo: don't remove addons that have a mutual dependency outside of the group
(defn-spec remove-addon nil?
  "removes the given addon. if addon is part of a group, all addons in group are removed"
  [addon-dir ::sp/addon-dir, toc :addon/toc]
  (if (contains? toc :group-addons)
    (doseq [subtoc (:group-addons toc)]
      (-remove-addon addon-dir (:dirname subtoc))) ;; top-level toc is contained in the :group-addons list
    (-remove-addon addon-dir (:dirname toc))))

;; ---

(defn-spec group-addons :addon/toc-list
  "an addon may actually be many addons bundled together in a single download.
  strongbox tags the bundled addons as they are unzipped and tries to determine the primary one.
  after we've loaded the addons and merged their nfo data, we can then group them"
  [addon-list :addon/toc-list]
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

(defn-spec load-installed-addons :addon/toc-list
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

;;


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

(defn-spec install-addon (s/or :ok (s/coll-of ::sp/extant-file), :error ::sp/empty-coll)
  "installs an addon given an addon description, a place to install the addon and the addon zip file itself"
  [addon :addon/installable, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file, game-track ::sp/game-track]
  (let [zipfile-entries (zip/zipfile-normal-entries downloaded-file)
        toplevel-dirs (filter (every-pred :dir? :toplevel?) zipfile-entries)
        primary-dirname (determine-primary-subdir toplevel-dirs)

        ;; "not a show stopper, but if there are bundled addons and they don't share a common prefix, let the user know"
        suspicious-bundle-check (fn []
                                  (let [sus-addons (zip/inconsistently-prefixed zipfile-entries)
                                        msg "%s will install inconsistently prefixed addons: %s"]
                                    (when sus-addons
                                      (warn (format msg (:label addon) (clojure.string/join ", " sus-addons))))))

        install-addon (fn []
                        (zip/unzip-file downloaded-file install-dir))

        ;; an addon may unzip to many directories, each directory needs the nfo file
        update-nfo-fn (fn [zipentry]
                        (let [addon-dirname (:path zipentry)
                              primary? (= addon-dirname (:path primary-dirname))]
                          (nfo/write-nfo install-dir addon addon-dirname primary? game-track)))

        update-nfo-files (fn []
                           ;; write the nfo files, return a list of all nfo files written
                           (mapv update-nfo-fn toplevel-dirs))]

    (suspicious-bundle-check)
    (when (s/valid? :addon/toc addon)
      (remove-addon install-dir addon))
    (install-addon)
    (let [retval (update-nfo-files)]
      (info (:label addon) "installed.")
      retval)))
