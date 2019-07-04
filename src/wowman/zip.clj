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

(st/instrument)
