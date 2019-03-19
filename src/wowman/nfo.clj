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
  {:installed-version (:version addon)
   :name (:name addon) ;; normalised name, used to match to online addon
   :group-id (:name addon) ;; groups all of an addon's directories together
   :primary? primary? ;; if addon is one of multiple addons, is this addon considered the primary one?
   })

(defn-spec nfo-path ::sp/file
  [install-dir ::sp/extant-dir, dirname string?]
  (join install-dir dirname ".wowman.json")) ;; /path/to/Addons/AddonName/.wowman.json

(defn-spec write-nfo ::sp/extant-file
  [install-dir ::sp/extant-dir, addon ::sp/addon-or-toc-addon, addon-dirname string?, primary? boolean?]
  (let [path (nfo-path install-dir addon-dirname)]
    (utils/dump-json-file path (derive addon primary?))
    path))

(defn-spec read-nfo (s/or :ok ::sp/nfo, :error nil?)
  [install-dir ::sp/extant-dir, dirname string?]
  (try
    (let [path (nfo-path install-dir dirname)]
      (when (fs/exists? path)
        (let [nfo (utils/load-json-file path)]
          (if (s/valid? ::sp/nfo nfo)
            nfo
            (error (format "invalid .wowman.json file, skipping: %s" path))))))
    (catch Exception e
      (error "unhandled exception attempting to read .wowman.json file: " (str e)))))
