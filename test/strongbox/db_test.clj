(ns strongbox.db-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.db :as db]))

(deftest put-many
  (testing "putting nothing into an empty database returns an empty database"
    (let [expected []]
      (is (= expected (db/put-many [] []))))))
