(ns strongbox.db-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.db :as db]))

(deftest put-many
  (let [expected []]
    (is (= expected (db/put-many [] [])))))
