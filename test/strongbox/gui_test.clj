(ns strongbox.gui-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [seesaw.core :as ss]
   [strongbox.ui.gui :as gui]
   [strongbox
    [main :as main]
    [test-helper :as helper :refer [fixture-path with-running-app with-running-app+opts]]]
  ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(use-fixtures :each helper/fixture-tempcwd)

(deftest gui-init
  (testing "the gui can be started and stopped"
    (with-running-app+opts {:ui :gui}
      (is (gui/select-ui :#root))))

  (testing "attempting to select components of the gui when the app is started but the gui isn't causes a runtime error"
    (with-running-app
      (is (thrown? RuntimeException (gui/select-ui :#root)))))

  (testing "coverage bump. gui debug tools don't require an initialised app in order to be accessed"
    (with-out-str ;; hide the debug output
      (is (nil? (gui/inspect (seesaw.core/vertical-panel)))))))

(deftest gui-update-available-button
  (testing "the 'new release' button is displayed when an update is available"
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 200 :body "{\"tag_name\": \"9.99.999\"}"})}}]
      (with-fake-routes-in-isolation fake-routes
        (with-running-app+opts {:ui :gui}
          (let [btn (gui/select-ui :#update-available-btn)]
            (is (= (ss/text btn) "Update Available: 9.99.999"))))))))

(deftest gui-stateless-calls
  (testing "coverage bump for all the stateless parts in gui"
    (is (= nil (gui/donothing "event object")))
    (is (= nil ((gui/handler (constantly :foo) (constantly :bar)) "event object")))))

(deftest as-selector
  (testing "a keyword is transformed into a selector as expected"
    (let [cases [[:foo :#foo]]]
      (doseq [[given expected] cases]
        (is (= expected (gui/as-selector given)))))))
