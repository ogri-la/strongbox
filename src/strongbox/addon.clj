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

(defn-spec -remove-addon nil?
  "safely removes the given `addon-dirname` from `install-dir`"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, group-id (s/nilable ::sp/group-id)]
  (let [addon-path (fs/file install-dir (fs/base-name addon-dirname)) ;; `fs/base-name` strips any parents
        addon-path (-> addon-path fs/absolute fs/normalized)]
    ;; if after resolving the given addon dir it's still within the install-dir, remove it
    (if (and
         (fs/directory? addon-path)
         (starts-with? addon-path install-dir)) ;; don't delete anything outside of install dir!
      (if-not (nfo/mutual-dependency? install-dir addon-dirname)
        (do
          (fs/delete-dir addon-path)
          (debug (format "removed '%s'" addon-path)))

        (let [updated-nfo-data (nfo/rm-nfo install-dir addon-dirname group-id)]
          (nfo/write-nfo install-dir addon-dirname updated-nfo-data)
          (debug (format "removed '%s' as mutual dependency" addon-dirname))))

      (error (format "directory '%s' is outside the current installation dir of '%s', not removing" addon-path install-dir)))))

(defn-spec remove-addon nil?
  "removes the given addon. if addon is part of a group, all addons in group are removed"
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (info (format "removing '%s' version '%s'" (:label addon) (:installed-version addon)))
  (cond
    ;; if addon is being ignored, refuse to remove addon.
    ;; note: `group-addons` will add a top level `:ignore?` flag if any addon in a bundle is being ignored.
    (:ignore? addon) (error "refusing to delete ignored addon:" install-dir)

    ;; addon is part of a bundle.
    ;; because the addon is also contained in `:group-addons` we just remove all in list
    (contains? addon :group-addons) (doseq [grouped-addon (:group-addons addon)]
                                      (-remove-addon install-dir (:dirname grouped-addon) (:group-id addon)))

    ;; addon is a single directory
    :else (-remove-addon install-dir (:dirname addon) (:group-id addon))))

;;

;; todo: toc-list to installed-list
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
                         ;; `addons` comes from `toc/installed-addon-list` fed by `fs/list-dir` that wraps `java.io.File/listFiles`:
                         ;; "There is no guarantee that the name strings in the resulting array will appear in any specific order"
                         ;;   - https://docs.oracle.com/javase/7/docs/api/java/io/File.html#listFiles()
                         addons (vec (sort-by :dirname addons))
                         primary (first (filter :primary? addons))
                         next-best (first addons)
                         new-data {:group-addons addons
                                   :group-addon-count (count addons)}
                         next-best-label (-> next-best :group-id fs/base-name)
                         ;; add a group-level ignore flag if any bundled addon is being ignored
                         ;; todo: test for this
                         ignore-group? (when (utils/any (map :ignore? addons))
                                         {:ignore? true})]
                     (if primary
                       ;; best, easiest case
                       (merge primary new-data ignore-group?)
                       ;; when we can't determine the primary addon, add a shitty synthetic one
                       (merge next-best new-data ignore-group?
                              {:label (format "%s (group)" next-best-label)
                               :description (format "group record for the %s addon" next-best-label)})))))

        ;; this flattens the newly grouped addons from a map into a list and joins the unknowns
        addon-list (apply conj (mapv expand addon-groups) unknown-grouping)]
    addon-list))

(defn-spec ungroup-addon :addon/installed-list
  "an addon may actually be many addons bundled together with a primary one chosen to represent them.
  sometimes we want to treat this addon as a list of addons"
  [addon :addon/installed]
  (if (empty? (:group-addons addon))
    [addon]
    (get addon :group-addons [])))

(defn-spec load-installed-addons :addon/toc-list
  "reads the .toc files from the given addon dir, reads any nfo data for 
  these addons, groups them, returns the mooshed data"
  [install-dir ::sp/extant-dir]
  (let [addon-list (strongbox.toc/installed-addons install-dir)

        ;; at this point we have a list of the 'top level' addons, with
        ;; any bundled addons grouped within each one.

        ;; each addon now needs to be merged with the 'nfo' data, the additional
        ;; data we store alongside each addon when it is installed/updated

        merge-nfo-data (fn [addon]
                         (let [nfo-data (nfo/read-nfo install-dir (:dirname addon))]
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

;;

(defn-spec install-addon (s/or :ok (s/coll-of ::sp/extant-file), :error ::sp/empty-coll)
  "installs an addon given an addon description, a place to install the addon and the addon zip file itself.
  handles suspicious looking bundles, conflicts with other addons, uninstalling previous addon version and updating nfo files."
  [addon :addon/installable, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file, game-track ::sp/game-track]
  (let [zipfile-entries (zip/zipfile-normal-entries downloaded-file)
        toplevel-dirs (zip/top-level-directories zipfile-entries)
        primary-dirname (determine-primary-subdir toplevel-dirs)

        ;; not a show stopper, but if there are bundled addons and they don't share a common prefix, let the user know
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
                              primary? (= addon-dirname (:path primary-dirname))
                              new-nfo-data (nfo/derive addon primary? game-track)
                              new-nfo-data (nfo/add-nfo install-dir addon-dirname new-nfo-data)]
                          (nfo/write-nfo install-dir addon-dirname new-nfo-data)))

        update-nfo-files (fn []
                           ;; write the nfo files, return a list of all nfo files written
                           (mapv update-nfo-fn toplevel-dirs))]

    (suspicious-bundle-check)

    ;; todo: remove support for v1 addons in 2.0.0
    ;; when is it not valid? when importing v1 addons. v2 addons need 'padding' as well :(
    (when (s/valid? :addon/toc addon)
      (remove-addon install-dir addon))

    (info (format "installing '%s' version '%s'" (:label addon) (:version addon)))
    (install-addon)
    (update-nfo-files)))

;;

(defn-spec ignored-dir-list (s/coll-of ::sp/dirname)
  "returns a list of unique addon directory names (including grouped addons) that are not being ignored"
  [addon-list (s/nilable :addon/installed-list)]
  (->> addon-list (filter :ignore?) (map :group-addons) flatten (map :dirname) (remove nil?) set))

(defn-spec overwrites-ignored? boolean?
  "returns true if given archive file would unpack over *any* ignored addon.
  this includes already installed versions of itself and is another check against modifying ignored addons."
  [downloaded-file ::sp/archive-file, addon-list (s/nilable :addon/installed-list)]
  (let [ignore-list (ignored-dir-list addon-list)
        zip-dir-list (->> downloaded-file
                          zip/zipfile-normal-entries
                          zip/top-level-directories
                          (map :path)
                          (mapv #(utils/rtrim % "/")))
        zip-dir-in-ignore-dir-list? (fn [zip-dir] (some #{zip-dir} ignore-list))]
    (utils/any (map zip-dir-in-ignore-dir-list? zip-dir-list))))

;;

(defn-spec implicitly-ignored? boolean?
  "returns `true` if the addon in the given `install-dir`+`addon-dirname` directory is being implicitly ignored.
  an 'implicit ignore' is when the addon is under version control or the `.toc` file looks like an unrendered template."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (let [path (utils/join install-dir addon-dirname)
        toc-data (toc/parse-addon-toc-guard path)]
    (or (contains? toc-data :ignore?)
        (nfo/version-controlled? path))))

(defn-spec clear-ignore nil?
  "clears the `ignore?` flag on an addon, either by removing it from the nfo or setting it in the nfo to `false`.
  Has to happen here so we can distinguish between 'toc-ignores' and 'nfo-ignores'."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (if (implicitly-ignored? install-dir addon-dirname)
    (nfo/stop-ignoring install-dir addon-dirname)
    (nfo/clear-ignore install-dir addon-dirname)))
