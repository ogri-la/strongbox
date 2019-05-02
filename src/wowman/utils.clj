(ns wowman.utils
  (:require
   [wowman.specs :as sp]
   [clojure.string]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [cheshire.core :as json]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [slugify.core :as sluglib]
   [orchestra.core :refer [defn-spec]]
   [clojure.data.codec.base64 :as b64]
   [taoensso.timbre :refer [debug info warn error spy]]
   [trptcolin.versioneer.core :as versioneer]
   [clj-http.conn-mgr]
   [clj-http.client :as client]
   [clj-time
    [coerce :as coerce-time]
    [format :as format-time]]))

(defn items
  [& lst]
  (vec (remove nil? (flatten lst))))

(def not-empty? (comp not empty?))

(defn-spec safe-subs string?
  [x string?, max int?]
  (subs x 0 (min (count x) max)))

(defn in?
  [vals]
  (fn [v]
    (some #{v} vals)))

(defn-spec idx map?
  [list-of-maps ::sp/list-of-maps, key keyword?]
  (into {} (map (fn [row] {(get row key) row}) list-of-maps)))

(defn merge-lists
  "given two lists and a key, returns list1 with matching entries from list2 when keys match. unmatched entries in list2 are prepended to list1"
  [key list1 list2 & {:keys [prepend?]}]
  (loop [index (idx list2 key)
         ilist list1
         list3 []]

    (if (empty? ilist)
      ;; we've exhaused list1, return
      (if prepend?
        (into (vec (vals index)) list3)
        (into list3 (vec (vals index))))

      (let [row (first ilist)
            keyed-val (get row key)]

        (if (contains? index keyed-val)
          (recur (dissoc index keyed-val) ;; shrink index as we go along
                 (next ilist)
                 (conj list3 (get index keyed-val)))

          (recur index
                 (next ilist)
                 (conj list3 row)))))))

;; TODO: replace with clj-time equivalent
(defn fmt-date
  ([dateobj]
   (fmt-date dateobj "yyyy-MM-dd'T'HH:mm:ss'Z'"))
  ([dateobj fmt]
   (.format (java.text.SimpleDateFormat. fmt) dateobj)))

(defn-spec from-epoch string?
  [epoch int?]
  (format-time/unparse ;; "unparse" ? what a dumb fucking name
   (format-time/formatters :date-time-no-ms) (coerce-time/from-epoch epoch)))

(defn datestamp-now-ymd
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.)))
;;  (fmt-date (java.util.Date.) "yyyy-MM-dd"))

;; Applies function f to each item in the data structure m
;; https://github.com/clojure/clojure-contrib/blob/b8d2743d3a89e13fc9deb2844ca2167b34aaa9b6/src/main/clojure/clojure/contrib/generic/functor.clj#L34
(defn fmap
  [f m]
  (into (empty m) (for [[k v] m] [k (f k v)])))

(defn filter-map
  [f m]
  (select-keys m (for [[k v] m :when (f k v)] k)))

(defn nil-if-empty
  [s]
  (when s
    (if (empty? (clojure.string/trim s)) nil s)))

(defn-spec to-json ::sp/json
  [x ::sp/anything]
  (json/generate-string x {:pretty true}))

(defn from-json
  [x]
  (clojure.data.json/read-str x :key-fn keyword))

(defn-spec dump-json-file nil?
  [path ::sp/file data ::sp/anything]
  ;; this `{:pretty true}` is the only reason we're keeping cheshire around
  (json/generate-stream data (clojure.java.io/writer path) {:pretty true})
  nil)

(defn-spec load-json-file ::sp/anything
  [path ::sp/extant-file]
  (clojure.data.json/read (clojure.java.io/reader path), :key-fn keyword))

(defn-spec to-int (s/or :ok int? :error nil?)
  [x any?]
  (try (Integer. x)
       (catch NumberFormatException nfe
         nil)))

(defn-spec slugify string?
  [string string?]
  (sluglib/slugify string))

(defn-spec interface-version-to-game-version (s/or :ok string?, :no-match nil?)
  [iface-version string?]
  ;; warning! there is no way to convert *unambiguously* between the 'patch level' and the 'interface version'
  ;; for example, patch "1.2.0" => "10200", but so does "1.20.0" => "10200"
  ;; there haven't been any minor versions >4 since MOP
  ;; we'll hit 10.0 soon enough (we're at 8.x at time of writing) so what then? "10.0.1" => "10000" is another collision
  ;; the below code should only be considered unambigous for versions of WoW between 2.x and 8.x
  ;; (and 9.x if that series follows the behaviour of all other patch levels since 2.x)
  ;; -- https://wow.gamepedia.com/Patches
  (let [iface-regex #"(?<major>\d{1})\d(?<minor>\d{1})\d(?<patch>\d{1}\w?)"
        matcher (re-matcher iface-regex iface-version)
        major-minor-patch (rest (re-find matcher))]
    (when-not (empty? major-minor-patch)
      (clojure.string/join "." major-minor-patch))))

(defn-spec game-version-to-interface-version (s/or :ok ::sp/interface-version :error nil?)
  [game-version string?]
  (let [;; patch-version isn't considered apparently: http://wowwiki.wikia.com/wiki/Getting_the_current_interface_number
        [major minor & _] (clojure.string/split game-version #"\.")
        major (to-int major)
        minor (to-int minor)]
    (when (and major minor)
      (+ (* 10000 major) (* 100 minor)))))

(defn-spec timestamp int?
  []
  (quot (System/currentTimeMillis) 100)) ;; 1000 = seconds

;; https://stackoverflow.com/questions/13789092/length-of-the-first-line-in-an-utf-8-file-with-bom
(defn debomify
  [^String line]
  (let [bom "\uFEFF"]
    (if (.startsWith line bom)
      (.substring line 1)
      line)))

(defn de-bom-slurp ;; gurgle, fart, splat
  [x]
  (debomify (slurp x)))

(defn join
  [& args]
  (str (apply clojure.java.io/file args))) ;; (join "/foo" "bar" "baz") => /foo/bar/baz

(defn-spec ltrim string?
  "strips leading chars in `m` from `s`"
  [s string? m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "^[%s]*" m))]
    (clojure.string/replace s pattern "")))

(defn-spec rtrim string?
  "strips trailing chars in `m` from `s`"
  [s string?, m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "[%s]*$" m))]
    (clojure.string/replace s pattern "")))

(defn-spec file-to-lazy-byte-array ::sp/file-byte-array-pair
  [path ::sp/extant-file root ::sp/extant-dir]
  (let [;; /foo/bar/baz/ => foo/bar/
        rooted-at (ltrim (clojure.string/replace path (str (fs/parent root)) "") "/")
        ;;f (java.io.File. path)
        f path
        ary (byte-array (.length f))
        is (java.io.FileInputStream. f)]
    (.read is ary)
    (.close is)
    [rooted-at ary]))

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
      out-path)))

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

(defn split-filter
  [f c]
  [(filterv f c) (filterv (complement f) c)])

(defn-spec cp ::sp/extant-file
  [old-path ::sp/extant-file new-dir ::sp/extant-dir]
  (let [new-path (join new-dir (fs/base-name old-path))]
    (fs/copy old-path new-path)
    new-path))

(defn -semver-comp
  [a-bits b-bits]
  (let [to-int (fn [x]
                 (try
                   (some-> x first Integer.)
                   (catch NumberFormatException nfe
                     ;; try again, this time ignore everything after any hyphen.
                     ;; if it's genuine bollocks we'll raise another exception
                     (some-> x first (clojure.string/split #"-") first Integer.))))
        result (compare (to-int a-bits) (to-int b-bits))
        more? (not (empty? (rest b-bits)))]
    (if (= result 0)
      (if more?
        (-semver-comp (rest a-bits) (rest b-bits))
        result)
      result)))

(defn semver-comp
  [a-string b-string]
  (let [a-bits (clojure.string/split a-string #"\.")
        b-bits (clojure.string/split b-string #"\.")]
    (-semver-comp a-bits b-bits)))

(defn-spec sort-semver-strings (s/or :ok (s/coll-of string?) :empty empty?)
  "sort a list of semver strings with support for '1.2.3-something' suffixes."
  [semver-list (s/coll-of string?)]
  (sort semver-comp semver-list))

(st/instrument)
