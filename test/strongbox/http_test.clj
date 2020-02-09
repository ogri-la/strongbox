(ns strongbox.http-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [strongbox
    [catalog :as catalog]
    [http :as http]]))

(deftest download-404
  (testing "regular (non-streaming) download that yields a 404 returns an error map"
    (let [;; listed in the curseforge catalog but returns a 404 when fetched
          zombie-addon {:name "Brewmaster Tools"
                        :url "https://www.curseforge.com/wow/addons/brewmastertools"
                        :label ""
                        :category-list []
                        :updated-date "2019-01-01T00:00:00Z" :download-count 0}
          fake-routes {"https://www.curseforge.com/wow/addons/brewmastertools/files"
                       {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (nil? (catalog/expand-summary zombie-addon "retail")))))))

(deftest url-to-filename
  (testing "urls can be converted to filenames safe for a filesystem"
    (let [cases [["https://user:name@example.org/foo#anchor?bar=baz&baz=bar"
                  "aHR0cHM6Ly91c2VyOm5hbWVAZXhhbXBsZS5vcmcvZm9vI2FuY2hvcj9iYXI9YmF6JmJhej1iYXI=.html"]

                 ;; forward slashes are replaced with underscores
                 ["https://example.org/?"
                  "aHR0cHM6Ly9leGFtcGxlLm9yZy8_.html"]

                 ;; extensions are preserved if possible
                 ["https://example.org/foo.asdf"
                  "aHR0cHM6Ly9leGFtcGxlLm9yZy9mb28uYXNkZg==.asdf"]]]
      (doseq [[given expected] cases]
        (is (= expected (http/url-to-filename given)))))))

(deftest user-agent
  (testing "user agent version number"
    (let [cases [["0.1.0" "Wowman/0.1 (https://github.com/ogri-la/strongbox)"]
                 ["0.1.0-unreleased" "Wowman/0.1-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["0.10.0" "Wowman/0.10 (https://github.com/ogri-la/strongbox)"]
                 ["0.10.0-unreleased" "Wowman/0.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["0.10.10" "Wowman/0.10 (https://github.com/ogri-la/strongbox)"]
                 ["0.10.10-unreleased" "Wowman/0.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["10.10.10" "Wowman/10.10 (https://github.com/ogri-la/strongbox)"]
                 ["10.10.10-unreleased" "Wowman/10.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["991.992.993-unreleased" "Wowman/991.992-unreleased (https://github.com/ogri-la/strongbox)"]]]

      (doseq [[given expected] cases]
        (is (= expected (http/strongbox-user-agent given)))))))
