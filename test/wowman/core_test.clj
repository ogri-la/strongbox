(ns wowman.core-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [core :as core]]))

(deftest determine-primary-subdir
  (testing "basic failure cases"
    (is (= nil (core/determine-primary-subdir [])))
    (is (= nil (core/determine-primary-subdir [{}])))
    (is (= nil (core/determine-primary-subdir [{:path nil}])))
    (is (= nil (core/determine-primary-subdir [{:path ""}])))
    (is (= nil (core/determine-primary-subdir [{:path "Foo/"} {:path "Bar/"}]))))

  (testing "multiple paths, different lengths, shortest is not a common prefix"
    (is (= nil (core/determine-primary-subdir [{:path "z"} {:path "az"}]))))

  (testing "basic success cases"
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo/"}])))
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo-Bar/"} {:path "Foo/"}]))))

  (testing "actual case with HealBot"
    (let [fixture [{:path "HealBot/"} {:path "HealBot_br/"} {:path "HealBot_cn/"} {:path "HealBot_de/"}
                   {:path "HealBot_es/"} {:path "HealBot_fr/"} {:path "HealBot_gr/"} {:path "HealBot_hu/"}
                   {:path "HealBot_it/"} {:path "HealBot_kr/"} {:path "HealBot_ru/"} {:path "HealBot_Tips/"} {:path "HealBot_tw/"}]
          expected {:path "HealBot/"}]
      (is (= expected (core/determine-primary-subdir fixture)))))

  (testing "only unique values are compared"
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo/"} {:path "Foo/"} {:path "Foo/"} {:path "Foo-Bar/"}]))))

  (testing "original value is preserved despite modification for comparison"
    (is (= {:path "Foo"} (core/determine-primary-subdir [{:path "Foo"}])))))

(deftest configure
  (testing "called with no overrides gives us whatever is in the state template"
    (let [expected (:cfg core/-state-template)
          file-opts {}
          cli-opts {}]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "file overrides are preserved and foreign keys are removed"
    (let [cli-opts {}
          file-opts {:foo "bar" ;; unknown
                     :debug? true}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "cli overrides are preserved and foreign keys are removed"
    (let [cli-opts {:foo "bar"
                    :debug? true}
          file-opts {}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "cli overrides file overrides"
    (let [cli-opts {:debug? true}
          file-opts {:debug? false}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts))))))
