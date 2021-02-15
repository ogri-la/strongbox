(ns strongbox.nfo
  (:refer-clojure :rename {derive clj-derive})
  (:require
   [strongbox
    [specs :as sp]
    [utils :as utils :refer [to-json join]]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]))

(comment
  "a '.strongbox.json' (nfo) file is written when an addon is installed or updated. 
  It serves to fill in any blank spots in our knowledge of the addon.
  The file can be safely deleted but some addons may fail to find a match 
  in the catalogue and will need to be found and re-installed.")

(def nfo-filename ".strongbox.json")

(def ignorable-dir-set #{".git" ".hg" ".svn"})

(defn-spec version-controlled? boolean?
  "returns `true` if addon looks like it's under version control"
  [path ::sp/extant-dir]
  (let [sub-dirs (->> path fs/list-dir (filter fs/directory?) (map fs/base-name) (mapv str))]
    (not (nil? (some ignorable-dir-set sub-dirs)))))

(defn-spec prune :addon/nfo
  "prevents writing unwanted data to nfo files by removing keys not present in the `addon/nfo` spec."
  [addon :addon/nfo]
  (if (sequential? addon)
    (mapv prune addon)
    (select-keys addon (sp/spec-to-kw-list :addon/-nfo))))

(defn-spec derive :addon/nfo
  "extract fields from the addon data that will be written to the nfo file"
  [addon :addon/nfo-input-minimum, primary? boolean?]
  (let [nfo {;; important! as an addon is updated or installed, the `:installed-version` from the .toc file is overridden by the `:version` online
             ;; later, when comparing installed addons against the catalogue, the comparisons will be more consistent
             :installed-version (:version addon)

             ;; knowing the regime the addon was installed under allows us to export and later re-import the correct version
             :installed-game-track (:game-track addon)

             ;; normalised name. once used to match to online addon, we now use source+source-id
             :name (:name addon)

             ;; groups all of an addon's directories together.
             :group-id (:url addon)

             ;; if addon is one of multiple addons, is this addon considered the 'primary' one?
             :primary? primary?

             ;; where the addon came from and how they identified it
             :source (:source addon)
             :source-id (:source-id addon)}

        ;; users can set this in the nfo file manually or
        ;; it can be drived later in the process by examining the addon's toc file or subdirs, or
        ;; it may be present when upgrading an existing nfo file and should be preserved
        ignore-flag (when-some [ignore? (:ignore? addon)]
                      {:ignore? ignore?})

        pinned-version (when-some [pinned-version (:pinned-version addon)]
                         {:pinned-version pinned-version})]

    (merge nfo ignore-flag pinned-version)))

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (join install-dir dirname nfo-filename)) ;; /path/to/addons/AddonName/.strongbox.json

(defn-spec rm-nfo-file nil?
  "deletes a nfo file and *only* a nfo file"
  [path ::sp/extant-file]
  (when (= nfo-filename (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo-file* (s/or :ok ::sp/map-or-list-of-maps, :error nil?)
  "safely reads a nfo file with basic transformations.
  old nfo data had no spec and it's shape changed several times.
  this function returns whatever we can find or nil.
  it won't destroy bad/invalid data like `read-nfo-file` will."
  [path ::sp/file & [opts] (s/* map?)]
  (let [default-opts {:no-file? nil
                      :bad-data? nil
                      :key-fn keyword
                      :transform-map {:installed-game-track keyword}}]
    (utils/load-json-file-safely path (merge default-opts opts))))

(defn-spec read-nfo-file (s/or :ok :addon/nfo, :error nil?)
  "reads the nfo file with basic transformations.
  failure to load the json results in the file being deleted.
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn []
                   (warn "bad nfo data, deleting file:" path)
                   (rm-nfo-file path))
        invalid-data (fn []
                       (warn "invalid nfo data, deleting file:" path)
                       (rm-nfo-file path))
        opts {:bad-data? bad-data
              :invalid-data? invalid-data,
              :data-spec :addon/nfo}]
    (read-nfo-file* path opts)))

(defn-spec mutual-dependency? boolean?
  "returns `true` if multiple sets of nfo data exist in file"
  ([nfo-data (s/nilable ::sp/map-or-list-of-maps)]
   (vector? nfo-data))
  ([install-dir ::sp/extant-dir, addon-dirname ::sp/dirname]
   (mutual-dependency? (read-nfo-file install-dir addon-dirname))))

(defn-spec read-nfo (s/or :ok :addon/nfo, :error nil?)
  "parses the contents of the .nfo file and checks if addon should be ignored or not"
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (let [nfo-file-contents (read-nfo-file install-dir dirname)
        nfo-file-contents (if (mutual-dependency? nfo-file-contents)
                            (last nfo-file-contents)
                            nfo-file-contents)
        ;; if `ignore?` is present in the nfo file it overrides the nfo and toc file checks.
        ;; this value may also be introduced in `toc.clj`
        user-ignored (contains? nfo-file-contents :ignore?)
        _ (when (and user-ignored
                     (:ignore? nfo-file-contents))
            (warn (format "ignoring '%s'" dirname)))

        ignore-flag (when (and (not user-ignored)
                               (version-controlled? (join install-dir dirname)))
                      (warn (format "ignoring '%s': addon directory contains a .git/.hg/.svn folder" dirname))
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
                         (warn (format "addon '%s' is overwriting '%s'" (:name new-nfo-data) (:name (last nfo-data-list)))))
                       nfo-data-list)]
    (-> (read-nfo-file install-dir addon-dirname)
        (rm-nfo* (:group-id new-nfo-data))
        user-warning
        (conj new-nfo-data)
        flatten-nfo)))

;;

(defn-spec write-nfo (s/or :ok ::sp/extant-file, :error nil?)
  "given an installation directory and an addon, select the neccessary bits (`prune`) and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, addon ::sp/map-or-list-of-maps]
  (let [path (nfo-path install-dir addon-dirname)]
    (if-not (s/valid? :addon/nfo addon)
      (error "new nfo data is invalid and won't be written to file")
      (utils/dump-json-file path (prune addon)))))

;; this function could definitely do with a second pass, but not right now.
;; it's doing two things: updating the nfo and conditionally writing/removing a file
;; and the update logic is tied to the removal logic
(defn-spec update-nfo nil?
  "updates *existing* nfo data with new values."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname, updates map?]
  (let [path (nfo-path install-dir dirname)
        nfo (read-nfo install-dir dirname)
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
      (write-nfo install-dir dirname new-nfo)))
  nil)

;;

(defn-spec ignore nil?
  "prevent any changes made by strongbox to this addon. 
  explicitly ignores this addon by setting the `ignore?` flag to `true`."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? true}))

(defn-spec stop-ignoring nil?
  "sets the `ignore?` flag to `false`, which is an explicit 'do not ignore'.
  used for implicitly ignored addons."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? false}))

(defn-spec clear-ignore nil?
  "removes the `ignore?` flag on an addon.
  the addon may still be implicitly ignored afterwards."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? nil}))

;;

(defn-spec pin nil?
  "'pins' the given `version` of a specific addon"
  [install-dir ::sp/extant-dir, dirname ::sp/dirname, version :addon/pinned-version]
  (update-nfo install-dir dirname {:pinned-version version}))

(defn-spec unpin nil?
  "removes `:pinned-version` from a specific addon's nfo file, if it exists"
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:pinned-version nil}))
