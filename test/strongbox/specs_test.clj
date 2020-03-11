(ns strongbox.specs-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [specs :as specs]]))

(deftest between
  (testing ""
    (let [cases [;; `between` is not exclusive
                 [[0 1] 0 false]
                 [[0 1] 1 false]
                 [[0 2] 1 true]

                 [[0 1] "" false]
                 [[0 1] " " false]
                 [[0 2] " " true]]]
      (doseq [[[min max] given expected] cases]
        (is (= expected ((specs/between min max) given)))))))
