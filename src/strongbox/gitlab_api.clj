(ns strongbox.gitlab-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :refer [ends-with? lower-case]]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [toc :as toc]
    [http :as http]
    [utils :as utils :refer [select-keys*]]
    [specs :as sp]])
  (:import
   [java.util Base64]))

(defn api-url
  [source-id]
  (let [encoded-source-id (-> source-id clojure.string/lower-case (java.net.URLEncoder/encode "UTF-8"))]
    (str "https://gitlab.com/api/v4/projects/" encoded-source-id)))

(defn-spec parse-release (s/or :ok :addon/release-list, :error nil?)
  "parses the list of 'links' in a gitlab release.
  like github, we can't use the automatically generated bundles because the foldername inside the ZIP looks
  like `Project-version` (AdiBags-v1.2.3) and may make use of templates that need rendering/processing before being
  uploaded as a proper asset ('link')."
  [release map?, game-track-list ::sp/game-track-list]
  (let [release-game-track (utils/guess-game-track (:name release))
        supported-link-types #{"package" "other"} ;; the others are 'runbook' and 'image'
        link-release (fn [link]
                       (let [link-game-track (utils/guess-game-track (:name link))
                             game-track-list (into game-track-list [release-game-track link-game-track])]
                         (for [game-track (set game-track-list)
                               :when (not (nil? game-track))]
                           {;; "The physical location of the asset can change at any time and the direct link remains unchanged"
                            ;; - https://docs.gitlab.com/ee/user/project/releases/index.html#permanent-links-to-release-assets
                            :download-url (:direct_asset_url link)
                            :version (:tag_name release)
                            :game-track game-track})))]
    (->> release
         :assets
         :links
         (filter (juxt false? :external))
         (filter (juxt supported-link-types :link_type))
         (mapcat link-release)
         (remove nil?))))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  ;; todo: sink errors
  (let [result (-> addon-summary :source-id api-url (str "/releases") http/download-with-backoff utils/from-json)
        known-game-tracks (-> addon-summary :game-track-list utils/nilable (or [:retail]))
        release-list (->> result
                          (remove :upcoming_release)
                          (mapcat #(parse-release % known-game-tracks))
                          (group-by :game-track))]
    (get release-list game-track)))

;; ---

(defn-spec base64-decode (s/or :ok? string?, :error nil?)
  [string string?]
  (String. (.decode (Base64/getDecoder) string)))

(defn-spec download-decode-blob map?
  [url ::sp/url]
  (->> url http/download utils/from-json :content base64-decode toc/-parse-toc-file))

(defn-spec find-toc-files map?
  "returns a map of {toc-filename->blob-url}"
  [source-id :addon/source-id]
  (let [result (-> source-id
                   api-url
                   (str "/repository/tree")
                   http/download
                   utils/from-json)

        blob-url (fn [item]
                   ;; {"Foo.toc" "https://gitlab.com/api/v4/projects/foo%2Fbar/repository/blobs/125c899d813d2e11c976879f28dccc2a36fd207b"}
                   {(:name item) (str (api-url source-id) "/repository/blobs/" (:id item))})
        
        toc-files (->> result
                       (filter #(-> % :path lower-case (ends-with? ".toc")))
                       (map blob-url)
                       (into {}))]

    toc-files))

(defn-spec guess-game-track-list ::sp/game-track-list
  [source-id :addon/source-id]
  (let [toc-file-map (find-toc-files source-id)]
    ;; if we have multiple toc files, assume multi-toc and check for prefixes
    (if (> (count toc-file-map) 1)
      (let [key-check (fn [key]
                        (cond
                          (ends-with? key "-bcc.toc") :classic-tbc
                          (ends-with? key "-classic.toc") :classic
                          :else :retail))]
        (->> toc-file-map keys (map lower-case) (map key-check) set sort vec))

      ;; otherwise, download toc file and analyse it's contents
      ;; todo: test, no toc-file-list is empty
      (->> toc-file-map
           vals ;; blob urls
           first
           download-decode-blob
           (select-keys* [:interface :#interface])
           vals
           (map utils/to-int)
           (map utils/interface-version-to-game-track)
           (remove nil?)
           set
           vec))))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (let [bits (take 3 (-> url java.net.URL. .getPath
                         (clojure.string/split #"/-")
                         first
                         (utils/trim "/")
                         (clojure.string/split #"/")))]
    (clojure.string/join "/" bits)))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  (let [url (api-url source-id)
        result (-> url http/download-with-backoff utils/from-json)]

    {:url (:web_url result)
     :created-date (:created_at result)
     :updated-date (:last_activity_at result)
     :source "gitlab"
     ;; prefer the github-like "owner/repo" id over the number id.
     ;; we have a choice so go with the more consistent and humane option.
     :source-id (:path_with_namespace result)
     :label (:name result)
     :name (:path result)
     ;; not available to the public. must be present, must be >= 0 :(
     ;; - https://docs.gitlab.com/ee/api/project_statistics.html
     :download-count 0
     :game-track-list (guess-game-track-list source-id)
     :tag-list []}))
