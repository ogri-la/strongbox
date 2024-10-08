(ns strongbox.addon
  (:require
   [clojure.string :refer [lower-case starts-with?]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [clojure.spec.alpha :as s]
   [clojure.set]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [strongbox
    [constants :as constants]
    [logging :as logging]
    [toc :as toc]
    [utils :as utils]
    [nfo :as nfo]
    [zip :as zip]
    [specs :as sp]]))

(comment "`addon` ties the `nfo` and `toc` logic together")

(def dummy-dirname "not-the-addon-dir-you-are-looking-for")

(defn-spec host-disabled? boolean?
  "returns `true` if the addon host has been disabled"
  [addon map?]
  (or (-> addon :source (= "curseforge"))
      (-> addon :source (utils/in? sp/tukui-source-list))))

(defn-spec -remove-addon! nil?
  "safely removes the given `addon-dirname` from `install-dir`.
  if the given `addon-dirname` is a mutual dependency with another addon, just remove it's entry from
  the nfo file instead of deleting the whole directory."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, group-id (s/nilable ::sp/group-id)]
  (let [addon-dirname (fs/base-name addon-dirname) ;; `fs/base-name` strips any parents. todo: when might this happen?
        addon-path (-> install-dir (utils/join addon-dirname) fs/absolute fs/normalized str)]
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
        (nfo/write-nfo! install-dir addon-dirname updated-nfo-data)
        (debug (format "removed \"%s\" as mutual dependency" addon-dirname)))

      ;; all good, remove addon
      :else (do (fs/delete-dir addon-path)
                (debug (format "removed '%s'" addon-path))))))

(defn-spec flatten-addon ::sp/list-of-maps
  "given an `addon`, returns a list of that addon's members, including itself."
  [addon map?]
  (if-let [group-members (:group-addons addon)]
    group-members
    [addon]))

(defn-spec dirname-set set?
  "returns a set of names of directories used by the given `addon` and it's grouped addons"
  [addon map?]
  ;; addon may not be installed yet, we may not have any `:dirname` values at all
  (->> addon flatten-addon (map :dirname) (remove nil?) utils/nilable set))

(defn-spec remove-addon! nil?
  "removes the given `addon`.
  if addon is part of a group, all addons in group are removed.
  if addon is being ignored, addon will not be removed."
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (info (format "removing \"%s\" version \"%s\"" (:label addon) (:installed-version addon)))
  (if (:ignore? addon)
    ;; if addon is being ignored, refuse to remove addon.
    ;; note: `group-addons` will add a top level `:ignore?` flag if any addon in a bundle is being ignored.
    ;; 2024-08-25: behaviour changed. this is not the place to prevent ignored addons from being removed.
    ;; see `core/install-addon`, `core/remove-many-addons`
    ;;(error "refusing to delete ignored addon:" (:label addon))
    (warn "deleting ignored addon:" (:label addon)))

  (doseq [grouped-addon (flatten-addon addon)]
    (-remove-addon! install-dir (:dirname grouped-addon) (:group-id addon))))

;;

(defn-spec group-addons :addon/installed-list
  "an addon may actually be many addons bundled together in a single download.
  strongbox tags the bundled addons as they are unzipped and tries to determine the primary one.
  after we've loaded the addons and merged their nfo data, we can then group them with the primary
  addon representing the group."
  [addon-list :addon/installed-list]
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
                         new-data {:group-addons addons}
                         next-best-label (-> next-best :group-id fs/base-name)
                         ;; add a group-level ignore flag if any bundled addon is being ignored
                         ;; todo: test for this.
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

                         ;; count total size in bytes, update top-level :dirsize
                         total-grouped-size (apply + (remove nil? (map :dirsize (:group-addons addon))))
                         addon (if (> total-grouped-size 0)
                                 (assoc addon :dirsize total-grouped-size)
                                 addon)

                         msg-str (clojure.string/join ", " (map :dirname addons))]

                     (logging/addon-log addon :info (format "contains %s addons: %s" (count addons) msg-str))
                     addon)))

        ;; this flattens the newly grouped addons from a map into a list and joins the unknowns
        addon-list (apply conj (mapv expand addon-groups) unknown-grouping)]
    addon-list))

(defn-spec merge-lists (s/or :ok vector? :empty nil?)
  "merges two lists of items returning a single list of distinct items or nil if empty"
  [list-a (s/nilable sequential?), list-b (s/nilable sequential?)]
  (some->> (into list-a list-b)
           (remove nil?)
           utils/nilable
           distinct
           vec))

(defn-spec extract-source-map-list (s/or :ok :addon/source-map-list, :empty nil?)
  "extracts a `:addon/source-map` from the given `data` and anything in `:source-map-list`, returning a single distinct `:addon/source-map-list`"
  [data (s/nilable map?)]
  (merge-lists
   (or (some-> data utils/source-map utils/nilable vector) [])
   (get data :source-map-list [])))

;; todo: this spec could do with tightening up. ~6 tests holding us back.
(defn-spec merge-toc-nfo (s/or :ok map?, :empty nil?)
  "merges `toc` data with `nfo` data with special handling for the `source-map-list`."
  [toc (s/nilable map?), nfo (s/nilable map?)]
  (let [source-map-list (some->> (merge-lists (extract-source-map-list toc)
                                              (extract-source-map-list nfo))
                                 (remove host-disabled?)
                                 vec
                                 (assoc {} :source-map-list))]
    (merge toc nfo source-map-list)))

(defn-spec -load-installed-addon (s/or :ok :addon/toc, :error nil?)
  "finds the toc data for the given `addon-dir`, using the `game-track` to choose the correct toc file if many toc files exist."
  [addon-dir ::sp/addon-dir, game-track ::sp/game-track]
  (logging/with-addon {:dirname (-> addon-dir fs/base-name str)}
    (let [toc-data-list (toc/parse-addon-toc-guard addon-dir)]
      (if (= 1 (count toc-data-list))
        ;; we only have 1 set of .toc data, so return that
        (-> toc-data-list first (dissoc :-toc/game-track-list))

        ;; we have multiple sets of .toc data to choose from. which to choose?
        ;; prefer the one for the given `game-track`, if it exists, otherwise do as we do with
        ;; the catalogue and use a list of priorities.
        (let [grouped-toc-data (utils/group-by-coll :-toc/game-track-list toc-data-list)
              safe-fallback [game-track]
              priorities (get constants/game-track-priority-map game-track safe-fallback)
              group (utils/first-nn #(get grouped-toc-data %) priorities)]

          ;; after grouping toc data by game-track we may have multiple `:retail` or `:classic` data sets.
          ;; we're going to use the first one in the list, but issue a warning anyway.
          (when (and (> (count group) 1)
                     ;; not all members in group are the same ...
                     (not (apply = group)))
            (debug (format "multiple sets of different toc data found for %s. using first." game-track)))

          (-> group first (dissoc :-toc/game-track-list)))))))

(defn-spec load-all-installed-addons :addon/toc-list
  "reads and merges the toc data and the nfo data from *all* addons in the given `install-dir`, groups them and returns the grouped mooshed data."
  [install-dir ::sp/extant-dir, game-track ::sp/game-track]
  (->> install-dir
       fs/list-dir
       (filter fs/directory?)
       (map #(-load-installed-addon (str %) game-track))
       (remove nil?) ;; when toc data is bad
       (map #(merge-toc-nfo % (nfo/read-nfo install-dir (:dirname %))))
       group-addons))

(defn-spec load-installed-addon (s/or :ok-toc :addon/toc, :ok-nfo :addon/nfo, :error nil?)
  "reads and merges the toc data and the nfo data for a specific addon at `addon-dir` (bad name) and all those in it's group,
  groups them up (again) and returns the grouped mooshed data.
  this was introduced much later than `load-all-installed-addons` as parallel installation and loading of addons was introduced."
  [addon-dir ::sp/addon-dir, game-track ::sp/game-track]
  (logging/with-addon {:dirname (-> addon-dir fs/base-name str)}
    (let [install-dir (str (fs/parent addon-dir))
          addon-dirname (str (fs/base-name addon-dir))
          target-nfo (nfo/read-nfo install-dir addon-dirname)
          target-toc (-load-installed-addon addon-dir game-track)
          target-addon (merge-toc-nfo target-toc target-nfo)
          group-id (:group-id target-addon)
          -load-installed-addon* (fn [[addon-path nfo-data]]
                                   (when-let [toc-data (if (= addon-dir addon-path)
                                                         ;; skip reading toc data a second time as any warnings generated are duplicated for the user.
                                                         target-toc
                                                         (-load-installed-addon addon-path game-track))]
                                     (merge-toc-nfo toc-data nfo-data)))

          grouped-addon
          (->> install-dir
               fs/list-dir
               (filter fs/directory?)
               ;; [[/path/to/addon, {nfo data, ...}], ...]
               (map #(vector (str %) (logging/silenced (nfo/read-nfo install-dir (str (fs/base-name %))))))
               (filter #(= group-id (:group-id (second %))))
               (map -load-installed-addon*)
               (remove nil?)
               group-addons ;; should only be one, we filtered by group-id earlier
               first)]
      (or grouped-addon target-addon))))

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
  "installs an addon given an addon description, a place to install the addon and the addon zip file itself.
  handles suspicious looking bundles, conflicts with other addons, uninstalling previous addon version and updating nfo files.

  relies on `core/install-addon` to block installations that would overwrite ignored or pinned addons.
  if it's gotten this far ignored/pinned addons will be deleted
  and the new addon will be unzipped over the top.

  returns a list of nfo files that were written to disk, if any."
  [addon :addon/nfo-input-minimum, install-dir ::sp/writeable-dir, downloaded-file ::sp/archive-file, opts ::sp/install-opts]
  (let [nom (or (:label addon) (:name addon) (fs/base-name downloaded-file))
        version (:version addon)

        ;; 'Installing "EveryAddon" version "1.2.3"'  or just  'Installing "EveryAddon"'
        _ (if version
            (info (format "installing \"%s\" version \"%s\"" nom version))
            (info (format "installing \"%s\"" nom)))

        zipfile-entries (zip/zipfile-normal-entries downloaded-file)
        toplevel-dirs (zip/top-level-directories zipfile-entries)

        toplevel-nfo (->> toplevel-dirs ;; [{:path "EveryAddon/", ...}, ...]
                          (map :path) ;; ["EveryAddon/", ...]
                          (map fs/base-name) ;; ["EveryAddon", ...]
                          (map #(nfo/read-nfo-file install-dir %))) ;; [`(read-nfo-file install-dir "EveryAddon"), ...]

        contains-nfo-with-ignored-flag (utils/any (map :ignore? toplevel-nfo))
        contains-nfo-with-pinned-version (utils/any (map :pinned-version toplevel-nfo))

        primary-dirname (determine-primary-subdir toplevel-dirs)

        ;; let the user know if there are bundled addons and they don't share a common prefix
        ;; "EveryAddon will also install these addons: Foo, Bar, Baz"
        suspicious-bundle-check (fn []
                                  (let [sus-addons (zip/inconsistently-prefixed zipfile-entries)
                                        msg "%s will also install these addons: %s"]
                                    (when sus-addons
                                      (warn (format msg nom (clojure.string/join ", " sus-addons))))))

        unzip-addon (fn []
                      (zip/unzip-file downloaded-file install-dir))

        ;; an addon may unzip to many directories, each directory needs the nfo file
        update-nfo-fn (fn [zipentry]
                        (let [addon-dirname (:path zipentry)
                              primary? (= addon-dirname (:path primary-dirname))
                              new-nfo-data (nfo/derive addon primary?)
                              ;; if any of the addons this addon is replacing are being ignored,
                              ;; the new nfo will be ignored too.
                              new-nfo-data (if contains-nfo-with-ignored-flag
                                             (nfo/ignore new-nfo-data)
                                             new-nfo-data)

                              ;; if any of the addons this addon is replacing are pinned,
                              ;; the pin is removed. We've just modified them and they are no longer at that version.
                              new-nfo-data (if contains-nfo-with-pinned-version
                                             (nfo/unpin new-nfo-data)
                                             new-nfo-data)

                              new-nfo-data (nfo/add-nfo install-dir addon-dirname new-nfo-data)]
                          (nfo/write-nfo! install-dir addon-dirname new-nfo-data)))

        ;; write the nfo files, return a list of all nfo files written
        ;; todo: if a zip file is being installed then we can't rely on `remove-addon!` having been called,
        ;; but `remove-completely-overwritten-addons` will have been called and *may* have removed the
        ;; addon *if* the new addon is a superset of the old one.
        ;; this leads to the possibility of a new addon that has dropped a subdir or added a new one (like a rename)
        ;; being skipped and orphaning the original subdir.
        ;; this means we could hit `unzip-addon` with the original addon still fully intact.
        update-nfo-files (fn []
                           (mapv update-nfo-fn toplevel-dirs))

        ;; an addon may completely replace an addon from another group.
        ;; if it's a complete replacement, uninstall addon instead of creating a mutual dependency.
        remove-completely-overwritten-addons
        (fn []
          ;; find the full addons for each 
          (let [strip-trailing-slash #(utils/rtrim % "/")
                ;; all of the directories this addon will create
                dir-superset (->> toplevel-dirs
                                  (map :path)
                                  (map fs/base-name)
                                  (map strip-trailing-slash)
                                  set)

                all-addon-data (logging/silenced ;; swallow log output, else warnings for unrelated addons are surfaced for *this* addon
                                ;; we don't care which game track is used, just that addons are logically grouped.
                                (load-all-installed-addons install-dir :retail))

                removeable? (fn [some-addon]
                              (let [dir-subset (->> some-addon
                                                    flatten-addon
                                                    (map :dirname)
                                                    set)]
                                (clojure.set/subset? dir-subset dir-superset)))]
            (->> all-addon-data
                 (filter removeable?)
                 (run! (partial remove-addon! install-dir)))))]

    (suspicious-bundle-check)

    ;; todo: remove support for v1 addons in 2.0.0 ;; todo!
    ;; when is it not valid?
    ;; * when importing v1 addons. v2 addons need 'padding' as well :(
    ;; * when installing from a file and we have nothing more than a generated ID value
    (when (s/valid? :addon/toc addon)
      (remove-addon! install-dir addon))

    (remove-completely-overwritten-addons)

    ;; `addon/install-addon` is all about installing an addon, not checking whether it's safe to do so.
    ;; use `core/install-addon` for safety checks.
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

;; todo: does this have tests? is it flawed like pinned-dir-list is? ;; todo: wtf?
(defn-spec ignored-dir-list (s/coll-of ::sp/dirname)
  "returns a list of unique addon directory names (including grouped addons) that are not being ignored"
  [addon-list (s/nilable :addon/installed-list)]
  (->> addon-list (filter :ignore?) (map flatten-addon) flatten (map :dirname) (remove nil?) set))

(defn-spec overwrites-ignored? boolean?
  "returns `true` if given archive file would unpack over *any* ignored addon.
  this includes already installed versions of itself and is another check against modifying ignored addons."
  [downloaded-file ::sp/archive-file, addon-list (s/nilable :addon/installed-list)]
  (let [ignore-list (ignored-dir-list addon-list)
        zip-dir-list (->> downloaded-file
                          zip/zipfile-normal-entries
                          zip/top-level-directories
                          (map :path)
                          (map #(utils/rtrim % "/")))
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

(defn-spec ignore! nil?
  "marks the given `addon` and all of it's group members (if any) as 'ignored'"
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (->> addon
       flatten-addon
       (map :dirname)
       (run! (partial nfo/ignore! install-dir))))

(defn-spec clear-ignore! nil?
  "clears the `ignore?` flag on an addon and all of it's grouped addons.
  It either removes the flag from the nfo or sets it to `false`.
  Has to happen here so we can distinguish between 'toc-ignores' and 'nfo-ignores'."
  [install-dir ::sp/extant-dir, addon :addon/installed]
  (let [addon-dirname (:dirname addon)
        ignore-fn (if (implicitly-ignored? install-dir addon-dirname)
                    nfo/stop-ignoring!
                    nfo/clear-ignore!)]
    (->> addon
         flatten-addon
         (map :dirname)
         (run! (partial ignore-fn install-dir)))))

;; pinning

(defn-spec pin! nil?
  "pins an `addon` and all of it's group members (if any) to the given `version` or the `:installed-version` when missing.
  if addon does not have an `:installed-version` it will fail silently."
  ([install-dir ::sp/install-dir, addon :addon/toc]
   (when-let [installed-version (:installed-version addon)]
     (pin! install-dir addon installed-version)))
  ([install-dir ::sp/install-dir, addon :addon/toc, version :addon/pinned-version]
   (->> addon
        flatten-addon
        (map :dirname)
        (run! #(nfo/pin! install-dir % version)))))

(defn-spec unpin! nil?
  "unpins an `addon` and all of it's group members. 
  if an addon is not pinned it will fail silently."
  [install-dir ::sp/extant-dir, addon :addon/toc]
  (->> addon
       flatten-addon
       (map :dirname)
       (run! #(nfo/unpin! install-dir %))))

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

;; predicates

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

      ;; versions are equal, game tracks may not be.
      (and (= version installed-version)
           (and game-track installed-game-track)) (if (utils/in? game-track supported-game-tracks)
                                                    ;; doesn't matter if installed game track doesn't match available game track, the available game track is supported.
                                                    false
                                                    (not= game-track installed-game-track))

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
  "returns `true` if *any* addon releases are available (including for the currently installed version) and the addon isn't pinned.
  ignored addons don't have a `:release-list` because they don't get expanded."
  [addon map?]
  (and (not (empty? (:release-list addon)))
       (not (pinned? addon))))

(defn-spec releases-visible? boolean?
  "returns `true` if there is more than 1 release available and the addon isn't pinned."
  [addon map?]
  (releases-available? (update-in addon [:release-list] rest)))

;;

(defn-spec update-nfo! nil?
  "updates the nfo data for the given `addon` and all of it's grouped addons"
  [install-dir ::sp/extant-dir, addon (s/keys :req-un [::sp/dirname]), updates map?]
  (doseq [grouped-addon (flatten-addon addon)]
    (nfo/update-nfo! install-dir (:dirname grouped-addon) updates)))

(defn-spec switch-source! nil?
  "switches addon from one source (like curseforge) to another (like wowinterface), rewriting nfo data.
  `new-source` must appear in the addon's `source-map-list`."
  [install-dir ::sp/extant-dir, addon :addon/installed, new-source-map :addon/source-map]
  (when (and (utils/in? new-source-map (:source-map-list addon))
             (not (ignored? addon))
             (not (pinned? addon)))
    (let [new-source-map-list (merge-lists (extract-source-map-list addon)
                                           [new-source-map])
          nfo-updates (merge new-source-map {:source-map-list new-source-map-list})]
      (update-nfo! install-dir addon nfo-updates))))
