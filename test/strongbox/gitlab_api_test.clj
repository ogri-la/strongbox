(ns strongbox.gitlab-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [gitlab-api :as gitlab-api]
    [test-helper :refer [fixture-path slurp-fixture]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

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

(deftest find-addon--multi-toc
  (testing "if more than one toc file is present, assume multi-toc and look for game tracks"
    (let [repo-fixture (slurp (fixture-path "gitlab-repo--woblight-nitro.json"))
          repo-tree-fixture (slurp (fixture-path "gitlab-repo-tree--woblight-nitro.json"))

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro"
                       {:get (fn [req] {:status 200 :body repo-fixture})}

                       "https://gitlab.com/api/v4/projects/woblight%2Fnitro/repository/tree"
                       {:get (fn [req] {:status 200 :body repo-tree-fixture})}}

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
                       {:get (fn [req] {:status 200 :body toc-file-fixture})}}

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
    (let [given {:url "https://gitlab.com/woblight/nitro"
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

          expected [{:download-url "https://gitlab.com/woblight/nitro/-/releases/v1.0-0-gddcb65a/downloads/Nitro"
                     :version "v1.0-0-gddcb65a"
                     :game-track :retail}]

          fixture (slurp (fixture-path "gitlab-repo-releases--woblight-nitro.json"))

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest expand-summary--strip-pre-release
  (testing "expand-summary removes 'upcoming' releases from the list of candidates."
    (let [given {:url "https://gitlab.com/woblight/nitro"
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

          expected nil

          fixture (slurp (fixture-path "gitlab-repo-releases--upcoming-release-dummy.json"))

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))

(deftest expand-summary--detect-classic
  (testing "expand-summary removes 'upcoming' releases from the list of candidates."
    (let [given {:url "https://gitlab.com/woblight/nitro"
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

          expected [{:download-url "https://gitlab.com/woblight/nitro/-/releases/v1.0-0-gddcb65a/downloads/Nitro"
                     :version "v1.0-0-gddcb65a"
                     :game-track :classic-tbc}]

          fixture (slurp (fixture-path "gitlab-repo-releases--classic-release-dummy.json"))

          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (gitlab-api/expand-summary given game-track)))))))
