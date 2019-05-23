(ns wowman.main-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [wowman
    [main :as main]]))

(defn tempcwd-fixture
  "each test is executed in a new location (accessible as fs/*cwd*)"
  [f]
  (let [temp-dir-path (fs/temp-dir "wowman.main-test.")]
    (fs/with-cwd temp-dir-path
      (debug "created temp working directory" fs/*cwd*)
      (f)
      (debug "destroying temp working directory" fs/*cwd*) ;; "with contents" (vec (file-seq fs/*cwd*)))
      (fs/delete-dir temp-dir-path))))

(use-fixtures :each tempcwd-fixture)

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
    (is (= :cli (-> (main/parse ["--action" "scrape-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "update-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "update-wowinterface-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "update-curseforge-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalog"]) :options :ui))))

  (testing "certain actions force the 'cli' ui, even when 'gui' is explicitly passed"
    (is (= :cli (-> (main/parse ["--action" "scrape-catalog" "--ui" "gui"]) :options :ui)))))

(deftest start-app
  (testing "starting app from a clean state, no dirs, no summary file, nothing"
    (try
      (main/-main "--ui" "cli")
      (finally
        (main/stop)))))
