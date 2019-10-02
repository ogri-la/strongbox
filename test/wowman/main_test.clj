(ns wowman.main-test
  (:require
   [envvar.core :refer [env with-env]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [wowman
    [utils :refer [join]]
    [main :as main]]))

(defn tempcwd-fixture
  "each test is executed in a new location (accessible as fs/*cwd*)"
  [f]
  (let [temp-dir-path (fs/temp-dir "wowman.main-test.")
        fake-routes {;; catalog
                     ;; return dummy data. we can do this because the catalog isn't loaded/parsed/validated
                     ;; until the UI (gui or cli) tells it to via a later call to `refresh`
                     "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                     {:get (fn [req] {:status 200 :body "{}"})}

                     ;; latest wowman version
                     "https://api.github.com/repos/ogri-la/wowman/releases/latest"
                     {:get (fn [req] {:status 200 :body "{\"tag_name\": \"0.0.0\"}"})}}]
    (try
      (with-fake-routes-in-isolation fake-routes
        (with-env [:xdg-data-home (join temp-dir-path "data")
                   :xdg-config-home (join temp-dir-path "config")]
          ;; Is this still necessary any more? I guess it improves test isolation
          (with-cwd temp-dir-path
            (debug "created temp working directory" fs/*cwd*)
            (f))))
      (finally
        (debug "destroying temp working directory" fs/*cwd*) ;; "with contents" (vec (file-seq fs/*cwd*)))
        (fs/delete-dir temp-dir-path)))))

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
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalog"]) :options :ui)))
    (is (= :cli (-> (main/parse ["--action" "scrape-curseforge-catalog"]) :options :ui))))

  (testing "certain actions force the 'cli' ui, even when 'gui' is explicitly passed"
    (is (= :cli (-> (main/parse ["--action" "scrape-catalog" "--ui" "gui"]) :options :ui)))))

(deftest start-app
  (testing "starting app from a clean state, no dirs, no catalog, nothing"
    (try
      (main/-main "--ui" "cli")
      (finally
        (main/stop)))))
