(ns strongbox.tukui-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :refer [lower-case]]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(def summary-list-url "https://www.tukui.org/api.php?addons")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons")
(def classic-tbc-summary-list-url "https://www.tukui.org/api.php?classic-tbc-addons")
(def classic-wotlk-summary-list-url "https://www.tukui.org/api.php?classic-wotlk-addons")

(def proper-url "https://www.tukui.org/api.php?ui=%s")

(defn-spec make-url (s/nilable ::sp/url)
  "given a map of addon data, returns a URL to the addon's tukui page or `nil`"
  [{:keys [name source-id interface-version]} map?]
  (cond
    (not source-id) nil

    (and (neg? source-id) name)
    (str "https://www.tukui.org/download.php?ui=" name)

    (and (pos? source-id) interface-version)
    (case (utils/interface-version-to-game-track interface-version)
      :retail (str "https://www.tukui.org/addons.php?id=" source-id)
      :classic (str "https://www.tukui.org/classic-addons.php?id=" source-id)
      :classic-tbc (str "https://www.tukui.org/classic-tbc-addons.php?id=" source-id)
      :classic-wotlk (str "https://www.tukui.org/classic-wotlk-addons.php?id=" source-id)
      nil)

    :else nil))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [source-id (:source-id addon)
        source-id-str (str source-id)

        url (cond
              (neg? source-id) (format proper-url (:name addon))
              (= game-track :retail) summary-list-url
              (= game-track :classic) classic-summary-list-url
              (= game-track :classic-tbc) classic-tbc-summary-list-url
              (= game-track :classic-wotlk) classic-wotlk-summary-list-url)

        ;; tukui addons do not share IDs across game tracks like curseforge does.
        ;; 2020-12-02: Tukui has dropped the per-addon endpoint, all results are now lists of items
        addon-list (some-> url http/download-with-backoff utils/nilable http/sink-error utils/from-json)
        addon-list (if (sequential? addon-list)
                     addon-list
                     (-> addon-list (update :id str) vector))

        ti (->> addon-list (filter #(= source-id-str (:id %))) first)

        interface-version (when-let [patch (:patch ti)]
                            {:interface-version (utils/game-version-to-interface-version patch)})]
    (when ti
      [(merge {:download-url (:url ti)
               :version (:version ti)
               :game-track game-track}
              interface-version)])))

(defn-spec parse-user-string (s/or :ok :addon/source-id, :error nil?)
  "extracts the addon ID from the given `url`, handling the edge cases of for retail tukui and elvui"
  [url ::sp/url]
  (let [[numeral string] (some->> url java.net.URL. .getQuery (re-find #"(?i)(?:id=(\d+)|ui=(tukui|elvui))") rest)]
    (if numeral
      (utils/to-int numeral)
      (case (-> string (or "") lower-case)
        "tukui" -1
        "elvui" -2
        nil))))
