(ns strongbox.catalogue-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [constants :as constants]
    [logging :as logging]
    [catalogue :as catalogue]
    [test-helper :as helper :refer [fixture-path]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

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
  (testing "merging of two catalogues"
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
        (is (= expected (catalogue/merge-catalogues a b))))))

  (testing "old catalogue data is replaced by newer catalogue data"
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
      (is (= merged (catalogue/merge-catalogues cat-a cat-b))))))

(deftest parse-user-string-router
  (testing "standard urls get routed and their results are turned into maps properly"
    (let [cases [["https://github.com/Aviana/HealComm" {:source "github" :source-id "Aviana/HealComm"}]
                 ["https://www.wowinterface.com/downloads/info8882" {:source "wowinterface" :source-id 8882}]

                 ["https://www.tukui.org/download.php?ui=tukui" {:source "tukui" :source-id -1}]
                 ["https://www.tukui.org/addons.php?id=38" {:source "tukui" :source-id 38}]
                 ["https://www.tukui.org/classic-addons.php?id=3" {:source "tukui-classic" :source-id 3}]
                 ["https://www.tukui.org/classic-tbc-addons.php?id=21" {:source "tukui-classic-tbc" :source-id 21}]
                 ["https://www.tukui.org/classic-wotlk-addons.php?id=21" {:source "tukui-classic-wotlk" :source-id 21}]

                 ;; edge cases that utils/unmangle-url fix
                 ["github.com/Aviana/HealComm" {:source "github" :source-id "Aviana/HealComm"}]
                 ["github.com/Aviana/HealComm/foo/bar/baz" {:source "github" :source-id "Aviana/HealComm"}]
                 ["github.com/Aviana/HealComm/foo/bar#anc?baz=bup" {:source "github" :source-id "Aviana/HealComm"}]

                 ["//github.com/Aviana/HealComm" {:source "github" :source-id "Aviana/HealComm"}]
                 ["//github.com/Aviana/HealComm/foo/bar/baz" {:source "github" :source-id "Aviana/HealComm"}]
                 ["//github.com/Aviana/HealComm/foo/bar/baz#anc?baz=bup" {:source "github" :source-id "Aviana/HealComm"}]]]

      (doseq [[given expected] cases]
        (is (= expected (catalogue/parse-user-string given)), (str "failed on url " given))))))

(deftest parse-user-string-router--bad-cases
  (let [cases [""
               "    "
               " \n "
               "foo"
               " foo "
               "213"
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
  (let [v2-catalogue-path (fixture-path "catalogue--v2.json")

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

(deftest shorten-catalogue
  (testing "a catalogue can be shortened by removing all addons before a cut off date"
    (let [catalogue
          {:spec {:version 2}
           :datestamp "2020-02-20"
           :total 3
           :addon-summary-list
           [{:updated-date "2019-10-19T01:01:01Z",
             :download-count 9,
             :game-track-list [:retail :classic],
             :label "Chinchilla",
             :name "chinchilla",
             :source "github",
             :source-id "Ravendwyr/Chinchilla",
             :tag-list [],
             :url "https://github.com/Ravendwyr/Chinchilla"}

            {:updated-date "2019-10-29T01:01:01Z",
             :created-date "2019-04-13T15:23:09.397Z",
             :description "A New Simple Percent",
             :download-count 1034,
             :label "A New Simple Percent",
             :name "a-new-simple-percent",
             :source "curseforge",
             :source-id 319346,
             :tag-list [:unit-frames],
             :url "https://www.curseforge.com/wow/addons/a-new-simple-percent"}

            {:updated-date "2019-11-19T01:01:01Z",
             :description "Skins for AddOns",
             :download-count 1112033,
             :game-track-list [:retail],
             :label "AddOnSkins",
             :name "addonskins",
             :source "tukui",
             :source-id 3,
             :tag-list [:ui],
             :url "https://www.tukui.org/addons.php?id=3"}]}

          cutoff "2019-11-01T01:01:01Z"
          expected (update-in catalogue [:addon-summary-list] #(vec (take-last 1 %)))
          expected (assoc expected :total 1)
          actual (catalogue/shorten-catalogue catalogue cutoff)]
      (is (= expected actual)))))

;;

(deftest github-500-error
  (testing "a github 500 (internal server error) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "github" :source-id "1"}
          game-track :retail
          strict? true
          fake-routes {"https://api.github.com/repos/1/releases"
                       {:get (fn [req] {:status 500 :reason-phrase "Internal Server Error"})}}
          expected ["failed to fetch 'https://api.github.com/repos/1/releases': Internal Server Error (HTTP 500)"
                    "Github: api is down. Check www.githubstatus.com and try again later."
                    "no 'Retail' release found on github."]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track strict?))))))))

(deftest github-api-500-error
  (testing "a github api 500 (internal server error) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "github" :source-id "1"}
          game-track :retail
          strict? true
          fake-routes {"https://api.github.com/repos/1/releases"
                       {:get (fn [req] {:status 500 :reason-phrase "Internal Server Error"})}}
          expected ["failed to fetch 'https://api.github.com/repos/1/releases': Internal Server Error (HTTP 500)"
                    "Github: api is down. Check www.githubstatus.com and try again later."
                    "no 'Retail' release found on github."]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track strict?))))))))

(deftest curseforge-502-bad-gateway
  (testing "a curseforge 502 (bad gateway) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "281321"}
          game-track :retail
          strict? true
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/281321"
                       {:get (fn [req] {:status 502 :reason-phrase "Gateway Time-out (HTTP 502)"})}}
          expected ["addon host 'curseforge' was disabled Feb 1st, 2022.\n • use 'Source' and 'Find similar' from the addon context menu for alternatives."]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track strict?))))))))

(deftest curseforge-504-gateway-timeout
  (testing "a 504 (gateway timeout) response gets a custom message"
    (let [addon {:name "foo" :label "Foo" :source "curseforge" :source-id "281321"}
          game-track :retail
          strict? true
          fake-routes {"https://addons-ecs.forgesvc.net/api/v2/addon/281321"
                       {:get (fn [req] {:status 504 :reason-phrase "Gateway Time-out (HTTP 504)"})}}
          expected ["addon host 'curseforge' was disabled Feb 1st, 2022.\n • use 'Source' and 'Find similar' from the addon context menu for alternatives."]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log :info (catalogue/expand-summary addon game-track strict?))))))))

;; retail

(deftest expand-summary--retail-strict--just-retail
  (testing "when just retail is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail]}
          game-track :retail
          strict? true
          response [{:game-track :retail
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail,
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--retail-strict--just-classic
  (testing "when just classic is available, use nothing"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646"
                 ;; 2023-06-11: test switched from curseforge to wowinterface.
                 ;; wowinterface doesn't expand to different game tracks, so what we're testing here is refusing to expand,
                 ;; rather than filtering out a classic release.
                 :game-track-list [:classic]}
          game-track :retail
          strict? true
          response [{:game-track :classic
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--retail-strict--retail-and-classic
  (testing "when both retail and classic are available, use retail"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail :classic]}
          game-track :retail
          strict? true
          response [{:game-track :retail
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646",
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646",
                                    :game-track :retail,
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--retail-then-classic
  (testing "when both retail and classic are available, game track is set to retail and and strict-mode is off, use retail."
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail :classic]}
          game-track :retail
          strict? false
          response [{:game-track :retail
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :version "2.4.5"
                    :game-track :retail
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

;; classic

(deftest expand-summary--classic-strict--just-classic
  (testing "when just classic is available, use it"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:classic]}
          game-track :classic
          strict? true
          response [{:game-track :classic
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :version "2.4.5"
                    :game-track :classic
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :classic,
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--classic-strict--just-retail
  (testing "when just retail is available, use nothing"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail]}
          game-track :classic
          strict? true
          response [{:game-track :retail
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected nil]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--classic-strict--retail-and-classic
  (testing "when both retail and classic are available, use classic"
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail :classic]}
          game-track :classic
          strict? true
          response [{:game-track :retail ;; 2023-06-09: this is interesting behaviour. retail response, classic returned
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :version "2.4.5"
                    :game-track :classic
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :classic,
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--classic-then-retail
  (testing "when both retail and classic are available, game track is set to classic and and strict-mode is off, use classic."
    (let [addon {:name "foo" :label "Foo" :source "wowinterface" :source-id "4646" :game-track-list [:retail :classic]}
          game-track :classic
          strict? false
          response [{:game-track :retail
                     :UIVersion "2.4.5"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}
          expected {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :version "2.4.5"
                    :game-track :classic
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :classic
                                    :version "2.4.5"}]}
          expected (merge addon expected)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

;; ---

(deftest expand-summary--pinned--use-pinned
  (testing "when an addon is pinned, look for it's release in the list of releases returned from the host"
    (let [addon {:name "foo"
                 :label "Foo"
                 :source "wowinterface"
                 :source-id "4646"
                 :installed-version "1.2.3"
                 :pinned-version "1.2.0"
                 :game-track-list [:retail]}
          game-track :retail
          strict? true
          expected {:name "foo"
                    :label "Foo"
                    :source "wowinterface"
                    :source-id "4646"
                    :installed-version "1.2.3"
                    :pinned-version "1.2.0"
                    :game-track-list [:retail]

                    ;; pinned release merged in
                    :download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :game-track :retail,
                    :version "1.2.0"

                    ;; pinned + new versions available in release list
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail,
                                    :version "1.2.3"}
                                   {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail,
                                    :version "1.2.0"}]}
          response [{:game-track :retail
                     :UIVersion "1.2.3"}
                    ;; pinned release
                    {:game-track :retail
                     :UIVersion "1.2.0"}]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

(deftest expand-summary--pinned--use-latest
  (testing "when a pinned addon cannot find it's pinned release, use the latest release available"
    (let [addon {:name "foo"
                 :label "Foo"
                 :source "wowinterface"
                 :source-id "4646"
                 :installed-version "0.9.9"
                 :pinned-version "0.9.9"
                 :game-track-list [:retail]}
          game-track :retail
          strict? true
          expected {:name "foo"
                    :label "Foo"
                    :source "wowinterface"
                    :source-id "4646"
                    :installed-version "0.9.9"
                    :pinned-version "0.9.9"
                    :game-track-list [:retail]

                    ;; latest release merged in
                    :download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                    :game-track :retail,
                    :version "1.2.3"

                    ;; pinned + new versions available in release list
                    :release-list [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail,
                                    :version "1.2.3"}
                                   {:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=4646"
                                    :game-track :retail,
                                    :version "1.2.0"}]}
          response [{:game-track :retail
                     :UIVersion "1.2.3"}
                    {:game-track :retail
                     :UIVersion "1.2.0"}
                    ;; pinned release missing from response
                    ;;{:game-track :retail
                    ;; :UIVersion "1.2.0"}
                    ]
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/4646.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json response)})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (catalogue/expand-summary addon game-track strict?)))))))

;;

(deftest toc2summary--plain
  (testing "plain toc data with no extras can't be coerced into an addon summary"
    (is (nil? (catalogue/toc2summary helper/toc-data)))))

(deftest toc2summary--toc+nfo
  (testing "toc with extra nfo data *can* be coerced into an addon summary with less guessing"
    (let [toc+nfo (merge helper/toc-data
                         {:group-id "https://example.org"
                          :source "wowinterface"
                          :source-id 123
                          :interface-version constants/default-interface-version-classic
                          :toc/game-track :classic
                          :supported-game-tracks [:classic]})
          expected {:label "EveryAddon 1.2.3",
                    :name "everyaddon",
                    :source "wowinterface",
                    :source-id 123

                    ;; synthetic
                    ;; 2021-12-30: changed, group-id is now ignored in favour of reconstructing the URL.
                    ;;:url "https://example.org" ;; url won't be derived from source and id
                    :url "https://www.wowinterface.com/downloads/info123"
                    :download-count 0,
                    :game-track-list [:classic], ;; classic is used
                    :tag-list [],
                    :updated-date "2001-01-01T01:01:01Z"}]
      (is (= expected (catalogue/toc2summary toc+nfo))))))

(deftest toc2summary--wowinterface
  (testing "toc data with a wowinterface source and id *can* be coerced to addon summary without a `:url`"
    (let [wowinterface-toc (merge helper/toc-data
                                  {:source "wowinterface"
                                   :source-id 123})
          expected {:label "EveryAddon 1.2.3",
                    :name "everyaddon",
                    :source "wowinterface",
                    :source-id 123

                    ;; synthetic
                    :url "https://www.wowinterface.com/downloads/info123"
                    :download-count 0,
                    :game-track-list [:retail],
                    :tag-list [],
                    :updated-date "2001-01-01T01:01:01Z"}]
      (is (= expected (catalogue/toc2summary wowinterface-toc))))))

(deftest toc2summary--tukui
  (testing "toc data with a tukui source and id can be coerced to an addon summary without a `:url`"
    (let [tukui-toc (merge helper/toc-data
                           {:source "tukui"
                            :source-id 123})
          expected {:source "tukui",
                    :source-id 123,
                    :name "everyaddon",
                    :label "EveryAddon 1.2.3",

                    ;; synthetic
                    :tag-list [],
                    :updated-date "2001-01-01T01:01:01Z",
                    :url "https://www.tukui.org/addons.php?id=123",
                    :download-count 0}]
      (is (= expected (catalogue/toc2summary tukui-toc))))))

(deftest toc2summary--curseforge
  (testing "toc data with a curseforge source and id cannot be coerced to an addon summary without a `:url`"
    (let [curseforge-toc (merge helper/toc-data
                                {:source "curseforge"
                                 :source-id 123})]
      (is (nil? (catalogue/toc2summary curseforge-toc))))))

(deftest toc2summary--github
  (testing "toc data with a github source and id can be coerced to an addon summary without a `:url`"
    (let [github-toc (merge helper/toc-data
                            {:source "github"
                             :source-id "everyman/everyaddon"})

          expected {:source "github",
                    :source-id "everyman/everyaddon",
                    :name "everyaddon",
                    :label "EveryAddon 1.2.3",

                    ;; synthetic
                    :tag-list [],
                    :updated-date "2001-01-01T01:01:01Z",
                    :url "https://github.com/everyman/everyaddon"
                    :download-count 0}]
      (is (= expected (catalogue/toc2summary github-toc))))))

(deftest toc2summary--ignored
  (testing "any data that is ignored cannot be coerced to an addon summary, even if all the right data is there."
    (let [wowi-toc (merge helper/toc-data
                          {:source "wowinterface"
                           :source-id 123
                           :ignore? true})]
      (is (nil? (catalogue/toc2summary wowi-toc))))))

(deftest filter-catalogue
  (testing "a catalogue can have it's addon-summary-list filtered by a specific source"
    (let [github
          {:updated-date "2019-10-19T01:01:01Z",
           :download-count 9,
           :game-track-list [:retail :classic],
           :label "Chinchilla",
           :name "chinchilla",
           :source "github",
           :source-id "Ravendwyr/Chinchilla",
           :tag-list [],
           :url "https://github.com/Ravendwyr/Chinchilla"}

          curseforge
          {:updated-date "2019-10-29T01:01:01Z",
           :created-date "2019-04-13T15:23:09.397Z",
           :description "A New Simple Percent",
           :download-count 1034,
           :label "A New Simple Percent",
           :name "a-new-simple-percent",
           :source "curseforge",
           :source-id 319346,
           :tag-list [:unit-frames],
           :url "https://www.curseforge.com/wow/addons/a-new-simple-percent"}

          tukui
          {:updated-date "2019-11-19T01:01:01Z",
           :description "Skins for AddOns",
           :download-count 1112033,
           :game-track-list [:retail],
           :label "AddOnSkins",
           :name "addonskins",
           :source "tukui",
           :source-id 3,
           :tag-list [:ui],
           :url "https://www.tukui.org/addons.php?id=3"}

          catalogue
          {:spec {:version 2}
           :datestamp "2020-02-20"
           :total 3
           :addon-summary-list [github curseforge tukui]}

          expected-wowi (-> catalogue
                            (assoc :total 0)
                            (assoc :addon-summary-list []))

          expected-github (-> catalogue
                              (assoc :total 1)
                              (assoc :addon-summary-list [github]))

          expected-curse (-> catalogue
                             (assoc :total 1)
                             (assoc :addon-summary-list [curseforge]))

          expected-tukui (-> catalogue
                             (assoc :total 1)
                             (assoc :addon-summary-list [tukui]))]

      (is (= expected-wowi (catalogue/filter-catalogue catalogue "wowinterface")))
      (is (= expected-github (catalogue/filter-catalogue catalogue "github")))
      (is (= expected-curse (catalogue/filter-catalogue catalogue "curseforge")))
      (is (= expected-tukui (catalogue/filter-catalogue catalogue "tukui"))))))

