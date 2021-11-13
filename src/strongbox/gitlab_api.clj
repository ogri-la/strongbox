(ns strongbox.gitlab-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :refer [ends-with? lower-case]]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [toc :as toc]
    [http :as http]
    [utils :as utils :refer [select-keys*]]
    [specs :as sp]]))

(defn-spec api-url ::sp/url
  "returns a gitlab API URL prefix where the `gitlab-source-id` is properly encoded"
  [gitlab-source-id string?]
  (let [encoded-source-id (-> gitlab-source-id clojure.string/lower-case (java.net.URLEncoder/encode "UTF-8"))]
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
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  (let [result (some-> addon-summary
                       :source-id
                       api-url
                       (str "/releases")
                       http/download-with-backoff
                       http/sink-error
                       utils/from-json)
        ;; if addon has no indication what game track it is, assume `:retail`.
        ;; we should have *some* idea from originally parsing the toc file or guessing from the multi-toc in `find-addon`
        known-game-tracks (-> addon-summary :game-track-list utils/nilable (or [:retail]))
        release-list (->> result
                          (remove :upcoming_release)
                          (mapcat #(parse-release % known-game-tracks))
                          (group-by :game-track))]
    (get release-list game-track)))

;; ---

(defn-spec download-decode-blob (s/or :ok map?, :error nil?)
  "downloads and base64 decodes the `:content` in a Gitlab `repository/blobs` 'blob' result"
  [url ::sp/url]
  (some->> url
           http/download
           http/sink-error
           utils/from-json
           :content
           utils/base64-decode
           toc/-parse-toc-file))

(defn-spec find-toc-files map?
  "returns a map of {filename.toc blob-url, ...}"
  [source-id :addon/source-id]
  (let [result (some-> source-id
                       api-url
                       (str "/repository/tree")
                       http/download
                       http/sink-error
                       utils/from-json)

        blob-url (fn [item]
                   ;; {"Foo.toc" "https://gitlab.com/api/v4/projects/foo%2Fbar/repository/blobs/125c899d813d2e11c976879f28dccc2a36fd207b"}
                   {(:name item) (str (api-url source-id) "/repository/blobs/" (:id item))})

        toc-files (->> result
                       (filter #(-> % :path lower-case (ends-with? ".toc")))
                       (map blob-url)
                       (into {}))]

    toc-files))

(defn-spec guess-game-track-list (s/or :ok ::sp/game-track-list, :error nil?)
  "attempts to guess the game tracks an addon may support.
  if multiple toc files exist it assumes they are being used for classic versions of the game.
  if only a single toc file exists, it downloads and inspects the `:interface` value in the toc file.
  if not toc files are found it returns `nil`."
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
      (some->> toc-file-map
               vals ;; blob urls
               first
               download-decode-blob
               (select-keys* [:interface :#interface])
               vals
               (map utils/to-int)
               (map utils/interface-version-to-game-track)
               (remove nil?)
               set
               sort
               vec))))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`.
  returns `nil` if an addon ID cannot be found."
  [url ::sp/url]
  (let [;; there will be 2-3 bits in a gitlab url after reaching the delimiter "/-/"
        bits (take 3 (-> url java.net.URL. .getPath ;; "/group/owner/project/-/foo". "/owner/project/-/foo"
                         (clojure.string/split #"/-") ;; ["/group/owner/project" "/foo"]
                         first
                         (utils/trim "/") ;; "group/owner/project", "owner/project"
                         (clojure.string/split #"/")))] ;; ["group" "owner" "project"]
    (when (> (count bits) 1)
      (clojure.string/join "/" bits))))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  "downloads the Gitlab project repository data and extracts an addon summary from it."
  [source-id :addon/source-id]
  (when-let [result (some-> source-id
                            api-url
                            http/download-with-backoff
                            http/sink-error
                            utils/from-json)]
    (let [game-track-list (guess-game-track-list source-id)
          addon-summary
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
           ;; needs more thought. authors are tagging the repo against other repos rather than the addon, so
           ;; we get labels like 'world of warcraft' and 'lua' which are not useful.
           :tag-list [] ;; (get result :tag_list []) 
           }]
      (if game-track-list
        (assoc addon-summary :game-track-list game-track-list)
        addon-summary))))
