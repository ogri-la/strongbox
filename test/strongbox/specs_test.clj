(ns strongbox.specs-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [specs :as specs]]))

(deftest game-tracks-label-map
  (let [expected {"Classic" :classic,
                  "Classic (Cata)" :classic-cata,
                  "Classic (TBC)" :classic-tbc,
                  "Classic (WotLK)" :classic-wotlk,
                  "Retail" :retail}]
    (is (= expected specs/game-track-labels-map-inv))))
