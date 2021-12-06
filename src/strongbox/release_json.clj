(ns strongbox.release-json
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox
    [specs :as sp]
    [utils :as utils]
    [http :as http]]))

(defn-spec download-release-json (s/or :ok ::sp/list-of-maps, :error nil?)
  "downloads a release.json file, removes any no-lib releases and returns the rest as a vec without the `:releases` nesting."
  [url ::sp/url]
  (some->> url
           http/download-with-backoff
           http/sink-error
           utils/from-json
           :releases
           (remove :nolib)
           vec))

(defn-spec release-json-game-tracks map?
  "returns a map of {asset-filename [:classic :retail], ...}"
  [release-json-data ::sp/list-of-maps]
  (let [game-tracks (fn [release]
                      {(:filename release)
                       (->> release
                            :metadata
                            (map :flavor)
                            (map utils/guess-game-track)
                            sort
                            vec)})]
    (->> release-json-data
         (map game-tracks)
         (into {}))))
