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

(def old-nfo-filename ".wowman.json")

(def nfo-filename ".strongbox.json")

(def ignorable-dir-set #{".git" ".hg" ".svn"})

(defn-spec ignore? boolean?
  "returns true if addon looks like it's under version control"
  [path ::sp/extant-dir]
  (let [sub-dirs (->> path fs/list-dir (filter fs/directory?) (map fs/base-name) (mapv str))]
    (not (nil? (some ignorable-dir-set sub-dirs)))))

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
                      {:ignore? ignore?})]
    (merge nfo ignore-flag)))

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname nfo-filename)) ;; /path/to/addons/AddonName/.strongbox.json

(defn-spec write-nfo (s/or :ok ::sp/extant-file, :error nil?)
  "given an installation directory and an addon, extract the neccessary bits and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon :addon/nfo-input-minimum, addon-dirname string?, primary? boolean?, game-track ::sp/game-track]
  (let [path (nfo-path install-dir addon-dirname)]
    (utils/dump-json-file path (derive addon primary? game-track))
    path))

(defn-spec upgrade-nfo-to-v2 (s/or :ok ::sp/extant-file, :invalid-updates nil?)
  "upgrades the nfo data from v1 nfo ('anything') to v2, if possible. 
  requires an addon directory and an addon version as an absolute minimum.
  returns nil if insufficient/invalid data."
  [install-dir ::sp/extant-dir, addon :addon/nfo-input-minimum]
  (debug "upgrading nfo file:" (nfo-path install-dir (:dirname addon)))
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

(defn-spec read-nfo-file (s/or :ok :addon/nfo, :error nil?)
  "reads the nfo file.
  failure to load the json results in the file being deleted.
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, dirname string?]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn []
                   (warn "bad nfo data, deleting file:" path)
                   (rm-nfo path))
        invalid-data (fn []
                       (warn "invalid nfo data, deleting file:" path)
                       (rm-nfo path))]
    (utils/load-json-file-safely
     path
     {:no-file? nil
      :bad-data? bad-data
      :invalid-data? invalid-data,
      :data-spec :addon/nfo
      :key-fn keyword
      :transform-map {:installed-game-track keyword}})))

(defn-spec read-nfo (s/or :ok :addon/nfo, :error nil?)
  "reads and parses the contents of the .nfo file and checks if addon should be ignored or not"
  [install-dir ::sp/extant-dir, dirname string?]
  (let [nfo-file-contents (read-nfo-file install-dir dirname)
        ;; `ignore?` is never written to file, although the user can put it there manually if they like.
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

(defn-spec has-nfo-file? boolean?
  "returns true if a nfo (.strongbox.json) file exists in addon directory"
  [install-dir ::sp/extant-dir, addon any?]
  (if-let [dirname (:dirname addon)]
    (fs/exists? (nfo-path install-dir dirname))
    false))

(defn-spec has-valid-nfo-file? boolean?
  "returns true if a nfo file exists and contains valid `:addon/nfo` data"
  [install-dir ::sp/extant-dir, addon any?]
  (and (has-nfo-file? install-dir addon)
       ;; don't use read-nfo-file here, it deletes invalid nfo files
       (s/valid? :addon/nfo (utils/load-json-file-safely (nfo-path install-dir (:dirname addon))
                                                         {:transform-map {:installed-game-track keyword}}))))

(defn-spec update-nfo nil?
  "updates *existing* nfo data with new values.
  content is only written if valid."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname, updates map?]
  (let [path (nfo-path install-dir dirname)
        nfo (read-nfo install-dir dirname)
        new-nfo (merge nfo updates)
        ;; convenience for implicitly ignored addons.
        ;; if the nfo file was missing and the new request is to remove the flag, set it to false instead
        new-nfo (if (and (not (fs/exists? path))
                         (= {:ignore? nil} new-nfo))
                  {:ignore? false}
                  new-nfo)
        ]
    (if (= {:ignore? nil} new-nfo)
      ;; edge case. a valid nfo file is also simply a `ignore: True`
      ;; when this is true, just delete the nfo file
      (rm-nfo path)
      (when (s/valid? :addon/nfo new-nfo)
        (utils/dump-json-file path new-nfo))))
  nil)

(defn-spec ignore nil?
  "explicitly ignores this addon by setting the `ignore?` flag to `true`.
  Prevents any changes made by strongbox to that addon."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? true})
  nil)

(defn-spec clear-ignore nil?
  "removes the `ignore?` flag on an addon. This is what you want most of the time.
  The addon may still be implicitly ignored afterwards."
  [install-dir ::sp/extant-dir, dirname ::sp/dirname]
  (update-nfo install-dir dirname {:ignore? nil})
  nil)

;;

(defn-spec copy-wowman-nfo-files nil?
  "makes a copy of any `.wowman.json` nfo files to `.strongbox.json` ones.
  user can delete `.wowman.json` files through the gui afterwards."
  [install-dir ::sp/extant-dir]
  (let [file-regex (re-pattern old-nfo-filename)
        path-list (mapv str (fs/find-files install-dir file-regex))
        copy (fn [old-path]
               (let [new-path (join (fs/parent old-path) nfo-filename)]
                 (when-not (fs/exists? new-path)
                   (fs/copy old-path new-path))))]
    (run! copy path-list)))
