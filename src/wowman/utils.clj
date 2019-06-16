(ns wowman.utils
  (:require
   [wowman.specs :as sp]
   [clojure.string]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clojure.pprint]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [cheshire.core :as json]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [slugify.core :as sluglib]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :refer [debug info warn error spy]]
   [java-time :as jt]
   [java-time.format]))

(defn-spec file-older-than boolean?
  [file ::sp/extant-file, hours pos-int?]
  (let [modtime (jt/instant (fs/mod-time file))
        now (java-time/instant)
        expiry-offset (jt/hours hours)
        expiry-date (jt/plus modtime expiry-offset)
        expired? (jt/before? expiry-date now)]
    (debug (format "path %s; modtime %s; expiry-offset %s; expiry-date %s; now %s; expired? %s" file modtime expiry-offset expiry-date now expired?))
    expired?))

(defn-spec days-between-then-and-now int?
  [datestamp ::sp/inst]
  (let [then (java-time/local-date datestamp)
        now (java-time/local-date)]
    (.getDays (java-time/period then now))))

(defn fmt-date
  ([dateobj]
   (fmt-date dateobj "yyyy-MM-dd'T'HH:mm:ss'Z'"))
  ([dateobj fmt]
   (.format (java.text.SimpleDateFormat. fmt) dateobj)))

(defn-spec from-epoch string?
  "epoch-time-with-ms to ymdhmstz"
  [epoch int?]
  ;; we *1000 to get the ms
  (-> epoch (* 1000) java-time/instant str))

(defn datestamp-now-ymd
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.)))

;; todo: handy, but target for pruning.
(defn detect-dt-formatting
  [dtstr]
  (info "testing" dtstr)
  (let [fmt (fn [dtfmt]
              (try
                (when-let [result (java-time/zoned-date-time (get java-time.format/predefined-formatters dtfmt) dtstr)]
                  (info "success with" dtfmt ":" result)
                  {dtfmt result})
                (catch Exception e
                  (info "failed testing" dtfmt))))]
    (into {} (mapv fmt (keys java-time.format/predefined-formatters)))))

;;

(defn repl-stack-element?
  [stack-element]
  (and (= "clojure.main$repl" (.getClassName  stack-element))
       (= "doInvoke"          (.getMethodName stack-element))))

(defn in-repl?
  []
  (let [current-stack-trace (.getStackTrace (Thread/currentThread))]
    (some repl-stack-element? current-stack-trace)))

(comment
  (defn ensure
    "wraps `assert` but fails on `nil` or `false` rather than passing on `true`. a message is required"
    [x message]
    (if (or (nil? x)
            (false? x))
      (AssertionError. message)
      x)))

(defn nav-map
  "wrapper around `get-in` that returns the map as-is if given `path` is empty"
  [m path]
  (if (empty? path)
    m
    ;; temporary, to shake out any bad lookups in state
    ;;(ensure (get-in m path) (str "path does not exist: " (clojure.string/join ", " path)))))
    (get-in m path)))

(defn-spec nav-map-fn fn?
  "given a map `m`, returns a function that accepts an optional path of keywords into that map"
  [m map?]
  (fn [& path]
    (nav-map m path)))

(defn to-uri
  [v]
  (when-not (empty? v)
    (-> v java.net.URI. str)))

(defn false-if-nil
  [x]
  (if (nil? x) false x))

(defn nil-if-false
  [x]
  (if (false? x) nil x))

(defn pprint
  [x]
  (with-out-str (clojure.pprint/pprint x)))

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

(defn-spec dump-json-file ::sp/extant-file
  [path ::sp/file, data ::sp/anything]
  ;; this `{:pretty true}` is the only reason we're keeping cheshire around
  (json/generate-stream data (clojure.java.io/writer path) {:pretty true})
  path)

(defn-spec load-json-file (s/or :ok ::sp/anything, :error nil?)
  [path ::sp/extant-file]
  (try
    (clojure.data.json/read (clojure.java.io/reader path), :key-fn keyword)
    (catch Exception e
      (warn e (format "failed to read data \"%s\" in file: %s" (.getMessage e) path)))))

(defn call-if-fn
  [x]
  (if (fn? x) (x) x))

(defn load-json-file-safely
  "loads json file at given path with handling for common error cases (no file, bad data, invalid data)
  if :invalid-data? given, then a :data-spec must also be given else nothing happens and you get nil back"
  [path & {:keys [no-file? bad-data? invalid-data? data-spec]}]
  (if-not (fs/file? path)
    (call-if-fn no-file?)
    (let [data (load-json-file path)]
      (cond
        (not data) (call-if-fn bad-data?)
        (and ;; both are present AND data is invalid
         (and invalid-data? data-spec)
         (not (s/valid? data-spec data))) (call-if-fn invalid-data?)
        :else data))))

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
