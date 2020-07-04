(ns strongbox.addon-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [test-helper :as helper :refer [fixture-path slurp-fixture helper-data-dir with-running-app]]
    ;;[core :as core]
    [addon :as addon]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest determine-primary-subdir
  (testing "basic failure cases"
    (is (= nil (addon/determine-primary-subdir [])))
    (is (= nil (addon/determine-primary-subdir [{}])))
    (is (= nil (addon/determine-primary-subdir [{:path nil}])))
    (is (= nil (addon/determine-primary-subdir [{:path ""}])))
    (is (= nil (addon/determine-primary-subdir [{:path "Foo/"} {:path "Bar/"}]))))

  (testing "multiple paths, different lengths, shortest is not a common prefix"
    (is (= nil (addon/determine-primary-subdir [{:path "z"} {:path "az"}]))))

  (testing "basic success cases"
    (is (= {:path "Foo/"} (addon/determine-primary-subdir [{:path "Foo/"}])))
    (is (= {:path "Foo/"} (addon/determine-primary-subdir [{:path "Foo-Bar/"} {:path "Foo/"}]))))

  (testing "actual case with HealBot"
    (let [fixture [{:path "HealBot/"} {:path "HealBot_br/"} {:path "HealBot_cn/"} {:path "HealBot_de/"}
                   {:path "HealBot_es/"} {:path "HealBot_fr/"} {:path "HealBot_gr/"} {:path "HealBot_hu/"}
                   {:path "HealBot_it/"} {:path "HealBot_kr/"} {:path "HealBot_ru/"} {:path "HealBot_Tips/"} {:path "HealBot_tw/"}]
          expected {:path "HealBot/"}]
      (is (= expected (addon/determine-primary-subdir fixture)))))

  (testing "only unique values are compared"
    (is (= {:path "Foo/"} (addon/determine-primary-subdir [{:path "Foo/"} {:path "Foo/"} {:path "Foo/"} {:path "Foo-Bar/"}]))))

  (testing "original value is preserved despite modification for comparison"
    (is (= {:path "Foo"} (addon/determine-primary-subdir [{:path "Foo"}])))))
