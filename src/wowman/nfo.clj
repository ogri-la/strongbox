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
  "a '.wowman.json' file is written when an addon is installed or updated. 
  It serves to fill in any blank spots in our knowledge of the addon.
  The file can be safely deleted but certain installed addons may fail 
  to find a match online again and will need to be found and re-installed.")

(defn-spec derive ::sp/nfo
  [addon ::sp/addon-or-toc-addon, primary? boolean?]
  {;; important! as an addon is (re)installed the addon :installed-version scraped from the toc file is replaced with the :version from the catalog
   ;; later, when comparing installed addons against the catalog, the comparisons will be more consistent
   :installed-version (:version addon)
   :name (:name addon) ;; normalised name. once used to match to online addon, we now use source+source-id
   :group-id (:uri addon) ;; groups all of an addon's directories together. this is a verbose but natural way of specifying source+source-id
   :primary? primary? ;; if addon is one of multiple addons, is this addon considered the primary one?
   :source (:source addon)
   :source-id (:source-id addon)})

(defn-spec nfo-path ::sp/file
  "given an installation directory and the directory name of an addon, return the absolute path to the nfo file"
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname ".wowman.json")) ;; /path/to/Addons/AddonName/.wowman.json

(defn-spec write-nfo ::sp/extant-file
  "given an installation directory and an addon, extract the neccessary bits and write them to a nfo file"
  [install-dir ::sp/extant-dir, addon ::sp/addon-or-toc-addon, addon-dirname string?, primary? boolean?]
  (let [path (nfo-path install-dir addon-dirname)]
    (utils/dump-json-file path (derive addon primary?))
    path))

(defn-spec rm-nfo nil?
  [path ::sp/extant-file]
  (when (= ".wowman.json" (fs/base-name path))
    (fs/delete path)
    nil))

(defn-spec read-nfo (s/or :ok ::sp/nfo, :error nil?)
  "reads the .wowman.json file. 
  failure to load the json results in the file being deleted. 
  failure to validate the json data results in the file being deleted."
  [install-dir ::sp/extant-dir, dirname string?]
  (let [path (nfo-path install-dir dirname)
        bad-data (fn [] (warn "bad data, deleting file:" path) (rm-nfo path))
        invalid-data (fn [] (warn "invalid data, deleting file:" path) (rm-nfo path))]
    (utils/load-json-file-safely path :bad-data? bad-data, :invalid-data? invalid-data, :data-spec ::sp/nfo)))
