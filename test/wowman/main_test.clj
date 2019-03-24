(ns wowman.main-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [wowman
    [main :as main]]))

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
    (is (= :cli (-> (main/parse ["--action" "scrape-addon-list"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "update-addon-list"]) :options :ui))))

  (testing "certain actions force the 'cli' ui, even when 'gui' is explicitly passed"
    (is (= :cli (-> (main/parse ["--action" "scrape-addon-list" "--ui" "gui"]) :options :ui)))))
