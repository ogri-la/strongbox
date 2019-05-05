(ns wowman.http
  (:require
   [wowman
    [specs :as sp]
    [utils :as utils :refer [join]]]
   [clojure.string]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clj-time
    [core :as t]
    [coerce :as coerce-time]]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [clojure.data.codec.base64 :as b64]
   [taoensso.timbre :refer [debug info warn error spy]]
   [trptcolin.versioneer.core :as versioneer]
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
  [etag-key req]
  (if-let [stored-etag (-> *cache* :etag-db (get etag-key))]
    (assoc-in req [:headers :if-none-match] stored-etag)
    req))

(defn- write-etag
  [etag-key resp]
  (when-let [etag (and etag-key (-> resp :headers (get "etag")))]
    ;;(debug "got headers" (-> resp :headers)) ;; cloudflare isn't adding etags to *all* responses, just binaries I think
    ((:set-etag *cache*) etag-key etag))
  resp)

(defn etag-middleware
  [etag-key]
  (fn [client]
    (fn
      ([req]
       (write-etag etag-key (client (add-etag-or-not etag-key req))))
      ([req resp raise]
       (write-etag etag-key (client (add-etag-or-not etag-key req) resp raise))))))

(defn-spec user-agent map?
  [use-anon-useragent? boolean?]
  (let [anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
        wowman-version (-> (versioneer/get-version "ogri-la" "wowman") (subs 0 3)) ;; `subs` here is fine until major or minor exceeds double digits
        wowman-useragent (format "Wowman/%s (https://github.com/ogri-la/wowman)" wowman-version)]
    {"http.useragent" (if use-anon-useragent? anon-useragent wowman-useragent)}))

(defn fresh-cache-file-exists?
  "returns `true` if the last modification time on given file is before the expiry date of +N hours"
  [output-file]
  (when (and output-file (fs/exists? output-file))
    (let [modtime (coerce-time/from-long (fs/mod-time output-file))
          now (t/now)
          expiry-offset (t/hours 24)
          expiry-date (t/plus modtime expiry-offset)
          expired? (t/before? expiry-date now)]
      (debug (format "modtime %s; expiry-offset %s; expiry-date %s; now %s; expired? %s"
                     modtime expiry-offset expiry-date now expired?))
      (not expired?))))

(defn-spec -download (s/or :file ::sp/extant-file, :raw ::sp/http-resp, :error ::sp/http-error)
  "if writing to a file is possible, then the output file will be returned, otherwise the raw http response
   writing to a file is possible when caching is available or an `output-file` is given to us."
  [uri ::sp/uri, output-file (s/nilable ::sp/file), message (s/nilable ::sp/short-string), extra-params map?]
  (let [cache? (not (nil? *cache*))
        ext (-> uri fs/split-ext second (or ".html")) ;; *probably* html, we don't particularly care
        encoded-path (-> uri .getBytes b64/encode String. (str ext))
        alt-output-file (when cache?
                          (utils/join (:cache-dir *cache*) encoded-path)) ;; "/path/to/cache/aHR0[...]cHM6=.html"
        output-file (or output-file alt-output-file) ;; `output-file` may still be nil after this!

        etag-key (when cache? (fs/base-name output-file))
        streaming-response? (-> extra-params :as (= :stream))]

    ;; ensures orphaned .etag files don't prevent download of missing files
    (when (and cache?
               (-> *cache* :etag-db (get etag-key))
               (not (fs/exists? output-file)))
      (warn "orphaned .etag found:" etag-key)
      ((:set-etag *cache*) etag-key)) ;; dissoc etag from db

    ;; use the file on disk if it's not too old ...
    (if (fresh-cache-file-exists? output-file)
      output-file

      ;; ... otherwise, we must sing and dance
      (try
        (info (or message (format "downloading %s to %s" (fs/base-name uri) output-file)))
        (client/with-additional-middleware [client/wrap-lower-case-headers (etag-middleware etag-key)]
          (let [params {:redirect-strategy curse-crap-redirect-strategy}
                use-anon-useragent? false
                params (merge params (user-agent use-anon-useragent?) extra-params)
                _ (debug "requesting" uri "with params" params)
                resp (client/get uri params)
                _ (debug "response status" (:status resp))

                not-modified (= 304 (:status resp)) ;; 304 is "not modified" (local file is still fresh). only happens when caching
                modified (not not-modified)]

            (when not-modified
              (debug "not modified, contents will be read from cache:" output-file))

            (cond
              ;; remote data has changed, write body to disk
              (and output-file modified) (do
                                           (clojure.java.io/copy (:body resp) (java.io.File. output-file)) ;; doesn't .close on streams
                                           (when streaming-response?
                                             ;; stream request responses get written to a file, the stream closed and the path to the output file returned
                                             (-> resp :body .close)) ;; not all requests are streams with a :body that needs to be closed
                                           output-file)

              ;; remote data has not changed, :body is nil, replace it with path to file
              (and output-file not-modified) (do
                                               (fs/touch output-file) ;; update modtime
                                               output-file)

              ;; (and (not output-file) modified) ;; standard http request of textual content with caching turned off. return the resp as-is
              ;; (and (not output-file) not-modified) ;; not possible. if-none-match header only added if etag-key found in etag-db. etag-key is derived from output-file

              :else resp)))

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
            (throw ex)))))))

(defn http-error?
  [http-resp]
  (and (map? http-resp)
       (<= 400 (:status http-resp))))

;;(defn-spec download (s/or :ok ::sp/http-resp, :error ::sp/http-error)
;;  [uri ::sp/uri, message ::sp/short-string]
(defn download
  [uri & {:keys [message]}]
  (let [output-file nil
        resp (-download uri output-file message {})]
    (cond
      (http-error? resp) resp ;; error http response
      (map? resp) (:body resp) ;; regular http response + caching disabled
      (fs/file? resp) (slurp resp)))) ;; file on disk + caching enabled

;;(defn-spec download-file (s/or :file ::sp/extant-file, :error ::sp/http-error)
;;  [uri ::sp/uri, output-file ::sp/file, & {:keys [message]}]
(defn download-file
  [uri, output-file & {:keys [message]}]
  (let [resp (-download uri output-file message {:as :stream})]
    (if-not (http-error? resp)
      output-file
      resp)))

(defn-spec prune-html-download-cache nil?
  "deprecated, will be removed in 0.8.0.
   removes directories in the :cache-dir that match a year-month-day pattern"
  [cache-dir ::sp/extant-dir]
  (let [todays-cache-dir (utils/datestamp-now-ymd)
        all-cache-dirs (fs/find-files cache-dir #"\d{4}\-\d{2}\-\d{2}")]
    (doseq [cache-dir all-cache-dirs]
      (warn "deleting cache dir " cache-dir)
      (fs/delete-dir cache-dir))))

(st/instrument)
