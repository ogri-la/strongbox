(ns strongbox.catalogue-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [catalogue :as catalogue]
    [test-helper :refer [fixture-path]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest de-dupe-wowinterface
  (testing "given multiple addons with the same name, the most recently updated one is preferred"
    (let [fixture [{:name "adibags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected [{:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]]
      (is (= (catalogue/de-dupe-wowinterface fixture) expected))))

  (testing "given multiple addons with distinct names, all addons are returned"
    (let [fixture [{:name "adi-bags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "baggy-adidas" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected fixture]
      (is (= (catalogue/de-dupe-wowinterface fixture) expected)))))

(deftest format-catalogue-data
  (testing "catalogue data has a consistent structure"
    (let [addon-list []
          created "2001-01-01"
          updated created
          expected {:spec {:version 1}
                    :datestamp created
                    :updated-datestamp updated
                    :total 0
                    :addon-summary-list addon-list}]
      (is (= (catalogue/format-catalogue-data addon-list created updated) expected)))))

(deftest merge-curse-wowi-catalogs
  (testing "dates are correct after a merge"
    (let [aa {:datestamp "2001-01-01" :updated-datestamp "2001-01-02" :spec {:version 1} :addon-summary-list [] :total 0}
          ab {:datestamp "2001-01-03" :updated-datestamp "2001-01-04" :spec {:version 1} :addon-summary-list [] :total 0}
          expected {:spec {:version 1}
                    :datestamp "2001-01-01"
                    :updated-datestamp "2001-01-04"
                    :total 0
                    :addon-summary-list []}]
      (is (= (catalogue/-merge-curse-wowi-catalogs aa ab) expected)))))

(deftest merge-catalogs
  (let [addon1 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :category-list []}

        addon2 {:url "https://github.com/Ravendwyr/Chinchilla"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Ravendwyr/Chinchilla"
                :label "Chinchilla"
                :name "chinchilla"
                :download-count 30946
                :category-list []}

        cat-a (catalogue/new-catalogue [addon1])
        cat-b (catalogue/new-catalogue [addon2])

        merged (catalogue/new-catalogue [addon1 addon2])

        cases [[[nil nil] nil]
               [[cat-a nil] cat-a]
               [[nil cat-b] cat-b]
               [[cat-a cat-b] merged]]]

    (doseq [[[a b] expected] cases]
      (testing (format "merging of two catalogs, case '%s'" [a b])
        (is (= expected (catalogue/merge-catalogs a b))))))

  (let [addon1 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2001-01-01T00:00:00Z" ;; <=
                :description "???" ;; <=
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :category-list []}

        addon2 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2019-10-09T17:40:04Z" ;; <=
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :category-list []}

        cat-a (catalogue/new-catalogue [addon1])
        cat-b (catalogue/new-catalogue [addon2])

        ;; addon1 has been overwritten by data in addon2
        ;; this means changes will accumulate until the addon summary is refreshed
        merged (catalogue/new-catalogue [(assoc addon2 :description "???")])]

    (testing "old catalogue data is replaced by newer catalogue data"
      (is (= merged (catalogue/merge-catalogs cat-a cat-b))))))

(deftest parse-user-string-router
  (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                     {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}

                     "https://api.github.com/repos/Aviana/HealComm/contents"
                     {:get (fn [req] {:status 200 :body "[]"})}}]
    (with-fake-routes-in-isolation fake-routes
      (let [github-api {:url "https://github.com/Aviana/HealComm"
                        :updated-date "2019-10-09T17:40:04Z"
                        :source "github"
                        :source-id "Aviana/HealComm"
                        :label "HealComm"
                        :name "healcomm"
                        :download-count 30946
                        :game-track-list []
                        :category-list []}

            cases [["https://github.com/Aviana/HealComm" github-api]]]
        (doseq [[given expected] cases]
          (testing (str "user input is routed to the correct parser")
            (is (= expected (catalogue/parse-user-string given))))))))

  (let [cases [""
               "foo"
               "https"
               "https://"
               "https://foo"
               "https://foo.com"
               "//foo.com"
               "foo.com"]
        expected nil]
    (doseq [given cases]
      (testing (format "parsing bad user string input, case: '%s'" given)
        (is (= expected (catalogue/parse-user-string given)))))))

