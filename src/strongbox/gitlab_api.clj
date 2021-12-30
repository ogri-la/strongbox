(ns strongbox.gitlab-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :refer [ends-with? lower-case]]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [strongbox
    [release-json :refer [download-release-json, release-json-game-tracks]]
    [toc :as toc]
    [http :as http]
    [utils :as utils :refer [select-keys* if-let*]]
    [specs :as sp]]))

(defn-spec api-url ::sp/url
  "returns a gitlab API URL prefix where the `gitlab-source-id` is properly encoded"
  [gitlab-source-id string?]
  (let [encoded-source-id (-> gitlab-source-id clojure.string/lower-case (java.net.URLEncoder/encode "UTF-8"))]
    (str "https://gitlab.com/api/v4/projects/" encoded-source-id)))

(defn-spec asset-url ::sp/url
  "returns the preferred URL for the given `asset` when a choice is available."
  [asset map?]
  (or (:direct_asset_url asset)
      (:url asset)))

(defn-spec parse-release (s/or :ok :addon/release-list, :error nil?)
  "parses the list of 'links' in a gitlab release.

  like github, we can't use the automatically generated bundles because the foldername inside the ZIP looks
  like `<Project>-<version>` (AdiBags-v1.2.3) and may make use of templates that need rendering/processing before being
  uploaded as a proper asset ('link')."
  [release map?, known-game-tracks ::sp/game-track-list]
  (let [release-game-track (utils/guess-game-track (:name release))
        supported-link-types #{"package" "other"} ;; the others are 'runbook' and 'image'

        published-before-classic? (utils/published-before-classic? (:released_at release))

        latest-release? (-> release (get :-i 0) (= 0))

        release-json (->> release
                          :assets
                          :links
                          (filter #(= "release.json" (:name %)))
                          first)

        ;; guess the asset game track from the asset name, the release name or the time it was published.
        classify (fn [asset]
                   (let [asset-game-track (utils/guess-game-track (:name asset))
                         source-info {;; "The physical location of the asset can change at any time and the direct link remains unchanged"
                                      ;; - https://docs.gitlab.com/ee/user/project/releases/index.html#permanent-links-to-release-assets
                                      :download-url (asset-url asset)
                                      :version (:tag_name release)
                                      :game-track nil
                                      :-name (:name asset)}
                         game-track-list
                         (cond
                           ;; game track present in file name, prefer that over `:game-track-list` and any game-track in release name
                           asset-game-track [asset-game-track]

                           ;; I imagine there were classic addons published prior to it's release.
                           ;; If we can use the asset name, brilliant, if not and it's before the cut off then it's retail.
                           published-before-classic? [:retail]

                           ;; game track present in release name, prefer that over `:game-track-list`
                           release-game-track [release-game-track]

                           :else [nil])]
                     (mapv #(assoc source-info :game-track %) game-track-list)))

        ;; if we have a telltale single unclassified asset in a set of classified assets, use that.
        classify2 (fn [asset-list]
                    (let [;; it's possible for `nil` game tracks to still exist.
                          ;; either the release.json file was incomplete or we simply couldn't
                          ;; guess the game track from the asset name and had nothing to fall back on.
                          unclassified-assets (->> asset-list (map :game-track) (filter nil?))
                          classified-assets (->> asset-list (map :game-track) (remove nil?) set)
                          diff (clojure.set/difference sp/game-tracks classified-assets)]
                      (if (and (= (count unclassified-assets) 1)
                               (= (count diff) 1))
                        (->> asset-list
                             (mapv #(if (nil? (:game-track %))
                                      (assoc % :game-track (first diff))
                                      %)))
                        asset-list)))

        ;; finally, try downloading the release.json file, if it exists, otherwise just use 'all' game tracks originally detected.
        classify3 (fn [asset]
                    (if (:game-track asset)
                      [asset]

                      (let [;; no game track present in asset name or release name BUT 
                            ;; a release.json file is present and this is still the first of N releases.
                            ;; fetch release and match asset to it's contents.
                            ;; if asset isn't found (!) then just use [nil]
                            game-track (when (and latest-release?
                                                  release-json)
                                         (let [release-json-data (download-release-json (asset-url release-json))]
                                           (get (release-json-game-tracks release-json-data) (:-name asset)
                                                (debug "release.json missing asset:" (:-name asset)))))

                            ;; assume all entries in `:game-track-list` supported.
                            game-track-list (if (empty? known-game-tracks) [nil] known-game-tracks)
                            game-track-list (or game-track
                                                game-track-list)]

                        (mapv #(assoc asset :game-track %) game-track-list))))

        classified? (fn [asset]
                      (if-not (:game-track asset)
                        (debug "failed to detect a game track for asset" (fs/base-name (get asset :download-url "")))
                        asset))]
    (->> release
         :assets
         :links
         (filter (juxt false? :external))
         (filter (juxt supported-link-types :link_type))
         (mapcat classify)
         classify2
         (mapcat classify3)
         (filter classified?)
         (remove (comp nil? :game-track))
         (map #(dissoc % :-name))
         vec)))

(defn-spec -parse-release fn?
  "convenience wrapper around `parse-assets` that returns a function that accepts a list of releases"
  [game-track-list ::sp/game-track-list]
  (fn [idx release]
    (parse-release (assoc release :-i idx) game-track-list)))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [result (some-> addon
                       :source-id
                       api-url
                       (str "/releases")
                       http/download-with-backoff
                       http/sink-error
                       utils/from-json)

        known-game-tracks (cond
                            (not (empty? (:game-track-list addon))) (:game-track-list addon)
                            (:installed-game-track addon) [(:installed-game-track addon)]
                            :else [])

        release-list (->> result
                          (remove :upcoming_release)
                          (map-indexed (-parse-release known-game-tracks))
                          flatten
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
           toc/parse-toc-file))

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

(defn-spec -toc-game-track (s/or :ok ::sp/game-track-list, :error nil?)
  "attempts to guess the game track of a [filename blob-url] pair.
  if the game track can't be guessed from the filename, it downlads the blob and inspects the interface version."
  [[filename blob-url] (s/coll-of string?)]
  (if-let [game-track (utils/guess-game-track filename)]
    [game-track]
    (do (debug "couldn't guess game track, downloading toc file and inspecting interface version:" filename)
        (some->> blob-url
                 download-decode-blob
                 (select-keys* [:interface :#interface])
                 vals
                 (map utils/to-int)
                 (mapv utils/interface-version-to-game-track)))))

(defn-spec guess-game-track-list (s/or :ok ::sp/game-track-list, :error nil?)
  "attempts to guess the game tracks an addon may support.
  if multiple toc files exist it assumes they are being used for classic versions of the game.
  if only a single toc file exists, it downloads and inspects the `:interface` value in the toc file.
  if no toc files are found it returns `nil`."
  [source-id :addon/source-id]
  (->> source-id
       find-toc-files
       (mapcat -toc-game-track)
       (remove nil?)
       set
       sort
       vec
       utils/nilable))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  "downloads the Gitlab project repository data and extracts an addon summary from it."
  [source-id :addon/source-id]
  (if-let* [repo (some-> source-id
                         api-url
                         http/download-with-backoff
                         http/sink-error
                         utils/from-json)

            game-track-list (guess-game-track-list source-id)

            addon-summary
            {:url (:web_url repo)
             :created-date (:created_at repo)
             :updated-date (:last_activity_at repo)
             :source "gitlab"
             ;; prefer the github-like "owner/repo" id over the number id.
             ;; we have a choice so go with the more consistent and humane option.
             :source-id (:path_with_namespace repo)
             :label (:name repo)
             :name (:path repo)
             ;; not available to the public. must be present, must be >= 0 :(
             ;; - https://docs.gitlab.com/ee/api/project_statistics.html
             :download-count 0
             ;; needs more thought. authors are tagging the repo against other repos rather than the addon, so
             ;; we get labels like 'world of warcraft' and 'lua' which are not useful.
             :tag-list [] ;; (get repo :tag_list [])
             :game-track-list game-track-list}

            ;; make sure at least one release exists
            latest-release (expand-summary addon-summary (first game-track-list))]

           addon-summary

           nil))

;; ---

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

;;

(defn-spec make-url (s/or :ok ::sp/url, :error nil?)
  [toc (s/or :just-toc :addon/toc, :mixed :addon/toc+nfo)]
  (str "https://gitlab.com/" (:source-id toc)))
