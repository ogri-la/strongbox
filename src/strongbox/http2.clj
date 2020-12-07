(ns strongbox.http2
  (:require
   [strongbox
    [http :as http1]
    [specs :as sp]
    [utils :as utils :refer [join]]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clj-http.fake :refer [with-fake-routes]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [orchestra.core :refer [defn-spec]]
   [trptcolin.versioneer.core :as versioneer]
   [clj-http.client :as client])
  (:import
   (java.nio.charset StandardCharsets)))

;; todo: revisit this value
(def expiry-offset-hours 24) ;; hours
(def ^:dynamic *cache* nil)

;;

(defn slurp-bytes
  "Read all bytes from the stream.
  Use for example when the bytes will be in demand after stream has been closed."
  [stream]
  (.getBytes (slurp stream) StandardCharsets/UTF_8))

(defn-spec streaming? boolean?
  [req :http/req]
  ;; https://github.com/dakrone/clj-http/blob/3.x/src/clj_http/client.clj#L423
  (-> req :as (= :stream)))

;;

(defn-spec file-is-fresh? boolean?
  "returns `true` if the last modification time on given file is before the expiry date of +N hours"
  [output-path ::sp/extant-file, age-in-hours pos-int?]
  (when (and output-path
             (fs/exists? output-path))
    (not (utils/file-older-than output-path age-in-hours))))

(defn-spec url-to-filename ::sp/file
  "safely encode a URI to something that can live cached on the filesystem"
  [url ::sp/url]
  (let [;; strip off any nasty parameters or anchors.
        ;; default to '.html' if there is no extension, it's just decorative
        ext (-> url java.net.URL. .getPath (subs 1) fs/split-ext second (or ".cache"))
        enc (java.util.Base64/getUrlEncoder)]
    (as-> url x
      (str x) (.getBytes x) (.encodeToString enc x) (str x ext))))

(defn cache-path-for-req
  "given a request map with an optional `:output-path` key,
  returns a predictable path that can be used to write a response's `:body`."
  [req]
  (when *cache*
    (let [default-output-file (url-to-filename (:url req))
          ;; "/path/to/cache/aHR0[...]cHM6=.html"
          default-output-path (utils/join (:cache-dir *cache*) default-output-file)
          explicit-output-path (:output-path req)]
      (or explicit-output-path default-output-path))))

(defn write-body-to-file
  [body output-path]
  ;; doesn't .close on streams
  (clojure.java.io/copy body (java.io.File. output-path)))

;;

(defn etag-middleware
  "attaches etags to requests and writes the responses to the etag database afterwards.
  depends on either `file-cache-middleware` to write response to a file,
  or `streaming-middleware` to write a file to disk."
  [client]
  (fn [req]
    (if-not *cache*
      ;; if we have no cache, we have nowhere to store the etag database or
      ;; it's references to cached files ...
      (client req)

      (let [output-path (cache-path-for-req req)
            etag-key output-path ;; not the etag itself
            stored-etag ((:get-etag *cache*) etag-key)

            ;; ensures orphaned .etag files don't prevent download of missing files
            _ (when (and stored-etag
                         (not (fs/exists? output-path)))
                (debug "orphaned .etag found:" etag-key)
                ((:set-etag *cache*) etag-key)) ;; dissoc etag from db
            
            req (if (and stored-etag
                         (fs/exists? output-path))
                  (assoc-in req [:headers :if-none-match] stored-etag)
                  req)
            resp (client req)]
        
        ;; if there is an etag in the response, preserve it.
        (when-let [etag (-> resp :headers (get "etag"))]
          ((:set-etag *cache*) etag-key etag))

        ;; if remote data is unmodified, read the file from cache
        (if (= 304 (:status resp))
          (merge resp {;;:status 200
                      :body (utils/file-to-lazy-byte-array output-path)
                      :output-path output-path})

          ;; modified, continue on
          resp)))))         

;;

(defn file-cache-middleware
  [client]
  (fn [req]
    (if (or
         (not *cache*)
         (streaming? req))
      ;; caching not configured so nowhere to write file OR we're streaming binary data, skip caching.
      (client req)

      (let [output-path (cache-path-for-req req)]
        (if (and
             (fs/exists? output-path)
             ;; todo: ignore freshness check when `:output-path` has been specified explicitly?
             (file-is-fresh? output-path expiry-offset-hours))

          ;; cached response present, use that.
          ;; fake the request so we get something vaguely response-like
          (with-fake-routes {(:url req) {:get (fn [req] {:status 200
                                                         :body (slurp output-path)
                                                         :output-path output-path})}}
            (client req))

          ;; no file on disk, make request
          (let [resp (client req)]
            (if (= 200 (:status resp))
              ;; successful response, write to output file
              (let [;; decompression happens transparently ordinarily, but if we slurp the body
                    ;; and don't update the headers we get Weirdness from the other middleware.
                    ;; - https://github.com/dakrone/clj-http/blob/d2f523dec05c0a85ef4cb21c7821c7e37e437e07/src/clj_http/client.clj#L374
                    resp (client/decompress-body resp)
                    ;; reads the bytestream preserving the bytes read so we can also write them to a file.
                    body (slurp-bytes (:body resp))]
                (write-body-to-file body output-path)
                (merge resp {:output-path output-path
                             :body body}))

              ;; unsuccessful response, return as-is
              resp)))))))

;;

(defn close-connection
  [resp]
  (-> resp :body .close))

(defn streaming-middleware
  "stream the body response to a temporary file and then cleans up afterwards."
  [client]
  (fn [req]
    (if (or
         (not (streaming? req))
         (not (:output-path req)))
      ;; not a streaming response, OR,
      ;; it's a streaming response but the code is handling the bytestream.
      (client req)

      ;; stream the response body to a temporary file with a `.part` extension.
      ;; lock on the anticipated output filename,
      ;; write file, clean up, close stream, etc.
      (let [resp (client req)
            output-path (:output-path req)
            output-dir (fs/parent output-path)
            partial-output-path (join output-dir (fs/temp-name "strongbox-" ".part"))]

        (if-not (= 200 (:status resp))
          ;; unsuccessful request, no file to download
          resp
        
          (try
            (write-body-to-file (:body resp) partial-output-path)
            (close-connection resp)

            (locking output-path
              ;; todo: does this overwrite?
              (fs/rename partial-output-path output-path))
            (-> resp
                (assoc :output-path output-path)
                ;; body was a stream of bytes and has been written to a file.
                ;; you don't have access to it anymore except to read from file.
                (assoc :body nil))

            (catch Exception uncaught-exception
              (error uncaught-exception "uncaught error downloading file")
              (close-connection (some-> uncaught-exception ex-data))
              (throw uncaught-exception))
            
            (finally
              (when (fs/exists? partial-output-path)
                (fs/delete partial-output-path)))))))))

;;


(defn-spec -download (s/or :ok :http/resp, :error :http/error)
  [url ::sp/url, output-path (s/nilable ::sp/file), extra-params map?]
  (try
    (client/with-additional-middleware
      [client/wrap-lower-case-headers
       file-cache-middleware
       etag-middleware
       streaming-middleware]
       
            
      (let [;; completely ignore cookies. doesn't stop HttpComponents warning
            params {:cookie-policy :ignore}
            output-path (when output-path {:output-path output-path})
            use-anon-useragent? false
            params (merge params
                          (http1/user-agent use-anon-useragent?)
                          output-path
                          extra-params)]
        
        (client/get url params)))
    
    (catch Exception ex
      (if (-> ex ex-data :status)
        ;; http error (status >=400)
        (let [request-obj (java.net.URL. url)
              http-error (merge (select-keys (ex-data ex) [:reason-phrase :status])
                                {:host (.getHost request-obj)})]
          (warn (format "failed to download file '%s': %s (HTTP %s)"
                        url
                        (-> http-error :reason-phrase (utils/safe-subs 150))
                        (:status http-error)))
          http-error)

        ;; unhandled non-http exception
        (throw ex)))))

(defn-spec download (s/or :ok string?, :error :http/error)
  "downloads the given `url` assuming a textual response, returning the body as a simple string.
  on http error, an error map with details is returned."
  [url ::sp/url]
  (let [resp (-download url nil {})]
    (if (http1/http-error? resp)
      resp ;; pass errors through
      (:body resp))))

(defn-spec download-file (s/or :ok ::sp/extant-file, :error :http/error)
  "downloads the given `url` to a file, returning the path to the file on success.
  on http error, an error map with details is returned."
  [url ::sp/url, output-path ::sp/file]
  (let [resp (-download url output-path {:as :stream})]
    (if (http1/http-error? resp)
      ;; pass errors through
      resp 
      ;; custom key added to response by streaming/etag middleware.
      (:output-path resp))))
