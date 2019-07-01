(ns wowman.zip
  (:require
   [taoensso.timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [clojure.set]
   [clojure.string :refer [split ends-with?]]
   [wowman
    [specs :as sp]]))

(comment "unused"
         (defn-spec list-files ::sp/list-of-files
           "returns a simple list of files and files in sub-directories rooted at `path`"
           [path ::sp/extant-dir]
           (mapv str (sort (remove fs/directory? (file-seq (java.io.File. path))))))

         (defn-spec zip-directory (s/or :ok ::sp/extant-archive-file :error nil?)
           "zips a directory of files. contents of zip will always live in a single top level directory"
           [in-path ::sp/extant-dir out-path ::sp/file]
           (let [files-to-be-zipped (list-files in-path)]
             (when-not (empty? files-to-be-zipped)
               (zip/zip out-path (mapv #(file-to-lazy-byte-array % in-path) files-to-be-zipped))
               out-path))))

(defn-spec valid-zip-file? boolean?
  "returns true if there are no apparent problems reading the given zip file."
  [zipfile-path ::sp/extant-archive-file]
  (try
    (-> zipfile-path java.util.zip.ZipFile. .close)
    true
    (catch java.util.zip.ZipException ze
      (debug ze "failed to open+close zip file:" zipfile-path)
      false)))

(defn-spec unzip-file (s/or :ok ::sp/extant-dir, :failed nil?)
  "unzips the zip file at the `zipfile-path` to the directory `output-dir-path`."
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]
  (debug (format "unzipping %s to %s" zipfile-path output-dir-path))
  (try
    (zip/unzip zipfile-path output-dir-path)
    output-dir-path
    (catch java.util.zip.ZipException e
      (error (format "failed to unzip '%s': %s" zipfile-path (.getMessage e))))))

;; deprecated in favour of zipfile-normal-entries
(defn zipfile-entries
  [zipfile-path]
  (with-open [zipfile (java.util.zip.ZipFile. zipfile-path)]
    (let [mkrow (fn [zipentry]
                  {:dir? (.isDirectory zipentry)
                   :path (.getName zipentry)})]
      (mapv mkrow (enumeration-seq (.entries zipfile))))))

;; deprecated in favour of zipfile-normal-entries
(defn zipfile-toplevel-entries
  "a list of paths in the top-level of the zipfile"
  [zipfile-path]
  (let [entries (zipfile-entries zipfile-path)
        fake-row (fn [ziprow]
                   (let [bits (split (:path ziprow) #"/")
                         toplevel? (= (count bits) 1)]
                     (if-not toplevel?
                       {:dir? true, :path (str (first bits) "/")})))

        ;; mostly duplicates, but we have to visit each row
        fake-rows (map fake-row entries)

        ;; single list of unique entries
        padded-rows (remove nil? (distinct (into entries fake-rows)))

        ;; urgh, repeated code :(
        tl? #(-> % :path (split #"/") count (= 1))]
    (filterv tl? padded-rows)))

(defn zipfile-normal-entries
  "not all zip files are created equally. some have explicit entries for directories within them, some do not.
  this function returns all entries within the given zipfile, plus dummy entries for any missing directories"
  [zipfile-path]
  (with-open [zipfile (java.util.zip.ZipFile. zipfile-path)]
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
                           :path pp})))

          rows (mapv mkrow (enumeration-seq (.entries zipfile)))]

      (->> rows (map fake-rows) flatten (into rows) distinct))))

;;
;;
;;

(defn -top-level-files?
  "returns true if there are top-level files"
  [zipfile-entries]
  (let [targets (->> zipfile-entries (filter :toplevel?) (remove :dir?))]
    (> (count targets) 0)))

(defn -top-level-non-addon-dirs?
  "returns true if there are top-level directories missing a toc file"
  [zipfile-entries]
  (let [;; what is happening here?
        ;; we create a set of all top-level directories, and a set of the parents of second-level toc files
        ;; if there are any directories left after we diff them
        toplevel-dirs (set (map :path (filter (every-pred :dir? :toplevel?) zipfile-entries)))
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

(defn-spec unzip-addon  (s/or :ok ::sp/extant-dir, :failed nil?)
  "wrapper around `unzip-file` that does additional addon-level checks against the content of the zipfile.
  if the addon zip file is bad, it will refuse to unzip the contents."
  ;; todo: it should be possible to have a more lenient strategy as well, simply excluding any bad files/folders.
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]
  (when (valid-addon-zip-file? zipfile-path)
    (unzip-file zipfile-path output-dir-path)))

(st/instrument)
