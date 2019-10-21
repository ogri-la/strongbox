(ns wowman.github-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [github-api :as github-api]
    ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
    [test-helper :refer [fixture-path]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest parse-user-addon
  (let [fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                     {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}}

        expected {:uri "https://github.com/Aviana/HealComm"
                  :updated-date "2019-10-09T17:40:04Z"
                  :source "github"
                  :source-id "Aviana/HealComm"
                  :label "HealComm"
                  :name "healcomm"
                  :download-count 30946
                  :category-list []}

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
        (testing (str "user input can be parsed and turned into a catalog item. case: " given)
          (is (= expected (github-api/parse-user-addon given)))))))

  (testing "bad URLs return nil. these *should* be caught in the dispatcher"
    (let [cases [["" nil]
                 ["    " nil]
                 [" \n " nil]
                 ["asdf" nil]
                 [" asdf " nil]
                 ["213" nil]]]
      (doseq [[given expected] cases]
        (is (= expected (github-api/parse-user-addon given)))))))


;; todo: test for github-repo-releases--necrosis-classic.json
;; it's an example of a github addon with no assets to download, thus no download-count to calculate
;; it seems reasonable we should be able to add it to the user-catalog though as it otherwise meets
;; the minimum requirements for an ::addon-summary.

;; todo: test for github-repo-releases--ravendwyr-chincilla.json
;; it's an example of a github addon with two relevant assets available to download, one retail, one for classic
