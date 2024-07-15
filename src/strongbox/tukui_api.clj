(ns strongbox.tukui-api
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :refer [lower-case]]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [specs :as sp]]))

;; todo: are these used anymore?
(def summary-list-url "https://www.tukui.org/api.php?addons")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons")
(def classic-tbc-summary-list-url "https://www.tukui.org/api.php?classic-tbc-addons")
(def classic-wotlk-summary-list-url "https://www.tukui.org/api.php?classic-wotlk-addons")

(def proper-url "https://www.tukui.org/api.php?ui=%s")

(defn-spec make-url (s/nilable ::sp/url)
  "given a map of addon data, returns a URL to the addon's tukui page or `nil`"
  [{:keys [name source-id interface-version-list]} map?]
  (cond
    (not source-id) nil

    (and (neg? source-id) name)
    (str "https://www.tukui.org/download.php?ui=" name)

    (and (pos? source-id) (not (empty? interface-version-list)))
    (case (utils/interface-version-to-game-track (first interface-version-list))
      :retail (str "https://www.tukui.org/addons.php?id=" source-id)
      :classic (str "https://www.tukui.org/classic-addons.php?id=" source-id)
      :classic-tbc (str "https://www.tukui.org/classic-tbc-addons.php?id=" source-id)
      :classic-wotlk (str "https://www.tukui.org/classic-wotlk-addons.php?id=" source-id)
      nil)

    :else nil))

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
