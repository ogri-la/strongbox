(ns wowman.github-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [github-api :as github-api]
    ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
    [test-helper :refer [fixture-path]]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]))

(deftest parse-user-string
  (let [fixture (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))

        fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                     {:get (fn [req] {:status 200 :body fixture})}

                     "https://api.github.com/repos/aviana/healcomm/releases"
                     {:get (fn [req] {:status 200 :body fixture})}}

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
        (testing (str "user input can be parsed and turned into a catalog item. case: " given)
          (is (= expected (github-api/parse-user-string given)))))))

  (let [cases [""
               "    "
               " \n "
               "asdf"
               " asdf "
               "213"]
        expected nil]
    (doseq [given cases]
      (testing (str "bad URLs return nil. these *should* be caught in the dispatcher. case: " given)
        (is (= expected (github-api/parse-user-string given))))))

  (testing "valid looking URLs with invalid release structure return nil"
    (let [reasonable-looking-url "https://github.com/Robert388/Necrosis-classic"
          expected nil

          ;; (fixture is missing uploaded assets)
          fixture (slurp (fixture-path "github-repo-releases--no-assets.json"))
          fake-routes {"https://api.github.com/repos/Robert388/Necrosis-classic/releases"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/parse-user-string reasonable-looking-url)))))))

(deftest expand-addon-summary
  (let [given {:uri "https://github.com/Aviana/HealComm"
               :updated-date "2019-10-09T17:40:04Z"
               :source "github"
               :source-id "Aviana/HealComm"
               :label "HealComm"
               :name "healcomm"
               :download-count 30946
               :category-list []}

        game-track "retail"

        expected {:uri "https://github.com/Aviana/HealComm"
                  :updated-date "2019-10-09T17:40:04Z"
                  :source "github"
                  :source-id "Aviana/HealComm"
                  :label "HealComm"
                  :name "healcomm"
                  :download-count 30946
                  :category-list []

                  :download-uri "https://github.com/Aviana/HealComm/releases/download/2.04/HealComm.zip"
                  :version "2.04 Beta"}

        fixture (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))
        fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                     {:get (fn [_] {:status 200 :body fixture})}}]

    (testing "expand-summary correctly extracts and adds additional properties"
      (with-fake-routes-in-isolation fake-routes

        (is (= expected (github-api/expand-summary given game-track))))))

  (let [given {:uri "https://github.com/Ravendwyr/Chinchilla"
               :updated-date "2019-10-09T17:40:04Z"
               :source "github"
               :source-id "Ravendwyr/Chinchilla"
               :label "Chinchilla"
               :name "chinchilla"
               :download-count 30946
               :category-list []}

        game-track "classic"

        expected {:category-list []
                  :updated-date "2019-10-09T17:40:04Z"
                  :name "chinchilla"
                  :source "github"
                  :label "Chinchilla"
                  :download-count 30946
                  :source-id "Ravendwyr/Chinchilla"
                  :uri "https://github.com/Ravendwyr/Chinchilla"

                  :download-uri "https://github.com/Ravendwyr/Chinchilla/releases/download/v2.10.0/Chinchilla-v2.10.0-classic.zip"
                  :version "v2.10.0-classic"}

        fixture (slurp (fixture-path "github-repo-releases--ravendwyr-chinchilla.json"))
        fake-routes {"https://api.github.com/repos/Ravendwyr/Chinchilla/releases"
                     {:get (fn [_] {:status 200 :body fixture})}}]

    (testing "classic addons are correctly detected"
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track))))))

  (let [given {:uri "https://github.com/Robert388/Necrosis-classic"
               :updated-date "2019-10-09T17:40:04Z"
               :source "github"
               :source-id "Robert388/Necrosis-classic"
               :label "Necrosis-classic"
               :name "necrosis-classic"
               :download-count 30946
               :category-list []}

        expected nil

        fixture (slurp (fixture-path "github-repo-releases--no-assets.json"))
        fake-routes {"https://api.github.com/repos/Robert388/Necrosis-classic/releases"
                     {:get (fn [_] {:status 200 :body fixture})}}]

    (testing "addons that have no assets (and no right to be in the catalogue) are not downloaded and no errors occur"
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given "classic")))
        (is (= expected (github-api/expand-summary given "retail"))))))

  (let [given {:uri "https://github.com/jsb/RingMenu"
               :updated-date "2019-10-09T17:40:04Z"
               :source "github"
               :source-id "jsb/RingMenu"
               :label "RingMenu"
               :name "ringmenu"
               :download-count 30946
               :category-list []}

        game-track "retail"

        expected nil

        fixture (slurp (fixture-path "github-repo-releases--broken-assets.json"))
        fake-routes {"https://api.github.com/repos/jsb/RingMenu/releases"
                     {:get (fn [_] {:status 200 :body fixture})}}]

    (testing "releases whose assets are only partially uploaded, due to an upload failure, are ignored"
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/expand-summary given game-track)))))))

(deftest extract-source-id
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
        (is (= expected (github-api/extract-source-id given)))))))

