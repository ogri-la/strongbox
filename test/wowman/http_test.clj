(ns wowman.http-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [wowman.http :as http]))

(deftest encode-url-path
  (testing "url whose path has spaces is correctly encoded"
    (let [path-with-spaces "https://addons.cursecdn.com/files/2548/794/AR 4.5.7.3.zip"
          path-enc-spaces "https://addons.cursecdn.com/files/2548/794/AR%204.5.7.3.zip"]
      (is (= path-enc-spaces (str (http/encode-url-path path-with-spaces)))))))

(deftest download-404
  (testing "regular (non-streaming) download that yields a 404 returns an error map"
    (let [;; listed in the curseforge catalog but returns a 404 when fetched
          zombie-addon {:name "Brewmaster Tools"
                        :uri "https://www.curseforge.com/wow/addons/brewmastertools"
                        :label ""
                        :category-list []
                        :updated-date "2019-01-01T00:00:00Z" :download-count 0}
          fake-routes {"https://www.curseforge.com/wow/addons/brewmastertools/files"
                       {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (nil? (wowman.curseforge/expand-summary zombie-addon)))))))

(deftest user-agent
  (testing "user agent version number"
    (let [cases [["0.1.0" "Wowman/0.1 (https://github.com/ogri-la/wowman)"]
                 ["0.1.0-unreleased" "Wowman/0.1-unreleased (https://github.com/ogri-la/wowman)"]

                 ["0.10.0" "Wowman/0.10 (https://github.com/ogri-la/wowman)"]
                 ["0.10.0-unreleased" "Wowman/0.10-unreleased (https://github.com/ogri-la/wowman)"]

                 ["0.10.10" "Wowman/0.10 (https://github.com/ogri-la/wowman)"]
                 ["0.10.10-unreleased" "Wowman/0.10-unreleased (https://github.com/ogri-la/wowman)"]

                 ["10.10.10" "Wowman/10.10 (https://github.com/ogri-la/wowman)"]
                 ["10.10.10-unreleased" "Wowman/10.10-unreleased (https://github.com/ogri-la/wowman)"]

                 ["991.992.993-unreleased" "Wowman/991.992-unreleased (https://github.com/ogri-la/wowman)"]]]

      (doseq [[given expected] cases]
        (is (= expected (http/wowman-user-agent given)))))))
