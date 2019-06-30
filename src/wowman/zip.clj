(ns wowman.zip
  (:require
   [taoensso.timbre :refer [debug info warn error spy]]
   ;;[me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
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
    (catch java.util.zip.ZipException _
      false)))

(defn-spec unzip-file (s/or :ok ::sp/extant-dir, :failed nil?)
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]
  (debug (format "unzipping %s to %s" zipfile-path output-dir-path))
  (try
    (zip/unzip zipfile-path output-dir-path)
    output-dir-path
    (catch java.util.zip.ZipException e
      (error (format "failed to unzip '%s': %s" zipfile-path (.getMessage e)))
      nil)))

(defn zipfile-entries
  [zipfile-path]
  (with-open [zipfile (java.util.zip.ZipFile. zipfile-path)]
    (let [mkrow (fn [zipentry]
                  {:dir? (.isDirectory zipentry)
                   :path (.getName zipentry)})]
      (mapv mkrow (enumeration-seq (.entries zipfile))))))

(defn zipfile-toplevel-entries
  "a list of paths in the top-level of the zipfile"
  [zipfile-path]
  (let [entries (zipfile-entries zipfile-path)
        fake-row (fn [ziprow]
                   (let [bits (clojure.string/split (:path ziprow) #"/")
                         toplevel? (= (count bits) 1)]
                     (if-not toplevel?
                       {:dir? true, :path (str (first bits) "/")})))

        ;; mostly duplicates, but we have to visit each row
        fake-rows (map fake-row entries)

        ;; single list of unique entries
        padded-rows (remove nil? (distinct (into entries fake-rows)))

        ;; urgh, repeated code :(
        tl? #(-> % :path (clojure.string/split #"/") count (= 1))]
    (filterv tl? padded-rows)))

(st/instrument)
