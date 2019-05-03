(ns wowman.http
  (:require
   [wowman
    [specs :as sp]
    [utils :as utils :refer [join]]]
   [clojure.string]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [clojure.data.codec.base64 :as b64]
   [taoensso.timbre :refer [debug info warn error spy]]
   [trptcolin.versioneer.core :as versioneer]
   [clj-http.conn-mgr]
   [clj-http.client :as client]))

(def ^:dynamic *cache* nil)

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
  (if-let [etag (-> *cache* :etag-db (get etag-path))]
    (assoc-in req [:headers :if-none-match] etag)
    req))

(defn- write-etag
  [etag-path resp]
  (when etag-path
    ((:set-etag *cache*) etag-path (-> resp :headers (get "etag"))))
  resp)

(defn etag-middleware
  [etag-path]
  (fn [client]
    (fn
      ([req]
       (write-etag etag-path (client (add-etag-or-not etag-path req))))
      ([req resp raise]
       (write-etag etag-path (client (add-etag-or-not etag-path req) resp raise))))))

(defn-spec user-agent map?
  [use-anon-useragent? boolean?]
  (let [anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
        wowman-version (-> (versioneer/get-version "ogri-la" "wowman") (subs 0 3)) ;; `subs` here is fine until major or minor exceeds double digits
        wowman-useragent (format "Wowman/%s (https://github.com/ogri-la/wowman)" wowman-version)]
    {"http.useragent" (if use-anon-useragent? anon-useragent wowman-useragent)}))

(defn-spec -download (s/or :file ::sp/extant-file, :raw ::sp/http-resp, :error ::sp/http-error)
  [uri ::sp/uri, output-file (s/nilable ::sp/file), message (s/nilable ::sp/short-string), extra-params map?]
   (let [cache? (not (nil? *cache*))
         
         ;; if we're caching and no output-file has been given, create an output path
         ;; it lasts for N hours and is cleaned up on restart
         ext (-> uri fs/split-ext second (or ".?"))
         encoded-path (-> uri .getBytes b64/encode String. (str ext))
         alt-output-file (when cache?
                           (utils/join (:cache-dir *cache*) encoded-path)) ;; "/path/to/cache/aHR0[...]cHM6=.html"

         ;; if output file not provided and caching is enabled, use a generated one. if caching not enabled, don't write anything at all.
         ;; `output-file` may still be `nil` after this.
         output-file (or output-file alt-output-file)

         etag-path (when cache?
                     (-> output-file fs/base-name (str ".etag")))]

     ;; ensures orphaned .etag files don't prevent download of missing files
     (when (and cache?
                (-> *cache* :etag-db (get etag-path))
                (not (fs/exists? output-file)))
       (warn "orphaned .etag found:" etag-path)
       ((:set-etag *cache*) etag-path)) ;; dissoc etag from db

     (try
       (info (or message (format "downloading %s to %s" (fs/base-name uri) output-file)))
       (client/with-additional-middleware [client/wrap-lower-case-headers (etag-middleware etag-path)]
         (let [params {:redirect-strategy curse-crap-redirect-strategy}
               use-anon-useragent? false
               params (merge params (user-agent use-anon-useragent?) extra-params)
               resp (client/get uri params)
               modified? (not (= 304 (:status resp)))] ;; 304 is "not modified" (local file is still fresh)
           
           (when (and output-file
                      modified?) ;; data has changed, write body to disk
             (info "writing" output-file)
             ;; closes :body
             ;;(spit output-file (:body resp))
             ;; doesn't close :body
             (clojure.java.io/copy (:body resp) (java.io.File. output-file)))

           resp))

       (catch Exception ex
         (if (-> ex ex-data :status)
           ;; "Note that the connection to the server will NOT be closed until the stream has been read"
           ;;  - https://github.com/dakrone/clj-http
           (let [_ (some-> ex ex-data :body .close)
                 http-error (select-keys (ex-data ex) [:reason-phrase :status])]
             (error (format "failed to download file '%s': %s (HTTP %s)"
                            uri (:reason-phrase http-error) (:status http-error)))
             http-error)

           ;; unhandled non-http exception
           (throw ex))))))

(defn-spec http-error? boolean?
  [http-resp ::sp/http-resp]
  (<= 400 (:status http-resp)))

;;(defn-spec download (s/or :ok ::sp/http-resp, :error ::sp/http-error)
;;  [uri ::sp/uri, message ::sp/short-string]
(defn download
  [uri & {:keys [message]}]
  (let [output-file nil
        resp (-download uri output-file message {})]
    (if-not (http-error? resp)
      (:body resp)
      resp)))

;;(defn-spec download-file (s/or :file ::sp/extant-file, :error ::sp/http-error)
;;  [uri ::sp/uri, output-file ::sp/file, & {:keys [message]}]
(defn download-file
  [uri, output-file & {:keys [message]}]
  (let [resp (-download uri output-file message {:as :stream})]
    (if-not (http-error? resp)
      output-file
      resp)))

(defn-spec prune-html-download-cache nil?
  [cache-dir ::sp/extant-dir]
  (let [todays-cache-dir (utils/datestamp-now-ymd)
        all-cache-dirs (fs/find-files cache-dir #"\d{4}\-\d{2}\-\d{2}")
        all-except-today (remove #(clojure.string/ends-with? (fs/base-name %) todays-cache-dir) all-cache-dirs)]
    (doseq [cache-dir all-except-today]
      (warn "deleting cache dir " cache-dir)
      (fs/delete-dir cache-dir))))

(st/instrument)
