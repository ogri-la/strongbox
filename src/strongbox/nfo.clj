(ns strongbox.nfo
  (:refer-clojure :rename {derive clj-derive})
  (:require
   [strongbox
    [specs :as sp]
    [utils :as utils]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]))

(comment
  "a '.strongbox.json' (nfo) file is written when an addon is installed or updated. 
  It serves to fill in any blank spots in our knowledge of the addon.
  The file can be safely deleted but some addons may become ungrouped and fail to find a catalogue match.")

(def nfo-filename ".strongbox.json")

(def ignorable-dir-set #{".git" ".hg" ".svn"})

(defn-spec version-controlled? boolean?
  "returns `true` if `path` contains a directory used for version control"
  [path ::sp/extant-dir]
  (let [sub-dir-list (->> path fs/list-dir (filter fs/directory?) (map fs/base-name) (mapv str))]
    (not (nil? (some ignorable-dir-set sub-dir-list)))))

(defn-spec prune :addon/nfo
  "removes keys not present in the `addon/nfo` spec to prevent writing unwanted data to nfo files."
  [addon :addon/nfo]
  (if (sequential? addon)
    (mapv prune addon)
    (select-keys addon [:installed-version
                        :name
                        :group-id
                        :primary?
                        :source
                        :installed-game-track
                        :source-id
                        :ignore?
                        :pinned-version
                        :source-map-list])))

(defn-spec -derive :addon/nfo
  "extract fields from the addon data that will be used in the nfo file"
  [addon :addon/nfo-input-minimum, primary? boolean?]
  (let [nfo {;; important! as an addon is updated or installed, the `:installed-version` value from the .toc file
             ;; is overridden by the `:version` value from the expand-summary action later.
             :installed-version (:version addon)

             ;; used to filter available updates.
             ;; also, knowing the regime the addon was installed under allows us to export and later re-import the correct version.
             :installed-game-track (:game-track addon)

             ;; normalised name.
             ;; once used to match to online addon, we now use source+source-id
             :name (:name addon)

             ;; groups all of an addon's directories together.
             :group-id (:url addon)

             ;; if addon is one of multiple addons, is this addon considered the 'primary' one?
             :primary? primary?

             ;; where the addon came from and how they identified it
             :source (:source addon)
             :source-id (:source-id addon)

             ;; record the origin and it's ID so we can switch back to it later if other sources present themselves.
             :source-map-list [{:source (:source addon), :source-id (:source-id addon)}]}

        ;; users can set this in the nfo file manually or
        ;; it can be drived later in the process by examining the addon's toc file or subdirs, or
        ;; it may be present when upgrading an existing nfo file and should be preserved
        ignore-flag (when-some [ignore? (:ignore? addon)]
                      {:ignore? ignore?})

        pinned-version (when-some [pinned-version (:pinned-version addon)]
                         {:pinned-version pinned-version})]

    (merge nfo ignore-flag pinned-version)))

(defn-spec derive :addon/nfo
  "extract fields from the addon data that will be written to the nfo file"
  [addon :addon/nfo-input-minimum, primary? boolean?]
  (if (s/valid? :addon/-nfo-just-group addon)
    ;; addon is coming from an unknown source. rather than munge unknown fields, use the given group-id
    {:group-id (:group-id addon)
     :primary? primary?}

    (-derive addon primary?)))

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (utils/join install-dir addon-dirname nfo-filename)) ;; /path/to/addons/AddonName/.strongbox.json

(defn-spec rm-nfo-file nil?
  "deletes a nfo file and *only* a nfo file"
  [path ::sp/extant-file]
  (when (= nfo-filename (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo-file (s/or :ok :addon/nfo, :error nil?)
  "reads the nfo file at the given `path` with basic transformations.
  failure to load the json results in the file being deleted.
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (let [path (nfo-path install-dir addon-dirname)
        bad-data (fn []
                   (warn (format "bad \"%s\" file, deleting: %s" nfo-filename path))
                   (rm-nfo-file path))
        invalid-data (fn []
                       (when (fs/exists? path)
                         (warn (format "invalid \"%s\" file, deleting: %s" nfo-filename path))
                         (rm-nfo-file path)))
        opts {:no-file? nil
              :bad-data? bad-data
              :key-fn keyword
              :transform-map {:installed-game-track keyword}}

        coerce (fn [nfo-data]
                 (if (and (map? nfo-data)
                          (contains? nfo-data :source)
                          (not (contains? nfo-data :source-map-list)))
                   (assoc nfo-data :source-map-list (-> nfo-data utils/source-map vector))
                   nfo-data))

        nfo-data (coerce (utils/load-json-file-safely path opts))]
    (if (s/valid? :addon/nfo nfo-data)
      nfo-data
      (invalid-data))))

(defn-spec mutual-dependencies (s/coll-of :addon/nfo)
  "returns a list of `addon/nfo` data for addons using the given `addon-dirname` (including itself)."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (let [contents (read-nfo-file install-dir addon-dirname)]
    (cond
      (nil? contents) [] ;; an ignored addon may not have nfo data
      (not (vector? contents)) [contents]
      :else contents)))

(defn-spec mutual-dependency? boolean?
  "returns `true` if multiple sets of nfo data exist in file"
  ([nfo-data (s/nilable ::sp/map-or-list-of-maps)]
   (vector? nfo-data))
  ([install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
   (mutual-dependency? (read-nfo-file install-dir addon-dirname))))

(defn-spec read-nfo (s/or :ok :addon/nfo, :error nil?)
  "parses the contents of the .nfo file and checks if addon should be ignored or not"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (let [nfo-file-contents (read-nfo-file install-dir addon-dirname)
        nfo-file-contents (if (mutual-dependency? nfo-file-contents)
                            (last nfo-file-contents)
                            nfo-file-contents)
        ;; if `ignore?` is present in the nfo file it overrides the nfo and toc file checks.
        ;; this value may also be introduced in `toc.clj`
        user-ignored (contains? nfo-file-contents :ignore?)
        _ (when (and user-ignored
                     (:ignore? nfo-file-contents))
            (debug (format "ignoring \"%s\"" addon-dirname)))

        ignore-flag (when (and (not user-ignored)
                               (version-controlled? (utils/join install-dir addon-dirname)))
                      (warn (format "ignoring \"%s\": addon directory contains a .git/.hg/.svn folder" addon-dirname))
                      {:ignore? true})]
    (merge nfo-file-contents ignore-flag)))

;;

(defn-spec flatten-nfo ::sp/map-or-list-of-maps
  "ensures given `nfo-data` is either a map or a list of multiple maps."
  [nfo-data ::sp/map-or-list-of-maps]
  (if (and (sequential? nfo-data)
           (-> nfo-data count (= 1)))
    (first nfo-data)
    nfo-data))

(defn-spec rm-nfo* (s/or :ok :addon/nfo, :error nil?)
  "removes any nfo data items matching `group-id`. returns a list of nfo data"
  [nfo-data (s/nilable :addon/nfo), group-id (s/nilable ::sp/group-id)]
  (when nfo-data
    (let [nfo-data (if (sequential? nfo-data) nfo-data [nfo-data])]
      (->> nfo-data (remove #(= group-id (:group-id %))) vec))))

(defn-spec rm-nfo (s/or :ok :addon/nfo, :error nil?)
  "removes any nfo data matching `group-id`. returns a nfo map or a list of nfo maps."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, group-id ::sp/group-id]
  (-> (read-nfo-file install-dir addon-dirname) (rm-nfo* group-id) flatten-nfo))

(defn-spec add-nfo :addon/nfo
  "adds the given nfo data to the end of the list (most recent) and removes it from any other position in the list"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, new-nfo-data :addon/nfo]
  (let [user-warning (fn [nfo-data-list]
                       (when-not (empty? nfo-data-list)
                         ;; catalogue overwriting catalogue
                         ;; '"Healbot Continued" (9.2.0.12) replaced dir 'HealBot/' of addon "Healbot Continued" (9.2.0.7)'

                         ;; catalogue overwriting file install
                         ;; '"Healbot Continued" (9.2.0.12) replaced dir 'HealBot/' of addon "healbot-continued-abcdef12345'

                         ;; file install overwriting catalogue
                         ;; '"healbot-continued-abcdef12345' replaced dir 'HealBot/' of addon "Healbot Continued" (9.2.0.12)'
                         (let [target (last nfo-data-list) ;; todo: when stacked N high, won't this report incorrectly?
                               nom #(or (:name %)
                                        (:group-id %))
                               version #(if-let [v (:installed-version %)]
                                          (format " (%s)" v) "")
                               msg (format "\"%s\"%s replaced directory \"%s\" of addon \"%s\"%s"
                                           (nom new-nfo-data) (version new-nfo-data)
                                           addon-dirname (nom target) (version target))]
                           (warn msg)))
                       nfo-data-list)]
    (-> (read-nfo-file install-dir addon-dirname)
        (rm-nfo* (:group-id new-nfo-data))
        user-warning
        (conj new-nfo-data)
        flatten-nfo)))

;;

(defn-spec write-nfo! (s/or :ok ::sp/extant-file, :error nil?)
  "given an installation directory and an addon, select the neccessary bits (`prune`) and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, addon ::sp/map-or-list-of-maps]
  (let [path (nfo-path install-dir addon-dirname)]
    (if-not (s/valid? :addon/nfo addon)
      ;; 'new "HealBot_ExtraSkins/.strongbox.json" data is invalid and won't be written to disk. This is a program error, please report it.'
      (do (error (format "new \"./%s/%s\" data is invalid and won't be written to disk. This is a program error, please report it." addon-dirname nfo-filename))
          (debug (s/explain :addon/nfo addon)))
      (utils/dump-json-file path (prune addon)))))

;; this function could definitely do with a second pass, but not right now.
;; it's doing two things: updating the nfo and conditionally writing/removing a file
;; and the update logic is tied to the removal logic
(defn-spec update-nfo! nil?
  "updates *existing* nfo data with new values."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, updates map?]
  (let [path (nfo-path install-dir addon-dirname)
        nfo (read-nfo install-dir addon-dirname)
        new-nfo (merge nfo updates)
        ;; convenience for implicitly ignored addons.
        ;; if the nfo file was missing (implicitly ignored) and the update is to clear the flag, set the flag to false instead
        ;; this will create a new nfo file explicitly *not* ignoring the addon.
        ;; if the flag is cleared again, it will encounter a nfo file and delete the nfo (see below)
        new-nfo (if (and (not (fs/exists? path))
                         (= {:ignore? nil} new-nfo))
                  {:ignore? false}
                  new-nfo)

        ;; if these values are nil they will be dissoc'ed from the map
        dissoc-nil-keys [:ignore? :pinned-version]
        new-nfo (utils/drop-nils new-nfo dissoc-nil-keys)]

    (if (empty? new-nfo)
      ;; edge case. a valid nfo file is also simply a `ignore: [True|False]`.
      ;; if `:ignore?` was dissociated, it's now empty. when this happens, just delete the nfo file.
      (rm-nfo-file path)
      (write-nfo! install-dir addon-dirname new-nfo)))
  nil)

;; ignoring

(defn-spec ignore :addon/nfo
  "add a `ignore?` flag to given `nfo` data."
  [nfo :addon/nfo]
  (assoc nfo :ignore? true))

(defn-spec ignore! nil?
  "prevent any changes made by strongbox to this addon. 
  explicitly ignores this addon by setting the `ignore?` flag to `true`."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (update-nfo! install-dir addon-dirname {:ignore? true}))

(defn-spec stop-ignoring! nil?
  "sets the `ignore?` flag to `false`, which is an explicit 'do not ignore'.
  used for implicitly ignored addons."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (update-nfo! install-dir addon-dirname {:ignore? false}))

(defn-spec clear-ignore! nil?
  "removes the `ignore?` flag on a specific addon.
  the addon may still be implicitly ignored afterwards."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (update-nfo! install-dir addon-dirname {:ignore? nil}))

;; pinning

(defn-spec pin! nil?
  "'pins' the given `version` of a specific addon"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, version :addon/pinned-version]
  (update-nfo! install-dir addon-dirname {:pinned-version version}))

(defn-spec unpin :addon/nfo
  "remove pin flag from given `nfo` data"
  [nfo :addon/nfo]
  (dissoc nfo :pinned-version))

(defn-spec unpin! nil?
  "removes `:pinned-version` from a specific addon's nfo file, if it exists"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
  (update-nfo! install-dir addon-dirname {:pinned-version nil}))
