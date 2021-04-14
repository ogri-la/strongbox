(ns strongbox.logging-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[strongbox
    ;;[logging :as logging]
    ;;[core :as core]]
   ;;[taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [strongbox.test-helper :as helper :refer [with-running-app fixture-path]]))

(use-fixtures :each helper/fixture-tempcwd)

(comment
  (deftest ui-appender
    (with-running-app
      (helper/install-dir) ;; sets the addon dir and the UI appender
      (let [expected {:log-lines [{:foo :bar}] :log-stats {:bar {:baz :bup}}}]
        (timbre/log :warn "warning message")
        (is (= expected (core/get-state :log-lines))))))

  ;;(is (= expected (select-keys (core/get-state) [:log-lines :log-stats]))))))
  )
