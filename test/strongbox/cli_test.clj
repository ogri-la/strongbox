(ns strongbox.cli-test
  (:require
   [envvar.core :refer [env with-env]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.ui.cli :as cli]
   [strongbox
    [main :as main]
    [utils :as utils :refer [join]]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(defn tempcwd-fixture
  "each test is executed in a new location (accessible as fs/*cwd*)"
  [f]
  (let [temp-dir-path (fs/temp-dir "strongbox.main-test.")
        fake-routes {;; catalogue
                     ;; return dummy data. we can do this because the catalogue isn't loaded/parsed/validated
                     ;; until the UI (gui or cli) tells it to via a later call to `refresh`
                     "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalogue.json"
                     {:get (fn [req] {:status 200 :body "{}"})}

                     ;; latest strongbox version
                     "https://api.github.com/repos/ogri-la/wowman/releases/latest"
                     {:get (fn [req] {:status 200 :body "{\"tag_name\": \"0.0.0\"}"})}}]
    (try
      (with-fake-routes-in-isolation fake-routes
        (with-env [:xdg-data-home (join temp-dir-path "data")
                   :xdg-config-home (join temp-dir-path "config")]
          ;; Is this still necessary any more? I guess it improves test isolation
          (with-cwd temp-dir-path
            (debug "created temp working directory" fs/*cwd*)
            (main/start {:ui :cli})
            (f))))
      (finally
        (main/stop)
        (debug "destroying temp working directory" fs/*cwd*) ;; "with contents" (vec (file-seq fs/*cwd*)))
        (fs/delete-dir temp-dir-path)))))

(use-fixtures :each tempcwd-fixture)

(deftest action
  (let [expected "0 installed\n0 updates\n"]
    (testing "action func can accept a plain keyword"
      (is (= (with-out-str (cli/action :list-updates)) expected))
      (testing "action func can accept a map of args"
        (is (= (with-out-str (cli/action {:action :list-updates})) expected))))))

(deftest shameless-coverage-bump
  (let [safe-actions [:???
                      :list :list-updates :update-all]]
    (doseq [action-kw safe-actions]
      (testing (str "cli: " action)
        (cli/action action-kw)))))
