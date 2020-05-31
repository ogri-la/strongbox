(ns strongbox.cli-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.ui.cli :as cli]
   [strongbox
    [main :as main]
    [catalogue :as catalogue]
    [core :as core]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox.test-helper :as helper :refer [with-running-app fixture-path]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest action
  (let [expected "0 installed\n0 updates\n"]
    (with-running-app
      (testing "action func can accept a plain keyword"
        (is (= (with-out-str (cli/action :list-updates)) expected)))
      (testing "action func can accept a map of args"
        (is (= (with-out-str (cli/action {:action :list-updates})) expected))))))

(deftest shameless-coverage-bump
  (let [safe-actions [:???
                      :list :list-updates :update-all]]
    (with-running-app
      (doseq [action-kw safe-actions]
        (testing (str "cli: " action)
          (cli/action action-kw))))))

(deftest write-catalogue
  (with-running-app
    (let [full (core/find-catalogue-local-path :full)
          short (core/find-catalogue-local-path :short)

          curse (core/find-catalogue-local-path :curseforge)
          wowi (core/find-catalogue-local-path :wowinterface)
          tukui (core/find-catalogue-local-path :tukui)]

      ;; copy some fixtures
      (fs/copy (fixture-path "catalogue--v2--curseforge.json") curse)
      (fs/copy (fixture-path "catalogue--v2--wowinterface.json") wowi)
      (fs/copy (fixture-path "catalogue--v2--tukui.json") tukui)

      (cli/action :write-catalogue)

      (testing "full and shortened catalogues were written"
        (is (fs/exists? (core/find-catalogue-local-path :full)))
        (is (fs/exists? (core/find-catalogue-local-path :short))))

      (testing "each catalogue has one addon each"
        (is (= 3 (-> full catalogue/read-catalogue :total))))

      (testing "the short catalogue has just one addon in range"
        (is (= 1 (-> short catalogue/read-catalogue :total)))))))
