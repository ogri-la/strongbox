(ns strongbox.wowinterface-api
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox
    [utils :as utils]
    [specs :as sp]
    [http :as http]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def wowinterface-api "https://api.mmoui.com/v3/game/WOW")

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary game-track ::sp/game-track]
  (if-not (some #{game-track} (:game-track-list addon-summary))
    (warn (format "no '%s' release available for '%s' on wowinterface" game-track (:name addon-summary)))
    (let [url (str wowinterface-api "/filedetails/" (:source-id addon-summary) ".json")
          result-list (-> url http/download utils/from-json)
          result (first result-list)]
      (when (> (count result-list) 1)
        (warn "wowinterface api returned more than one result for addon with :source-id" (:source-id addon-summary)))
      (merge addon-summary {:download-url (str "https://cdn.wowinterface.com/downloads/getfile.php?id=" (:source-id addon-summary))
                            :version (:UIVersion result)}))))
