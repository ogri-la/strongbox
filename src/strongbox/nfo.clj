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

(defn-spec derive ::sp/nfo-v2
  "extract fields from the addon data that will be written to the nfo file"
  [addon ::sp/nfo-input-minimum, primary? boolean?, game-track ::sp/game-track]
  (let [nfo {;; important! as an addon is updated or installed, the `:installed-version` from the .toc file is overridden by the `:version` online
             ;; later, when comparing installed addons against the catalogue, the comparisons will be more consistent
             :installed-version (:version addon)

             ;; knowing the regime the addon was installed under allows us to export and later re-import the correct version
             :installed-game-track game-track

             ;; normalised name. once used to match to online addon, we now use source+source-id
             :name (:name addon)

             ;; groups all of an addon's directories together.
             :group-id (:uri addon)

             ;; if addon is one of multiple addons, is this addon considered the 'primary' one?
             :primary? primary?

             ;; where the addon came from and how they identified it
             :source (:source addon)
             :source-id (:source-id addon)}

        ;; users can set this in the nfo file manually or
        ;; it can be drived later in the process by examining the addon's toc file or subdirs, or
        ;; it may be present when upgrading an existing nfo file and should be preserved
        ignore-flag (when-some [ignore? (:ignore? addon)]
                      {:ignore? ignore?})]
    (merge nfo ignore-flag)))

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname nfo-filename)) ;; /path/to/addons/AddonName/.strongbox.json

(defn-spec write-nfo ::sp/extant-file
  "given an installation directory and an addon, extract the neccessary bits and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon ::sp/nfo-input-minimum, addon-dirname string?, primary? boolean?, game-track ::sp/game-track]
  (let [path (nfo-path install-dir addon-dirname)]
    (utils/dump-json-file path (derive addon primary? game-track))
    path))

(defn-spec upgrade-nfo ::sp/extant-file
  "refreshes the nfo data for the given addon. does NOT bump installed version"
  [install-dir ::sp/extant-dir, addon ::sp/nfo-input-minimum]
  (let [;; important! as an addon is updated or installed, the `:installed-version` is overridden by the `:version`
        ;; we don't want to alter the version it thinks is installed here
        addon (merge addon {:version (:installed-version addon)})]
    (write-nfo install-dir addon (:dirname addon) (:primary? addon) (:game-track addon))))

(defn-spec rm-nfo nil?
  "deletes a nfo file and only a nfo file"
  [path ::sp/extant-file]
  (when (= nfo-filename (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo-file (s/or :ok ::sp/nfo, :error nil?)
  "reads the nfo file.
  failure to load the json results in the file being deleted.
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, dirname string?]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn [] (warn "bad nfo data, deleting file:" path) (rm-nfo path))
        invalid-data (fn [] (warn "invalid nfo data, deleting file:" path) (rm-nfo path))

        nfo-data (utils/load-json-file-safely
                  path
                  :no-file? nil
                  :bad-data? bad-data
                  :invalid-data? invalid-data,
                  :data-spec ::sp/nfo) ;; either v1 or v2
        ]
    (cond
      ;; failed to read nfo file. it may not exist. if it did exist but was malformed, then
      ;; it was removed and definitely doesn't exist anymore.
      (not nfo-data) nil

      ;; valid v2 nfo data, perfect case
      (s/valid? ::sp/nfo-v2 nfo-data) nfo-data

      ;; data was a map of some sort, but we're not going to dwell on what it's contents were
      ;; TODO: remove this in 0.14.0 and delete invalid nfo-v2 files
      ;; it's reasonable to assume nfo files will consistently have a :game-track by then
      :else nfo-data)))

(defn-spec read-nfo (s/or :ok ::sp/nfo, :error nil?)
  "reads and parses the contents of the .nfo file and checks if addon should be ignored or not"
  [install-dir ::sp/extant-dir, dirname string?]
  (let [nfo-file-contents (read-nfo-file install-dir dirname)
        ;; `ignore?` is never written to file, although the user can put it there manually if they like.
        ;; if `ignore?` is present in the nfo file it overrides the nfo and toc file checks.
        ;; this value may also be introduced in `toc.clj`
        user-ignored (contains? nfo-file-contents :ignore?)
        _ (when (and user-ignored (:ignore? nfo-file-contents))
            (warn (format "addon '%s' is being manually ignored" dirname)))

        ignore-flag (when (and (not user-ignored)
                               (ignore? (join install-dir dirname)))
                      (warn (format "ignoring addon '%s': addon directory contains a SVC sub-directory (.git/.hg/.svn etc)" dirname))
                      {:ignore? true})]
    (merge nfo-file-contents ignore-flag)))

(defn-spec has-nfo-file? boolean?
  "returns true if a nfo (.strongbox.json) file exists in addon directory"
  [install-dir ::sp/extant-dir, addon any?]
  (if-let [dirname (:dirname addon)]
    (fs/exists? (nfo-path install-dir dirname))
    false))

(defn-spec has-valid-nfo-file? boolean?
  "returns true if a nfo file exists and contains valid nfo-v2 data"
  [install-dir ::sp/extant-dir, addon any?]
  (and (has-nfo-file? install-dir addon)
       (s/valid? ::sp/nfo-v2 (read-nfo-file install-dir (:dirname addon)))))
