(ns wowman.gui-test
  (:require
   [envvar.core :refer [env with-env]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.ui.gui :as gui]
   [wowman
    [main :as main]
    [core :as core]
    [utils :as utils :refer [join]]
    [test-helper :as helper :refer [fixture-path temp-path]]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

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
        (main/stop)))))
