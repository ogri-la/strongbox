(ns strongbox.gitlab-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [constants :as constants]
    [logging :as logging]
    [gitlab-api :as gitlab-api]
    [test-helper :refer [fixture-path slurp-fixture]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest parse-user-string
  (let [expected "woblight/nitro"
        ;; all of these should yield the above
        cases ["https://gitlab.com/woblight/nitro"
               
               ;; all valid variations of the above
               "https://gitlab.com/woblight/nitro/" ;; trailing slash
               "http://gitlab.com/woblight/nitro" ;; http
               "https://www.github.com/woblight/nitro" ;; leading 'www'
               
               ;; looser matching we can also support
               "https://gitlab.com/woblight/nitro/-/releases"
               "https://user:name@gitlab.com/woblight/nitro/foo/bar?baz=bup&boo="]]
    (doseq [given cases]
      (testing (str "case: " given)
        (is (= expected (gitlab-api/parse-user-string given)))))))
