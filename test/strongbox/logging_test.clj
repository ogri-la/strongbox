(ns strongbox.logging-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [logging :as logging]
    [core :as core]]
   [taoensso.timbre :as timbre]
   [strongbox.test-helper :as helper :refer [with-running-app fixture-path]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest log-level-while-testing
  (testing "the log level is set to `:debug` while testing"
    (is (= :debug (-> timbre/*config* :min-level)))))

(deftest log-level-while-running-app
  (testing "the default log level is *always* set to `:debug` while testing, even when running an app"
    (with-running-app
      (is (not (= logging/default-log-level (-> timbre/*config* :min-level))))
      (is (= :debug (-> timbre/*config* :min-level))))))

(deftest changing-log-level-while-testing
  (let [current-log-level #(-> timbre/*config* :min-level)]
    (testing "it is possible to change the log level while testing"
      (core/set-log-level! :warn)
      (is (= :warn (current-log-level))))

    (testing "it will revert to `:debug` on `start` and `restart` however"
      (is (= :warn (current-log-level)))
      (with-running-app
        (is (= :debug (current-log-level)))
        (core/set-log-level! :warn)
        (is (= :warn (current-log-level)))))

    (testing "it will also revert when changing the addon dir"
      (with-running-app
        (is (= :debug (current-log-level)))
        (core/set-log-level! :warn)
        (is (= :warn (current-log-level)))
        (helper/install-dir)
        (is (= :debug (current-log-level)))))))

(deftest stateful-logging--no-state
  (testing "stateful logging just needs a place to store it's log lines"
    (timbre/warn "some test message")
    (is (nil? (-> @core/state :log-lines)))))

(deftest stateful-logging--basic
  (testing "stateful logging just needs a place to store it's log lines"
    (with-running-app
      (timbre/warn "some test message")
      (let [expected {:level :warn, :message "some test message", :source {:install-dir nil}}
            actual (->> (core/get-state :log-lines) (filter #(= :warn (:level %))) last)
            actual (dissoc actual :time)]
        (is (= expected actual))))))

(deftest stateful-logging--basic-w.addon
  (testing "addon logging is possible without a selected addon directory, but you probably want to avoid it"
    (with-running-app
      (logging/addon-log {:dirname "EveryAddon"} :warn "some test message")
      (let [expected {:level :warn, :message "some test message", :source {:install-dir nil, :dirname "EveryAddon"}}
            actual (->> (core/get-state :log-lines) (filter #(= :warn (:level %))) last)
            actual (dissoc actual :time)]
        (is (= expected actual))))))

(deftest stateful-logging--addon-dir-w.addon
  (testing "addon logging is possible without a selected addon directory, but you probably want to avoid it"
    (with-running-app
      (let [install-dir (helper/install-dir)
            expected {:level :warn, :message "some test message", :source {:install-dir install-dir, :dirname "EveryAddon"}}
            _ (logging/addon-log {:dirname "EveryAddon"} :warn "some test message")
            actual (->> (core/get-state :log-lines) (filter #(= :warn (:level %))) last)
            actual (dissoc actual :time)]
        (is (= expected actual))))))

(deftest stateful-logging--report-level
  (testing "addon logging at the `:report` is stripped of addon context"
    (with-running-app
      (helper/install-dir)
      (let [expected {:level :report, :message "some test message"}
            _ (logging/addon-log {:dirname "EveryAddon"} :report "some test message")
            actual (->> (core/get-state :log-lines) (filter #(= :report (:level %))) last)
            actual (dissoc actual :time)]
        (is (= expected actual))))))
