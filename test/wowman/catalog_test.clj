(ns wowman.catalog-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [catalog :as catalog]
    ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
    [test-helper :refer [fixture-path]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest de-dupe-wowinterface
  (testing "given multiple addons with the same name, the most recently updated one is preferred"
    (let [fixture [{:name "adibags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected [{:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]]
      (is (= (catalog/de-dupe-wowinterface fixture) expected))))

  (testing "given multiple addons with distinct names, all addons are returned"
    (let [fixture [{:name "adi-bags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "baggy-adidas" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected fixture]
      (is (= (catalog/de-dupe-wowinterface fixture) expected)))))

(deftest format-catalog-data
  (testing "catalog data has a consistent structure"
    (let [addon-list []
          created "2001-01-01"
          updated created
          expected {:spec {:version 1}
                    :datestamp created
                    :updated-datestamp updated
                    :total 0
                    :addon-summary-list addon-list}]
      (is (= (catalog/format-catalog-data addon-list created updated) expected)))))

(deftest merge-catalogs
  (testing "dates are correct after a merge"
    (let [aa {:datestamp "2001-01-01" :updated-datestamp "2001-01-02" :spec {:version 1} :addon-summary-list [] :total 0}
          ab {:datestamp "2001-01-03" :updated-datestamp "2001-01-04" :spec {:version 1} :addon-summary-list [] :total 0}
          expected {:spec {:version 1}
                    :datestamp "2001-01-01"
                    :updated-datestamp "2001-01-04"
                    :total 0
                    :addon-summary-list []}]
      (is (= (catalog/-merge-catalogs aa ab) expected)))))

(deftest parse-user-addon
  (testing "user input, presumably a path to an addon, can be parsed into a catalog item"
    (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}}]
      (with-fake-routes-in-isolation fake-routes
        (let [cases [["https://github.com/Aviana/HealComm" {:uri "https://github.com/Aviana/HealComm"
                                                            :updated-date "2019-10-09T17:40:01Z"
                                                            :source "github"
                                                            :source-id "Aviana/HealComm"
                                                            :label "HealComm"
                                                            :name "healcomm"
                                                            :download-count 30946
                                                            :category-list []}]]]
          (doseq [[given expected] cases]
            (is (= expected (catalog/parse-user-addon given)))))))))

