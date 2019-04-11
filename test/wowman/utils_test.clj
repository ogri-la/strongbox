(ns wowman.utils-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.utils :as utils :refer [join]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "wowman.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

;;
;;
;;

(deftest valid-zip-file?
  (testing "predicate detects basic problems with zip files"
    (is (= (utils/valid-zip-file? "test/fixtures/bad-empty.zip") false))
    (is (= (utils/valid-zip-file? "test/fixtures/bad-truncated.zip") false))
    (is (= (utils/valid-zip-file? "test/fixtures/everyaddon--1-2-3.zip") true))))

(deftest unzip-file
  (testing "unzipping a file returns it's given output path. given output path contains unzipped contents"
    (let [zip-file "test/fixtures/empty.zip"
          output-path *temp-dir-path*
          result (utils/unzip-file zip-file output-path)
          expected-file (join *temp-dir-path* "empty.txt")]
      (is (= result output-path))
      (is (fs/exists? expected-file))))

  (testing "attempting to unzip a bad zip file (empty) returns nil"
    (let [zip-file "test/fixtures/bad-empty.zip"]
      (is (= (utils/unzip-file zip-file *temp-dir-path*) nil))))

  (testing "attempting to unzip a bad zip file (truncated/corrupted) returns nil"
    (let [zip-file "test/fixtures/bad-truncated.zip"]
      (is (= (utils/unzip-file zip-file *temp-dir-path*) nil)))))

(deftest list-files
  (testing "listing a directory returns a list of pairs [[path, filename], ...] sorted alphabetically"
    (let [target "test-dir"
          expected [(join target "d1" "d2" "f3")
                    (join target "f1")
                    (join target "f2")]]
      (is (= (utils/list-files target) expected)))))

(deftest zip-directory
  (testing "directory of files can be zipped"
    (let [in-path "test-dir"
          out-path (join *temp-dir-path* "test.zip")]
      (is (= (utils/zip-directory in-path out-path) out-path)))))

(deftest encode-url-path
  (testing "url whose path has spaces is correctly encoded"
    (let [path-with-spaces "https://addons.cursecdn.com/files/2548/794/AR 4.5.7.3.zip"
          path-enc-spaces "https://addons.cursecdn.com/files/2548/794/AR%204.5.7.3.zip"]
      (is (= path-enc-spaces (str (utils/encode-url-path path-with-spaces)))))))

(deftest format-interface-version
  (testing "integer interface version converted to dot-notation correctly"
    (let [cases
          ["00000" "0.0.0"
           "10000" "1.0.0"
           "00100" "0.1.0"
           "00001" "0.0.1"

           ;; actual cases
           "00304" "0.3.4" ;; first pre-release
           "10000" "1.0.0" ;; first release
           "20001" "2.0.1" ;; Burning Crusade, Before The Storm
           "30002" "3.0.2" ;; WotLK, Echos of Doom
           "30008a" "3.0.8a" ;; 'a' ?? supported, but eh ...

            ;; ambiguous/broken cases
           "00010" "0.0.0"
           "01000" "0.0.0"
           "10100" "1.1.0" ;; ambiguous, also, 1.10.0, 10.1.0, 10.10.0
           "10123" "1.1.3" ;; last patch of 1.x, should be 1.12.3

           ;; no match, return nil
           "" nil
           "0" nil
           "00" nil
           "000" nil
           "0000" nil
           "a" nil
           "aaaaa" nil
           "!" nil
           "!!!!!" nil]]

      (doseq [[case expected] (partition 2 cases)]
        (testing (str "testing " case " expecting: " expected)
          (is (= expected (utils/interface-version-to-game-version case))))))))

(deftest merge-lists
  (testing "the two lists are just merged when there are no matches"
    (let [a [{:id "bar"}]
          b [{:id "baz"}]
          expected [{:id "bar"} {:id "baz"}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "when simple merging happens, order is preserved and items in list b replace their counterparts in list a"
    (let [a [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          b [{:id "z" :foo "bar"} {:id "x" :val false}]
          expected [{:id "x" :val false} {:id "y" :val true} {:id "z" :foo "bar"}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "an empty list for b does nothing"
    (let [a [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          b []
          expected [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "an empty list for a does nothing"
    (let [a []
          b [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          expected [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "the entries from list b can prepended if a truth-y :prepend? flag is passed in"
    (let [a [{:id "bar"}]
          b [{:id "baz"}]
          expected [{:id "baz"} {:id "bar"}]]
      (is (= expected (utils/merge-lists :id a b :prepend? true))))))

