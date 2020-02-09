(ns strongbox.http
  (:require
   [strongbox
    [specs :as sp]
    [utils :as utils :refer [join]]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [trptcolin.versioneer.core :as versioneer]
   [clj-http.client :as client]))

(def expiry-offset-hours 24) ;; hours
(def ^:dynamic *cache* nil)

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

(defn-spec strongbox-user-agent string?
  [strongbox-version string?]
  (let [regex #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})(.*)?"
        ;; => {:major "0" :minor "10" :patch "0" :qualifier "-unreleased"}
        v (zipmap [:major :minor :patch :qualifier] (rest (re-find regex strongbox-version)))]
    (format "Wowman/%s.%s%s (https://github.com/ogri-la/strongbox)" (:major v) (:minor v) (:qualifier v))))

(defn-spec user-agent map?
  [use-anon-useragent? boolean?]
  (let [;; https://techblog.willshouse.com/2012/01/03/most-common-user-agents/ (last updated 2019-09-14)
        anon-useragent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36"
        useragent (strongbox-user-agent (versioneer/get-version "ogri-la" "strongbox"))]
    {"http.useragent" (if use-anon-useragent? anon-useragent useragent)}))

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

(defn-spec -download (s/or :file ::sp/extant-file, :raw ::sp/http-resp, :error ::sp/http-error)
  "if writing to a file is possible then the output file is returned, else the raw http response.
   writing response body to a file is possible when caching is available or `output-file` provided."
  [url ::sp/url, output-file (s/nilable ::sp/file), message (s/nilable ::sp/short-string), extra-params map?]
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
      (warn "orphaned .etag found:" etag-key)
      ((:set-etag *cache*) etag-key)) ;; dissoc etag from db

    ;; use the file on disk if it's not too old ...
    (if (fresh-cache-file-exists? output-file)
      (do
        (debug "cache hit for:" url "(" output-file ")")
        output-file)

      ;; ... otherwise, we must sing and dance
      (try
        (when message (info message)) ;; always show the message that was explicitly passed in
        (debug (format "downloading %s to %s" (fs/base-name url) output-file))
        (client/with-additional-middleware [client/wrap-lower-case-headers (etag-middleware etag-key)]
          (let [params {:cookie-policy :ignore} ;; completely ignore cookies. doesn't stop HttpComponents warning
                use-anon-useragent? false
                params (merge params (user-agent use-anon-useragent?) extra-params)
                _ (debug "requesting" url "with params" params)
                resp (client/get url params)
                _ (debug "response status" (:status resp))

                not-modified (= 304 (:status resp)) ;; 304 is "not modified" (local file is still fresh). only happens when caching
                modified (not not-modified)

                ;; streaming responses are not buffered entirely in memory as their full length cannot be anticipated.
                ;; instead we open a file handle and pour the response bytes into it as we receive them.
                ;; if the output file already exists (like a catalogue file) it may be possible another thread is reading
                ;; this file leading to malformed/invalid data.
                ;; so we write the incoming bytes to a unique temporary file and then move that file into place, using 
                ;; the intended output file as a lock.
                partial-output-file (when output-file
                                      (join (fs/parent output-file) (fs/temp-name "strongbox-" ".part")))]

            (when not-modified
              (debug "not modified, contents will be read from cache:" output-file))

            (cond
              ;; remote data has changed, write body to disk
              (and output-file modified) (try
                                           (clojure.java.io/copy (:body resp) (java.io.File. partial-output-file)) ;; doesn't .close on streams
                                           (when streaming-response?
                                             ;; stream request responses get written to a file, the stream closed and the path to the output file returned
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

        (catch Exception ex
          (if (-> ex ex-data :status)
            ;; http error (status >=400)
            (let [_ (when streaming-response?
                      ;; "Note that the connection to the server will NOT be closed until the stream has been read"
                      ;;  - https://github.com/dakrone/clj-http
                      (some-> ex ex-data :body .close))
                  request-obj (java.net.URL. url)
                  http-error (merge (select-keys (ex-data ex) [:reason-phrase :status])
                                    {:host (.getHost request-obj)})]
              (warn (format "failed to download file '%s': %s (HTTP %s)"
                            url (:reason-phrase http-error) (:status http-error)))

              http-error)

            ;; unhandled non-http exception
            (throw ex)))))))

(defn http-error?
  [http-resp]
  (and (map? http-resp)
       (<= 400 (:status http-resp))))

(defn-spec http-error string?
  "returns an error specific to code and host"
  [http-err ::sp/http-error]
  (let [key (-> http-err (select-keys [:host :status]) vals set)]
    (condp (comp clojure.set/intersection =) key
      ;; github api quota exceeded OR github thinks we were making requests too quickly
      #{"api.github.com" 403} "Github: we've exceeded our request quota and have been blocked for an hour."

      ;; issue 91, CDN problems 
      #{"addons-ecs.forgesvc.net" 502} "Curseforge: the API is having problems right now (502). Try again later."
      #{"addons-ecs.forgesvc.net" 504} "Curseforge: the API is habing problems right now (504). Trye again later."

      #{403} "Forbidden: we've been blocked from accessing that (403)"

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

;;(defn-spec download (s/or :ok ::sp/http-resp, :error ::sp/http-error)
;;  [url ::sp/url, message ::sp/short-string]
(defn download
  [url & {:keys [message]}]
  (let [output-file nil
        resp (-download url output-file message {})]
    (cond
      (http-error? resp) resp ;; error http response
      (map? resp) (:body resp) ;; regular http response + caching disabled
      (fs/file? resp) (slurp resp)))) ;; file on disk + caching enabled

;;(defn-spec download-file (s/or :file ::sp/extant-file, :error ::sp/http-error)
;;  [url ::sp/url, output-file ::sp/file, & {:keys [message]}]
(defn download-file
  [url, output-file, & {:keys [message]}]
  (let [resp (-download url output-file message {:as :stream})]
    (if-not (http-error? resp)
      output-file
      resp)))

;; deprecated, to be removed in 0.10.0
;; both curseforge and wowinterface catalogs are needed to scrape their respective updates
;; settle for some cache misses as we delete these files on startup then download them again for a scrape?
;; becomes less relevant as scrapes become automated
(defn-spec prune-old-curseforge-files nil?
  "curseforge.json may be hanging around in the cache-dir or in the parent (:data-dir)"
  [cache-dir ::sp/extant-dir]
  (let [cache-cf-file (join cache-dir "curseforge.json")
        data-cf-file (join (fs/parent cache-dir) "curseforge.json")]
    (when (fs/exists? cache-cf-file)
      (fs/delete cache-cf-file))
    (when (fs/exists? data-cf-file)
      (fs/delete data-cf-file)))
  nil)

(defn-spec prune-cache-dir nil?
  [cache-dir ::sp/extant-dir]
  ;;(prune-old-curseforge-files cache-dir) ;; this is problematic when generating the curseforge catalogue
  (doseq [cache-file (fs/list-dir cache-dir)
          :when (and (fs/file? cache-file)
                     (utils/file-older-than (str cache-file) (* 2 expiry-offset-hours)))]
    (warn "deleting cached file (expired):" cache-file)
    (fs/delete cache-file)))

(st/instrument)
