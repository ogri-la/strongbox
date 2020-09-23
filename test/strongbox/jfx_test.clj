(ns strongbox.jfx-test
  (:require
   ;;[clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[strongbox.ui.jfx :as jfx]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [main :as main]
    [core :as core]
    [test-helper :as helper :refer [fixture-path with-running-app with-running-app+opts]]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest gui-init
  (testing "the gui can be started and stopped"
    (with-running-app+opts {:ui :gui2}
      (is (core/get-state :gui-showing?)))))
