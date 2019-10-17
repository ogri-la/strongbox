(ns wowman.utils
  (:require
   [wowman.specs :as sp]
   [clojure.string :refer [trim lower-case]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clojure.pprint]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [cheshire.core :as json]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [slugify.core :as sluglib]
   [taoensso.timbre :refer [debug info warn error spy]]
   [java-time :as jt]
   [java-time.format]))

(defmacro static-slurp
  "just like `slurp`, but file is read at compile time.
  good for static, unchanging, files. less good during development"
  [path]
  (slurp path))

(defn-spec safe-to-delete? boolean?
  "predicate, returns true if given file is rooted in given directory"
  [dir ::sp/extant-dir file ::sp/extant-file]
  (clojure.string/starts-with? file dir))

(defn-spec delete-many-files! nil?
  "deletes a list of files rooted in given directory"
  [dir ::sp/dir, regex ::sp/regex, file-type ::sp/short-string]
  (if-not (fs/exists? dir)
    (warn "directory does not exist:" dir) ;; app may not have been started yet
    (let [file-list (mapv str (fs/find-files dir regex))
          suspicious (remove (partial safe-to-delete? dir) file-list)
          alert #(warn "deleting file " %)]
      (if-not (empty? suspicious)
        ;; this is a programming error, not a user error. if there is a problem we don't want N-1 more problems
        (error (format "refusing to delete all files. files were found not rooted at %s" (count suspicious) dir))

        (if (empty? file-list)
          (info (format "no %s files to delete" file-type))
          (do
            (warn (format "deleting %s %s files" (count file-list) file-type))
            (dorun (map (juxt alert fs/delete) file-list))))))))

(defn shallow-flatten
  [lst]
  (mapcat identity lst))

(defn coerce-map-values
  "given a mapping of {key fn} matching keys in given map will be transformed
  (coerce-map-values {:foo str} {:foo 123}) => {:foo '123'}"
  [mapping row]
  (let [reducer (fn [ncoll [k v]]
                  (assoc ncoll k
                         (if (contains? mapping k)
                           ((get mapping k) v)
                           v)))]
    (reduce reducer {} row)))

(defn uuid
  []
  (.toString (java.util.UUID/randomUUID)))

(defn dissoc-all
  [m l]
  (apply dissoc m l))

;; orphaned, might be useful still?
(defn-spec file-ext-as-kw (s/or :ok keyword?, :error nil?)
  [path ::sp/file]
  ;; /tmp/foo.edn => :edn
  ;; /tmp/foo     =>  nil
  (some-> path fs/extension (subs 1) trim lower-case keyword))

(defn-spec replace-file-ext (s/or :ok string?, :error nil?)
  [path ::sp/file, ext string?]
  (-> path str fs/split-ext first (str ext)))

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

(defn-spec todt ::sp/zoned-dt-obj
  "takes an ISO8901 string and returns a java.time.ZonedDateTime object. 
  these are needed to calculate durations"
  [dt ::sp/inst]
  (java-time/zoned-date-time (get java-time.format/predefined-formatters "iso-zoned-date-time") dt))

(defn-spec utcnow ::sp/zoned-dt-obj
  "returns a UTC timestamp for *right now*"
  []
  (java-time/zoned-date-time (java-time/local-date-time) "UTC"))


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
  ([needle haystack]
   (not (nil? (some #{needle} haystack))))
  ([haystack]
   (fn [needle]
     (in? needle haystack))))

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
  "applies fn to each key-val in map"
  [f m]
  (into (empty m) (for [[k v] m] [k (f k v)])))

(defn filter-map
  "filters a map using f"
  [f m]
  (select-keys m (for [[k v] m :when (f k v)] k)))

(defn filter+map
  "filters and transforms a list at the same time. transformed value must be truth-y"
  [f l]
  (for [x l :let [tx (f x)] :when tx] tx))

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
  ([path ::sp/extant-file]
   (load-json-file path {}))
  ([path ::sp/extant-file, transform-map (s/nilable map?)]
   (try
     (let [value-fn (fn [key val]
                      ((get transform-map key (constantly val)) val))]
       (clojure.data.json/read (clojure.java.io/reader path), :key-fn keyword, :value-fn value-fn))
     (catch Exception e
       (warn e (format "failed to read data \"%s\" in file: %s" (.getMessage e) path))))))

(defn call-if-fn
  [x]
  (if (fn? x) (x) x))

(defn load-json-file-safely
  "loads json file at given path with handling for common error cases (no file, bad data, invalid data)
  if :invalid-data? given, then a :data-spec must also be given else nothing happens and you get nil back"
  [path & {:keys [no-file? bad-data? invalid-data? data-spec transform-map]}]
  (if-not (fs/file? path)
    (call-if-fn no-file?)
    (let [data (load-json-file path transform-map)]
      (cond
        (not data) (call-if-fn bad-data?)
        (and ;; both are present AND data is invalid
         (and invalid-data? data-spec)
         (not (s/valid? data-spec data))) (call-if-fn invalid-data?)
        :else data))))

(defn-spec load-edn-file any?
  [path ::sp/extant-file]
  (-> path slurp read-string))

(defn load-edn-file-safely
  [path & {:keys [no-file? bad-data? invalid-data? data-spec]}]
  (if-not (fs/file? path)
    (call-if-fn no-file?)
    (let [data (load-edn-file path)]
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

(defn game-version-to-game-track
  "'1.13.2' => 'classic', '8.2.0' => 'retail'"
  [game-version]
  (if (= "1." (subs game-version 0 2))
    ;; 1.x.x == classic (for now)
    "classic"
    "retail"))

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

;; https://stackoverflow.com/questions/26790881/clojure-file-to-byte-array
(comment "orphaned. was once used in zip.clj to create a zip file of a directory."
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
             [rooted-at ary])))

;; repurposing
(defn-spec file-to-lazy-byte-array bytes?
  [path ::sp/extant-file]
  (let [fobj (java.io.File. path)
        ary (byte-array (.length fobj))
        is (java.io.FileInputStream. fobj)]
    (.read is ary)
    (.close is)
    ary))

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

;; https://stackoverflow.com/questions/25892277/clojure-regex-named-groups#answer-25892938
(defn named-regex-groups
  [regex groups value]
  (zipmap groups (rest (re-find regex value))))

(defn-spec unmangle-https-url (s/or :ok ::sp/uri, :error nil?)
  "given something that is supposed to be a valid http URL, try our hardest to return an actual URL without actually visiting anything.
  if we fail, return nil, otherwise return a string that can be converted to a URL"
  [uin string?]
  (let [;; pretty strict, try this first
        url (try
              (java.net.URL. uin)
              (catch java.net.MalformedURLException _
                nil))

        ;; looser, but still better than a regex
        uri (try
              (java.net.URI. uin)
              (catch java.net.URISyntaxException _
                nil))

        ;; and finally, if url and uri approaches fail, try a quick and dirty regex
        regex #"(\w*:)?(\/\/)?(www\.)?(.+\.\w{2,4})(/?.*)?"
        groups [:protocol :lines :sub :host        :path]
        parsed (named-regex-groups regex groups uin)]

    (cond
      ;; woo! we have something valid already, return as-is
      (not (nil? url)) uin

      ;; ...woo? we have something with a host that isn't total garbage
      ;; try to recreate it, filling in a few blanks.
      (and (not (nil? uri))
           (.getHost uri)) (format "%s://%s%s"
                                   (or (.getScheme uri) "https")
                                   (.getHost uri)
                                   (or (.getRawPath uri) ""))

      (:host parsed) (format "%s://%s%s"
                             (or (:protocol parsed) "https")
                             (:host parsed)
                             (or (:path parsed) ""))

      ;; completely unparseable
      :else nil)))

;; https://clojure.atlassian.net/browse/CLJ-2007
(defmacro if-let*
  "Multiple binding version of if-let"
  ([bindings then]
   `(if-let ~bindings ~then nil))
  ([bindings then else]
   ;; assert-args is in clojure.core but private
   ;;(assert-args
   ;;  (vector? bindings) "a vector for its binding"
   ;;  (even? (count bindings)) "exactly even forms in binding vector")
   (if (== 2 (count bindings))
     `(let [temp# ~(second bindings)]
        (if temp#
          (let [~(first bindings) temp#]
            ~then)
          ~else))
     (let [if-let-else (keyword (name (gensym "if_let_else__")))
           inner (fn inner [bindings]
                   (if (seq bindings)
                     `(if-let [~(first bindings) ~(second bindings)]
                        ~(inner (drop 2 bindings))
                        ~if-let-else)
                     then))]
       `(let [temp# ~(inner bindings)]
          (if (= temp# ~if-let-else) ~else temp#))))))


;;


(st/instrument)
