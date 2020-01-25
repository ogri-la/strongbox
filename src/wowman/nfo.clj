(ns wowman.nfo
  (:refer-clojure :rename {derive clj-derive})
  (:require
   [wowman
    [specs :as sp]
    [utils :as utils :refer [to-int to-json fmap join]]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]))

(comment
  "a '.wowman.json' (nfo) file is written when an addon is installed or updated. 
  It serves to fill in any blank spots in our knowledge of the addon.
  The file can be safely deleted but some addons may fail to find a match 
  in the catalogue and will need to be found and re-installed.")

(def nfo-filename ".wowman.json")

(def ignorable-dir-set #{".git" ".hg" ".svn"})

(defn-spec ignore? boolean?
  "returns true if addon looks like it's under version control"
  [path ::sp/extant-dir]
  (let [sub-dirs (->> path fs/list-dir (filter fs/directory?) (map fs/base-name) (mapv str))]
    (not (nil? (some ignorable-dir-set sub-dirs)))))

(defn-spec derive ::sp/nfo-v2
  "extract fields from the addon data that will be written to the nfo file"
  [addon ::sp/addon-or-toc-addon, primary? boolean?, game-track ::sp/game-track]
  {;; important! as an addon is updated or installed, the `:installed-version` from the .toc file is overridden by the `:version` online
   ;; later, when comparing installed addons against the catalogue, the comparisons will be more consistent
   :installed-version (:version addon)

   ;; knowing the game track at time of installation lets us include that in any exports and later re-import the correct version 
   :installed-game-track game-track

   ;; normalised name. once used to match to online addon, we now use source+source-id
   :name (:name addon)

   ;; groups all of an addon's directories together. this is a verbose but natural way of specifying source+source-id
   :group-id (:uri addon)

   ;; if addon is one of multiple addons, is this addon considered the 'primary' one?
   :primary? primary?

   ;; where the addon came from and how they identified it
   :source (:source addon)
   :source-id (:source-id addon)})

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname nfo-filename)) ;; /path/to/addons/AddonName/.wowman.json

(defn-spec write-nfo ::sp/extant-file
  "given an installation directory and an addon, extract the neccessary bits and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon ::sp/addon-or-toc-addon, addon-dirname string?, primary? boolean?, game-track ::sp/game-track]
  (let [path (nfo-path install-dir addon-dirname)]
    (utils/dump-json-file path (derive addon primary? game-track))
    path))

(defn-spec rm-nfo nil?
  "deletes a nfo file and only a nfo file"
  [path ::sp/extant-file]
  (when (= nfo-filename (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo-file (s/or :ok-v1 ::sp/nfo, :ok-v2 ::sp/nfo-v2, :error nil?)
  "reads the nfo file.
  failure to load the json results in the file being deleted.
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, dirname string?]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn [] (warn "bad data, deleting file:" path) (rm-nfo path))
        invalid-data (fn [] (warn "invalid data, deleting file:" path) (rm-nfo path))]
    (utils/load-json-file-safely path
                                 :no-file? nil
                                 :bad-data? bad-data
                                 :invalid-data? invalid-data,
                                 :data-spec ::sp/nfo)))

(defn-spec read-nfo (s/or :ok-v1 ::sp/nfo, :ok-v2 ::sp/nfo-v2, :error nil?)
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
