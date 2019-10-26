(ns wowman.catalog-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [catalog :as catalog]
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

(deftest merge-curse-wowi-catalogs
  (testing "dates are correct after a merge"
    (let [aa {:datestamp "2001-01-01" :updated-datestamp "2001-01-02" :spec {:version 1} :addon-summary-list [] :total 0}
          ab {:datestamp "2001-01-03" :updated-datestamp "2001-01-04" :spec {:version 1} :addon-summary-list [] :total 0}
          expected {:spec {:version 1}
                    :datestamp "2001-01-01"
                    :updated-datestamp "2001-01-04"
                    :total 0
                    :addon-summary-list []}]
      (is (= (catalog/-merge-curse-wowi-catalogs aa ab) expected)))))

;; todo: add tests for catalog/merge-catalogs 
;; - include cat-b precedence over cat-a
;; - include merging behaviour (vs replacement)

(deftest merge-catalogs
  (let [addon1 {:uri "https://github.com/Aviana/HealComm"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :category-list []}

        addon2 {:uri "https://github.com/Ravendwyr/Chinchilla"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Ravendwyr/Chinchilla"
                :label "Chinchilla"
                :name "chinchilla"
                :download-count 30946
                :category-list []}

        cat-a (catalog/new-catalog [addon1])
        cat-b (catalog/new-catalog [addon2])

        merged (catalog/new-catalog [addon1 addon2])

        cases [[[nil nil] nil]
               [[cat-a nil] cat-a]
               [[nil cat-b] cat-b]
               [[cat-a cat-b] merged]]]

    (doseq [[[a b] expected] cases]
      (testing (format "merge catalogs, case '%s'" [a b])
        (is (= expected (catalog/merge-catalogs a b)))))))

(deftest parse-user-addon
  (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                     {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}}]
    (with-fake-routes-in-isolation fake-routes
      (let [github-api {:uri "https://github.com/Aviana/HealComm"
                        :updated-date "2019-10-09T17:40:04Z"
                        :source "github"
                        :source-id "Aviana/HealComm"
                        :label "HealComm"
                        :name "healcomm"
                        :download-count 30946
                        :category-list []}

            cases [["https://github.com/Aviana/HealComm" github-api]]]
        (doseq [[given expected] cases]
          (testing (str "user input is routed to the correct parser")
            (is (= expected (catalog/parse-user-addon given)))))))))

