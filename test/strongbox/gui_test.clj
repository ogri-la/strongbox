(ns strongbox.gui-test
  (:require
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [seesaw.core :as ss]
   [strongbox.ui.gui :as gui]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [main :as main]
    [core :as core]
    [test-helper :as helper :refer [fixture-path with-running-app with-running-app+opts]]]))

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
  (testing "the 'update available' button is displayed when a new update is available"
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 200 :body "{\"tag_name\": \"9.99.999\"}"})}}]
      (with-global-fake-routes-in-isolation fake-routes
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

(deftest gui-select-installed-addons
  (testing "selecting addons using the gui selects the corresponding installed addons in the application state"
    (let [addon {:name "everyaddon" :label "EveryAddon" :version "1.2.3" :url "https://group.id/never/fetched"
                 :source "curseforge" :source-id 1
                 :-testing-zipfile (fixture-path "everyaddon--1-2-3.zip")}
          expected [{:description "Does what no other addon does, slightly differently",
                     :dirname "EveryAddon",
                     :group-id "https://group.id/never/fetched",
                     :installed-game-track :retail,
                     :installed-version "1.2.3",
                     :interface-version 70000,
                     :label "EveryAddon 1.2.3",
                     :name "everyaddon",
                     :primary? true,
                     :source "curseforge",
                     :source-id 1
                     ;; 2020-09-22: with the change to selecting addons, the fixes to the test script,
                     ;; switching to `with-global-fake-routes-in-isolation`, and (possibly) the removal of the state watchers in core,
                     ;; I've discovered this test has a problem being deterministic.
                     ;; without the Thread/sleep below, the gui init doesn't have time to complete and this `update?` field
                     ;; may or may not appear.
                     ;; :update? false
                     }]]
      (with-running-app+opts {:ui :gui}
        (Thread/sleep 200)
        (helper/install-dir)
        (core/install-addon addon)
        (core/load-installed-addons) ;; refresh our knowledge of what is installed
        (gui/select-one (gui/select-ui :#tbl-installed-addons) 0) ;; row at index 0
        (is (= expected (core/get-state :selected-installed)))))))
