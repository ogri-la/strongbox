(ns strongbox.tukui-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [tukui-api :as tukui-api]]))

(deftest parse-user-string
  (let [cases [;; retail
               ["https://www.tukui.org/download.php?ui=tukui" -1] ;; tukui retail download
               ["https://www.tukui.org/download.php?ui=elvui" -2] ;; elvui retail download
               ["https://www.tukui.org/addons.php?id=38" 38]

               ;; classic
               ["https://www.tukui.org/classic-addons.php?id=1" 1] ;; tukui classic download
               ["https://www.tukui.org/classic-addons.php?id=2" 2] ;; elvui classic download
               ["https://www.tukui.org/classic-addons.php?id=14" 14]

               ;; classic tbc
               ["https://www.tukui.org/classic-tbc-addons.php?id=1" 1] ;; tukui tbc download
               ["https://www.tukui.org/classic-tbc-addons.php?id=2" 2] ;; elvui tbc download
               ["https://www.tukui.org/classic-tbc-addons.php?id=21" 21]

               ;; contrived cases
               ["https://www.tukui.org/download.php?ui=TUKUI" -1] ;; case insensitive
               ["https://www.tukui.org/classic-tbc-addons.php?id=1&id=2&id=3" 1] ;; multiple identical params (use first)
               ["https://www.tukui.org/classic-tbc-addons.php?foo=bar&id=21&baz=bup" 21] ;; multiple params
               ["https://tukui.org/classic-tbc-addons.php?id=21" 21] ;; no 'www'

               ;; invalid cases
               ["https://www.tukui.org/addons.php?id=foo" nil]
               ["https://www.tukui.org/classic-addons.php?id=foo" nil]
               ["https://www.tukui.org/classic-tbc-addons.php?id=foo" nil]]]

    (doseq [[given expected] cases]
      (is (= expected (tukui-api/parse-user-string given))))))

(deftest make-url
  (let [cases [;; retail tukui addon
               [{:name "foo" :source-id 123 :interface-version-list [90000]} "https://www.tukui.org/addons.php?id=123"]
               ;; classic tukui addon
               [{:name "foo" :source-id 123 :interface-version-list [10000]} "https://www.tukui.org/classic-addons.php?id=123"]
               ;; classic-tbc tukui addon
               [{:name "foo" :source-id 123 :interface-version-list [20000]} "https://www.tukui.org/classic-tbc-addons.php?id=123"]

               ;; tukui retail proper url
               [{:name "tukui" :source-id -1 :interface-version-list [90000]} "https://www.tukui.org/download.php?ui=tukui"]
               ;; elvui retail proper url
               [{:name "elvui" :source-id -2 :interface-version-list [90000]} "https://www.tukui.org/download.php?ui=elvui"]

               ;; tukui classic url
               [{:name "tukui" :source-id 1 :interface-version-list [10000]} "https://www.tukui.org/classic-addons.php?id=1"]
               ;; elvui classic url
               [{:name "tukui" :source-id 2 :interface-version-list [10000]} "https://www.tukui.org/classic-addons.php?id=2"]

               ;; ..etc

               ;; dodgy url
               [{:name "foo" :source-id -99 :interface-version-list [90000]} "https://www.tukui.org/download.php?ui=foo"]
               [{:source-id 1 :interface-version-list [10000]} "https://www.tukui.org/classic-addons.php?id=1"]

               ;; bad urls
               [{} nil]
               [{:source-id 1} nil]
               [{:source-id -1 :interface-version-list [90000]} nil]]]

    (doseq [[given expected] cases]
      (is (= expected (tukui-api/make-url given)), (str "failed on given: " given)))))
