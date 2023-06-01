(ns strongbox.utils
  (:require
   [strongbox
    [specs :as sp]
    [constants :as constants]]
   [clojure.java.shell]
   [clojure.string :refer [lower-case]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clojure.pprint]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [slugify.core :as sluglib]
   [taoensso.timbre :refer [debug info warn error spy]]
   [java-time :as jt]
   [java-time.format])
  (:import
   [java.util List Calendar Locale]
   [java.lang Math]
   [java.util Base64]
   [org.apache.commons.compress.compressors CompressorStreamFactory CompressorException]
   [org.ocpsoft.prettytime.units Decade]
   [org.ocpsoft.prettytime PrettyTime]
   [java.text NumberFormat]))

(defn repl-stack-element?
  [stack-element]
  (and (= "clojure.main$repl" (.getClassName  stack-element))
       (= "doInvoke"          (.getMethodName stack-element))))

(defn in-repl?
  []
  (let [current-stack-trace (.getStackTrace (Thread/currentThread))]
    (some repl-stack-element? current-stack-trace)))

(defn instrument
  "if `flag` is true, enables spec checking instrumentation, otherwise disables it."
  [flag]
  (if flag
    (do
      (st/instrument)
      (info "instrumentation is ON"))
    (do
      (st/unstrument)
      (info "instrumentation is OFF"))))

(defn-spec all boolean?
  "true if all items in `lst` are neither nil nor false"
  [lst sequential?]
  (every? identity lst))

(defn-spec any boolean?
  "false if any item in `lst` is either nil or false"
  [lst sequential?]
  ((complement not-any?) identity lst))

(defn nilable
  [x]
  (cond
    (nil? x) nil
    (false? x) nil
    (and (coll? x)
         (empty? x)) nil
    (and (string? x)
         (clojure.string/blank? x)) nil
    :else x))

(defn-spec safe-to-delete? boolean?
  "predicate, returns `true` if given file is prefixed with given directory."
  [dir ::sp/extant-dir file ::sp/extant-file]
  (clojure.string/starts-with? file dir))

(defn-spec delete-file! (s/or :ok nil?, :failed ::sp/file)
  "returns `nil` if deleting `path` inside `dir` was successful. returns `path` if deleting it was unsuccessful."
  [dir ::sp/extant-dir, path ::sp/extant-file]
  ;; these checks are important despite `defn-spec` as instrumentation is disabled for release.
  (cond
    (not (fs/exists? dir)) (do (warn "directory does not exist:" dir) path) ;; app may not have been started yet
    (not (safe-to-delete? dir path)) (do (error (format "refusing to delete file. file was not rooted at %s" dir)) path)
    (not (fs/file? path)) (do (error (format "refusing to delete file. file is not a file! %s" path)) path)
    :else (try
            (fs/delete path)
            (when (fs/exists? path)
              path)
            (catch Exception uncaught-exception
              (error uncaught-exception (str "unexpected error attempting to delete file: " path))
              path))))

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

#_(defn-spec days-between-then-and-now int?
    [datestamp ::sp/inst]
    (let [then (java-time/local-date datestamp)
          now (java-time/local-date)]
      (.getDays (java-time/period then now))))

(defn datestamp-now-ymd
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.)))

(defn-spec todt ::sp/zoned-dt-obj
  "takes an ISO8601 string and returns a java.time.ZonedDateTime object.
  if the date is missing it's time portion, it's assumed to be `T00:00:00Z`."
  [dt ::sp/inst]
  (let [dt (if (-> dt count (= 10)) (str dt "T00:00:00Z") dt)]
    (java-time/zoned-date-time (get java-time.format/predefined-formatters "iso-zoned-date-time") dt)))

(defn-spec dt-before? boolean?
  "returns `true` if `date-1` happened before `date-2`"
  [date-1 ::sp/inst, date-2 ::sp/inst]
  (jt/before? (todt date-1) (todt date-2)))

(defn-spec older-than? boolean?
  "returns `true` if the `period` (hours, days) between *now* and `then` is greater than `threshold`."
  [then ::sp/inst, threshold ::sp/gte-zero, period keyword?]
  (let [expiry-offset
        (case period
          :minutes (jt/minutes threshold)
          :hours (jt/hours threshold)
          :days (jt/days threshold))
        now (java-time/instant)
        expiry-date (jt/plus (jt/instant (todt then)) expiry-offset)]
    (jt/before? expiry-date now)))

(defn-spec file-older-than boolean?
  "returns `true` if given `file` has a modification date older than given `hours`."
  [file ::sp/extant-file, threshold ::sp/gte-zero, period keyword?]
  (-> file fs/mod-time jt/instant str (older-than? threshold period)))

(defn-spec published-before-classic? (s/or :ok boolean?, :error nil?)
  [dt-string (s/nilable ::sp/inst)]
  (try
    (boolean (some-> dt-string (dt-before? constants/release-of-wow-classic)))
    (catch RuntimeException e
      (if (= (.getMessage e) "Conversion failed")
        (warn (str "bad date: " dt-string)))
      nil)))

(def -pretty-dt-printer (doto (PrettyTime.)
                          (.removeUnit Decade)))

(def ^:dynamic *pretty-dt-printer* -pretty-dt-printer)

(defn-spec format-dt string?
  "returns a PrettyTime formatted datetime representation or an empty string"
  [val (s/or :ok ::sp/inst, :supported nil?, :gui-edge-case ::sp/empty-string)]
  ;; the `gui-edge-case` comes from converting nil values (crashes widgets) to empty strings (less crashy)
  (or (some->> val nilable todt (.format *pretty-dt-printer*)) ""))

(defn nav-map
  "wrapper around `get-in` that returns the map as-is if given `path` is empty"
  [m path]
  (if (empty? path)
    m
    ;; temporary, to shake out any bad lookups in state
    ;;(ensure (get-in m path) (str "path does not exist: " (clojure.string/join ", " path)))))
    (get-in m path)))

#_(defn-spec nav-map-fn fn?
    "given a map `m`, returns a function that accepts an optional path of keywords into that map"
    [m map?]
    (fn [& path]
      (nav-map m path)))

(defn pprint
  [x]
  (with-out-str (clojure.pprint/pprint x)))

(defn items
  [& lst]
  (vec (remove nil? (flatten lst))))

(defn-spec safe-subs (s/nilable string?)
  "similar to `subs` but can handle `nil` input and a `max` value larger than (or less than) length of given string `x`."
  [^String x (s/nilable string?), ^Integer maxval int?]
  (when x
    (subs x 0 (min (count x) (if (neg? maxval) 0 maxval)))))

(defn in?
  ([needle haystack]
   (not (nil? (some #{needle} haystack))))
  ([haystack]
   (fn [needle]
     (in? needle haystack))))

(defn-spec idx map?
  [list-of-maps ::sp/list-of-maps, key keyword?]
  (into {} (map (fn [row] {(get row key) row}) list-of-maps)))

(defn-spec to-json string?
  [x ::sp/anything]
  (with-out-str (clojure.data.json/pprint x :escape-slash false)))

(defn from-json*
  [x]
  (some-> x (clojure.data.json/read-str :key-fn keyword)))

(defn from-json
  [x & [msg]]
  (try
    (from-json* x)
    (catch Exception exc
      (error (format (or msg (str "failed to parse json: %s")) (.getMessage exc)))
      nil)))

(defn-spec dump-json-file ::sp/extant-file
  [path ::sp/file, data ::sp/anything]
  (spit path (to-json data))
  path)

(defn call-if-fn
  [x]
  (if (fn? x) (x) x))

(defn-spec load-json-file-safely (s/or :ok ::sp/anything, :error nil?)
  "loads json file at given path with handling for common error cases (no file, bad data, invalid data)
  if :invalid-data? given, then a :data-spec must also be given else nothing happens and you get nil back"
  ([path ::sp/file]
   (load-json-file-safely path {}))
  ([path (s/or :file ::sp/file, :bytes bytes?), opts map?]
   (let [{:keys [no-file? bad-data? invalid-data? data-spec value-fn key-fn transform-map]} opts
         default-key-fn keyword
         default-value-fn (fn [key val]
                            ((get transform-map key (constantly val)) val))
         ;; specific `key-fn` and `value-fn` take precendence over anything in `transform-map`
         value-fn (or value-fn default-value-fn)
         key-fn (or key-fn default-key-fn)]
     (if (and (not (bytes? path))
              (not (fs/file? path)))
       (call-if-fn no-file?)
       (let [data (try
                    (with-open [reader (clojure.java.io/reader path)]
                      (clojure.data.json/read reader :key-fn key-fn, :value-fn value-fn))
                    (catch Exception uncaught-exc
                      (warn uncaught-exc (format "failed to read data \"%s\" in file: %s" (.getMessage uncaught-exc) path))))]
         (cond
           (not data) (call-if-fn bad-data?)
           (and ;; both are present AND data is invalid
            (and invalid-data? data-spec)
            (not (s/valid? data-spec data))) (call-if-fn invalid-data?)
           :else data))))))

#_(defn-spec load-edn-file any?
    [path ::sp/extant-file]
    (-> path slurp read-string))

#_(defn load-edn-file-safely
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

(defn-spec to-int (s/or :ok int?, :error nil?)
  "given any value `x`, converts it to an integer or returns `nil` if it can't be converted."
  [x any?]
  (if (int? x)
    x
    (try (Integer/valueOf (str x))
         (catch NumberFormatException nfe
           nil))))

(defn-spec slugify string?
  [string string?]
  (sluglib/slugify string))

(defn-spec interface-version-to-game-version (s/or :ok string?, :no-match nil?)
  [iface-version (s/or :deprecated string?, :ok int?)]
  ;; warning! there is no way to convert *unambiguously* between the 'patch level' and the 'interface version'
  ;; for example, patch "1.2.0" => "10200", but so does "1.20.0" => "10200"
  ;; there haven't been any minor versions >4 since MOP
  ;; we'll hit 10.0 soon enough (we're at 8.x at time of writing) so what then? "10.0.1" => "10000" is another collision
  ;; the below code should only be considered unambigous for versions of WoW between 2.x and 8.x
  ;; (and 9.x if that series follows the behaviour of all other patch levels since 2.x)
  ;; see: https://wow.gamepedia.com/Patches
  (let [iface-regex #"(?<major>\d0|\d{1})\d(?<minor>\d{1})\d(?<patch>\d{1}\w?)"
        matcher (re-matcher iface-regex (str iface-version))
        major-minor-patch (rest (re-find matcher))]
    (when-not (empty? major-minor-patch)
      (clojure.string/join "." major-minor-patch))))

(defn-spec game-version-to-interface-version (s/or :ok ::sp/interface-version :error nil?)
  "'8.2.0' => '80200', '1.13.2' => '11300', '10.0.0' => '100000'"
  [game-version string?]
  (let [;; patch-version isn't considered apparently: http://wowwiki.wikia.com/wiki/Getting_the_current_interface_number
        [major minor & _] (clojure.string/split game-version #"\.")
        major (to-int major)
        minor (to-int minor)]
    (when (and major minor)
      (+ (* 10000 major) (* 100 minor)))))

(defn-spec game-version-to-game-track ::sp/game-track
  "'1.13.2' => ':classic', '8.2.0' => 'retail'"
  [game-version string?]
  (let [prefix (safe-subs game-version 2)]
    (case prefix
      ;; 1.x.x == classic (vanilla)
      "1." :classic
      ;; 2.x.x == classic (burning crusade)
      "2." :classic-tbc
      ;; 3.x.x == classic (wrath of the lich king)
      "3." :classic-wotlk
      :retail)))

(defn-spec interface-version-to-game-track (s/or :ok ::sp/game-track, :err nil?)
  "converts an interface version like '80000' to a game track like ':retail'"
  [interface-version int?]
  (some-> interface-version ;; 80000
          interface-version-to-game-version ;; 8.0
          game-version-to-game-track)) ;; :retail

(defn-spec game-track-to-latest-game-version (s/or :ok string?, :err nil?)
  "':classic' => '1.13.0'"
  [game-track ::sp/game-track]
  (case game-track
    :retail constants/latest-retail-game-version
    :classic constants/latest-classic-game-version
    :classic-tbc constants/latest-classic-tbc-game-version
    :classic-wotlk constants/latest-classic-wotlk-game-version))

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
  [s string?, m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "^[%s]*" m))]
    (clojure.string/replace s pattern "")))

(defn-spec rtrim string?
  "strips trailing chars in `m` from `s`"
  [s string?, m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "[%s]*$" m))]
    (clojure.string/replace s pattern "")))

(defn-spec trim string?
  "strips leading and trailing chars in `m` from `s`"
  [s string?, m string?]
  (-> s (ltrim m) (rtrim m)))

(defn-spec replace-file-ext (s/or :ok ::sp/file, :error nil?)
  [path ::sp/file, ext string?]
  (let [ext (ltrim ext ".")
        ext (str "." ext)
        ;; "foo" => nil, "foo/bar" => ["foo"], "/foo/bar" => ["/" "foo"]
        parent (some->> (-> path fs/split butlast) (apply join))]
    (join parent (-> path str fs/split-ext first (str ext)))))

(defn split-filter
  [f c]
  [(filterv f c) (filterv (complement f) c)])

(defn -semver-comp
  [a-bits b-bits]
  (let [find-int (fn [x]
                   (or (some-> x first to-int)
                       ;; try again, this time ignore everything after any hyphen.
                       ;; if it's genuine bollocks we'll raise another exception
                       (some-> x first (clojure.string/split #"-") first to-int)))
        result (compare (find-int a-bits) (find-int b-bits))
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
  "returns a map of values that were matched in the given regular expression using groups
  for example: (named-regex-groups #'(.*)' [:foo] 'bar') => {:foo 'bar'}"
  [regex groups value]
  (zipmap groups (rest (re-find regex value))))

(defn-spec unmangle-https-url (s/or :ok ::sp/url, :error nil?)
  "given something that is supposed to be a valid http URL, try our hardest to return an actual URL without actually visiting anything.
  if we fail, return nil, otherwise return a string that can be converted to a URL"
  [uin string?]
  (let [;; pretty strict, try this first
        url (try
              (java.net.URL. uin)
              (catch java.net.MalformedURLException _
                nil))

        ;; looser, but still better than a regex
        ^java.net.URI uri
        (try
          (java.net.URI. uin)
          (catch java.net.URISyntaxException _
            nil))

        ;; and finally, if url and uri approaches fail, try a quick and dirty regex
        groups [:protocol :lines :sub :host        :path]
        regex #"(\w*:)?(\/\/)?(www\.)?(.+\.\w{2,4})(/?.*)?"
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

      ;; unparseable
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

(defn-spec expand-path ::sp/file
  "given a path, expands any 'user' directories, relative directories and symbolic links"
  [path ::sp/file]
  (-> path fs/expand-home fs/normalized fs/absolute str))

(defn last-writeable-dir
  "given a path, returns the last writable directory or nil if no writable directory available"
  [path]
  (when path
    (if (and (fs/directory? path) (fs/writeable? path))
      (str path)
      (last-writeable-dir (fs/parent path)))))

(defn-spec drop-nils (s/or :ok map?, :empty nil?)
  "given a map `m` and a set of `fields`, if field is `nil`, `dissoc` it"
  [m map?, fields sequential?]
  (if (empty? fields)
    m
    (drop-nils
     (if (nil? (get m (first fields)))
       (dissoc m (first fields))
       m)
     (rest fields))))

#_(defn-spec copy-old-to-new-if-safe (s/or :copied ::sp/extant-file, :no-op nil?)
    "copies `old-path` to `new-path` if `old-path` exists and `new-path` doesn't exist"
    [old-path ::sp/file, new-path ::sp/file]
    (when (and (fs/exists? old-path)
               (not (fs/exists? new-path)))
      (fs/copy old-path new-path)))

;;

(defn-spec browser (s/or :ok fn? :error nil?)
  "given the name of a binary, returns a function that will open a given URL in a browser or nil if 
  the binary cannot be found."
  [bin string?]
  (try
    (when (->> bin (clojure.java.shell/sh "which") :exit (= 0))
      (fn [url]
        (info (format "opening URL with %s: %s" bin url))
        (clojure.java.shell/sh bin url)))
    (catch Exception uncaught-exception
      (error uncaught-exception "failed to call `which`"))))

(defn-spec java-browser (s/or :ok fn? :error nil?)
  "returns a function that will open a given value or nil if current Desktop is not supported.
  if the given value is a URL, we attempt to 'browse' it.
  if the given value is an extant directory, we attempt to 'open' it."
  []
  (let [desktop (java.awt.Desktop/getDesktop)]
    (when (and (java.awt.Desktop/isDesktopSupported)
               (.isSupported desktop java.awt.Desktop$Action/BROWSE)
               (.isSupported desktop java.awt.Desktop$Action/OPEN))
      (fn [path]
        (cond
          (s/valid? ::sp/url path) (do (info "opening URL:" path)
                                       (.browse desktop (java.net.URI. path)))
          (s/valid? ::sp/extant-dir path) (do (info "opening directory:" path)
                                              (.open desktop (java.io.File. ^String path)))
          :else (error "can't open: " path))))))

(defn-spec find-browser fn?
  "returns a function that attempts to open a given URL in a browser.
  Prints an error message to console if URL cannot be opened."
  []
  (let [xdg-open #(browser "xdg-open")
        gnome-open #(browser "gnome-open")
        kde-open #(browser "kde-open")
        fail (constantly #(error "failed to find a program to open URL:" (str %)))]
    (loop [lst [java-browser
                xdg-open gnome-open kde-open fail]]
      (if-let [browser-fn ((first lst))]
        browser-fn
        (recur (rest lst))))))

(defn-spec browse-to nil?
  "given a URL, open a browser window with it"
  [x (s/or :url ::sp/url, :dir ::sp/extant-dir)]
  (future
    ((find-browser) x))
  nil)

(defn-spec no-new-lines (s/or :ok string? :also-ok nil?)
  "removes all \n and \r\n from a string"
  [string (s/nilable string?)]
  (some-> string
          (clojure.string/replace "\r\n" " ")
          (clojure.string/replace "\n" " ")))

(defn-spec drop-idx (s/or :ok vector? :bad nil?)
  "removes element at index `idx` within vector `v`.
  if `v` is nil or empty, `v` will be returned as-is.
  if `idx` is negative or greater than the number of elements in `v`, `v` will be returned as-is."
  [v (s/nilable vector?), idx (s/nilable int?)]
  (when-not (nil? v)
    (let [c (count v)]
      (cond
        (nil? idx) v
        (empty? v) v
        (< idx 0) v ;; negative indices return the vector as-is
        (>= idx c) v ;; indicies greater than num items return vector as-is
        :else (into (subvec v 0 idx) (subvec v (inc idx) c))))))

(defn-spec extract-addon-id map?
  "attempts to extract a set of uniquely identifying attributes of the given `addon`.
  if it fails to find these attributes, the whole map will be returned as-is."
  [addon map?]
  (let [addon-id (select-keys addon [:source :source-id :dirname])]
    (if (not (empty? addon-id))
      addon-id
      addon)))

(defn-spec unique-id string?
  "returns a UUID as a string that is guaranteed to always be unique."
  []
  (str (java.util.UUID/randomUUID)))

(defn count-occurances
  ;; {"Foo-v1.zip" 1, "Foo-v2.zip 1, "Foo.zip" 5}
  [my-list my-key]
  (let [-count-occurances (fn [accumulator-m m]
                            (update accumulator-m (get m my-key) (fn [n] (inc (or n 0)))))]
    (reduce -count-occurances {} my-list)))

;; https://clojuredocs.org/clojure.core/zipmap#example-56fbf77de4b069b77203b858
(defn csv-map
  "ZipMaps header as keys and values from lines.
  usage: `(apply utils/csv-map [header row1 row2 rowN])`"
  [head & lines]
  (map #(zipmap (map keyword head) %1) lines))

(defn-spec guess-game-track (s/nilable ::sp/game-track)
  "returns the first game track it finds in the given string, preferring `:classic-wotlk`, then `:classic-tbc`, then `:classic`, then `:retail` (most to least specific).
  returns `nil` if no game track found."
  [string (s/nilable string?)]
  (when string
    (let [;; matches 'classic-wotlk', 'classic_wotlk', 'classic-wrath', 'classic_wrath', 'wotlk', 'wrath'
          classic-wotlk-regex #"(?i)(classic[\W_])?(wrath|wotlk){1}\W?"
          ;; matches 'classic-tbc', 'classic-bc', 'classic-bcc', 'classic_tbc', 'classic_bc', 'classic_bcc', 'tbc', 'tbcc', 'bc', 'bcc'
          ;; but not 'classictbc' or 'classicbc' or 'classicbcc'
          ;; see tests.
          classic-tbc-regex #"(?i)classic[\W_]t?bcc?|[\W_]t?bcc?\W?|t?bcc?$"
          classic-regex #"(?i)classic|vanilla"
          retail-regex #"(?i)retail|mainline"]
      (cond
        (re-find classic-wotlk-regex string) :classic-wotlk
        (re-find classic-tbc-regex string) :classic-tbc
        (re-find classic-regex string) :classic
        (re-find retail-regex string) :retail))))

(defn-spec url-to-addon-source (s/or :known-source :addon/source, :unknown-source nil?)
  "returns the source of an addon for a given `url`"
  [url-str ::sp/url]
  (let [url-obj (-> url-str java.net.URL.)
        host (.getHost url-obj)
        host-sans-www (if (clojure.string/starts-with? host "www.")
                        (subs host 4)
                        host)]
    (case host-sans-www
      "gitlab.com" "gitlab"
      "github.com" "github"
      "wowinterface.com" "wowinterface"
      "curseforge.com" "curseforge"
      "tukui.org" (case (.getPath url-obj)
                    "/download.php" "tukui"
                    "/addons.php" "tukui"
                    "/classic-addons.php" "tukui-classic"
                    "/classic-tbc-addons.php" "tukui-classic-tbc"
                    "/classic-wotlk-addons.php" "tukui-classic-wotlk"
                    nil)
      nil)))

(defn-spec message-list string?
  "returns a multi-line string with the given `msg` on top and each message in `msg-list` bulleted beneath it"
  [msg string?, msg-list ::sp/list-of-strings]
  (clojure.string/join (format "\n %s " constants/bullet) (into [msg] msg-list)))

(defn-spec reportable-error string?
  ([msg string?]
   (reportable-error msg "please report this!"))
  ([msg string?, report-msg string?]
   (message-list msg [(str report-msg " https://github.com/ogri-la/strongbox/issues")])))

(defn-spec select-vals coll?
  "like `get` on `m` but for each key in `ks`. removes nils."
  [m map?, ks (s/coll-of any?)]
  (remove #(= % :-missing) (map #(get m % :-missing) ks)))

;; https://github.com/unrelentingtech/clj-http-fake/blob/920630d21bbd9b3203c07bc458d4da1070fd6113/src/clj_http/fake.clj#L136
(let [byte-array-type (Class/forName "[B")]
  (defn byte-array?
    "Is `obj` a java byte array?"
    [obj]
    (instance? byte-array-type obj)))

(defn atom?
  "Is `obj` an atom?"
  [obj]
  (instance? clojure.lang.Atom obj))

(defn thread-pool-executor?
  [obj]
  (instance? java.util.concurrent.ThreadPoolExecutor obj))

;; https://gist.github.com/danielpcox/c70a8aa2c36766200a95#gistcomment-2759496-permalink
(defn deep-merge
  "merges `b` into `a` when `a` is a map, otherwise returns `b`.
  other collections are not considered."
  [a b]
  (if (map? a)
    (into a (for [[k v] b]
              [k (deep-merge (a k) v)]))
    b))

(defn rmv
  "removes element `x` from collection `coll`, returning a vector"
  [coll x]
  (into [] (remove #{x} coll)))

(defn select-keys*
  "same as `select-keys`, but with parameter order changed for expression threading."
  [ks m]
  (select-keys m ks))

(defn-spec base64-decode (s/or :ok? string?, :error nil?)
  [string (s/nilable string?)]
  (when string
    (String. (.decode (Base64/getDecoder) string))))

;; https://stackoverflow.com/questions/3407876/how-do-i-avoid-clojures-chunking-behavior-for-lazy-seqs-that-i-want-to-short-ci
(defn unchunk
  "wraps sequence `s` in a call to `lazy-seq` that produces a closure, avoiding chunking behaviour."
  [s]
  (when (seq s)
    (lazy-seq
     (cons (first s)
           (unchunk (next s))))))

(defn first-nn
  "returns the first non-nil value of lazily applying `f` to `lst`, avoiding chunking behaviour so side-effects are safe"
  [f lst]
  (->> lst
       unchunk
       (map f)
       (remove nil?)
       first))

(defn-spec github-url-to-source-id (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec source-map (s/nilable map?)
  [addon (s/nilable map?)]
  (select-keys addon [:source :source-id]))

(defn-spec find-depth int?
  "given a map `m`, if it contains `:children`, increments `i` and calls self"
  [m map?, i int?]
  (if (contains? m :children)
    (let [children (:children m)]
      (if (sequential? children)
        (apply max (conj (mapv #(find-depth % (inc i)) children) (inc i)))
        (inc i)))
    i))

(def -with-lock-lock (Object.))

(def -with-lock-wait-retry-time 10) ;; ms

(defmacro with-lock
  "executes `form` once all items in given `user-set` are available in `lock-set-atom`."
  [lock-set-atom user-set & form]
  `(loop [waited# 0]

       ;; ensure reading the atom is single threaded (locking) and that when we read it and test the result,
       ;; we update it in the same operation (dosync).
     (let [lock-set#
           (locking -with-lock-lock
             (dosync
              (debug "current locks:" (deref ~lock-set-atom))
              (when (empty? (clojure.set/intersection (deref ~lock-set-atom) ~user-set))
                  ;; there is no overlap between the locks we have and what the user wants.
                  ;; add the user locks to the working set and execute body
                (swap! ~lock-set-atom into ~user-set))))]

       (debug "acquiring locks:" ~user-set)
       (if (not (nil? lock-set#))
         (try
           (debug "locks acquired:" ~user-set)
           ~@form
           (finally
               ;; when body is complete, release the locks
               ;; synchronised access not required ?
             (debug "releasing locks:" ~user-set)
             (swap! ~lock-set-atom clojure.set/difference ~user-set)))

           ;; something else holds one or more of the desired locks! wait a duration and try again
         (do (debug "blocked!")
             (Thread/sleep -with-lock-wait-retry-time)
             (debug (format "recurring in %s ms, have waited %s ms" -with-lock-wait-retry-time waited#))
             (recur (+ waited# -with-lock-wait-retry-time)))))))

(defn-spec patch-name (s/or :ok string?, :not-found nil?)
  "returns the 'patch' name for the given `game-version`, considering only the major and minor values.
  if a precise match is not found, the major version is then considered.
  if a major version is not found, nil is returned.
  For example, 9.2.5 has no patch name, but 9.2 is 'Shadowlands: Eternity's End'"
  [game-version string?]
  (let [[major, minor] (clojure.string/split game-version #"\.")
        major-minor (clojure.string/join "." [major minor])]
    (or (get constants/releases major-minor)
        (get constants/releases major))))

(defmacro compile-time-slurp
  "slurps given `resource` file at macro-expansion (compile) time."
  [resource]
  `(slurp (clojure.java.io/resource ~resource)))

(defn-spec folder-size-bytes int?
  "returns the size of a directory and it's contents in bytes"
  [path ::sp/extant-dir]
  (let [count-subdirs (fn [root dir-set]
                        (map #(-> root (fs/file %) .length) dir-set))
        count-files (fn [root file-list]
                      (map #(.length (fs/file root %)) file-list))
        count-subdirs+files (fn [root dir-set file-list]
                              (into (count-subdirs root dir-set)
                                    (count-files root file-list)))]
    (+ (-> path fs/file .length)
       (reduce + (flatten (fs/walk count-subdirs+files path))))))

;; ---
;; copied from: https://github.com/clj-commons/humanize/blob/master/src/clj_commons/humanize.cljc
;; on: 2023-04-08
;; with licence: EPL v1.0
;; added to GPL exclusion list, see LICENCE.md.

(defn logn [num base]
  (/ (Math/round (Math/log num))
     (Math/round (Math/log base))))

(defn filesize
  "Format a number of bytes as a human readable filesize (eg. 10 kB).
  decimal suffixes (kB, MB) are used."
  [bytes]
  (cond
    (not (number? bytes)) ""
    (zero? bytes) "0" ;; special case for zero

    :else
    (let [format-string "%.1f"
          decimal-sizes  [:B, :KB, :MB, :GB, :TB,
                          :PB, :EB, :ZB, :YB]

          units decimal-sizes
          base  1000

          base-pow  (int (Math/floor (logn bytes base)))
          ;; if base power shouldn't be larger than biggest unit
          base-pow  (if (< base-pow (count units))
                      base-pow
                      (dec (count units)))
          suffix (name (get units base-pow))
          ;; TODO: Math/pow isn't a drop-in for `expt`:
          ;; https://github.com/clojure/math.numeric-tower/blob/97827be66f35feebc3c89ba81c546fef4adc7947/src/main/clojure/clojure/math/numeric_tower.clj#L89-L103
          value (float (/ bytes (Math/pow base base-pow)))]

      (str (format format-string value) suffix))))

(defn-spec now ::sp/inst
  "returns the date and time right now as a datetime string"
  []
  (str (java-time/instant)))

(defn-spec unix-time-to-datetime ::sp/inst
  "converts a number in the unix time format '1685623484' to milliseconds, then a `java.time.Instant` then a string"
  [unix-time-seconds number?]
  (-> unix-time-seconds (* 1000) java-time/instant str))

(defn-spec minutes-from-now number?
  "returns the number of minutes between `(now)` and the given `instant`"
  [instant ::sp/inst]
  (let [duration (->> instant java-time/instant (java-time/duration (java-time/instant (now))))]
    (java-time/as duration :minutes)))

(defn user-locale
  []
  (Locale/getDefault))

(defn-spec format-number string?
  "locale-aware number formatting."
  [^Integer n number?]
  (.format ^java.text.NumberFormat (NumberFormat/getNumberInstance (user-locale)) n))

(defn-spec pretty-print-keyword (s/nilable string?)
  "converts a keyword into a string.
  hyphens are removed: :foo-bar => 'foo bar'
  namespaces are handled: :foo/bar-baz => 'foo / bar baz'
  non-keywords return nil.
  nil returns nil."
  [kw (s/nilable keyword?)]
  (when (keyword? kw) ;; :foo, :foo/bar-baz
    (let [ns-str (namespace kw) ;; nil, :foo
          rest-str (some-> kw name (clojure.string/replace "-" " ")) ;; "bar baz"
          ]
      (if ns-str
        (format "%s / %s" ns-str rest-str) ;; "foo / bar baz"
        rest-str)))) ;; "bar baz"

(defn-spec pretty-print-value string?
  "converts any value into a friendly string.
  strings are returned as-is.
  integers are formatted according to locale.
  lists and maps are recursively formatted.
  empty lists and maps return '(empty)'
  `nil` becomes the string '(none)'.
  "
  [v any?]
  (cond
    (nil? v) "(none)"
    (number? v) (format-number v)
    (sequential? v) (if (empty? v) "(empty)" (clojure.string/join ", " (mapv pretty-print-value v)))
    (map? v) (if (empty? v)
               "(empty)"
               (clojure.string/join ", " (mapv (fn [[key val]]
                                                 (format "%s: %s" (pretty-print-value key) (pretty-print-value val))) (sort-by first v))))
    (boolean? v) (-> v str clojure.string/capitalize)
    (keyword? v) (name v)
    :else (str v)))
