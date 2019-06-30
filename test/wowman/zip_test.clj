(ns wowman.zip-test
  (:require
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [utils :as utils :refer [join]]
    [zip :as zip]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "wowman.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

(comment "unused"
         (deftest zip-directory
           (testing "directory of files can be zipped"
             (let [in-path "test-dir"
                   out-path (join *temp-dir-path* "test.zip")]
               (is (= (zip/zip-directory in-path out-path) out-path)))))

         (deftest list-files
           (testing "listing a directory returns a list of pairs [[path, filename], ...] sorted alphabetically"
             (let [target "test-dir"
                   expected [(join target "d1" "d2" "f3")
                             (join target "f1")
                             (join target "f2")]]
               (is (= (zip/list-files target) expected))))))

(deftest valid-zip-file?
  (testing "predicate detects basic problems with zip files"
    (is (= (zip/valid-zip-file? "test/fixtures/bad-empty.zip") false))
    (is (= (zip/valid-zip-file? "test/fixtures/bad-truncated.zip") false))
    (is (= (zip/valid-zip-file? "test/fixtures/everyaddon--1-2-3.zip") true))))

(deftest unzip-file
  (testing "unzipping a file returns it's given output path. given output path contains unzipped contents"
    (let [zip-file "test/fixtures/empty.zip"
          output-path *temp-dir-path*
          result (zip/unzip-file zip-file output-path)
          expected-file (join *temp-dir-path* "empty.txt")]
      (is (= result output-path))
      (is (fs/exists? expected-file))))

  (testing "attempting to unzip a bad zip file (empty) returns nil"
    (let [zip-file "test/fixtures/bad-empty.zip"]
      (is (= (zip/unzip-file zip-file *temp-dir-path*) nil))))

  (testing "attempting to unzip a bad zip file (truncated/corrupted) returns nil"
    (let [zip-file "test/fixtures/bad-truncated.zip"]
      (is (= (zip/unzip-file zip-file *temp-dir-path*) nil)))))
