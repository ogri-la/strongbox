(ns strongbox.catalogue-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [logging :as logging]
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
          expected {:spec {:version 2}
                    :datestamp created
                    :total 0
                    :addon-summary-list addon-list}]
      (is (= (catalogue/format-catalogue-data addon-list created) expected)))))

(deftest merge-catalogues
  (let [addon1 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :tag-list []}

        addon2 {:url "https://github.com/Ravendwyr/Chinchilla"
                :updated-date "2019-10-09T17:40:04Z"
                :source "github"
                :source-id "Ravendwyr/Chinchilla"
                :label "Chinchilla"
                :name "chinchilla"
                :download-count 30946
                :tag-list []}

        cat-a (catalogue/new-catalogue [addon1])
        cat-b (catalogue/new-catalogue [addon2])

        merged (catalogue/new-catalogue [addon1 addon2])

        cases [[[nil nil] nil]
               [[cat-a nil] cat-a]
               [[nil cat-b] cat-b]
               [[cat-a cat-b] merged]]]

    (doseq [[[a b] expected] cases]
      (testing (format "merging of two catalogues, case '%s'" [a b])
        (is (= expected (catalogue/merge-catalogues a b))))))

  (let [addon1 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2001-01-01T00:00:00Z" ;; <=
                :description "???" ;; <=
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :tag-list []}

        addon2 {:url "https://github.com/Aviana/HealComm"
                :updated-date "2019-10-09T17:40:04Z" ;; <=
                :source "github"
                :source-id "Aviana/HealComm"
                :label "HealComm"
                :name "healcomm"
                :download-count 30946
                :tag-list []}

        cat-a (catalogue/new-catalogue [addon1])
        cat-b (catalogue/new-catalogue [addon2])

        ;; addon1 has been overwritten by data in addon2
        ;; this means changes will accumulate until the addon summary is refreshed
        merged (catalogue/new-catalogue [(assoc addon2 :description "???")])]

    (testing "old catalogue data is replaced by newer catalogue data"
      (is (= merged (catalogue/merge-catalogues cat-a cat-b))))))

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
                        :tag-list []}

            cases [["https://github.com/Aviana/HealComm" github-api]]]
        (doseq [[given expected] cases]
          (testing (str "user input is routed to the correct parser")
            (is (= expected (catalogue/parse-user-string given)))))))))

(deftest parse-user-string-router--bad-cases
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

(deftest read-catalogue
  (let [v1-catalogue-path (fixture-path "catalogue--v1.json")
        v2-catalogue-path (fixture-path "catalogue--v2.json")

        expected-addon-list
        [{:download-count 1077,
          :game-track-list [:retail],
          :label "$old!it",
          :name "$old-it",
          :source "wowinterface",
          :source-id 21651,
          :tag-list [:auction-house :vendors],
          :updated-date "2012-09-20T05:32:00Z",
          :url "https://www.wowinterface.com/downloads/info21651"}
         {:created-date "2019-04-13T15:23:09.397Z",
          :description "A New Simple Percent",
          :download-count 1034,
          :label "A New Simple Percent",
          :name "a-new-simple-percent",
          :source "curseforge",
          :source-id 319346,
          :tag-list [:unit-frames],
          :updated-date "2019-10-29T22:47:42.463Z",
          :url "https://www.curseforge.com/wow/addons/a-new-simple-percent"}
         {:description "Skins for AddOns",
          :download-count 1112033,
          :game-track-list [:retail],
          :label "AddOnSkins",
          :name "addonskins",
          :source "tukui",
          :source-id 3,
          :tag-list [:ui],
          :updated-date "2019-11-17T23:02:23Z",
          :url "https://www.tukui.org/addons.php?id=3"}
         {:download-count 9,
          :game-track-list [:retail :classic],
          :label "Chinchilla",
          :name "chinchilla",
          :source "github",
          :source-id "Ravendwyr/Chinchilla",
          :tag-list [],
          :updated-date "2019-10-19T15:07:07Z",
          :url "https://github.com/Ravendwyr/Chinchilla"}]

        expected {:spec {:version 2}
                  :datestamp "2020-02-20"
                  :total 4
                  :addon-summary-list expected-addon-list}]

    (testing "a v1 (wowman-era) catalogue spec can be processed and coerced to a valid v2 spec"
      (is (= expected (catalogue/read-catalogue v1-catalogue-path))))

    (testing "a v2 (strongbox-era) catalogue spec can be read and validated as a v2 spec"
      (is (= expected (catalogue/read-catalogue v2-catalogue-path))))))

(deftest read-bad-catalogue
  (let [catalogue-with-bad-date
        {:spec {:version 2}
         :datestamp "foo" ;; not valid
         :total 0
         :addon-summary-list []}

        catalogue-with-bad-total
        {:spec {:version 2}
         :datestamp "2001-01-01" ;; not valid
         :total "foo"
         :addon-summary-list []}

        catalogue-with-incorrect-total
        {:spec {:version 2}
         :datestamp "2001-01-01" ;; not valid
         :total 999
         :addon-summary-list []}]

    (testing "catalogue with a bad date yields `nil`"
      (is (nil? (catalogue/validate catalogue-with-bad-date))))

    (testing "catalogue with a bad total yields `nil`"
      (is (nil? (catalogue/validate catalogue-with-bad-total))))

    (testing "catalogue with an incorrect total yields `nil`"
      (is (nil? (catalogue/validate catalogue-with-incorrect-total))))))

(deftest github-500-error
  (testing "a github 500 (internal server error) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "github" :source-id "1"}
          game-track :retail
          fake-routes {"https://api.github.com/repos/1/releases"
                       {:get (fn [req] {:status 500 :reason-phrase "Internal Server Error"})}}
          expected ["failed to download file 'https://api.github.com/repos/1/releases': Internal Server Error (HTTP 500)"
                    "Github: api is down. Check www.githubstatus.com and try again later."
                    "no release found for 'foo' (retail) on github"]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track))))))))

(deftest github-api-500-error
  (testing "a github api 500 (internal server error) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "github" :source-id "1"}
          game-track :retail
          fake-routes {"https://api.github.com/repos/1/releases"
                       {:get (fn [req] {:status 500 :reason-phrase "Internal Server Error"})}}
          expected ["failed to download file 'https://api.github.com/repos/1/releases': Internal Server Error (HTTP 500)"
                    "Github: api is down. Check www.githubstatus.com and try again later."
                    "no release found for 'foo' (retail) on github"]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track))))))))

(deftest curseforge-502-bad-gateway
  (testing "a curseforge 502 (bad gateway) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "281321"}
          game-track :retail
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/281321"
                       {:get (fn [req] {:status 502 :reason-phrase "Gateway Time-out (HTTP 502)"})}}
          expected ["failed to download file 'https://addons-ecs.forgesvc.net/api/v2/addon/281321': Gateway Time-out (HTTP 502) (HTTP 502)"
                    "Curseforge: the API is having problems right now. Try again later."
                    "no release found for 'foo' (retail) on curseforge"]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track))))))))

(deftest curseforge-504-gateway-timeout
  (testing "a 504 (gateway timeout) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "281321"}
          game-track :retail
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/281321"
                       {:get (fn [req] {:status 504 :reason-phrase "Gateway Time-out (HTTP 504)"})}}
          expected ["failed to download file 'https://addons-ecs.forgesvc.net/api/v2/addon/281321': Gateway Time-out (HTTP 504) (HTTP 504)"
                    "Curseforge: the API is having problems right now. Try again later."
                    "no release found for 'foo' (retail) on curseforge"]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track))))))))

;; retail

(deftest expand-summary--retail-strict
  (testing "when just retail is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :retail
          response (slurp (fixture-path "curseforge-api-addon--retail.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                    :interface-version 90000,
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                                    :game-track :retail,
                                    :interface-version 90000,
                                    :release-label "[WoW 9.0.1] Pawn-2.4.5",
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track))))))

  (testing "when just classic is available, use nothing"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :retail
          response (slurp (fixture-path "curseforge-api-addon--classic.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track))))))

  (testing "when both retail and classic are available, use retail"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :retail
          response (slurp (fixture-path "curseforge-api-addon--retail-AND-classic.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                    :interface-version 90000,
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                                    :game-track :retail,
                                    :interface-version 90000,
                                    :release-label "[WoW 9.0.1] Pawn-2.4.5",
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track)))))))

(deftest expand-summary--retail-then-classic
  (testing "when just classic is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :retail-classic
          response (slurp (fixture-path "curseforge-api-addon--classic.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                    :interface-version 11300,
                    :version "2.4.5 (Classic)"
                    :game-track :classic
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                                    :game-track :classic,
                                    :interface-version 11300,
                                    :release-label "[WoW 1.13.5] Pawn-2.4.5-Classic",
                                    :version "2.4.5 (Classic)"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track)))))))

;; classic

(deftest expand-summary--classic-strict
  (testing "when just classic is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :classic
          response (slurp (fixture-path "curseforge-api-addon--classic.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                    :interface-version 11300,
                    :version "2.4.5 (Classic)"
                    :game-track :classic
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                                    :game-track :classic,
                                    :interface-version 11300,
                                    :release-label "[WoW 1.13.5] Pawn-2.4.5-Classic",
                                    :version "2.4.5 (Classic)"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track))))))

  (testing "when just retail is available, use nothing"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :classic
          response (slurp (fixture-path "curseforge-api-addon--retail.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track))))))

  (testing "when both retail and classic are available, use classic"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :classic
          response (slurp (fixture-path "curseforge-api-addon--retail-AND-classic.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                    :interface-version 11300,
                    :version "2.4.5 (Classic)"
                    :game-track :classic
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/60/Pawn-2.4.5-Classic.zip",
                                    :game-track :classic,
                                    :interface-version 11300,
                                    :release-label "[WoW 1.13.5] Pawn-2.4.5-Classic",
                                    :version "2.4.5 (Classic)"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track)))))))

(deftest expand-summary--classic-then-retail
  (testing "when just retail is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"}
          game-track :classic-retail
          response (slurp (fixture-path "curseforge-api-addon--retail.json"))
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/4646"
                       {:get (fn [req] {:status 200 :body response})}}
          expected {:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                    :interface-version 90000,
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                                    :game-track :retail,
                                    :interface-version 90000,
                                    :release-label "[WoW 9.0.1] Pawn-2.4.5",
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track)))))))

(deftest expand-summary--pinned
  (testing "when an addon is pinned, look for it's release in the list of releases returned from the host"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"
                 :installed-version "1.2.3" :pinned-version "1.2.0"}
          game-track :retail
          fixture [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                    :game-track :retail,
                    :interface-version 90000,
                    :release-label "[WoW 9.0.1] Pawn-1.2.3.zip",
                    :version "1.2.3"}
                   ;; pinned release
                   {:download-url "https://edge.forgecdn.net/files/3104/062/Addon-2.4.5.zip",
                    :game-track :retail,
                    :interface-version 90000,
                    :release-label "[WoW 9.0.1] Addon-1.2.0",
                    :version "1.2.0"}]
          expected (-> addon
                       (merge {:release-list fixture}
                              (second fixture))
                       (dissoc :release-label))]
      (with-fake-routes-in-isolation {}
        ;; omg, with-redefs is fantastic
        (with-redefs [strongbox.curseforge-api/expand-summary (constantly fixture)]
          (is (= expected (catalogue/expand-summary addon game-track)))))))

  (testing "when a pinned addon cannot find it's pinned release, use the latest release available"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "4646"
                 :installed-version "0.9.9" :pinned-version "0.9.9"}
          game-track :retail
          fixture [{:download-url "https://edge.forgecdn.net/files/3104/62/Pawn-2.4.5.zip",
                    :game-track :retail,
                    :interface-version 90000,
                    :release-label "[WoW 9.0.1] Pawn-1.2.3",
                    :version "1.2.3"}
                   {:download-url "https://edge.forgecdn.net/files/3104/062/Addon-2.4.5.zip",
                    :game-track :retail,
                    :interface-version 90000,
                    :release-label "[WoW 9.0.1] Addon-1.2.0",
                    :version "1.2.0"}]
          expected (-> addon
                       (merge {:release-list fixture}
                              (first fixture))
                       (dissoc :release-label))]
      (with-fake-routes-in-isolation {}
        ;; omg, with-redefs is fantastic
        (with-redefs [strongbox.curseforge-api/expand-summary (constantly fixture)]
          (is (= expected (catalogue/expand-summary addon game-track))))))))
