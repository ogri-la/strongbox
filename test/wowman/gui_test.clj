(ns wowman.gui-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.ui.gui :as gui]
   [wowman
    [main :as main]
    [test-helper :as helper :refer [fixture-path]]]
  ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(use-fixtures :each helper/fixture-tempcwd)

(deftest gui-init
  (testing "the gui can be started and stopped"
    (try
      (main/start {:ui :gui})
      (is (gui/select-ui :#root))
      (finally
        (main/stop))))

  (testing "attempting to select bits of the gui when not the app is started but the gui isn't causes a runtime error"
    (try
      (main/start {:ui :cli})
      (is (thrown? RuntimeException (gui/select-ui :#root)))
      (finally
        (main/stop))))

  (testing "gui debug tools don't require an initialised anything in order to be accessed"
    (with-out-str ;; hide the debug output
      (is (nil? (gui/inspect (seesaw.core/vertical-panel)))))))

(deftest gui-stateless-calls
  (testing "shameless coverage bump for all the stateless parts in gui"
    (is (= nil (gui/donothing "event object")))
    (is (= nil ((gui/handler (constantly :foo) (constantly :bar)) "event object")))))

(deftest as-selector
  (testing "a keyword is transformed into a selector as expected"
    (let [cases [[:foo :#foo]]]
      (doseq [[given expected] cases]
        (is (= expected (gui/as-selector given)))))))
