(ns strongbox.github-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [constants :as constants]
    [logging :as logging]
    [github-api :as github-api]
    [test-helper :refer [fixture-path slurp-fixture]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest parse-user-string
  (let [expected "Aviana/HealComm"
        ;; all of these should yield the above
        cases ["https://github.com/Aviana/HealComm" ;; perfect case

               ;; all valid variations of the above
               "http://github.com/Aviana/HealComm"
               "https://github.com/Aviana/HealComm/"
               "https://user:name@github.com/Aviana/HealComm"
               "https://github.com/Aviana/HealComm?foo=bar"
               "https://github.com/Aviana/HealComm#foo/bar"
               "https://github.com/Aviana/HealComm?foo=bar&baz=bup"

               ;; looser matching we can support
               "https://github.com/Aviana/HealComm/foo/bar/baz"

               ;; leading 'www'
               "https://www.github.com/Aviana/HealComm"]]
    (doseq [given cases]
      (testing (str "case: " given)
        (is (= expected (github-api/parse-user-string given)))))))

(deftest parse-user-string--empty-cases
  (testing "a source-id can be extracted from a github URL"
    (let [cases ["https://github.com"
                 "https://github.com/"
                 "https://github.com/Aviana"
                 "https://github.com/Aviana/"]]
      (doseq [given cases]
        (testing (str "a source-id can be extracted from a github URL, case:" given)
          (is (nil? (github-api/parse-user-string given)), (str "failed case: " given)))))))

(deftest find-addon--gametracks-release-list
  (testing "user input can be parsed and turned into a catalogue item. game-track-list is derived by looking at all releases."
    (let [fixture (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body fixture})}

                       "https://api.github.com/repos/aviana/healcomm/releases"
                       {:get (fn [req] {:status 200 :body fixture})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}

                       "https://api.github.com/repos/aviana/healcomm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}}

          expected {:url "https://github.com/Aviana/HealComm"
                    :updated-date "2019-10-09T17:40:04Z"
                    :source "github"
                    :source-id "Aviana/HealComm"
                    :label "HealComm"
                    :name "healcomm"
                    :download-count 30946
                    ;; older releases from before classic, so we know retail at least is supported.
                    :game-track-list [:retail]
                    :tag-list []}

          ;; all of these should yield the above
          cases ["Aviana/HealComm" ;; perfect case
                 "aviana/healcomm"]]

      (with-fake-routes-in-isolation fake-routes
        (doseq [given cases]
          (testing (str "case: " given)
            (is (= expected (github-api/find-addon given)))))))))

(deftest find-addon--gametracks-release-json
  (testing "game-track-list is derived by looking at the first release.json it finds"
    (let [expected {:download-count 10888,
                    :game-track-list [:classic :classic-tbc :retail],
                    :label "Necrosis",
                    :name "necrosis",
                    :source "github",
                    :source-id "robert388/Necrosis",
                    :tag-list [],
                    :updated-date "2019-08-08T05:42:53Z",
                    :url "https://github.com/robert388/Necrosis"}

          source-id "Aviana/HealComm"

          releases (slurp (fixture-path "github-repo-releases--single-release-json.json"))
          release-json (slurp (fixture-path "github-repo-releases--full-release-json.json"))

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 200 :body releases})}

                       "https://github.com/robert388/Necrosis/releases/download/release.json"
                       {:get (fn [_] {:status 200 :body release-json})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/find-addon source-id)))))))

(deftest find-addon--gametracks-toc-data
  (testing "game-track-list is derived by looking at the toc file and parsing interface values"
    (let [expected {:download-count 14947,
                    :game-track-list [:classic-tbc],
                    :label "HealComm",
                    :name "healcomm",
                    :source "github",
                    :source-id "Aviana/HealComm",
                    :tag-list [],
                    :updated-date "2019-10-09T17:40:04Z",
                    :url "https://github.com/Aviana/HealComm"}

          source-id "Aviana/HealComm"

          releases (slurp (fixture-path "github-repo-releases--no-game-tracks.json"))
          contents [{:name "foo.toc", :download_url "https://raw.githubusercontent.com/Aviana/HealComm/master/Addon.toc"}]
          toc-data "## Interface: 20100"

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 200 :body releases})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body (utils/to-json contents)})}

                       "https://raw.githubusercontent.com/Aviana/HealComm/master/Addon.toc"
                       {:get (fn [req] {:status 200 :body toc-data})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/find-addon source-id)))))))

(deftest find-addon--no-assets
  (testing "addons with no uploaded assets return `nil`"
    (let [source-id "Robert388/Necrosis-classic"
          expected nil
          fixture (slurp (fixture-path "github-repo-releases--no-assets.json"))
          fake-routes {"https://api.github.com/repos/Robert388/Necrosis-classic/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/find-addon source-id)))))))

(deftest parse-github-release-data
  (testing "a complete list of release data can be parsed and filtered"
    (let [fixture (slurp-fixture "github-repo-releases--altoholic-classic.json")
          addon-summary
          {:url "https://github.com/Bar/Foo"
           :updated-date "2019-10-09T17:40:04Z"
           :source "github"
           :source-id "Bar/Foo"
           :label "Foo"
           :name "foo"
           :download-count 123
           :game-track-list []
           :tag-list []}

          ;; 2021-05-08: release name checking added. The two defaulting to retail were two assets both named "Altoholic.zip",
          ;; from two releases both named "Teelo's Altoholic Classic Fork". These two assets are now correctly labelled classic.
          ;;expected-classic 45
          ;;expected-retail 2
          expected-classic 47
          expected-retail 0]
      (is (= expected-classic (count (github-api/parse-github-release-data fixture addon-summary :classic))))
      (is (= expected-retail (count (github-api/parse-github-release-data fixture addon-summary :retail)))))))

;;(deftest parse-github-release-data--installed-game-track
;;  (testing "the :installed-game-track is used to populate the 'known game tracks' value"
;;    (is false)))

(deftest find-gametracks-toc-data
  (testing "games tracks are correctly detected from toc data"
    (let [cases [[{} nil]
                 [{:interface constants/default-interface-version} [:retail]]
                 [{:interface constants/default-interface-version
                   :#interface constants/default-interface-version-classic} [:retail :classic]]
                 [{:interface 20501 :#interface 90000} [:retail :classic-tbc]]]]

      (doseq [[given expected] cases]
        (is (= expected (github-api/-find-gametracks-toc-data given)))))))

(deftest expand-addon-summary--retail
  (testing "expand-summary correctly extracts and adds additional properties"
    (let [given {:url "https://github.com/Aviana/HealComm"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "Aviana/HealComm"
                 :label "HealComm"
                 :name "healcomm"
                 :download-count 30946
                 :tag-list []
                 ;; 2021-12: changed from [] to [:retail]
                 ;; ':retail' can't be used as a default for addons after a certain date.
                 ;; extra effort is made to determine the game-track-list on import/refresh so they can be
                 ;; used when forced to guess.
                 :game-track-list [:retail]}

          game-track :retail

          expected [{:download-url "https://github.com/Aviana/HealComm/releases/download/2.04/HealComm.zip"
                     :version "2.04 Beta"
                     :game-track :retail}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/2.03/HealComm.zip",
                     :game-track :retail,
                     :version "2.03 Beta"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/2.02/HealComm.zip",
                     :game-track :retail,
                     :version "2.02 Beta"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/2.01/HealComm-2.01.zip",
                     :game-track :retail,
                     :version "2.01 Beta"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.15/HealComm.zip",
                     :game-track :retail,
                     :version "1.15"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.14/HealComm.zip",
                     :game-track :retail,
                     :version "1.14"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.13/HealComm.zip",
                     :game-track :retail,
                     :version "1.13"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.12/HealComm.zip",
                     :game-track :retail,
                     :version "1.12"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.11/HealComm.zip",
                     :game-track :retail,
                     :version "1.11"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.10/HealComm.zip",
                     :game-track :retail,
                     :version "1.10"}
                    {:download-url "https://github.com/Aviana/HealComm/releases/download/1.9/HealComm.zip",
                     :game-track :retail,
                     :version "1.9"}]

          fixture (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))
          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track)))))))

(deftest expand-addon-summary--classic
  (testing "classic addons are correctly detected"
    (let [given {:url "https://github.com/Ravendwyr/Chinchilla"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "Ravendwyr/Chinchilla"
                 :label "Chinchilla"
                 :name "chinchilla"
                 :download-count 30946
                 :tag-list []}

          game-track :classic

          expected [{:download-url "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0/Chinchilla-v2.10.0-classic.zip"
                     :game-track game-track
                     :version "v2.10.0"}
                    {:download-url "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0-beta3/Chinchilla-v2.10.0-beta3-classic.zip",
                     :game-track game-track,
                     :version "v2.10.0-beta3"}]

          fixture (slurp (fixture-path "github-repo-releases--mixed-game-tracks.json"))
          fake-routes {"https://api.github.com/repos/Ravendwyr/Chinchilla/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track)))))))

(deftest expand-addon-summary--classic-tbc
  (testing "classic-tbc addons are correctly detected"
    (let [given {:url "https://github.com/Foo/Bar"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "Foo/Bar"
                 :label "Bar"
                 :name "bar"
                 :download-count 30946
                 :tag-list []}

          game-track :classic-tbc

          expected [{:download-url "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0/Chinchilla-v2.10.0-classic-tbc.zip",
                     :game-track :classic-tbc,
                     :version "v2.10.0"}
                    {:download-url "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0-beta3/Chinchilla-v2.10.0-beta3-classic-tbc.zip",
                     :game-track :classic-tbc,
                     :version "v2.10.0-beta3"}]

          fixture (slurp (fixture-path "github-repo-releases--mixed-game-tracks.json"))
          fake-routes {"https://api.github.com/repos/Foo/Bar/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track)))))))

(deftest expand-addon-summary--missing-assets
  (testing "releases that have no assets are not considered and no errors occur"
    (let [given {:url "https://github.com/Robert388/Necrosis-classic"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "Robert388/Necrosis-classic"
                 :label "Necrosis-classic"
                 :name "necrosis-classic"
                 :download-count 30946
                 :tag-list []}

          expected nil

          fixture (slurp (fixture-path "github-repo-releases--no-assets.json"))
          fake-routes {"https://api.github.com/repos/Robert388/Necrosis-classic/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        ;; testing each game track isn't really neccessary but can't hurt
        (is (= expected (github-api/expand-summary given :classic-tbc)))
        (is (= expected (github-api/expand-summary given :classic)))
        (is (= expected (github-api/expand-summary given :retail)))))))

(deftest expand-addon-summary--partial-assets
  (testing "releases whose assets are only partially uploaded are ignored"
    (let [given {:url "https://github.com/jsb/RingMenu"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "jsb/RingMenu"
                 :label "RingMenu"
                 :name "ringmenu"
                 :download-count 30946
                 :tag-list []}

          game-track :retail

          expected nil

          fixture (slurp (fixture-path "github-repo-releases--broken-assets.json"))
          fake-routes {"https://api.github.com/repos/jsb/RingMenu/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track)))))))

(deftest expand-addon-summary--matching-game-tracks-only
  (testing "releases whose assets do not match the given `:game-track` are skipped"
    (let [fixture (slurp (fixture-path "github-repo-releases--altoholic-classic--leading-bad-assets.json"))
          addon-summary
          {:url "https://github.com/Bar/Foo"
           :updated-date "2019-10-09T17:40:04Z"
           :source "github"
           :source-id "Bar/Foo"
           :label "Foo"
           :name "foo"
           :download-count 123
           :game-track-list []
           :tag-list []}

          game-track :classic

          expected [{:game-track game-track
                     :download-url "https://github.com/teelolws/Altoholic-Classic/releases/download/v1.13.2-009/Altoholic.zip",
                     :version "Teelo's Altoholic Classic Fork"}
                    {:game-track game-track
                     :version "Classic-v1.13.6-057"
                     :download-url "https://github.com/teelolws/Altoholic-Classic/releases/download/Classic-v1.13.6-057/Altoholic-Classic-v1.13.6-057.zip"}]

          fake-routes {"https://api.github.com/repos/Bar/Foo/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary addon-summary game-track)))))))

(deftest parse-assets--worst-case
  (testing "no game track present in asset name or release name, no `:game-track-list`, no `release.json` file and no known game tracks."
    (let [expected []
          release {:name "Release 1.2.3"
                   :assets [{:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "1.2.3"}]}
          known-game-tracks []]
      (is (= expected (github-api/parse-assets release known-game-tracks))))))

(deftest parse-assets--asset-name
  (let [expected [{:game-track :classic, :version "Release 1.2.3", :download-url "https://example.org"}]
        release {:name "Release 1.2.3"
                 :assets [{:browser_download_url "https://example.org"
                           :content_type "application/zip"
                           :state "uploaded"
                           :name "1.2.3-Classic.zip"}]}
        known-game-tracks []]
    (is (= expected (github-api/parse-assets release known-game-tracks)))))

(deftest parse-assets--release-name
  (let [expected [{:download-url "https://example.org", :game-track :classic-tbc, :version "Foo 1.2.3-Classic TBC"}]
        release {:name "Foo 1.2.3-Classic TBC"
                 :assets [{:browser_download_url "https://example.org"
                           :content_type "application/zip"
                           :state "uploaded"
                           :name "1.2.3"}]}
        known-game-tracks []]
    (is (= expected (github-api/parse-assets release known-game-tracks)))))

(deftest parse-assets--game-track-list
  (testing "with no game track in asset name, release name or a release.json, use the known game tracks detected on import."
    (let [expected1 [{:download-url "https://example.org", :game-track :retail, :version "Release 1.2.3"}]
          expected2 [{:download-url "https://example.org", :game-track :classic, :version "Release 1.2.3"}]
          expected3 [{:download-url "https://example.org", :game-track :retail, :version "Release 1.2.3"}
                     {:download-url "https://example.org", :game-track :classic, :version "Release 1.2.3"}
                     {:download-url "https://example.org", :game-track :classic-tbc, :version "Release 1.2.3"}]
          release {:name "Release 1.2.3"
                   :assets [{:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "1.2.3"}]}]
      (is (= expected1 (github-api/parse-assets release [:retail])))
      (is (= expected2 (github-api/parse-assets release [:classic])))
      (is (= expected3 (github-api/parse-assets release [:retail :classic :classic-tbc]))))))

(deftest parse-assets--odd-one-out
  (testing "sole remaining asset is classified as the sole remaining classification"
    (let [release {:name "Release 1.2.3"
                   :assets [{:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "1.2.3"}
                            {:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "1.2.3-Classic"}
                            {:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "1.2.3-Classic-BCC"}]}
          expected [{:download-url "https://example.org", :game-track :retail, :version "Release 1.2.3"}
                    {:download-url "https://example.org", :game-track :classic, :version "Release 1.2.3"}
                    {:download-url "https://example.org", :game-track :classic-tbc, :version "Release 1.2.3"}]
          known-game-tracks []]
      (is (= expected (github-api/parse-assets release known-game-tracks))))))

(deftest parse-assets--release-json
  (testing "no game track present in asset name or release name, no `:game-track-list`, no `release.json` file and no known game tracks."
    (let [expected [{:download-url "https://example.org", :game-track :classic-tbc, :version "Release 1.2.3"}]
          release-json {:releases [{:filename "AdvancedInterfaceOptions-1.5.0.zip",
                                    :nolib false,
                                    :metadata [{:flavor "bcc", :interface 20501}]}]}
          release {:name "Release 1.2.3"
                   :assets [{:browser_download_url "https://example.org"
                             :content_type "application/zip"
                             :state "uploaded"
                             :name "AdvancedInterfaceOptions-1.5.0.zip"}

                            {:browser_download_url "https://example.org/release.json"
                             :content_type "application/json"
                             :state "uploaded"
                             :name "release.json"}]}
          known-game-tracks []
          fake-routes {"https://example.org/release.json"
                       {:get (fn [_] {:status 200 :body (utils/to-json release-json)})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/parse-assets release known-game-tracks)))))))

(deftest rate-limit-exceeded
  (testing "403 while importing an addon is handled"
    (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}}

          source-id "Aviana/HealComm"

          ;; I don't like testing for log messages, but in this case it's the only indication the error has been handled properly
          expected ["failed to fetch 'https://api.github.com/repos/Aviana/HealComm/releases': Forbidden (HTTP 403)"
                    "Github: we've exceeded our request quota and have been blocked for an hour."]]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log
                         :info (github-api/find-addon source-id)))))))

  (testing "403 while expanding addon is handled"
    (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}}

          addon-summary
          {:url "https://github.com/Aviana/HealComm"
           :updated-date "2019-10-09T17:40:04Z"
           :source "github"
           :source-id "Aviana/HealComm"
           :label "HealComm"
           :name "healcomm"
           :download-count 30946
           :game-track-list [] ;; 'no game tracks'
           :tag-list []}

          game-track :retail

          ;; I don't like testing for log messages, but in this case it's the only indication the error has been handled properly
          expected ["failed to fetch 'https://api.github.com/repos/Aviana/HealComm/releases': Forbidden (HTTP 403)"
                    "Github: we've exceeded our request quota and have been blocked for an hour."]]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log
                         :info (github-api/expand-summary addon-summary game-track))))))))

(deftest pick-version-name
  (testing "use the release name if possible"
    (let [release {:name "4.050 Beta" :tag_name "4050"}
          asset {:name "LunaUnitFrames-classic.zip"}
          expected "4.050 Beta"]
      (is (= expected (github-api/pick-version-name release asset)))))

  (testing "releases missing titles use the `tag-name` instead."
    (let [release {:name "" :tag_name "4050"}
          asset {:name "LunaUnitFrames-classic.zip"}
          expected "4050"]
      (is (= expected (github-api/pick-version-name release asset)))))

  (testing "releases missing titles and tags (!!) use the asset's file `name`"
    (let [release {:name ""} ;; :tag_name "4050"}
          asset {:name "LunaUnitFrames-classic.zip"}
          expected "LunaUnitFrames-classic.zip"]
      (is (= expected (github-api/pick-version-name release asset))))))

(deftest contents-url
  (let [cases [["" nil]
               ["user/repo" "https://api.github.com/repos/user/repo/contents"]
               ["user" "https://api.github.com/repos/user/contents"]]]
    (doseq [[given expected] cases]
      (is (= expected (github-api/contents-url given))))))

(deftest download-root-listing
  (let [expected [{:foo "bar"} {:baz "bup"}]
        fixture "[{\"foo\": \"bar\"}, {\"baz\": \"bup\"}]"
        fake-routes {"https://api.github.com/repos/user/repo/contents"
                     {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (github-api/download-root-listing "user/repo"))))))

(deftest download-root-listing--bad-json
  (let [expected nil
        fixture "[}"
        fake-routes {"https://api.github.com/repos/user/repo/contents"
                     {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (github-api/download-root-listing "user/repo"))))))

(deftest releases-url
  (let [cases [["" nil]
               ["user/repo" "https://api.github.com/repos/user/repo/releases"]
               ["user" "https://api.github.com/repos/user/releases"]]]
    (doseq [[given expected] cases]
      (is (= expected (github-api/releases-url given))))))

(deftest download-release-listing
  (let [expected [{:foo "bar"} {:baz "bup"}]
        fixture "[{\"foo\": \"bar\"}, {\"baz\": \"bup\"}]"
        fake-routes {"https://api.github.com/repos/user/repo/releases"
                     {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (github-api/download-release-listing "user/repo"))))))

(deftest download-release-listing--bad-json
  (let [expected nil
        fixture "[}"
        fake-routes {"https://api.github.com/repos/user/repo/releases"
                     {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (github-api/download-release-listing "user/repo"))))))

(deftest build-catalogue
  (let [expected [{:description "Allows filtering of premade applicants using advanced filter expressions.",
                   :download-count 0,
                   :game-track-list [:retail],
                   :label "premade-applicants-filter",
                   :name "premade-applicants-filter",
                   :source "github",
                   :source-id "0xbs/premade-applicants-filter",
                   :tag-list [],
                   :updated-date "2021-07-19T21:11:20+00:00",
                   :url "https://github.com/0xbs/premade-applicants-filter"}
                  {:download-count 0,
                   :game-track-list [:classic :retail],
                   :label "ArenaLeaveConfirmer",
                   :name "arenaleaveconfirmer",
                   :source "github",
                   :source-id "AlexFolland/ArenaLeaveConfirmer",
                   :tag-list [],
                   :updated-date "2021-07-04T22:12:06+00:00",
                   :url "https://github.com/AlexFolland/ArenaLeaveConfirmer"}
                  {:download-count 0,
                   :game-track-list [:classic-tbc :classic :retail],
                   :label "BattlegroundSpiritReleaser",
                   :name "battlegroundspiritreleaser",
                   :source "github",
                   :source-id "AlexFolland/BattlegroundSpiritReleaser",
                   :tag-list [],
                   :updated-date "2021-07-04T21:55:31+00:00",
                   :url "https://github.com/AlexFolland/BattlegroundSpiritReleaser"}
                  {:description "AltReps is an addon that allows you to track reputations across your characters",
                   :download-count 0,
                   :game-track-list [:retail],
                   :label "AltReps",
                   :name "altreps",
                   :source "github",
                   :source-id "Alastair-Scott/AltReps",
                   :tag-list [],
                   :updated-date "2021-07-01T18:21:23+00:00",
                   :url "https://github.com/Alastair-Scott/AltReps"}
                  {:download-count 0,
                   :game-track-list [:classic-tbc :classic :retail],
                   :label "ChatCleaner",
                   :name "chatcleaner",
                   :source "github",
                   :source-id "GoldpawsStuff/ChatCleaner",
                   :tag-list [],
                   :updated-date "2021-11-17T10:12:53+00:00",
                   :url "https://github.com/GoldpawsStuff/ChatCleaner"}]
        fixture (slurp (fixture-path "github-catalogue--dummy.csv"))
        fake-routes {"https://raw.githubusercontent.com/ogri-la/github-wow-addon-catalogue/main/addons.csv"
                     {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (github-api/build-catalogue))))))

(deftest make-url
  (let [cases [[nil nil]
               [{} nil]
               [{:foo :bar} nil]
               [{:source-id ""} "https://github.com/"]
               [{:source-id "foo"} "https://github.com/foo"]
               [{:source-id "foo/bar"} "https://github.com/foo/bar"]]]
    (doseq [[given expected] cases]
      (is (= expected (github-api/make-url given))))))
