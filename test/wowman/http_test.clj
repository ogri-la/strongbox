(ns wowman.http-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.http :as http]
   [me.raynes.fs :as fs]))

(deftest encode-url-path
  (testing "url whose path has spaces is correctly encoded"
    (let [path-with-spaces "https://addons.cursecdn.com/files/2548/794/AR 4.5.7.3.zip"
          path-enc-spaces "https://addons.cursecdn.com/files/2548/794/AR%204.5.7.3.zip"]
      (is (= path-enc-spaces (str (http/encode-url-path path-with-spaces)))))))
