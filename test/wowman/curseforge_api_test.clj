(ns wowman.curseforge-api-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [envvar.core :refer [with-env]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [curseforge-api :as curseforge-api]
    [main :as main]
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path temp-path]]
    [core :as core]]))

(deftest expand-summary
  (testing "curseforge-api addon expansion"
    (let [api-results (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body api-results})}}

          ;; what would be seen in the catalog
          addon-summary {:created-date "2010-05-07T18:48:16Z",
                         :description "Does what no other addon does, slightly differently",
                         :category-list ["Bags & Inventory"],
                         :updated-date "2019-06-26T01:21:39Z",
                         :age "new",
                         :name "everyaddon",
                         :source "curseforge",
                         :alt-name "everyaddon",
                         :label "EveryAddon",
                         :download-count 3000000,
                         :source-id 1,
                         :uri "https://www.curseforge.com/wow/addons/everyaddon"}

          ;; what is added to figure out how to download file
          expected (merge addon-summary {:download-uri "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                                         :version "v8.2.0-v1.13.2-7135.139"
                                         :interface-version 11300 ;; "1.13.2" => 11300
                                         })]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/expand-summary addon-summary)))))))
