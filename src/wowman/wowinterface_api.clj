(ns wowman.wowinterface-api
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [wowman
    [utils :as utils]
    [specs :as sp]
    [http :as http]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(def wowinterface-api "https://api.mmoui.com/v3/game/WOW")

(defn-spec api-uri ::sp/uri
  [path string?, & args (s/* any?)]
  (str wowinterface-api (apply format path args)))

(defn expand-summary
  [addon-summary]
  (let [url (api-uri "/filedetails/%s.json" (:source-id addon-summary))
        ;; returns a map nested in a list? todo: are there any conditions where more than one item is returned?
        result (-> url http/download utils/from-json first)]
    (merge addon-summary {:download-uri (str "https://cdn.wowinterface.com/downloads/getfile.php?id=" (:source-id addon-summary))
                          :version (:UIVersion result)
                          ;;:interface-version ;; this is available through the filedetails.json :(
                          })))

(st/instrument)
