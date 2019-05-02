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

(def ^:dynamic *cache-dir* nil)

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
    (fs/mkdirs (fs/parent etag-path)) ;; when the clock ticks over and the app hasn't been restarted ...
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

(defn-spec user-agent map?
  [use-anon-useragent? boolean?]
  (let [anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
        wowman-version (-> (versioneer/get-version "ogri-la" "wowman") (subs 0 3)) ;; `subs` here is fine until major or minor exceeds double digits
        wowman-useragent (format "Wowman/%s (https://github.com/ogri-la/wowman)" wowman-version)]
    {"http.useragent" (if use-anon-useragent? anon-useragent wowman-useragent)}))

;; disabled until I figure out how to spec the parameters:
;;(defn-spec download-file (s/or :ok ::sp/extant-file, :http-error ::sp/http-error)
;;  [uri ::sp/uri, output-file ::sp/file, & {:keys [overwrite?]} (s/map-of keyword? any?)]
(defn download-file
  [uri output-file & {:keys [overwrite?]}]
  (let [;; we can have local file based caching or we can rely on etags for fresh data
        cache? (not (nil? *cache-dir*))
        etag-path (when cache?
                    (join *cache-dir* (-> output-file fs/base-name (str ".etag"))))]

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
        ;;(Thread/sleep 50) ;; simulates a slow download
        output-file)

      ;; we're overwriting files. still a chance nothing is downloaded or overwritten
      (try
        (debug "cache miss for" output-file)
        (info (format "downloading %s to %s" uri output-file))
        (client/with-additional-middleware [client/wrap-lower-case-headers (etag-middleware etag-path)]
          (let [params {:as :stream
                        :redirect-strategy curse-crap-redirect-strategy}
                use-anon-useragent? false
                params (merge params (user-agent use-anon-useragent?))
                resp (client/get uri params)]

            (when-not (= 304 (:status resp)) ;; 'when-not not-modified' or just 'when modified'
              (clojure.java.io/copy (:body resp) (java.io.File. output-file)))
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
  (let [cache? (not (nil? *cache-dir*)) ;; only cache when we have somewhere to cache.
        ;; only the filename is being encoded, not the contents of the download. it's ugly, but safe and reversible.
        cache-key (-> uri .getBytes b64/encode String. (str ".html"))
        cache-path (fs/file *cache-dir* cache-key)] ;; "/path/to/cache/aHR0[...]cHM6=.html"

    ;; it's part of `core/init-dirs`, but I like `download` available regardless of whether app has started
    (when cache?
      (fs/mkdirs *cache-dir*))

    (if (and cache? (fs/exists? cache-path))
      (do
        (debug "cache hit: " uri)
        ;;(Thread/sleep 50) ;; simulates a slow download
        (slurp cache-path))

      ;; not caching or cache miss
      (let [_ (when message (info message))
            _ (when cache? (debug "cache miss: " uri))
            params {:connection-manager conn-manager
                    :cookie-policy :none ;; Completely ignore cookies
                    :redirect-strategy :none} ;; do not follow redirects
            use-anon-useragent? false
            params (merge params (user-agent use-anon-useragent?))
            remote-content (client/get uri params)
            remote-content (:body remote-content)]

        (when cache?
          (spit cache-path remote-content))
        remote-content))))

(defn-spec prune-html-download-cache nil?
  [cache-dir ::sp/extant-dir]
  (let [todays-cache-dir (utils/datestamp-now-ymd)
        all-cache-dirs (fs/find-files cache-dir #"\d{4}\-\d{2}\-\d{2}")
        all-except-today (remove #(clojure.string/ends-with? (fs/base-name %) todays-cache-dir) all-cache-dirs)]
    (doseq [cache-dir all-except-today]
      (warn "deleting cache dir " cache-dir)
      (fs/delete-dir cache-dir))))

(st/instrument)
