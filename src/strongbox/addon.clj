(ns strongbox.addon
  (:require
   [clojure.string :refer [lower-case starts-with?]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [strongbox
    [logging :as logging]
    [toc :as toc]
    [utils :as utils]
    [nfo :as nfo]
    [zip :as zip]
    [specs :as sp]]))

(def dummy-dirname "not-the-addon-dir-you-are-looking-for")

(defn-spec -remove-addon nil?
  "safely removes the given `addon-dirname` from `install-dir`"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, group-id (s/nilable ::sp/group-id)]
  (let [addon-path (fs/file install-dir (fs/base-name addon-dirname)) ;; `fs/base-name` strips any parents
        addon-path (-> addon-path fs/absolute fs/normalized)
        addon-path (str addon-path)] ;; very important, otherwise a bunch of checks quietly pass
    (cond
      ;; todo: unhandled case here when importing addons into a directory of pre-existing addons.
      ;; what happens when we import the same set of addons over each other? it bypasses the mutual dependency handling for one thing ...
      (= addon-dirname dummy-dirname)
      (debug "dummy dirname found, skipping removal")

      ;; directory to remove is not a directory!
      (not (fs/directory? addon-path))
      (error (str "addon not removed, path is not a directory: " addon-path))

      ;; directory to remove is outside of addon directory (or exactly equal to it)!
      (or (not (starts-with? addon-path install-dir))
          (= addon-path install-dir))
      (error (format "directory is outside the current installation dir, not removing: %s" addon-path))

      ;; other addons depend on this addon, just remove the nfo file entry
      (nfo/mutual-dependency? install-dir addon-dirname)
      (let [updated-nfo-data (nfo/rm-nfo install-dir addon-dirname group-id)]
        (nfo/write-nfo install-dir addon-dirname updated-nfo-data)
        (debug (format "removed \"%s\" as mutual dependency" addon-dirname)))

      ;; all good, remove addon
      :else (do (fs/delete-dir addon-path)
                (debug (format "removed '%s'" addon-path))))))

(defn-spec remove-addon nil?
  "removes the given addon. if addon is part of a group, all addons in group are removed"
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (info (format "removing \"%s\" version \"%s'" (:label addon) (:installed-version addon)))
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

        expand (fn [[_ addons]]
                 (if (= 1 (count addons))
                   ;; perfect case, no grouping.
                   (first addons)

                   ;; multiple addons in group
                   (let [;; `addons` comes from `toc/installed-addon-list` fed by `fs/list-dir` that wraps `java.io.File/listFiles`:
                         ;; "There is no guarantee that the name strings in the resulting array will appear in any specific order"
                         ;;   - https://docs.oracle.com/javase/7/docs/api/java/io/File.html#listFiles()
                         addons (vec (sort-by :dirname addons))
                         primary (first (filter :primary? addons))
                         next-best (first addons)
                         new-data {:group-addons addons
                                   :group-addon-count (count addons)} ;; todo: do I need this?
                         next-best-label (-> next-best :group-id fs/base-name)
                         ;; add a group-level ignore flag if any bundled addon is being ignored
                         ;; todo: test for this
                         ignore-group? (when (utils/any (map :ignore? addons))
                                         {:ignore? true})

                         addon
                         (if primary
                           ;; best, easiest case
                           (merge primary new-data ignore-group?)
                           ;; when we can't determine the primary addon, add a shitty synthetic one
                           (merge next-best new-data ignore-group?
                                  {:label (format "%s (group)" next-best-label)
                                   :description (format "group record for the %s addon" next-best-label)}))

                         addon-str (clojure.string/join ", " (map :dirname addons))]

                     (logging/addon-log addon :info (format "contains %s addons: %s" (count addons) addon-str))
                     addon)))

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

(defn merge-toc-nfo
  "it's own function because the logic is duplicated in tests otherwise"
  [toc nfo]
  (merge toc nfo))

(defn-spec -load-installed-addons (s/or :ok :addon/toc-list, :error nil?)
  "returns a list of addon data scraped from the .toc files of all addons in given `install-dir`"
  [install-dir ::sp/addon-dir, game-track ::sp/game-track]
  (let [addon-dir-list (->> install-dir fs/list-dir (filter fs/directory?) (map str))
        parse-toc (fn [addon-dir]
                    (logging/with-addon {:dirname (-> addon-dir fs/file fs/base-name str)}
                      (let [toc-data-list (toc/parse-addon-toc-guard addon-dir)]
                        (if (= 1 (count toc-data-list))
                          ;; whatever toc data we have, we only have one of it (vast majority of cases) so return that
                          (first toc-data-list)

                          ;; we have multiple sets of toc-data to choose from. which to choose?
                          ;; prefer the one for the current game track, if it exists, otherwise do as we do in catalogue
                          ;; and use a list of priorities.
                          (let [grouped-toc-data (group-by :-toc/game-track toc-data-list)
                                priority-map {:retail [:retail :classic :classic-tbc]
                                              :classic [:classic :classic-tbc :retail]
                                              :classic-tbc [:classic-tbc :classic :retail]}
                                priorities (get priority-map game-track)
                                group (utils/first-nn #(get grouped-toc-data %) priorities)]
                            (when (and (> (count group) 1)
                                       ;; not all members in group are equal ...
                                       (not (apply = group)))
                              (warn (format "multiple sets of different toc data found for %s. using first." game-track)))
                            (first group))))))]
    (->> addon-dir-list
         (map parse-toc)
         (map #(dissoc % :-toc/game-track))
         (remove nil?)
         vec)))

(defn-spec load-installed-addons :addon/toc-list
  "reads the .toc files from the given addon dir, reads any nfo data for 
  these addons, groups them and returns the mooshed data."
  [install-dir ::sp/extant-dir, game-track ::sp/game-track]
  (let [addon-list (-load-installed-addons install-dir game-track)

        ;; at this point we have a list of the 'top level' addons, with
        ;; any bundled addons grouped within each one.

        ;; each addon now needs to be merged with the 'nfo' data, the additional
        ;; data we store alongside each addon when it is installed/updated

        merge-nfo-data (fn [addon]
                         (logging/with-addon addon
                           (let [nfo-data (nfo/read-nfo install-dir (:dirname addon))]
                             ;; merge the addon with the nfo data.
                             ;; when `ignore?` flag in addon is `true` but `false` in nfo-data, nfo-data will take precedence.
                             (merge-toc-nfo addon nfo-data))))

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
  [addon :addon/installable, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file]
  (let [zipfile-entries (zip/zipfile-normal-entries downloaded-file)
        toplevel-dirs (zip/top-level-directories zipfile-entries)
        primary-dirname (determine-primary-subdir toplevel-dirs)

        ;; not a show stopper, but if there are bundled addons and they don't share a common prefix, let the user know
        suspicious-bundle-check (fn []
                                  (let [sus-addons (zip/inconsistently-prefixed zipfile-entries)
                                        msg "%s will also install these addons: %s"]
                                    (when sus-addons
                                      (warn (format msg (:label addon) (clojure.string/join ", " sus-addons))))))

        unzip-addon (fn []
                      (zip/unzip-file downloaded-file install-dir))

        ;; an addon may unzip to many directories, each directory needs the nfo file
        update-nfo-fn (fn [zipentry]
                        (let [addon-dirname (:path zipentry)
                              primary? (= addon-dirname (:path primary-dirname))
                              new-nfo-data (nfo/derive addon primary?)
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

    (info (format "installing \"%s\" version \"%s\"" (:label addon) (:version addon)))
    (unzip-addon)
    (update-nfo-files)))

(defn-spec downloaded-addon-fname string?
  "given an addon's `name` and `version`, returns the expected addon zip filename."
  [name ::sp/name, version ::sp/version]
  (format "%s--%s.zip" name (utils/slugify version))) ;; addonname--1-2-3.zip

(defn-spec remove-zip-files! nil?
  "given a directory `install-dir`, and a prefix `addon-name`, find all zip files that match a pattern, sort 
  them, keep `n-zips-to-keep` files and delete the rest."
  [install-dir ::sp/install-dir, addon-name ::sp/name, n-zips-to-keep (s/or :ok zero? :also-ok pos-int?)]
  (let [pattern (re-pattern (str (utils/slugify addon-name) "\\-\\-.+\\.zip$")) ;; #"addonname\-\-.+\.zip$"
        file-list (fs/find-files install-dir pattern)
        ;; sort files oldest to newest
        asc >
        sorted-file-list (mapv str (sort-by #(.lastModified ^java.io.File %) asc file-list))
        ;; drop N of the newest files
        to-be-deleted (drop n-zips-to-keep sorted-file-list)
        failed-to-delete (remove nil? (mapv (partial utils/delete-file! install-dir) to-be-deleted))]
    (when-not (empty? failed-to-delete)
      (warn (format "failed to delete: %s"
                    (clojure.string/join ", " (mapv fs/base-name failed-to-delete))))))
  nil)

(defn-spec post-install nil?
  "does any final cleanup tasks.
  executed immediately after `install-addon`, coordinated by `core.clj`.
  executed regardless of success of installation."
  [addon :addon/installable, install-dir ::sp/install-dir, n-zips-to-keep :config/addon-zips-to-keep]
  (when n-zips-to-keep
    (remove-zip-files! install-dir (:name addon) n-zips-to-keep)))

;; ignore

;; todo: does this have tests? is it flawed like pinned-dir-list is?
(defn-spec ignored-dir-list (s/coll-of ::sp/dirname)
  "returns a list of unique addon directory names (including grouped addons) that are not being ignored"
  [addon-list (s/nilable :addon/installed-list)]
  (->> addon-list (filter :ignore?) (map :group-addons) flatten (map :dirname) (remove nil?) set))

(defn-spec overwrites-ignored? boolean?
  "returns `true` if given archive file would unpack over *any* ignored addon.
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

(defn-spec implicitly-ignored? boolean?
  "returns `true` if the addon in the given `install-dir`+`addon-dirname` directory is being implicitly ignored.
  an 'implicit ignore' is when the addon is under version control or the `.toc` file looks like an unrendered template."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (let [path (utils/join install-dir addon-dirname)
        check-toc-data (fn [[_ filename]]
                         (-> path
                             (utils/join filename)
                             toc/read-toc-file
                             (toc/parse-addon-toc path)
                             (contains? :ignore?)))]
    (or (nfo/version-controlled? path) ;; cheapest check first
        (->> path toc/find-toc-files (map check-toc-data) utils/any))))

(defn-spec ignore nil?
  "marks the given `addon` and all of it's group members (if any) as 'ignored'"
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (->> addon
       ungroup-addon
       flatten
       (map :dirname)
       (run! (partial nfo/ignore install-dir))))

(defn-spec clear-ignore nil?
  "clears the `ignore?` flag on an addon, either by removing it from the nfo or setting it in the nfo to `false`.
  Has to happen here so we can distinguish between 'toc-ignores' and 'nfo-ignores'."
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (let [addon-dirname (:dirname addon)
        ignore-fn (if (implicitly-ignored? install-dir addon-dirname)
                    nfo/stop-ignoring
                    nfo/clear-ignore)]
    (->> addon
         ungroup-addon
         (mapv :dirname)
         (run! (partial ignore-fn install-dir)))))

;;

(defn flatten-addon
  "given an `addon`, returns a list of that addon's members (that includes itself) or the addon wrapped in a list."
  [addon]
  (if-let [group-members (:group-addons addon)]
    group-members
    [addon]))

(defn-spec pin nil?
  "pins an `addon` and all of it's group members (if any) to the given `version` or the `:installed-version` when missing.
  if addon does not have an `:installed-version` it will fail silently."
  ([install-dir ::sp/install-dir, addon :addon/toc]
   (when-let [installed-version (:installed-version addon)]
     (pin install-dir addon installed-version)))
  ([install-dir ::sp/install-dir, addon :addon/toc, version :addon/pinned-version]
   (->> addon
        flatten-addon
        (map :dirname)
        (run! #(nfo/pin install-dir % version)))))

(defn-spec unpin nil?
  "unpins an `addon` and all of it's group members. 
  if an addon is not pinned it will fail silently."
  [install-dir ::sp/extant-dir, addon :addon/toc]
  (->> addon
       flatten-addon
       (map :dirname)
       (run! #(nfo/unpin install-dir %))))

(defn-spec pinned-dir-list (s/coll-of ::sp/dirname)
  "returns a set of unique addon directory names (including grouped addons) that are pinned"
  [addon-list (s/nilable :addon/installed-list)]
  (into (->> addon-list (filter :pinned-version) (map :dirname) (remove nil?) set)
        (->> addon-list (map :group-addons) flatten (filter :pinned-version) (map :dirname) (remove nil?))))

(defn-spec overwrites-pinned? boolean?
  "returns `true` if given `downloaded-file` would unpack over any pinned addons.
  this includes already installed versions of itself."
  [downloaded-file ::sp/archive-file, addon-list (s/nilable :addon/installed-list)]
  (let [pinned-list (pinned-dir-list addon-list)
        zip-dir-list (->> downloaded-file
                          zip/zipfile-normal-entries
                          zip/top-level-directories
                          (map :path)
                          (mapv #(utils/rtrim % "/")))
        zip-dir-in-pinned-dir-list? (fn [zip-dir] (some #{zip-dir} pinned-list))]
    (utils/any (map zip-dir-in-pinned-dir-list? zip-dir-list))))

(defn-spec find-release (s/or :ok :addon/source-updates :not-found nil?)
  "returns the first release from an addon's `:release-list` that matches the addon's `:installed-version`"
  [addon :addon/expanded]
  (some->> addon :release-list (filter #(= (:installed-version addon) (:version %))) first))

(defn-spec find-pinned-release (s/or :ok :addon/source-updates :not-found nil?)
  "returns the first release from an addon's `:release-list` that matches the addon's `:pinned-version`"
  [addon :addon/expandable]
  (when-let [{:keys [pinned-version]} addon]
    (some->> addon :release-list (filter #(= pinned-version (:version %))) first)))

;;

(defn-spec installed? boolean?
  "returns `true` if given `addon` is present on filesystem."
  [addon map?] ;; deliberately lenient
  (contains? addon :dirname))

(defn-spec ignored? boolean?
  "returns `true` if given `addon` is being ignored."
  [addon map?]
  (get addon :ignore? false))

(defn-spec ignorable? boolean?
  "returns `true` if given `addon` can be ignored."
  [addon map?]
  (and (installed? addon)
       (not (ignored? addon))))

(defn-spec updateable? boolean?
  "returns `true` when given `addon` can be updated to a newer version."
  [addon map?] ;; deliberately lenient
  (let [{:keys [installed-version pinned-version version
                game-track installed-game-track
                supported-game-tracks]} addon]
    (cond
      ;; not expanded
      (not version) false

      ;; ignored
      (:ignore? addon) false

      ;; pinned
      ;; a pinned addon can only be *updated* if the version installed doesn't match its pinned version and it's pinned version matches the available version.
      pinned-version (and (not= pinned-version installed-version)
                          (= pinned-version version))

      ;; if the available version is equal to the installed version
      ;; AND the installed game track is the same as the available game track
      ;; (OR, the installed game track is present in the list of game tracks supported by the addon),
      ;; then the addon is NOT updateable.
      (and game-track installed-game-track)
      (not (and (= version installed-version)
                (or (= game-track installed-game-track)
                    (utils/in? installed-game-track supported-game-tracks))))

      :else (not= version installed-version))))

(defn-spec re-installable? boolean?
  "returns `true` if given `addon` can be re-installed to its current `:installed-version`."
  [addon map?] ;; deliberately lenient
  (boolean
   (and (installed? addon)
        (contains? addon :release-list)
        (some? (find-release addon)))))

(defn-spec pinned? boolean?
  "returns `true` if given `addon` is pinned to a specific version."
  [addon map?]
  (some? (:pinned-version addon)))

(defn-spec pinnable? boolean?
  "returns `true` if given `addon` can be pinned."
  [addon map?]
  (and (installed? addon)
       (contains? addon :installed-version) ;; could this live in `installed?`
       (not (pinned? addon))
       (not (ignored? addon))))

(defn-spec unpinnable? boolean?
  "returns `true` if given `addon` can be un-pinned."
  [addon map?]
  (and (pinned? addon)
       (not (ignored? addon))))

(defn-spec deletable? boolean?
  "returns `true` if the given `addon` can be deleted."
  [addon map?]
  (and (installed? addon)
       (not (ignored? addon))))

(defn-spec releases-available? boolean?
  "returns `true` if *any* addons are available, including the currently installed version, and the addon isn't pinned.
  ignored addons shouldn't have a `:release-list`."
  [addon map?]
  (and (not (empty? (:release-list addon)))
       (not (pinned? addon))))

(defn-spec releases-visible? boolean?
  "returns `true` if there is more than 1 release available and the addon isn't pinned"
  [addon map?]
  (releases-available? (update-in addon [:release-list] rest)))
