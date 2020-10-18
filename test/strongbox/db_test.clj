(ns strongbox.db-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.db :as db]))

(deftest put-many
  (testing "putting nothing into an empty database returns an empty database"
    (let [expected []]
      (is (= expected (db/put-many [] []))))))

(deftest db-match-installed-addons-with-catalogue
  (testing "matched addons return a map of useful information"
    (let [toc {:name "every-addon"
               :label "Every Addon"
               :description "foo"
               :dirname "EveryAddon"
               :interface-version 70000
               :installed-version "v8.10.00"}
          installed-addon-list [toc]

          catalogue-entry {:name "every-addon",
                           :label "Every Addon"
                           :tag-list [],
                           :download-count 1
                           :source "curseforge",
                           :source-id 0
                           :updated-date "2012-09-20T05:32:00Z",
                           :url "https://www.curseforge.com/wow/addons/every-addon"}
          db [catalogue-entry]

          expected [{;; how they were matched
                     :idx [[:name] [:name]]
                     ;; the value they were matched on
                     :key ["every-addon"]
                     ;; convenient flag for having matched
                     :matched? true
                     ;; catalogue entry that was matched
                     :catalogue-match catalogue-entry
                     ;; installed addon that was matched
                     :installed-addon toc}]]
      (is (= expected (db/-db-match-installed-addons-with-catalogue db installed-addon-list))))))

(deftest db-match-installed-addons-with-catalogue--ignored-addons-are-skipped
  (testing "ignored addons are not matched to the catalogue and always return themselves"
    (let [toc {:name "every-addon"
               :label "Every Addon"
               :description "foo"
               :dirname "EveryAddon"
               :interface-version 70000
               :installed-version "v8.10.00"
               :ignore? true}
          installed-addon-list [toc]

          db [{:name "every-addon",
               :label "Every Addon"
               :tag-list [],
               :download-count 1
               :source "curseforge",
               :source-id 0
               :updated-date "2012-09-20T05:32:00Z",
               :url "https://www.curseforge.com/wow/addons/every-addon"}]

          expected [toc]]
      (is (= expected (db/-db-match-installed-addons-with-catalogue db installed-addon-list))))))
