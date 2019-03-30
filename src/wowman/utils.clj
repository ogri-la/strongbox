(ns wowman.utils
  (:require
   [wowman.specs :as sp]
   [clojure.string]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [cheshire.core :as json]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [me.raynes.fs.compression :as zip]
   [slugify.core :as sluglib]
   [orchestra.core :refer [defn-spec]]
   [clojure.data.codec.base64 :as b64]
   [taoensso.timbre :refer [debug info warn error spy]]
   [clj-http.conn-mgr]
   [clj-http.client :as client]
   [clj-time
    [coerce :as coerce-time]
    [format :as format-time]]))

(def ^:dynamic cache-dir nil)

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

(defn-spec merge-lists1 ::sp/list-of-maps
  "given two lists and a key, returns list1 with matching entries from list2 when keys match"
  [key keyword?, list1 ::sp/list-of-maps, list2 ::sp/list-of-maps]
  (let [index (idx list2 key)]
    (mapv (fn [row] (or (get index (get row key)) row)) list1)))

(defn merge-lists2
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

(def merge-lists merge-lists2)

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

;; https://clojuredocs.org/clojure.core/when-let
(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defn-spec to-json ::sp/json
  [x ::sp/anything]
  (json/generate-string x {:pretty true}))

(defn-spec dump-json-file nil?
  [path ::sp/file data ::sp/anything]
  ;; this `{:pretty true}` is the only reason we're keeping cheshire around
  (json/generate-stream data (clojure.java.io/writer path) {:pretty true})
  nil)

(defn-spec load-json-file ::sp/anything
  [path ::sp/extant-file]
  (clojure.data.json/read (clojure.java.io/reader path), :key-fn keyword))

;; todo: deprecated, remove once we no longer have java.net.URI:: prefixed strings around
(defn-spec decode-java-net-uri (s/or :ok ::sp/uri :error nil?)
  [string (s/or :ok string?, :nil nil?)]
  (if string
    (if ;; old serialised data
     (and string (clojure.string/starts-with? string "java.net.URI::"))
      (-> string (subs 14) java.net.URI. str)

      ;; new serialised data
      (-> string java.net.URI. str))

    string))

;; todo: possibly redundant once uri decoding is removed. 
(defn-spec load-json-file-with-decoding ::sp/anything
  "like `load-json-file`, however specific fields are handled specially (see `decode-map`). 
  this is because encoding values to strings is easy, decoding strings to values is expensive"
  [path ::sp/extant-file]
  (let [value-fn (fn [k v]
                   (if-let [f (case k
                                :uri decode-java-net-uri
                                :download-uri decode-java-net-uri
                                :donation-uri decode-java-net-uri
                                nil)]
                     (f v)
                     v))]
    (clojure.data.json/read (clojure.java.io/reader path), :key-fn keyword, :value-fn value-fn)))

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

(defn-spec unzip-file ::sp/extant-dir
  [zipfile-path ::sp/extant-archive-file, output-dir-path ::sp/extant-dir]
  (debug (format "unzipping %s to %s" zipfile-path output-dir-path))
  (zip/unzip zipfile-path output-dir-path)
  output-dir-path)

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

(defn-spec encode-url-path uri?
  "given a url, explodes it, encodes the path, returns a uri object"
  [url string?]
  (let [url (java.net.URL. url)
        protocol (.getProtocol url)
        host (.getHost url)
        path (.getPath url) ;; unencoded
        fragment nil]
    ;; properly encoded
    (java.net.URI. protocol host path fragment)))

(def curse-crap-redirect-strategy
  (proxy [org.apache.http.impl.client.DefaultRedirectStrategy] []
    (createLocationURI [loc]
      (try
        (java.net.URI. loc)
        (catch java.net.URISyntaxException e
          (warn "redirected to bad URI! encoding path:" loc)
          (encode-url-path loc))))))

(defn- add-etag-or-not
  [etag-path req]
  (if (and etag-path (fs/file? etag-path))
    (assoc-in req [:headers :if-none-match] (slurp etag-path))
    req))

(defn- write-etag
  [etag-path resp]
  (when etag-path
    (spit etag-path (-> resp :headers (get "etag"))))
  resp)

(defn etag-middleware
  [etag-path]
  (fn [client]
    (fn
      ([req]
       (write-etag etag-path (client (add-etag-or-not etag-path req))))
      ([req resp raise]
       (write-etag etag-path (client (add-etag-or-not etag-path req) resp raise))))))

;;(defn-spec download-file (s/or :ok ::sp/extant-file, :http-error ::sp/http-error)
;;  [uri ::sp/uri, output-file ::sp/file, & {:keys [overwrite?]} (s/map-of keyword? any?)]
(defn download-file
  [uri output-file & {:keys [overwrite?]}]
  (let [;; we can have local file based caching or we can rely on etags for fresh data
        ;; erring on the side of etags for now as a dumb cache is a little too-dumb for me
        cache? (not (nil? cache-dir))
        etag-path (when cache?
                    (join cache-dir (-> output-file fs/base-name (str ".etag"))))]

    ;; when etag path exists but output file doesn't, delete etag file
    ;; ensures orphaned .etag files don't prevent download
    (when (and (fs/exists? etag-path)
               (not (fs/exists? output-file)))
      (warn "orphaned .etag found:" etag-path)
      (fs/delete etag-path))

    ;; file exists and we're not overwriting existing file, return path to what we have
    (if (and
         (fs/exists? output-file)
         (not overwrite?))
      (do
        (debug "cache hit for" output-file)
        output-file)

      ;; we're overwriting files. still a chance nothing is downloaded or overwritten
      (try
        (debug "cache miss for" output-file)
        (info (format "downloading %s to %s" uri output-file))
        (client/with-additional-middleware [client/wrap-lower-case-headers (etag-middleware etag-path)]
          (let [resp (client/get uri {:as :stream
                                      :redirect-strategy curse-crap-redirect-strategy})]
            (when-not (= 304 (:status resp)) ;; 'when-not not-modified' or just 'when modified'
              (clojure.java.io/copy
               (:body resp)
               (java.io.File. output-file)))

            output-file))

        (catch Exception e
          (if (-> e ex-data :status)
            ;; "Note that the connection to the server will NOT be closed until the stream has been read"
            ;;  - https://github.com/dakrone/clj-http
            ;; not sure if necessary, but can't hurt: https://github.com/dakrone/clj-http/issues/461
            (let [_ (some-> e ex-data :body .close)
                  http-error (select-keys (ex-data e) [:reason-phrase :status])]
              (error (format "failed to download file '%s': %s (HTTP %s)"
                             uri (:reason-phrase http-error) (:status http-error)))
              http-error)

            ;; unhandled non-http exception
            (throw e)))))))

;; todo: revisit this
;; https://github.com/dakrone/clj-http/blob/master/src/clj_http/conn_mgr.clj#L251
(def conn-manager (clj-http.conn-mgr/make-reusable-conn-manager
                   {:default-per-route 4 ;; max simultaneous connections per host
                    }))

;;
;;

;; disabled until I figure out how to spec the parameters:
;; https://github.com/jeaye/orchestra/issues/43
;;(defn-spec download (s/or :ok ::sp/html :error nil?)
;;  "downloads content at given path if contents of uri not on fs already."
;;  [uri ::sp/uri, & {:keys [message]} (s/map-of keyword? string?)]

(defn download
  [uri & {:keys [message]}]
  (let [cache? (not (nil? cache-dir)) ;; only cache when we have somewhere to cache.
        ;; only the filename is being encoded, not the contents of the download. it's ugly, but safe and reversible.
        cache-key (-> uri .getBytes b64/encode String. (str ".html"))
        cache-dir (fs/file cache-dir (datestamp-now-ymd))
        cache-path (fs/file cache-dir cache-key)] ;; "/path/to/cache/2001-01-01/aHR0[...]cHM6=.html
    (when cache?
      (fs/mkdirs cache-dir))
    (if (and cache? (fs/exists? cache-path))
      (do
        (debug "cache hit: " uri)
        ;;(Thread/sleep 50) ;; simulates a slow download
        (slurp cache-path))

      ;; not caching or cache miss
      (let [_ (when message (info message))
            _ (when cache? (debug "cache miss: " uri))

            anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
            wow-useragent "Wowman/0.1 (https://github.com/ogri-la/wowman)"
            use-anon-useragent? false
            remote-content (client/get uri {:connection-manager conn-manager
                                            :cookie-policy :none ;; Completely ignore cookies
                                            :redirect-strategy :none ;; do not follow redirects
                                            :client-params {"http.useragent" (if use-anon-useragent? anon-useragent wow-useragent)}})
            remote-content (:body remote-content)]

        (when cache?
          (spit cache-path remote-content))
        remote-content))))
