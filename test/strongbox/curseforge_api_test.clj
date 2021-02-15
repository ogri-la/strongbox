(ns strongbox.curseforge-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [curseforge-api :as curseforge-api]
    [test-helper :as helper :refer [fixture-path]]]))

(deftest expand-summary
  (testing "simple addon expansion, ideal conditions"
    (let [api-results (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body api-results})}}

          game-track :retail

          ;; what would be seen in the catalogue
          addon-summary {:created-date "2010-05-07T18:48:16Z",
                         :description "Does what no other addon does, slightly differently",
                         :tag-list [:bags :inventory]
                         :updated-date "2019-06-26T01:21:39Z",
                         :name "everyaddon",
                         :source "curseforge",
                         :label "EveryAddon",
                         :download-count 3000000,
                         :source-id 1,
                         :url "https://www.curseforge.com/wow/addons/everyaddon"}

          ;; what is added to figure out how to download file
          expected [{:download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                     :version "v8.2.0-v1.13.2-7135.139"
                     :interface-version 80000 ;; "8.0.1" => 80000
                     :release-label "[WoW 8.0.1] EveryAddon-v8.2.0-v1.13.2-7135.139"
                     :game-track game-track}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/expand-summary addon-summary game-track)))))))

(deftest expand-summary--no-matching-release
  (testing "addon expansion when selected game track doesn't match anything available in releases"
    (let [api-results (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body api-results})}}
          addon-summary {:created-date "2010-05-07T18:48:16Z",
                         :description "Does what no other addon does, slightly differently",
                         :tag-list [:bags :inventory]
                         :updated-date "2019-06-26T01:21:39Z",
                         :name "everyaddon",
                         :source "curseforge",
                         :label "EveryAddon",
                         :download-count 3000000,
                         :source-id 1,
                         :url "https://www.curseforge.com/wow/addons/everyaddon"}
          game-track :classic
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/expand-summary addon-summary game-track)))))))

(deftest group-releases
  ;; todo: we are testing multiple things here:
  ;; gameVersionFlavor grouping when gameVersion is absent
  ;; excluding non-stable and alternate releases
  (testing "releases are filtered and grouped correctly when `:gameVersion` is not present (unstable and alternate releases are not considered)."
    (let [[alpha, beta, stable] [3 2 1]
          latest-files [;; retail versions
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-03T00:00:00.000Z", :releaseType alpha, :exposeAsAlternative nil
                         :displayName "1.2.3-alpha" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3-alpha.zip"}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-02T00:00:00.000Z", :releaseType beta, :exposeAsAlternative nil
                         :displayName "1.2.3-beta" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3-beta.zip"}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.3" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3.zip"}
                        {:gameVersionFlavor "wow_retail", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative true
                         :displayName "1.2.3-nolib" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3-no-lib.zip"}

                        ;; classic versions, mirror retail releases
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-03T00:00:00.000Z", :releaseType alpha, :exposeAsAlternative nil
                         :displayName "a.b.c-nolib" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/a.b.c-alpha.zip"}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-02T00:00:00.000Z", :releaseType beta, :exposeAsAlternative nil
                         :displayName "a.b.c-beta" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/a.b.c-beta.zip"}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "a.b.c" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/a.b.c.zip"}
                        {:gameVersionFlavor "wow_classic", :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative true
                         :displayName "a.b.c-nolib" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/a.b.c-no-lib.zip"}]

          fixture {:latestFiles latest-files}
          expected {:retail [{:download-url "https://example.org/path/to/1.2.3.zip"
                              :version "1.2.3"
                              :game-track :retail
                              :release-label "[WoW 9.0.3] Foo",
                              ;; synthetic, we had to guess using `:gameVersionFlavor`
                              :interface-version 90000}]
                    :classic [{:download-url "https://example.org/path/to/a.b.c.zip"
                               :version "a.b.c"
                               :game-track :classic
                               :release-label "[WoW 1.13.5] Foo"
                               ;; synthetic, we had to guess using `:gameVersionFlavor`
                               :interface-version 11300}]}]
      (is (= expected (curseforge-api/group-releases fixture)))))

  (testing "a release using both `:gameVersionFlavor` and a list of supported `:gameVersion` game tracks ignores `:gameVersionFlavor` and is expanded into multiple releases"
    (let [stable 1
          latest-files [{:gameVersionFlavor "wow_retail", :gameVersion ["1.13.1" "8.2.5"]
                         :fileDate "2001-01-03T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.3" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/foo.zip"}]
          fixture {:latestFiles latest-files}

          expected {:retail [{:download-url "https://example.org/path/to/foo.zip"
                              :version "1.2.3"
                              :game-track :retail
                              :release-label "[WoW 1.13.1] Foo",
                              :interface-version 80200}]
                    :classic [{:download-url "https://example.org/path/to/foo.zip"
                               :version "1.2.3"
                               :game-track :classic
                               :release-label "[WoW 1.13.1] Foo",
                               :interface-version 11300}]}]
      (is (= expected (curseforge-api/group-releases fixture)))))

  (testing "multiple releases supporting mixed, multiple, game tracks are expanded and ordered correctly"
    (let [stable 1
          latest-files [{:gameVersionFlavor "wow_retail", :gameVersion ["8.2.5"]
                         :fileDate "2001-01-03T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.4" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.4.zip"}

                        {:gameVersionFlavor "wow_retail", :gameVersion ["1.13.1" "8.2.5"]
                         :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.3" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3.zip"}]
          fixture {:latestFiles latest-files}

          expected {;; retail versions available from 2001-01-03 and 2001-01-01 releases
                    :retail [{:download-url "https://example.org/path/to/1.2.4.zip"
                              :version "1.2.4"
                              :game-track :retail
                              :release-label "[WoW 8.2.5] Foo"
                              :interface-version 80200}
                             {:download-url "https://example.org/path/to/1.2.3.zip"
                              :version "1.2.3"
                              :game-track :retail
                              :release-label "[WoW 1.13.1] Foo",
                              :interface-version 80200}]

                    ;; classic version available from the 2001-01-01 release
                    :classic [{:download-url "https://example.org/path/to/1.2.3.zip"
                               :version "1.2.3"
                               :game-track :classic
                               :release-label "[WoW 1.13.1] Foo",
                               :interface-version 11300}]}]
      (is (= expected (curseforge-api/group-releases fixture)))))

  (testing "use `:gameVersionFlavor` to decide the game track when `:gameVersion` is empty."
    (let [stable 1
          latest-files [{:gameVersionFlavor "wow_classic", :gameVersion []
                         :fileDate "2019-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.4" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.4.zip"}

                        {:gameVersionFlavor "wow_retail", :gameVersion ["1.13.1" "8.2.5"]
                         :fileDate "2001-01-01T00:00:00.000Z", :releaseType stable, :exposeAsAlternative nil
                         :displayName "1.2.3" :fileName "Foo.zip" :downloadUrl "https://example.org/path/to/1.2.3.zip"}]
          fixture {:latestFiles latest-files}

          expected {:retail [{:download-url "https://example.org/path/to/1.2.3.zip"
                              :version "1.2.3"
                              :game-track :retail
                              :release-label "[WoW 1.13.1] Foo",
                              :interface-version 80200}]

                    :classic [{:download-url "https://example.org/path/to/1.2.4.zip"
                               :version "1.2.4"
                               :game-track :classic
                               :release-label "[WoW 1.13.5] Foo",
                               :interface-version 11300}
                              {:download-url "https://example.org/path/to/1.2.3.zip"
                               :version "1.2.3"
                               :game-track :classic
                               :release-label "[WoW 1.13.1] Foo",
                               :interface-version 11300}]}]
      (is (= expected (curseforge-api/group-releases fixture))))))

(deftest extract-addon-summary
  (testing "data extracted from curseforge api 'search' results is correct"
    (let [fixture (slurp (fixture-path "curseforge-api-search--truncated.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/search?gameId=1&index=0&pageSize=255&searchFilter=&sort=3"
                       {:get (fn [req] {:status 200 :body fixture})}}
          expected [{:created-date "2016-05-09T17:21:30.1Z",
                     :description "Restores access to removed interface options in Legion",
                     :tag-list [:misc],
                     :updated-date "2019-08-30T14:39:44.943Z",
                     :name "advancedinterfaceoptions",
                     :label "AdvancedInterfaceOptions",
                     :download-count 2923589,
                     :source "curseforge"
                     :source-id 99982,
                     :url "https://www.curseforge.com/wow/addons/advancedinterfaceoptions"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (curseforge-api/download-all-summaries-alphabetically)))))))

;;

(deftest download-addon-404
  (testing "regular addon fetch that yields a 404 returns nil"
    (let [;; listed in the curseforge catalogue but returns (returned) a 404 when fetched
          zombie-addon {:name "Brewmaster Tools"
                        :url "https://www.curseforge.com/wow/addons/brewmastertools"
                        :label ""
                        :tag-list []
                        :updated-date "2019-01-01T00:00:00Z"
                        :download-count 0
                        :source-id 1
                        :source "curseforge"}
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (nil? (curseforge-api/expand-summary zombie-addon :retail)))))))

;;

(deftest release-download-url
  (let [cases [[1234 "foo.zip" "https://edge.forgecdn.net/files/1/234/foo.zip"]
               [12345 "foo.zip" "https://edge.forgecdn.net/files/12/345/foo.zip"]
               [123456 "foo.zip" "https://edge.forgecdn.net/files/123/456/foo.zip"]
               [1234567 "foo.zip" "https://edge.forgecdn.net/files/1234/567/foo.zip"]
               [12345678 "foo.zip" "https://edge.forgecdn.net/files/12345/678/foo.zip"]
               [123456789 "foo.zip" "https://edge.forgecdn.net/files/123456/789/foo.zip"]
               [1234567899 "foo.zip" "https://edge.forgecdn.net/files/1234567/899/foo.zip"]
               ;; actual examples
               [842942 "DraenorTreasures-r20141229205945.zip" "https://edge.forgecdn.net/files/842/942/DraenorTreasures-r20141229205945.zip"]
               ;; leading zeroes stripped on second bit to more closely match URLs from `:latestFiles`
               [3117033 "CanIMogIt-9.0.2v1.30.zip" "https://edge.forgecdn.net/files/3117/33/CanIMogIt-9.0.2v1.30.zip"]
               [2731023 "xptracker.zip" "https://edge.forgecdn.net/files/2731/23/xptracker.zip"]]]

    (doseq [[project-file-id project-file-name expected] cases]
      (is (= expected (curseforge-api/release-download-url project-file-id project-file-name))))))

(deftest older-releases
  (testing "bad cases"
    (let [cases [[[] []]
                 [[{}] []]]]
      (doseq [[given expected] cases]
        (is (= expected (curseforge-api/older-releases given))))))

  (testing "single release"
    (let [given [{:fileType 1 :projectFileId 123456 :projectFileName "Foo.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}]
          expected [{:download-url "https://edge.forgecdn.net/files/123/456/Foo.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.3] Foo",
                     :version "Foo"}]]
      (is (= expected (curseforge-api/older-releases given)))))

  (testing "multiple releases, only stable releases returned"
    (let [given [{:fileType 1 :projectFileId 123456 :projectFileName "Foo.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}
                 {:fileType 2 :projectFileId 123457 :projectFileName "Foo-beta.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}
                 {:fileType 3 :projectFileId 123458 :projectFileName "Foo-alpha.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}]
          expected [{:download-url "https://edge.forgecdn.net/files/123/456/Foo.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.3] Foo",
                     :version "Foo"}]]
      (is (= expected (curseforge-api/older-releases given)))))

  (testing "multiple releases, multiple stable"
    (let [given [{:fileType 1 :projectFileId 123456 :projectFileName "Foo-v2.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}
                 {:fileType 1 :projectFileId 123457 :projectFileName "Foo-v1.zip" :gameVersion "8.0.0" :gameVersionFlavor "wow_classic"}]
          expected [{:download-url "https://edge.forgecdn.net/files/123/456/Foo-v2.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.3] Foo-v2",
                     :version "Foo-v2"}
                    {:download-url "https://edge.forgecdn.net/files/123/457/Foo-v1.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.0] Foo-v1",
                     :version "Foo-v1"}]]
      (is (= expected (curseforge-api/older-releases given)))))

  (testing "multiple releases, multiple stable, ambiguous naming"
    (let [given [{:fileType 1 :projectFileId 123456 :projectFileName "Foo.zip" :gameVersion "8.0.3" :gameVersionFlavor "wow_classic"}
                 {:fileType 1 :projectFileId 123457 :projectFileName "Foo.zip" :gameVersion "8.0.0" :gameVersionFlavor "wow_classic"}]
          expected [{:download-url "https://edge.forgecdn.net/files/123/456/Foo.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.3] Foo--123456",
                     :version "Foo--123456"}
                    {:download-url "https://edge.forgecdn.net/files/123/457/Foo.zip",
                     :game-track :classic,
                     :interface-version 80000,
                     :release-label "[WoW 8.0.0] Foo--123457",
                     :version "Foo--123457"}]]
      (is (= expected (curseforge-api/older-releases given))))))

(deftest strip-leading-duplicates
  (testing "second addon is removed if it's url matches the first. third is preserved."
    (let [given [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                  :game-track :retail,
                  :interface-version 90000,
                  :release-label "[WoW 9.0.1] Pawn-2.4.5.zip",
                  :version "2.4.5"}
                 {:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                  :game-track :retail,
                  :interface-version 90000,
                  :release-label "[WoW 9.0.1] Pawn-2.4.5.zip",
                  :version "Pawn-2.4.5.zip"}
                 {:download-url "https://edge.forgecdn.net/files/3100/42/Pawn-2.4.0.zip",
                  :game-track :retail,
                  :interface-version 90000,
                  :release-label "[WoW 9.0.0] Pawn-2.4.0.zip",
                  :version "Pawn-2.4.0.zip"}]
          expected (concat [(first given)] (rest (rest given)))]
      (is (= expected (curseforge-api/prune-leading-duplicates given)))))

  (testing "single release isn't modified"
    (let [given [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                  :game-track :retail,
                  :interface-version 90000,
                  :release-label "[WoW 9.0.1] Pawn-2.4.5.zip",
                  :version "2.4.5"}]
          expected given]
      (is (= expected (curseforge-api/prune-leading-duplicates given)))))

  (testing "empty/nil releases return nil"
    (let [cases [[[] nil]
                 [nil nil]]]
      (doseq [[given expected] cases]
        (is (= expected (curseforge-api/prune-leading-duplicates given)))))))

