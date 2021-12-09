(ns strongbox.gitlab-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [gitlab-api :as gitlab-api]
    [test-helper :refer [fixture-path slurp-fixture]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest api-url
  (let [cases [["" "https://gitlab.com/api/v4/projects/"]
               ["foo" "https://gitlab.com/api/v4/projects/foo"]
               ["foo/bar" "https://gitlab.com/api/v4/projects/foo%2Fbar"]
               ["foo/bar/baz" "https://gitlab.com/api/v4/projects/foo%2Fbar%2Fbaz"]

               ;; probably invalid
               ["foo/bar/baz!" "https://gitlab.com/api/v4/projects/foo%2Fbar%2Fbaz%21"]]]
    (doseq [[given expected] cases]
      (is (= expected (gitlab-api/api-url given))))))

(deftest parse-user-string
  (testing "the gitlab ID can be extracted from a given URL"
    (let [expected "woblight/nitro"
          ;; all of these should yield the above
          cases ["https://gitlab.com/woblight/nitro"

                 ;; all valid variations of the above
                 "https://gitlab.com/woblight/nitro/" ;; trailing slash
                 "http://gitlab.com/woblight/nitro" ;; http
                 "https://www.github.com/woblight/nitro" ;; leading 'www'

                 ;; looser matching we can also support
                 "https://gitlab.com/woblight/nitro/-/releases"
                 "http://user:name@www.gitlab.com/woblight/nitro/?baz=bup&boo=#anchor"]]

      (doseq [given cases]
        (testing (str "case: " given)
          (is (= expected (gitlab-api/parse-user-string given))))))))

(deftest parser-user-string--with-group
  (testing "gitlab IDs that include a group are extracted as expected"
    (let [expected "thing-engineering/wowthing/wowthing-sync"
          cases ["https://gitlab.com/thing-engineering/wowthing/wowthing-sync"

                 ;; all valid variations of the above
                 "https://gitlab.com/thing-engineering/wowthing/wowthing-sync/" ;; trailing slash
                 "http://gitlab.com/thing-engineering/wowthing/wowthing-sync" ;; http
                 "https://www.gitlab.com/thing-engineering/wowthing/wowthing-sync" ;; leading 'www'

                 ;; looser matching we can also support
                 "https://www.gitlab.com/thing-engineering/wowthing/wowthing-sync/-/releases"
                 "http://user:name@www.gitlab.com/thing-engineering/wowthing/wowthing-sync/?baz=bup&boo=#anchor"

                 ;; only the first three segments are used at most
                 "https://gitlab.com/thing-engineering/wowthing/wowthing-sync/foo/bar/baz"]]
      (doseq [given cases]
        (testing (str "case: " given)
          (is (= expected (gitlab-api/parse-user-string given))))))))

(deftest parse-user-string--bad-cases
  (testing "urls with not enough information return `nil`"
    (let [cases [["https://gitlab.com/" nil] ;; no path
                 ["https://gitlab.com/foo" nil] ;; path too short
                 ["http://user:name@www.gitlab.com/foo/?baz=bup&boo=#anchor" nil]]] ;; path still too short
      (doseq [[given expected] cases]
        (is (= expected (gitlab-api/parse-user-string given)))))))

(deftest find-addon--multi-toc
  (testing "if more than one toc file is present, assume multi-toc and look for game tracks"
    (let [repo-fixture (slurp (fixture-path "gitlab-repo--woblight-nitro.json"))
          repo-tree-fixture (slurp (fixture-path "gitlab-repo-tree--woblight-nitro.json"))
          blob-fixture (slurp (fixture-path "gitlab-repo-blobs--woblight-nitro.json"))
          repo-releases-fixture (slurp (fixture-path "gitlab-repo-releases--woblight-nitro.json"))

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro"
                       {:get (fn [req] {:status 200 :body repo-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                       {:get (fn [req] {:status 200 :body repo-tree-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/blobs/6293629816b04063b391e845a67ea8f5e313d540"
                       {:get (fn [req] {:status 200 :body blob-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [req] {:status 200 :body repo-releases-fixture})}}

          expected {:url "https://gitlab.com/woblight/nitro"
                    :created-date "2020-09-07T08:30:52.562Z"
                    :updated-date "2021-05-31T18:07:41.182Z"
                    :source "gitlab"
                    :source-id "woblight/nitro"
                    :label "Nitro"
                    :name "nitro"
                    :download-count 0
                    :tag-list []
                    :game-track-list [:classic :classic-tbc :retail]}

          ;; all of these should yield the above
          cases ["woblight/nitro"
                 "Woblight/Nitro"]]
      (with-fake-routes-in-isolation fake-routes
        (doseq [given cases]
          (testing (str "case: " given)
            (is (= expected (gitlab-api/find-addon given)))))))))

(deftest find-addon--single-toc
  (testing "toc files can be inspected for game tracks"
    (let [repo-fixture (slurp (fixture-path "gitlab-repo--woblight-nitro.json"))
          repo-tree-fixture (slurp (fixture-path "gitlab-repo-tree--single-toc-dummy.json"))
          repo-releases-fixture (slurp (fixture-path "gitlab-repo-releases--woblight-nitro.json"))

          toc-file-fixture (utils/to-json
                            {:size 258
                             :encoding "base64"
                             :content "IyMgSW50ZXJmYWNlOiA5MDAwNQojIyBUaXRsZTogV29XdGhpbmcgQ29sbGVjdG9yCiMjIE5vdGVzOiBDb2xsZWN0cyBkYXRhIHRvIHN5bmMgd2l0aCBXb1d0aGluZy4KIyMgVmVyc2lvbjogOS4wLjUuNAojIyBBdXRob3I6IEZyZWRkaWUKIyMgU2F2ZWRWYXJpYWJsZXM6IFdXVENTYXZlZAoKbGlic1xMaWJTdHViXExpYlN0dWIubHVhCmxpYnNcTGliUmVhbG1JbmZvMTdqYW5la2psXExpYlJlYWxtSW5mbzE3amFuZWtqbC5sdWEKCkNvbGxlY3Rvci5sdWEK"
                             :sha "125c899d813d2e11c976879f28dccc2a36fd207b"})

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro"
                       {:get (fn [req] {:status 200 :body repo-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                       {:get (fn [req] {:status 200 :body repo-tree-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/blobs/125c899d813d2e11c976879f28dccc2a36fd207b"
                       {:get (fn [req] {:status 200 :body toc-file-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [req] {:status 200 :body repo-releases-fixture})}}

          expected {:url "https://gitlab.com/woblight/nitro"
                    :created-date "2020-09-07T08:30:52.562Z"
                    :updated-date "2021-05-31T18:07:41.182Z"
                    :source "gitlab"
                    :source-id "woblight/nitro"
                    :label "Nitro"
                    :name "nitro"
                    :download-count 0
                    :tag-list []
                    :game-track-list [:retail]}

          ;; all of these should yield the above
          cases ["woblight/nitro"
                 "Woblight/Nitro"]]
      (with-fake-routes-in-isolation fake-routes
        (doseq [given cases]
          (testing (str "case: " given)
            (is (= expected (gitlab-api/find-addon given)))))))))

(deftest expand-summary
  (testing "expand-summary correctly extracts and adds additional properties"
    (let [expected [{:download-url "https://gitlab.com/woblight/nitro/-/releases/v1.0-0-gddcb65a/downloads/Nitro"
                     :version "v1.0-0-gddcb65a"
                     :game-track :retail}]
          given {:url "https://gitlab.com/woblight/nitro"
                 :created-date "2020-09-07T08:30:52.562Z"
                 :updated-date "2021-05-31T18:07:41.182Z"
                 :source "gitlab"
                 :source-id "woblight/nitro"
                 :label "Nitro"
                 :name "nitro"
                 :download-count 0
                 :game-track-list [:retail]
                 :tag-list []}
          game-track :retail
          fixture (slurp (fixture-path "gitlab-repo-releases--woblight-nitro.json"))
          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest expand-summary--http-error
  (testing "expand-summary safely recovers from http errors"
    (let [expected nil
          given {:url "https://gitlab.com/woblight/nitro"
                 :created-date "2020-09-07T08:30:52.562Z"
                 :updated-date "2021-05-31T18:07:41.182Z"
                 :source "gitlab"
                 :source-id "woblight/nitro"
                 :label "Nitro"
                 :name "nitro"
                 :download-count 0
                 :game-track-list []
                 :tag-list []}
          game-track :retail
          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 504 :reason-phrase "Internal server error"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest expand-summary--strip-pre-release
  (testing "expand-summary removes 'upcoming' releases from the list of candidates."
    (let [expected nil
          given {:url "https://gitlab.com/woblight/nitro"
                 :created-date "2020-09-07T08:30:52.562Z"
                 :updated-date "2021-05-31T18:07:41.182Z"
                 :source "gitlab"
                 :source-id "woblight/nitro"
                 :label "Nitro"
                 :name "nitro"
                 :download-count 0
                 :game-track-list []
                 :tag-list []}
          game-track :retail
          fixture (slurp (fixture-path "gitlab-repo-releases--upcoming-release-dummy.json"))
          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest expand-summary--detect-classic
  (testing "expand-summary removes 'upcoming' releases from the list of candidates."
    (let [expected [{:download-url "https://gitlab.com/woblight/nitro/-/releases/v1.0-0-gddcb65a/downloads/Nitro"
                     :version "v1.0-0-gddcb65a"
                     :game-track :classic-tbc}]
          given {:url "https://gitlab.com/woblight/nitro"
                 :created-date "2020-09-07T08:30:52.562Z"
                 :updated-date "2021-05-31T18:07:41.182Z"
                 :source "gitlab"
                 :source-id "woblight/nitro"
                 :label "Nitro"
                 :name "nitro"
                 :download-count 0
                 :game-track-list []
                 :tag-list []}
          game-track :classic-tbc
          fixture (slurp (fixture-path "gitlab-repo-releases--classic-release-dummy.json"))
          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest parse-release--no-assets
  (testing "a release with no assets returns no results"
    (let [expected []
          given {}
          game-track-list [:retail]]
      (is (= expected (gitlab-api/parse-release given game-track-list))))))

(deftest parse-release--worst-case
  (testing "no game track present in asset name or release name, no `:game-track-list`, no `release.json` file and no known game tracks."
    (let [expected []
          given {:name "EveryAddon"
                 :tag_name "1.2.3"
                 :assets {:links [{:external false
                                   :link_type "other"
                                   :name "Foo"
                                   :direct_asset_url "http://example.org"}]}}
          known-game-tracks []]
      (is (= expected (gitlab-api/parse-release given known-game-tracks))))))

(deftest parse-release--asset-name
  (let [expected [{:game-track :classic, :version "1.2.3", :download-url "https://example.org"}]
        release {:name "Release"
                 :tag_name "1.2.3"
                 :assets {:links [{:external false
                                   :link_type "other"
                                   :name "Foo-Classic"
                                   :direct_asset_url "https://example.org"}]}}
        known-game-tracks []]
    (is (= expected (gitlab-api/parse-release release known-game-tracks)))))

(deftest parse-assets--release-name
  (let [expected [{:download-url "https://example.org", :game-track :classic-tbc, :version "1.2.3"}]
        release {:name "Release-Classic-BCC"
                 :tag_name "1.2.3"
                 :assets {:links [{:external false
                                   :link_type "other"
                                   :name "Foo"
                                   :direct_asset_url "https://example.org"}]}}
        known-game-tracks []]
    (is (= expected (gitlab-api/parse-release release known-game-tracks)))))

(deftest parse-release--multiple-game-tracks
  (testing "a release with a single asset and multiple game tracks returns multiple releases"
    (let [expected [{:download-url "http://example.org",
                     :game-track :retail,
                     :version "1.2.3"}
                    {:download-url "http://example.org",
                     :game-track :classic,
                     :version "1.2.3"}]
          given {:name "EveryAddon"
                 :tag_name "1.2.3"
                 :assets {:links [{:external false
                                   :link_type "other"
                                   :name "Foo"
                                   :direct_asset_url "http://example.org"}]}}
          game-track-list [:retail :classic]]
      (is (= expected (gitlab-api/parse-release given game-track-list))))))

(deftest parse-assets--odd-one-out
  (testing "sole remaining asset is classified as the sole remaining classification"
    (let [release {:name "Release 1.2.3"
                   :tag_name "1.2.3"
                   :assets {:links [{:external false
                                     :link_type "other"
                                     :name "Foo-Classic"
                                     :direct_asset_url "http://example.org"}
                                    {:external false
                                     :link_type "other"
                                     :name "Foo-BCC"
                                     :direct_asset_url "http://example.org"}
                                    {:external false
                                     :link_type "other"
                                     :name "Foo"
                                     :direct_asset_url "http://example.org"}]}}
          
          expected [{:download-url "http://example.org", :game-track :classic, :version "1.2.3"}
                    {:download-url "http://example.org", :game-track :classic-tbc, :version "1.2.3"}
                    {:download-url "http://example.org", :game-track :retail, :version "1.2.3"}]
          known-game-tracks []]
      (is (= expected (gitlab-api/parse-release release known-game-tracks))))))

(deftest parse-assets--release-json
  (testing "no game track present in asset name or release name, no `:game-track-list`, no `release.json` file and no known game tracks."
    (let [expected [{:download-url "https://example.org", :game-track :classic-tbc, :version "1.2.3"}]
          release-json {:releases [{:filename "AdvancedInterfaceOptions-1.5.0.zip",
                                    :nolib false,
                                    :metadata [{:flavor "bcc", :interface 20501}]}]}
          release {:name "Release 1.2.3"
                   :tag_name "1.2.3"
                   :assets {:links [{:direct_asset_url "https://example.org"
                                     :link_type "other"
                                     :external false
                                     :name "AdvancedInterfaceOptions-1.5.0.zip"}
                                    {:direct_asset_url "https://example.org/release.json"
                                     :link_type "application/json"
                                     :external false
                                     :name "release.json"}]}}
          known-game-tracks []
          fake-routes {"https://example.org/release.json"
                       {:get (fn [_] {:status 200 :body (utils/to-json release-json)})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/parse-release release known-game-tracks)))))))

(deftest download-decode-blob
  (let [expected {:author "Freddie",
                  :interface "90005",
                  :notes "Collects data to sync with WoWthing.",
                  :savedvariables "WWTCSaved",
                  :title "WoWthing Collector",
                  :version "9.0.5.4"}
        source-id "thing-engineering/wowthing/wowthing-collector"
        fixture (slurp (fixture-path "gitlab-repo-blobs--wowthing.json"))
        url (str (gitlab-api/api-url source-id) "repository/blobs/125c899d813d2e11c976879f28dccc2a36fd207b")
        fake-routes {url {:get (fn [req] {:status 200 :body fixture})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (gitlab-api/download-decode-blob url))))))

(deftest find-toc-files--bad-request
  (let [expected {}
        source-id "woblight/nitro"
        fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                     {:get (fn [req] {:status 504 :reason-phrase "Internal server error."})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (gitlab-api/find-toc-files source-id))))))

(deftest find-toc-files--bad-json
  (let [expected {}
        source-id "woblight/nitro"
        fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                     {:get (fn [req] {:status 200 :body "{"})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (gitlab-api/find-toc-files source-id))))))

(deftest guess-game-track-list--no-toc-files
  (let [expected nil
        source-id "woblight/nitro"
        fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                     {:get (fn [req] {:status 200 :body "{}"})}}]
    (with-fake-routes-in-isolation fake-routes
      (is (= expected (gitlab-api/guess-game-track-list source-id))))))
