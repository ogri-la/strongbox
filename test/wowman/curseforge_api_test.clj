(ns wowman.curseforge-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [curseforge-api :as curseforge-api]
    [test-helper :as helper :refer [fixture-path temp-path]]]))

(deftest expand-summary
  (testing "simple addon expansion, ideal conditions"
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
                                         })
          game-track "retail"]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/expand-summary addon-summary game-track))))))

  (testing "addon expansion when selected game track doesn't match anything available in releases"
    (let [api-results (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body api-results})}}
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
          game-track "classic"
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/expand-summary addon-summary game-track)))))))

(deftest latest-versions-by-gameVersionFlavor
  (testing "data in :latestFiles is filtered and grouped correctly"
    (let [[alpha, beta, stable] [3 2 1]
          latest-files [;; retail versions
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-03T00:00:00.000Z", :releaseType alpha, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-02T00:00:00.000Z", :releaseType beta, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative true}

                        ;; classic versions, mirror retail releases
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-03T00:00:00.000Z", :releaseType alpha, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-02T00:00:00.000Z", :releaseType beta, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative true}]

          fixture {:latestFiles latest-files}
          expected {"wow_retail" [{:gameVersionFlavor "wow_retail", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil}]
                    "wow_classic" [{:gameVersionFlavor "wow_classic", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil}]}]
      (is (= expected (curseforge-api/latest-versions-by-gameVersionFlavor fixture))))))

(deftest extract-addon-summary
  (testing "data extracted from curseforge api 'search' results is correct"
    (let [fixture (slurp (fixture-path "curseforge-api-search--truncated.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/search?gameId=1&index=0&pageSize=255&searchFilter=&sort=3"
                       {:get (fn [req] {:status 200 :body fixture})}}
          expected [{:created-date "2016-05-09T17:21:30.1Z",
                     :description "Restores access to removed interface options in Legion",
                     :category-list '("Miscellaneous" "Miscellaneous"),
                     :updated-date "2019-08-30T14:39:44.943Z",
                     :name "advancedinterfaceoptions",
                     :alt-name "advancedinterfaceoptions",
                     :label "AdvancedInterfaceOptions",
                     :download-count 2923589,
                     :source-id 99982,
                     :uri "https://www.curseforge.com/wow/addons/advancedinterfaceoptions"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/download-all-summaries-alphabetically)))))))

