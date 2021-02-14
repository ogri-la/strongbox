(ns strongbox.github-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [logging :as logging]
    [github-api :as github-api]
    [test-helper :refer [fixture-path slurp-fixture]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest parse-user-string
  (testing "user input can be parsed and turned into a catalogue item."
    (let [fixture (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body fixture})}

                       "https://api.github.com/repos/aviana/healcomm/releases"
                       {:get (fn [req] {:status 200 :body fixture})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}}

          expected {:url "https://github.com/Aviana/HealComm"
                    :updated-date "2019-10-09T17:40:04Z"
                    :source "github"
                    :source-id "Aviana/HealComm"
                    :label "HealComm"
                    :name "healcomm"
                    :download-count 30946
                    :game-track-list []
                    :tag-list []}

          ;; all of these should yield the above
          cases ["https://github.com/Aviana/HealComm" ;; perfect case

                 ;; all valid variations of the above
                 "http://github.com/Aviana/HealComm"
                 "https://github.com/Aviana/HealComm/"
                 "https://user:name@github.com/Aviana/HealComm"
                 "https://github.com/Aviana/HealComm?foo=bar"
                 "https://github.com/Aviana/HealComm#foo/bar"
                 "https://github.com/Aviana/HealComm?foo=bar&baz=bup"

                 ;; valid, for github
                 "https://github.com/aviana/healcomm" ;; no redirect for lowercase :(

                 ;; looser matching we can support
                 "https://github.com/Aviana/HealComm/foo/bar/baz"
                 "//github.com/Aviana/HealComm"
                 "//github.com/Aviana/HealComm/foo/bar/baz"
                 "//github.com/Aviana/HealComm/foo/bar/baz#anc?baz=bup"
                 "github.com/Aviana/HealComm"
                 "github.com/Aviana/HealComm/foo/bar/baz"
                 "github.com/Aviana/HealComm/foo/bar#anc?baz=bup"

                 ;; bad/invalid urls we shouldn't support but do
                 "https://www.github.com/Aviana/HealComm"]]

      (with-fake-routes-in-isolation fake-routes
        (doseq [given cases]
          (testing (str "case: " given)
            (is (= expected (github-api/parse-user-string given))))))))

  (testing "bad URLs return nil. these *should* be caught in the dispatcher."
    (let [cases [""
                 "    "
                 " \n "
                 "asdf"
                 " asdf "
                 "213"]
          expected nil]
      (doseq [given cases]
        (testing (str "case: " given)
          (is (= expected (github-api/parse-user-string given)))))))

  (testing "valid looking URLs with invalid release structure return nil"
    (let [reasonable-looking-url "https://github.com/Robert388/Necrosis-classic"
          expected nil

          ;; (fixture is missing uploaded assets)
          fixture (slurp (fixture-path "github-repo-releases--no-assets.json"))
          fake-routes {"https://api.github.com/repos/Robert388/Necrosis-classic/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/parse-user-string reasonable-looking-url)))))))

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

          expected-classic 45
          expected-retail 2]
      (with-fake-routes-in-isolation {} ;; necessary?
        (is (= expected-classic (count (github-api/parse-github-release-data fixture addon-summary :classic))))
        (is (= expected-retail (count (github-api/parse-github-release-data fixture addon-summary :retail))))))))

(deftest expand-addon-summary
  (testing "expand-summary correctly extracts and adds additional properties"
    (let [given {:url "https://github.com/Aviana/HealComm"
                 :updated-date "2019-10-09T17:40:04Z"
                 :source "github"
                 :source-id "Aviana/HealComm"
                 :label "HealComm"
                 :name "healcomm"
                 :download-count 30946
                 :tag-list []}

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
        (is (= expected (github-api/expand-summary given game-track))))))

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
                     :version "v2.10.0-classic"
                     :game-track game-track}
                    {:download-url "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0-beta3/Chinchilla-v2.10.0-beta3-classic.zip",
                     :game-track :classic,
                     :version "v2.10.0-beta3-classic"}]

          fixture (slurp (fixture-path "github-repo-releases--many-assets-many-gametracks.json"))
          fake-routes {"https://api.github.com/repos/Ravendwyr/Chinchilla/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track))))))

  (testing "addons that have no assets (and no right to be in the catalogue) are not downloaded and no errors occur"
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
        (is (= expected (github-api/expand-summary given :classic)))
        (is (= expected (github-api/expand-summary given :retail))))))

  (testing "releases whose assets are only partially uploaded due to an upload failure are ignored"
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
        (is (= expected (github-api/expand-summary given game-track))))))

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

          expected [{:game-track :classic
                     :version "Classic-v1.13.6-057-classic"
                     :download-url "https://github.com/teelolws/Altoholic-Classic/releases/download/Classic-v1.13.6-057/Altoholic-Classic-v1.13.6-057.zip"}]

          fake-routes {"https://api.github.com/repos/Bar/Foo/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary addon-summary game-track)))))))

(deftest extract-source-id
  (testing "a source-id can be extracted from a github URL"
    (let [cases [["https://github.com/Aviana/HealComm" "Aviana/HealComm"] ;; perfect case
                 ["https://github.com/Aviana/HealComm/foo/bar" "Aviana/HealComm"]
                 ["https://github.com/Aviana/HealComm?foo=bar" "Aviana/HealComm"]
                 ["https://github.com/Aviana/HealComm#foo=bar" "Aviana/HealComm"]

                 ;; fail cases
                 ["https://github.com" nil]
                 ["https://github.com/" nil]
                 ["https://github.com/Aviana" nil]
                 ["https://github.com/Aviana/" nil]]]
      (doseq [[given expected] cases]
        (testing (str "a source-id can be extracted from a github URL, case:" given)
          (is (= expected (github-api/extract-source-id given))))))))

(deftest gametrack-detection
  (testing "detecting github addon game track, single asset cases"
    (let [addon-summary
          {:url "https://github.com/Aviana/HealComm"
           :updated-date "2019-10-09T17:40:04Z"
           :source "github"
           :source-id "Aviana/HealComm"
           :label "HealComm"
           :name "healcomm"
           :download-count 30946
           :game-track-list [] ;; 'no game tracks'
           :tag-list []}

          latest-release {:name "Release 1.2.3"
                          :assets [{:content_type "application/zip"
                                    :state "uploaded"
                                    :name "1.2.3"}]}

          cases [;; addon-summary updates, latest-release updates, expected

                 ;; case: asset has 'classic' in it's name
                 [{} [[:assets 0 :name] "1.2.3-Classic"]
                  {:classic [{:content_type "application/zip", :state "uploaded", :name "1.2.3-Classic", :game-track :classic, :version "Release 1.2.3-classic" :-mo :classic-in-name}]}]

                 ;; case: single asset, no game track present in file name, no known game tracks. default to :retail
                 [{} {}
                  {:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :sa--ngt}]}]

                 ;; case: single asset, no game track present in file name, single known game track. use that
                 [{:game-track-list [:retail]} {}
                  {:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :sa--1gt}]}]
                 [{:game-track-list [:classic]} {}
                  {:classic [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :classic, :version "Release 1.2.3-classic" :-mo :sa--1gt}]}]

                 ;; case: single asset, no game track present in file name, multiple known game tracks. assume all game tracks supported
                 [{:game-track-list [:classic :retail]} {}
                  {:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :sa--Ngt}]
                   :classic [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :classic, :version "Release 1.2.3-classic" :-mo :sa--Ngt}]}]]]

      (doseq [[addon-summary-updates, release-updates, expected] cases
              :let [summary (merge addon-summary addon-summary-updates)
                    release (if (vector? release-updates)
                              (assoc-in latest-release (first release-updates) (second release-updates))
                              (merge latest-release release-updates))]]
        (is (= expected (github-api/group-assets summary release))))))

  (testing "detecting github addon game track, multiple asset cases"
    (let [addon-summary
          {:url "https://github.com/Aviana/HealComm"
           :updated-date "2019-10-09T17:40:04Z"
           :source "github"
           :source-id "Aviana/HealComm"
           :label "HealComm"
           :name "healcomm"
           :download-count 30946
           :game-track-list [] ;; 'no game tracks'
           :tag-list []}

          latest-release {:name "Release 1.2.3"
                          :assets [{:content_type "application/zip"
                                    :state "uploaded"
                                    :name "1.2.3"}

                                   {:content_type "application/zip"
                                    :state "uploaded"
                                    :name "1.2.3-nolib"}]}

          cases [;; addon-summary updates, latest-release updates, expected

                 ;; case: multiple assets, no game track present in file name, no known game tracks. default to :retail
                 [{} {}
                  {:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :ma--ngt}
                            {:content_type "application/zip", :state "uploaded", :name "1.2.3-nolib", :game-track :retail, :version "Release 1.2.3" :-mo :ma--ngt}]}]

                 ;; case: multiple assets, no game track present in file name, single known game track. use that.
                 [{:game-track-list [:retail]} {}
                  {:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :ma--1gt}
                            {:content_type "application/zip", :state "uploaded", :name "1.2.3-nolib", :game-track :retail, :version "Release 1.2.3" :-mo :ma--1gt}]}]
                 [{:game-track-list [:classic]} {}
                  {:classic [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :classic, :version "Release 1.2.3-classic" :-mo :ma--1gt}
                             {:content_type "application/zip", :state "uploaded", :name "1.2.3-nolib", :game-track :classic, :version "Release 1.2.3-classic" :-mo :ma--1gt}]}]

                 ;; case: multiple assets, no game track present in file name, multiple known game tracks. default to :retail
                 [{:game-track-list [:classic :retail]} {}
                  ;;{:retail [{:content_type "application/zip", :state "uploaded", :name "1.2.3", :game-track :retail, :version "Release 1.2.3" :-mo :ma--Ngt}
                  ;;           {:content_type "application/zip", :state "uploaded", :name "1.2.3-nolib", :game-track :retail, :version "Release 1.2.3" :-mo :ma--Ngt}]}]]]
                  ;; 2019-11-21: changed my mind, refusing to install in very ambiguous caseses
                  nil]]]

      (doseq [[addon-summary-updates, release-updates, expected] cases
              :let [summary (merge addon-summary addon-summary-updates)
                    release (if (vector? release-updates)
                              (assoc-in latest-release (first release-updates) (second release-updates))
                              (merge latest-release release-updates))]]
        (is (= expected (github-api/group-assets summary release)))))))

(deftest rate-limit-exceeded
  (testing "403 while importing an addon is handled"
    (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [_] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 403 :host "api.github.com" :reason-phrase "Forbidden"})}}

          given "https://github.com/Aviana/HealComm"

          ;; I don't like testing for log messages, but in this case it's the only indication the error has been handled properly
          expected ["failed to download file 'https://api.github.com/repos/Aviana/HealComm/releases': Forbidden (HTTP 403)"
                    "Github: we've exceeded our request quota and have been blocked for an hour."]]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (logging/buffered-log
                         :info (github-api/parse-user-string given)))))))

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
          expected ["failed to download file 'https://api.github.com/repos/Aviana/HealComm/releases': Forbidden (HTTP 403)"
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
