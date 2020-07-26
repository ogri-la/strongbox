(ns strongbox.nfo
  (:refer-clojure :rename {derive clj-derive})
  (:require
   [strongbox
    [specs :as sp]
    [utils :as utils :refer [to-int to-json fmap join]]]
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

(defn-spec ignore? boolean?
  "returns true if addon looks like it's under version control"
  [path ::sp/extant-dir]
  (let [sub-dirs (->> path fs/list-dir (filter fs/directory?) (map fs/base-name) (mapv str))]
    (not (nil? (some ignorable-dir-set sub-dirs)))))

(defn-spec prune :addon/nfo
  [addon :addon/nfo]
  (select-keys addon [:installed-version :installed-game-track :name :group-id :primary? :ignore? :source :source-id :replaced]))

(defn-spec derive :addon/nfo
  "extract fields from the addon data that will be written to the nfo file"
  [addon :addon/nfo-input-minimum, primary? boolean?, game-track ::sp/game-track]
  (let [nfo {;; important! as an addon is updated or installed, the `:installed-version` from the .toc file is overridden by the `:version` online
             ;; later, when comparing installed addons against the catalogue, the comparisons will be more consistent
             :installed-version (:version addon)

             ;; knowing the regime the addon was installed under allows us to export and later re-import the correct version
             :installed-game-track game-track

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

        ;; if present in the addon data, ensure it gets preserved
        replaced-list (when-some [replaced (:replaced addon)]
                        {:replaced replaced})]
    (merge nfo ignore-flag replaced-list)))

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname nfo-filename)) ;; /path/to/addons/AddonName/.strongbox.json

(defn-spec rm-nfo nil?
  "deletes a nfo file and only a nfo file"
  [path ::sp/extant-file]
  (when (= nfo-filename (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo-file* (s/or :ok map?, :error nil?)
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
  [install-dir ::sp/extant-dir, dirname string?]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn []
                   (warn "bad nfo data, deleting file:" path)
                   (rm-nfo path))
        invalid-data (fn []
                       (info "slurp" path (slurp path))
                       (warn "invalid nfo data, deleting file:" path)
                       (rm-nfo path))
        opts {:bad-data? bad-data
              :invalid-data? invalid-data,
              :data-spec :addon/nfo}]
    (read-nfo-file* path opts)))

(defn-spec read-nfo (s/or :ok :addon/nfo, :error nil?)
  "parses the contents of the .nfo file and checks if addon should be ignored or not"
  [install-dir ::sp/extant-dir, dirname string?]
  (let [nfo-file-contents (read-nfo-file install-dir dirname)
        ;; if `ignore?` is present in the nfo file it overrides the nfo and toc file checks.
        ;; this value may also be introduced in `toc.clj`
        user-ignored (contains? nfo-file-contents :ignore?)
        _ (when (and user-ignored
                     (:ignore? nfo-file-contents))
            (warn (format "ignoring '%s'" dirname)))

        ignore-flag (when (and (not user-ignored)
                               (ignore? (join install-dir dirname)))
                      (warn (format "ignoring '%s': addon directory contains a .git/.hg/.svn folder" dirname))
                      {:ignore? true})]
    (merge nfo-file-contents ignore-flag)))

;;

(defn-spec push-nfo :addon/nfo
  "given a map of new nfo data, replace existing nfo data (if it exists) while preserving it for later operations."
  [new-nfo-data :addon/nfo, old-nfo-data (s/nilable :addon/nfo)]
  ;; if no previous nfo data or the previous nfo data belongs to the same addon as the one given, skip.
  (if (or (not old-nfo-data)
          (= (:group-id old-nfo-data) (:group-id new-nfo-data)))
    new-nfo-data

    (let [;; we may be replacing nfo data that replaced something else.
          replaced-list (or (:replaced old-nfo-data) [])
          old-nfo-data (dissoc old-nfo-data :replaced)

          ;; tack the old nfo data on to the list of replaced
          replaced-list (conj replaced-list old-nfo-data)

          ;; addon the new nfo data refers to may exist in the list of replaced.
          ;; these should be removed now that it's back on top again.
          replaced-list (remove (fn [replaced]
                                  (= (:group-id replaced) (:group-id new-nfo-data))) replaced-list)

          new-nfo-data (if-not (empty? replaced-list)
                         (assoc new-nfo-data :replaced (vec replaced-list))
                         new-nfo-data)]
      new-nfo-data)))

;; (defn pop-nfo ...

;;

(defn-spec write-nfo (s/or :ok ::sp/extant-file, :error nil?)
  "given an installation directory and an addon, select the neccessary bits (`prune`) and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, addon map?] ;; addon data is validated before being written
  (let [path (nfo-path install-dir addon-dirname)]
    (if (s/valid? :addon/nfo addon)
      (let [new-nfo-data (prune addon)
            old-nfo-data (read-nfo-file install-dir addon-dirname)]
        (utils/dump-json-file path (push-nfo new-nfo-data old-nfo-data)))
      (error "new nfo data is invalid and won't be written to file"))))

(defn-spec derive+write-nfo (s/or :ok ::sp/extant-file, :error nil?)
  "convenience. generates nfo data from given `addon` and then writes it to a file."
  [install-dir ::sp/extant-dir, addon-dirname ::sp/dirname, addon :addon/nfo-input-minimum, primary? boolean?, game-track ::sp/game-track]
  (write-nfo install-dir addon-dirname (derive addon primary? game-track)))

;; this function could definitely do with a second pass, but not right now.
;; it's doing two things: updating the nfo and writing/removing a file, but conditionally,
;; and the update logic is tied to the removal logic
(defn-spec update-nfo nil?
  "updates *existing* nfo data with new values.
  differs from `upgrade-nfo` in that existing nfo data is not being manipulated to make 
  it valid for the current nfo spec."
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

        new-nfo (if (nil? (:ignore? new-nfo))
                  (dissoc new-nfo :ignore?)
                  new-nfo)]
    (if (empty? new-nfo)
      ;; edge case. a valid nfo file is also simply a `ignore: [True|False]`
      ;; when this condition is true, just delete the nfo file.
      (rm-nfo path)
      (write-nfo install-dir dirname new-nfo)))
  nil)

(defn-spec ignore nil?
  "Prevent any changes made by strongbox to this addon. 
  Explicitly ignores this addon by setting the `ignore?` flag to `true`."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? true}))

(defn-spec clear-ignore nil?
  "removes the `ignore?` flag on an addon.
  The addon may still be implicitly ignored afterwards."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? nil}))
