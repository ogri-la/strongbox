(ns strongbox.zip
  (:require
   [taoensso.timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [clojure.set]
   [clojure.string :refer [split ends-with?]]
   [strongbox
    [utils :as utils]
    [specs :as sp]]))

(defn-spec valid-zip-file? boolean?
  "returns true if there are no apparent problems opening+reading+closing the given zip file."
  [zipfile-path ::sp/extant-archive-file]
  (try
    (with-open [zipfile (java.util.zip.ZipFile. zipfile-path)]
      (vec (enumeration-seq (.entries zipfile))))
    true
    (catch java.util.zip.ZipException ze
      (debug "failed to open+close zip file:" zipfile-path)
      false)
    (catch java.lang.IllegalArgumentException ze
      ;; encountered 2019-07-02 attempting to read "2 UI" (no test for this case yet)
      ;; -- https://www.wowinterface.com/downloads/info23799
      ;; Zip 3.0 (2008) doesn't complain, doesn't find anything wrong
      ;; engrampa can read the contents but fails integrity check, complaining:
      ;;   ERROR: Headers Error : Interface/Addons/_ShiGuang/Modules/Nameplate/Nameplate - 副本.lua
      ;;   ERROR: Headers Error : Interface/Addons/_ShiGuang/Modules/Nameplate/Nameplate哎.lua
      (debug "failed to open/read/close zip file (IllegalArgumentException):" zipfile-path)
      false)))

(defn-spec unzip-file (s/or :ok ::sp/extant-dir, :failed nil?)
  "unzips the zip file at the `zipfile-path` to the directory `output-dir-path`."
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]
  (debug (format "unzipping %s to %s" zipfile-path output-dir-path))
  (try
    (zip/unzip zipfile-path output-dir-path)
    output-dir-path
    (catch java.util.zip.ZipException ze
      (error (format "failed to unzip '%s': %s" zipfile-path (.getMessage ze))))))

(defn zipfile-normal-entries
  "not all zip files are created equally. some have explicit entries for directories within them, some do not.
  this function returns all entries within the given zipfile, plus dummy entries for any missing directories"
  [zipfile-path]
  (let [mkrow (fn [zipentry]
                (let [path (.getName zipentry)
                      dir? (.isDirectory zipentry)
                      bits (split path #"/")
                      level (count bits)]
                  {:dir? dir?
                   :level level
                   :toplevel? (= level 1) ;; exists at the very top of the file hierarchy
                   :path path}))

        fake-rows (fn [row]
                    (let [parents (->> row :path (str "/") fs/parents (map str) butlast)]
                      (for [p parents :let [pp (-> p (subs 1) (str "/")) ;; /foo/bar => foo/bar/
                                            bits (split pp #"/") ;; ["foo" "bar"]
                                            level (count bits)]]
                        {:dir? true
                         ;; fs/parents strips trailing slashes, java ZipEntry objects preserve them
                         ;; subs strips leading slash introduced when calling fs/parents
                         :level level
                         :toplevel? (= level 1)
                         :path pp})))]
    (with-open [zipfile (java.util.zip.ZipFile. zipfile-path)]
      (let [rows (mapv mkrow (enumeration-seq (.entries zipfile)))]
        (->> rows (map fake-rows) flatten (into rows) distinct)))))

;;

(defn-spec prefix-groups sequential?
  "groups top-level directories by their :path prefix, sorts by size of group (largest to smallest)"
  [zipfile-entries :zipfile/entry-list]
  (let [desc #(compare %2 %1)
        group (fn [x]
                (group-by #(utils/safe-subs (:path %) 3) x))] ;; first three characters
    ;; to be called *after* validity check so there should be no toplevel files. doesn't hurt to exclude them though
    (->> zipfile-entries (filter :toplevel?) (filter :dir?) group vals (sort-by count desc))))

(defn-spec inconsistently-prefixed (s/or :ok nil?, :inconsistencies sequential?)
  "returns a list of inconsistently prefixed top-level directories sorted by most to least common (with the most common excluded).
  nil if no inconsistencies found.
  Call *after* the validity checks on the zip file and addon. 
  Ensure the zipfile-entry list has been normalised"
  [zipfile-entries :zipfile/entry-list]
  (let [grouped-entries (prefix-groups zipfile-entries) ;; [[{...}, {...}], [{...}]]
        magnitude 3 ;; ignore if there are no groups smaller than this
        num-groups (count grouped-entries) ;; 3
        num-group-members (mapv count grouped-entries) ;; [2 1]
        strip-suffix #(utils/rtrim % "/")]
    (cond
      ;; single group, ignore
      ;; this condition is actually catered for in the ambiguity checking below ((= 1) => true), but for clarity I'll keep it separate
      (< num-groups 2) nil

      ;; multiple large groups, ignore
      ;; altoholic has two very large groups of addons: DataStore* and Altoholic*
      ;; since `grouped-entries` is sorted, if the smallest group is larger than our threshold, ignore
      (-> num-group-members last (> magnitude)) nil

      ;; ambiguous case, multiple groups with no largest group. ignore.
      ;; each of the groups has the same number of members like [1 1] or [2 2 2]
      (apply = num-group-members) nil

      ;; multiple groups with at least one group below the cutoff
      ;; in this case, anything that doesn't share the most common prefix is considered suspicious
      ;; this is not perfect! there will be outliers!
      :else (->> grouped-entries rest flatten (map :path) (map strip-suffix) vec))))

;;
;;
;;

(defn-spec top-level-directories :zipfile/entry-list
  "returns a list of all zipfile entries that are directories and exist at the top level"
  [zipfile-entries :zipfile/entry-list]
  (filter (every-pred :dir? :toplevel?) zipfile-entries))

(defn-spec top-level-files :zipfile/entry-list
  "returns a list of all zipfile entries that are files and exist at the top level"
  [zipfile-entries :zipfile/entry-list]
  (->> zipfile-entries (filter :toplevel?) (remove :dir?)))

(defn-spec -top-level-files? boolean?
  "returns true if there are top-level files"
  [zipfile-entries :zipfile/entry-list]
  (-> zipfile-entries top-level-files count (> 0)))

(defn -top-level-non-addon-dirs?
  "returns true if there are top-level directories missing a toc file"
  [zipfile-entries]
  (let [;; what is happening here?
        ;; we create a set of all top-level directories, and a set of the parents of second-level toc files
        ;; if there are any directories left after we diff them
        toplevel-dirs (->> zipfile-entries top-level-directories (map :path) set)
        toplevel-tocfiles (filter #(and (-> % :level (= 2))
                                        (-> % :path (ends-with? ".toc"))) zipfile-entries)
        toplevel-tocfile-dirs (set (map #(-> % :path (split #"/") first (str "/")) toplevel-tocfiles))
        diff (clojure.set/difference toplevel-dirs toplevel-tocfile-dirs)]
    (not (empty? diff))))

(defn-spec valid-addon-zip-file? boolean?
  "returns true if there are no apparent problems reading the given zip file AND the addon isn't smelly"
  [zipfile-path ::sp/extant-archive-file]
  (if (valid-zip-file? zipfile-path)
    (let [entries (zipfile-normal-entries zipfile-path)]
      ;; if either check fails, return false
      ;; if neither check fails, return true
      (not (or (-top-level-files? entries)
               (-top-level-non-addon-dirs? entries))))
    ;; couldn't get past the bad zip file
    false))
