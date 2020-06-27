(ns strongbox.main-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [strongbox
    [test-helper :as helper]
    [main :as main]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest parse-args
  (testing "default ui is 'gui'"
    (is (= :gui (-> (main/parse []) :options :ui))))

  (testing "explicit ui of 'cli' overrides default of 'gui'"
    (is (= :cli (-> (main/parse ["--ui" "cli"]) :options :ui))))

  (testing "explicit ui of 'gui' possible, even though it's default :P"
    (is (= :gui (-> (main/parse ["--ui" "gui"]) :options :ui))))

  (testing "ui default becomes 'cli' when 'headless' present ..."
    (is (= :cli (-> (main/parse ["--headless"]) :options :ui))))

  (testing "... although a headless gui is possible"
    (is (= :gui (-> (main/parse ["--ui" "gui" "--headless"]) :options :ui))))

  (testing "certain actions force the 'cli' ui"
    (is (= :cli (-> (main/parse ["--action" "scrape-catalogue"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalogue"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalogue"]) :options :ui))))

  (testing "certain actions force the 'cli' ui, even when 'gui' is explicitly passed"
    (is (= :cli (-> (main/parse ["--action" "scrape-catalogue" "--ui" "gui"]) :options :ui))))

  (testing "verbosity is forced to :debug when --debug is passed in"
    (is (= :debug (-> (main/parse ["--verbosity" "info" "--debug"]) :options :verbosity)))))

(deftest start-app
  (testing "starting app from a clean state, no dirs, no catalogue, nothing"
    (try
      (main/-main "--ui" "cli")
      (finally
        (main/stop)))))
