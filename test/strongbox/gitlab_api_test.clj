(ns strongbox.gitlab-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
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


                 ;; all valid variabtions of the above


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

(deftest find-addon
  (testing "user input can be parsed and turned into a catalogue item."
    (let [fixture (slurp (fixture-path "gitlab-repo--woblight-nitro.json"))
          fake-routes {"https://gitlab.com/api/v4/projects/woblight%2Fnitro"
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected {:url "https://gitlab.com/woblight/nitro"
                    :created-date "2020-09-07T08:30:52.562Z"
                    :updated-date "2021-05-31T18:07:41.182Z"
                    :source "gitlab"
                    :source-id "woblight/nitro"
                    :label "Nitro"
                    :name "nitro"
                    :download-count 0
                    :game-track-list []
                    :tag-list []}

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
