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

(defn-spec expand-summary (s/or :ok :addon/source-updates, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary :addon/summary game-track ::sp/game-track]
  ;; this check is a little different to the others.
  ;; the `game-track-list` is stored in the catalogue for wowinterface because it's available at creation time.
  ;; however! we know this information isn't good and doesn't always match what we see on the website.
  ;; until wowinterface improve, and short of doing more scraping of html, this is the best we can do.
  (if (some #{game-track} (:game-track-list addon-summary))
    (let [url (str wowinterface-api "/filedetails/" (:source-id addon-summary) ".json")
          result-list (-> url http/download utils/from-json)
          result (first result-list)]
      ;; has this happened before? can we find an example?
      (when (> (count result-list) 1)
        (warn "wowinterface api returned more than one result for addon with :source-id" (:source-id addon-summary)))
      {:download-url (str "https://cdn.wowinterface.com/downloads/getfile.php?id=" (:source-id addon-summary))
       :version (:UIVersion result)})))
