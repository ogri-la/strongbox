(ns strongbox.utils-test
  (:require
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.utils :as utils :refer [join]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "strongbox.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

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

(deftest semver-sort
  (testing "basic sort"
    (let [given ["1.2.3" "4.11.6" "4.2.0" "1.5.19" "1.5.5" "4.1.3" "1.2" "2.3.1" "10.5.5" "1.6.0" "1.2.3" "11.3.0" "1.2.3.4" "1.6.0-unstable" "1.6.0-aaaaaa"]
          ;; sort order for the '-something' cases are unspecified. depends entirely on input order
          expected '("1.2" "1.2.3" "1.2.3" "1.2.3.4" "1.5.5" "1.5.19" "1.6.0" "1.6.0-unstable" "1.6.0-aaaaaa" "2.3.1" "4.1.3" "4.2.0" "4.11.6" "10.5.5" "11.3.0")]
      (is (= expected (utils/sort-semver-strings given))))))

(deftest days-between-then-and-now
  (testing "the number of days between two dates"
    (java-time/with-clock (java-time/fixed-clock "2001-01-02T00:00:00Z")
      (is (= (utils/days-between-then-and-now "2001-01-01") 1)))))

(deftest file-older-than
  (testing "files whose modification times are older than N hours"
    (java-time/with-clock (java-time/fixed-clock "1970-01-01T02:00:00Z") ;; jan 1st 1970, 2 am
      (let [path (utils/join *temp-dir-path* "foo")]
        (try
          (fs/touch path 0) ;; created Jan 1st 1970
          (.setLastModified (fs/file path) 0) ;; modified Jan 1st 1970
          (is (utils/file-older-than path 1))
          (is (not (utils/file-older-than path 3)))
          (finally
            (fs/delete path)))))))

(deftest pad
  (let [cases [;;[[nil 2] nil] ;; must be a collection
               [[[] 0] []]
               [[[] 2] [nil nil]]
               [[[nil nil] 2] [nil nil]]
               [[[:foo :bar] 2] [:foo :bar]]
               [[[:foo] 2] [:foo nil]]
               [[[:foo :bar] 1] [:foo :bar]]]]
    (doseq [[[coll pad-amt] expected] cases]
      (testing (str "list is padded, case:" expected)
        (is (= expected (utils/pad coll pad-amt)))))))

(deftest nilable
  (let [cases [[nil nil]
               [[] nil]
               ['() nil]
               [{} nil]
               [false nil]
               ["" nil]
               ["      " nil]

               [1 1]
               [:foo :foo]
               [[1] [1]]
               [{:foo 1} {:foo 1}]]]
    (doseq [[given expected] cases]
      (testing (str "certain false-y values can be coerced to nil, case: " given)
        (is (= expected (utils/nilable given)))))))

(deftest named-regex-groups
  (let [cases [[[#"(.*)" [] "bar"] {}]
               [[#"(.*)" [:foo] "bar"] {:foo "bar"}]]]
    (doseq [[args expected] cases]
      (testing (str "pattern matches are extracted into a map correctly, case: " args)
        (is (= expected (apply utils/named-regex-groups args)))))))

(deftest replace-file-ext
  (let [cases [[["/path/to/foo.ext" ".json"] "/path/to/foo.json"]
               [["/path/to/foo.ext" "json"] "/path/to/foo.json"]
               [["foo.ext" ".json"] "foo.json"]
               [["foo.ext" "json"] "foo.json"]

               [["foo" ".json"] "foo.json"]]]
    (doseq [[[given given-ext] expected] cases]
      (testing (format "a file can have it's extension replaced, case: (%s %s)" given given-ext)
        (is (= expected (utils/replace-file-ext given given-ext)))))))

(deftest all
  (let [cases [[[nil] false]
               [[nil nil nil] false]
               [[false] false]
               [[false false false] false]
               [[nil false nil] false]
               [[false nil false] false]

               [[] true]
               [[""] true]
               [[0] true]
               [[1 2 3] true]

               [[1 2 nil] false]
               [[1 nil 3] false]
               [[false 2 3] false]]]
    (doseq [[given expected] cases]
      (testing (format "'all', case: (%s %s)" given expected)
        (is (= expected (utils/all given)))))))

(deftest any
  (let [cases [[[nil] false]
               [[nil nil nil] false]
               [[false] false]
               [[false false false] false]
               [[nil false nil] false]
               [[false nil false] false]

               [[] false] ;; different from 'and' behaviour
               [[""] true]
               [[0] true]
               [[1 2 3] true]

               [[1 2 nil] true]
               [[1 nil 3] true]
               [[false 2 3] true]]]
    (doseq [[given expected] cases]
      (testing (format "'any', case: (%s %s)" given expected)
        (is (= expected (utils/any given)))))))

(deftest drop-nils
  (let [cases [[{} [] {}]
               [{} [:foo] {}]
               [{} [nil] {}]
               [{} ["foo"] {}]

               [{:foo nil} [] {:foo nil}]
               [{:foo nil} [:foo] {}]
               [{:foo :bar} [:foo] {:foo :bar}]
               [{:foo :bar, :bar nil} [:foo] {:foo :bar, :bar nil}]
               [{:foo :bar, :bar nil} [:bar] {:foo :bar}]]]

    (doseq [[m fields expected] cases]
      (testing (format "'drop-nils', case: (%s %s => %s)" m fields expected)
        (is (= expected (utils/drop-nils m fields)))))))

(deftest safe-subs
  (let [cases [[[nil 0] nil]
               [[nil 1] nil]
               [[nil 99] nil]
               [["" 0] ""]
               [["" 1] ""]
               [["foo" 0] ""]
               [["foo" 1] "f"]
               [["foo" 100] "foo"]
               [["foo" -100] ""]]]
    (doseq [[[string max] expected] cases]
      (is (= expected (utils/safe-subs string max))))))

(deftest no-new-lines
  (let [cases [[nil nil]
               ["" ""]
               [" " " "]
               ["\n" " "]
               ["\r\n" " "]
               ["foo\nbar" "foo bar"]
               ["foo\r\nbar" "foo bar"]
               ["foo\nbar\r\nbaz" "foo bar baz"]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/no-new-lines given)) (format "failed given '%s'" given)))))
