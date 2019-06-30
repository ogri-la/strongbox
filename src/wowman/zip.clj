(ns wowman.zip
  (:require
   [taoensso.timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [clojure.string :refer [split]]
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
                        bits (split path #"/")]
                    {:dir? dir?
                     :toplevel? (-> bits count (= 1)) ;; exists at the very top of the file hierarchy
                     :path path}))

          fake-rows (fn [row]
                      (let [parents (map str (fs/parents (str "/" (:path row))))
                            parents (butlast parents)] ;; exclude final "/"
                        (for [p parents :let [pp (str (subs p 1) "/")]] ;; pp => foo/bar/
                          {:dir? true
                           ;; fs/parents strips trailing slashes, java ZipEntry objects preserve them
                           ;; subs strips leading slash introduced when calling fs/parents
                           :toplevel? (->> pp (re-seq #"/") count (= 1))
                           :path pp})))

          rows (mapv mkrow (enumeration-seq (.entries zipfile)))]

      (->> rows (map fake-rows) flatten (into rows) distinct))))

(defn-spec unzip-addon  (s/or :ok ::sp/extant-dir, :failed nil?)
  "wrapper around `unzip-file` that does additional addon-level checks against the content of the zipfile.
  if the addon zip file is bad, it will refuse to unzip the contents."
  ;; todo: it should be possible to have a more lenient strategy as well, simply excluding any bad files/folders.
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]

  ;; test addon file:
  ;; - every top-level directory must have a something.toc file in it
  ;; - no top-level files should exist

  (unzip-file zipfile-path output-dir-path))

(st/instrument)
