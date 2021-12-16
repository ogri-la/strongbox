(ns strongbox.http
  (:require
   [strongbox
    [joblib :as joblib]
    [logging :as logging]
    [specs :as sp]
    [utils :as utils :refer [join]]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [trptcolin.versioneer.core :as versioneer]
   [clj-http
    [core]
    [client :as client]])
  (:import
   ;; from clj-commons/fs
   [org.apache.commons.io.input CountingInputStream]))

(def expiry-offset-hours 1) ;; hours
(def ^:dynamic *cache* nil)
(def ^:dynamic *default-pause* 1000)
(def ^:dynamic *default-attempts* 3)

(defn simple-cache
  "binds a simplistic getter+setter and `/tmp` to *cache* when caching http requests.
  good for debugging, don't use otherwise."
  []
  {:set-etag (constantly nil)
   :get-etag (constantly nil)
   :cache-dir (fs/tmpdir)})

(defn- add-etag-or-not
  [etag-key req]
  (if-let [;; for some reason this dynamic binding of *cache* to nil results in:
           ;; (strongbox.http/*cache* nil) => NullPointerException
           ;; but not this:
           ;; (nil nil) => CompilerException java.lang.IllegalArgumentException: Can't call nil, form: (nil nil)
           ;; I suspect the devil is in the difference between compilation-time and run-time
           ;;stored-etag ((:get-etag *cache*) etag-key)
           ;; this totally does work though :)
           stored-etag (and *cache* ((:get-etag *cache*) etag-key))]
    (assoc-in req [:headers :if-none-match] stored-etag)
    req))

(defn- write-etag
  [etag-key resp]
  (when-let [etag (and etag-key (-> resp :headers (get "etag")))]
    ;; curseforge/cloudflare are not adding etags to *all* responses, just binaries (I think)
    ;;(debug "got headers" (-> resp :headers)) 
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


;; https://github.com/dakrone/clj-http/blob/3.x/examples/progress_download.clj


(defn wrap-downloaded-bytes-counter-middleware
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [client]
  (fn [req]
    (let [resp (client req)]
      (if (= (:as req) :stream)
        ;; file downloads and clj-fake responses (bytes coerced to input-streams)
        (let [body (:body resp)
              counter (CountingInputStream. (if (utils/byte-array? body)
                                              ;; clj-fake response, probably
                                              (clojure.java.io/input-stream body)
                                              body))]
          (merge resp {:body counter
                       :downloaded-bytes-counter counter}))
        ;; text/html/json downloads
        resp))))

(defn-spec strongbox-user-agent string?
  [strongbox-version string?]
  (let [regex #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})(.*)?"
        ;; => {:major "0" :minor "10" :patch "0" :qualifier "-unreleased"}
        v (zipmap [:major :minor :patch :qualifier] (rest (re-find regex strongbox-version)))]
    (format "strongbox/%s.%s%s (https://github.com/ogri-la/strongbox)" (:major v) (:minor v) (:qualifier v))))

(defn-spec user-agent string?
  [use-anon-useragent? boolean?]
  (let [;; https://techblog.willshouse.com/2012/01/03/most-common-user-agents/ (last updated 2021-07-31)
        anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        useragent (strongbox-user-agent (versioneer/get-version "ogri-la" "strongbox"))]
    (if use-anon-useragent? anon-useragent useragent)))

(defn fresh-cache-file-exists?
  "returns `true` if the last modification time on given file is before the expiry date of +N hours"
  [output-file]
  (when (and output-file (fs/exists? output-file))
    (not (utils/file-older-than output-file expiry-offset-hours))))

(defn-spec url-to-filename ::sp/file
  "safely encode a URI to something that can live cached on the filesystem"
  [url ::sp/url]
  (let [;; strip off any nasty parameters or anchors.
        ;; default to '.html' if there is no extension, it's just decorative
        ext (-> url java.net.URL. .getPath (subs 1) fs/split-ext second (or ".html"))
        enc (java.util.Base64/getUrlEncoder)]
    (as-> url x
      (str x) (.getBytes x) (.encodeToString enc x) (str x ext))))

(defn close-stream
  [ex]
  (when (-> ex ex-data :status)
    ;; "Note that the connection to the server will NOT be closed until the stream has been read"
    ;;  - https://github.com/dakrone/clj-http
    (some-> ex ex-data :body .close)))

(defn-spec -download (s/or :file ::sp/extant-file, :raw :http/resp, :error :http/error)
  "if writing to a file is possible then the output file is returned, else the raw http response.
   writing response body to a file is possible when caching is available or `output-file` provided."
  [^String url ::sp/url, output-file (s/nilable ::sp/file), message (s/nilable ::sp/short-string), extra-params map?]
  (let [cache? (not (nil? *cache*))
        encoded-path (url-to-filename url)
        alt-output-file (when cache?
                          (utils/join (:cache-dir *cache*) encoded-path)) ;; "/path/to/cache/aHR0[...]cHM6=.html"
        output-file (or output-file alt-output-file) ;; `output-file` may still be nil after this!
        etag-key (when cache? (fs/base-name output-file))
        streaming-response? (-> extra-params :as (= :stream))]

    ;; ensures orphaned .etag files don't prevent download of missing files
    (when (and cache?
               ((:get-etag *cache*) etag-key)
               (not (fs/exists? output-file)))
      (debug "orphaned .etag found:" etag-key)
      ((:set-etag *cache*) etag-key)) ;; dissoc etag from db

    ;; use the file on disk if it's not too old ...
    (if (fresh-cache-file-exists? output-file)
      (do
        (debug (format "cache hit for: %s (%s)" url output-file))
        output-file)

      ;; ... otherwise, we must sing and dance
      (try
        (when message (info message)) ;; always show the message that was explicitly passed in
        (debug (format "downloading %s to %s" (fs/base-name url) output-file))
        (client/with-additional-middleware [wrap-downloaded-bytes-counter-middleware
                                            client/wrap-lower-case-headers
                                            (etag-middleware etag-key)]

          (let [;; streaming responses are not buffered entirely in memory as their full length cannot be anticipated.
                ;; instead we open a file handle and pour the response bytes into it as we receive them.
                ;; if the output file already exists (like a catalogue file) it may be possible another thread is reading
                ;; this file leading to malformed/invalid data.
                ;; so we write the incoming bytes to a unique temporary file and then move that file into place, using 
                ;; the intended output file as a lock.
                ^String partial-output-file
                (when output-file
                  (join (fs/parent output-file) (fs/temp-name "strongbox-" ".part")))

                use-anon-useragent? false
                params {:cookie-policy :ignore ;; completely ignore cookies. doesn't stop HttpComponents warning
                        :http-request-config (clj-http.core/request-config
                                              {:normalize-uri false
                                               ;; both of these throw a SocketTimeoutException:
                                               ;; - https://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html
                                               :connection-timeout 5000 ;; allow 5s to connect to host
                                               :socket-timeout 5000 ;; allow 5s stall reading from a host
                                               :connection-request-timeout 5000 ;; allow 5s to receive bytes
                                               })
                        :headers {"User-Agent" (user-agent use-anon-useragent?)}}
                params (merge params extra-params)

                github-request? (.startsWith url "https://api.github.com")
                github-auth-token (System/getenv "GITHUB_TOKEN")

                params (cond-> params
                         (and github-request? github-auth-token)
                         (assoc :basic-auth github-auth-token))

                _ (debug "requesting" url "with params" params)

                resp (client/get url params)
                _ (debug "response status" (:status resp))

                not-modified (= 304 (:status resp)) ;; 304 is "not modified" (local file is still fresh). only happens when caching
                modified (not not-modified)]

            (when (and github-request? github-auth-token)
              (logging/without-addon
               (info (format "%s of %s Github API requests remaining."
                             (-> resp :headers (get "x-ratelimit-remaining"))
                             (-> resp :headers (get "x-ratelimit-limit"))))))

            (when not-modified
              (debug "not modified, contents will be read from cache:" output-file))

            (cond
              ;; remote data has changed, write `:body` bytes to disk
              (and output-file modified) (try
                                           (if-not streaming-response?
                                             ;; doesn't .close on streams
                                             (clojure.java.io/copy (:body resp) (java.io.File. partial-output-file))

                                             ;; count bytes transferred so we can update the job progress (if one exists). taken from:
                                             ;; - https://github.com/dakrone/clj-http/blob/7aa6d02ad83dff9af6217f39e517cde2ded73a25/examples/progress_download.clj
                                             (let [length (-> resp (get-in [:headers "content-length"] 0) utils/to-int)
                                                   buffer-size (* 1024 10)]
                                               (with-open [^java.io.InputStream input (:body resp)
                                                           output (clojure.java.io/output-stream partial-output-file)]
                                                 (let [buffer (make-array Byte/TYPE buffer-size)
                                                       ^CountingInputStream counter (:downloaded-bytes-counter resp)]
                                                   (loop []
                                                     (let [size (.read input buffer)]
                                                       (when (pos? size)
                                                         (.write output buffer 0 size)
                                                         (joblib/tick (joblib/progress length (.getByteCount counter)))
                                                         (recur))))))))

                                           (when streaming-response?
                                             ;; stream responses get written to a file, the stream closed and the path to the output file returned
                                             (-> resp :body .close)) ;; not all requests are streams with a :body that needs to be closed

                                           ;; lock is held for ~0.11 msecs to ~0.18 msecs
                                           ;; much shorter than N seconds to download a file
                                           (locking output-file
                                             (fs/rename partial-output-file output-file))

                                           output-file

                                           (finally
                                             (if (fs/exists? partial-output-file)
                                               (fs/delete partial-output-file))))

              ;; remote data has not changed, :body is nil, replace it with path to file
              (and output-file not-modified) (do
                                               (fs/touch output-file) ;; update modtime
                                               output-file)

              ;; (and (not output-file) modified) ;; standard http request of textual content with caching turned off. return the resp as-is
              ;; (and (not output-file) not-modified) ;; not possible. if-none-match header only added if etag-key found in etag-db. etag-key is derived from output-file

              :else resp)))

        ;; "Signals that a timeout has occurred on a socket read or accept."
        ;; - https://docs.oracle.com/javase/7/docs/api/java/net/SocketTimeoutException.html
        (catch java.net.SocketTimeoutException ste
          (when streaming-response?
            (close-stream ste))
          ;; return a synthetic HTTP error
          (let [request-obj (java.net.URL. url)
                http-error {:status 608 ;; 'Request Timeout'
                            :host (.getHost request-obj)
                            :reason-phrase "Connection timed out"}]
            (warn (format "failed to fetch '%s': connection timed out." url))
            http-error))

        ;; "Signals that an error occurred while attempting to connect a socket to a remote address and port.
        ;; Typically, the connection was refused remotely (e.g., no process is listening on the remote address/port)."
        ;; - https://docs.oracle.com/javase/7/docs/api/java/net/ConnectException.html
        (catch java.net.ConnectException ce
          ;; return a synthetic HTTP error
          (let [request-obj (java.net.URL. url)
                http-error {:status 608 ;; 'Request Timeout'
                            :host (.getHost request-obj)
                            :reason-phrase "Connection timed out"}]
            (warn (format "failed to connect '%s': connection timed out." url))
            http-error))

        (catch java.net.UnknownHostException uhe
          (when streaming-response?
            (close-stream uhe))
          ;; return a synthetic HTTP error
          (let [request-obj (java.net.URL. url)
                http-error {:status 503 ;; 'Service Unavailable'
                            :host (.getHost request-obj)
                            :reason-phrase (str "Unknown host: " (.getHost request-obj))}]
            (warn (format "failed to fetch '%s': unknown host." url))
            http-error))

        (catch Exception ex
          (when streaming-response?
            (close-stream ex))
          (if (-> ex ex-data :status)
            ;; http error (status >=400)
            (let [request-obj (java.net.URL. url)
                  http-error (merge (select-keys (ex-data ex) [:reason-phrase :status])
                                    {:host (.getHost request-obj)})]
              ;; "failed to fetch 'https://api.github.com/foo/bar/baz.json': connection timed out (HTTP 401)"
              (warn (format "failed to fetch '%s': %s (HTTP %s)"
                            url
                            (-> http-error :reason-phrase (utils/safe-subs 150))
                            (:status http-error)))

              http-error)

            ;; this is an unhandled exception
            (throw ex)))))))

(defn http-error?
  "returns `true` if the http response code is either a client error (4xx) or a server error (5xx)"
  [http-resp]
  (and (map? http-resp)
       (> (:status http-resp) 399)))

(defn http-server-error?
  "returns `true` if the http response code is a server error (5xx)"
  [http-resp]
  (and (map? http-resp)
       (> (:status http-resp) 499)))

(defn-spec http-error string?
  "returns an error specific to code and host or just a more helpful http error message"
  [http-err :http/error]
  (let [key (-> http-err (select-keys [:host :status]) vals set)
        bin-pred (fn [case-set key-set]
                   ;; `case-set` => #{"api.github.com 404}
                   ;; `key-set`  => #{"example.org 400}
                   (= (clojure.set/intersection case-set key-set) case-set))]
    (condp bin-pred key
      #{"raw.githubusercontent.com" 500} "Github: service is down. Check www.githubstatus.com and try again later."

      ;; github api quota exceeded OR github thinks we were making requests too quickly
      #{"api.github.com" 403} "Github: we've exceeded our request quota and have been blocked for an hour."
      #{"api.github.com" 500} "Github: api is down. Check www.githubstatus.com and try again later."

      ;; issue 91, CDN problems
      #{"addons-ecs.forgesvc.net" 404} "Curseforge: the API can't find that addon (can be temporary or permanent). Try visiting the addon on Curseforge."
      #{"addons-ecs.forgesvc.net" 502} "Curseforge: the API is having problems right now. Try again later."
      #{"addons-ecs.forgesvc.net" 504} "Curseforge: the API is having problems right now. Try again later."

      #{403} "Forbidden: we've been blocked from accessing that (403)."
      #{503} "Not found: the host is down (unlikely) or your connection to the internet is down (more likely)."

      (:reason-phrase http-err))))

(defn sink-error
  "given a http response, if response was unsuccessful, emit warning/error message and return nil,
  else return response."
  [http-resp]
  (if-not (http-error? http-resp)
    ;; no error, pass response through
    http-resp
    ;; otherwise, scream and yell and return nil
    (error (http-error http-resp))))

(defn-spec download (s/or :ok-file ::sp/extant-file, :ok-body string?, :error :http/error)
  "downloads the given `url` assuming a textual response, returning the body as a simple string.
  on http error, an error map with details is returned.
  an optional `message` can be supplied as the second argument that will be displayed on a cache miss."
  ([url ::sp/url]
   (download url nil))
  ([url ::sp/url, message (s/nilable ::sp/short-string)]
   (let [output-file nil
         resp (-download url output-file message {})]
     (cond
       (http-error? resp) resp ;; error http response
       (map? resp) (:body resp) ;; regular http response + caching disabled
       (fs/file? resp) (slurp resp))))) ;; file on disk + caching enabled

(defn-spec download-file (s/or :file ::sp/extant-file, :error :http/error)
  "downloads the given `url` to the given `output-file`, assuming a bytestream response.
  returns the path to the file on success.
  on http error, an error map with details is returned.
  an optional `message` can be supplied as the second argument that will be displayed on a cache miss."
  ([url ::sp/url, output-file ::sp/file]
   (download-file url output-file nil))
  ([url ::sp/url, output-file ::sp/file, message (s/nilable ::sp/short-string)]
   (let [resp (-download url output-file message {:as :stream})]
     (if-not (http-error? resp)
       output-file
       resp))))

;;

(defn-spec download-with-backoff (s/or :ok-file ::sp/extant-file, :ok-body string?, :error :http/error)
  "wrapper around `download` that will pause and retry a download several times with an exponentially increasing duration between each attemp"
  ([url ::sp/url]
   (download-with-backoff url nil))
  ([url ::sp/url, message (s/nilable ::sp/short-string)]
   (loop [attempt 1
          pause *default-pause*]
     (let [result (try
                    (when (> attempt 1)
                      (warn (format "trying again (attempt %s of 3)" attempt)))
                    (download url message)
                    (catch Exception e
                      e))]
       (if (or (instance? Exception result)
               (http-server-error? result))
         (if (= attempt *default-attempts*)
           ;; tried three times and failed three times. raise the exception or return the error.
           (if (instance? Exception result)
             (throw result)
             result)
           ;; try again after a pause
           (do (Thread/sleep pause)
               (recur (inc attempt) (* pause 2))))
         result)))))

(defmacro with-simple-cache
  "executes the body form with results cached in `/tmp`.
  just like `simple-cache`, don't use outside of debugging."
  [& form]
  `(binding [*cache* (simple-cache)]
     ~@form))

;;

(defn-spec prune-cache-dir nil?
  "deletes files in the given `cache-dir` that are older than the `expiry-offset-hours`"
  [cache-dir ::sp/extant-dir]
  (let [expiry-date (* 2 expiry-offset-hours)]
    (doseq [cache-file (fs/list-dir cache-dir)
            :when (and (fs/file? cache-file)
                       (utils/file-older-than (str cache-file) expiry-date))]
      (fs/delete cache-file)
      (debug "deleted expired cache file:" cache-file))))

