(ns strongbox.addon-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [strongbox
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path slurp-fixture helper-data-dir with-running-app]]
    [nfo :as nfo]
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

(deftest group-addons
  (testing "addons with nothing to group on are not modified"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"}]
          expected addon-list]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "addons with groupable data but no groupings are not modified"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "bar" :primary? true}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :group-id "baz" :primary? true}]
          expected addon-list]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "addons with groupable data with one marked as the `primary`, group as expected"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :group-id "bar" :primary? true}]

          expected [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                     :group-id "foo" :primary? true :group-addon-count 2 :group-addons
                     [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "foo" :primary? false}]}

                    {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                     :group-id "bar" :primary? true}]]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "synthetic records are created for groupable addons with no primary addon"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? false}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :group-id "bar" :primary? true}]

          expected [{:name "a1", :dirname "A1", :label "foo (group)", :description "group record for the foo addon" :interface-version 80300 :installed-version "1.2.3"
                     :group-id "foo" :primary? false :group-addon-count 2 :group-addons
                     [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? false}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "foo" :primary? false}]}

                    {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                     :group-id "bar" :primary? true}]]
      (is (= expected (addon/group-addons addon-list))))))

;;

(deftest load-installed-addons-1
  (testing "regular .toc file can be loaded"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))

(deftest load-installed-addons-2
  (testing "toc data and nfo data are mooshed together as expected"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          nfo-data {:source "curseforge"
                    :source-id 123
                    :installed-version "1.2.3"
                    :name "someaddon"
                    :group-id "fdsa"
                    :primary? true
                    :installed-game-track :retail}
          _ (spit some-addon-nfo (utils/to-json nfo-data))

          expected [{;; toc data
                     :name "someaddon",
                     :dirname "SomeAddon",
                     :label "SomeAddon",
                     :description "asdf",
                     :interface-version 80300,

                     ;; shared between toc and nfo, nfo wins out
                     :installed-version "1.2.3"

                     ;; unique items from nfo data
                     :source "curseforge"
                     :source-id 123
                     :group-id "fdsa"
                     :installed-game-track :retail
                     :primary? true}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))

(deftest load-installed-addons-3
  (testing "invalid nfo data is not loaded"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          nfo-data {:source nil ;; invalid
                    :source-id 123
                    :installed-version "1.2.3"
                    :name "someaddon"
                    ;; also invalid. all of these are required
                    ;;:group-id "asdf"
                    ;;:primary? true
                    ;;:installed-game-track :retail
                    }
          _ (spit some-addon-nfo (utils/to-json nfo-data))

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))

(deftest load-installed-addons-4
  (testing "ignore flag in nfo data overrides any ignore flag in toc data"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: @project-version@")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          _ (spit some-addon-nfo (utils/to-json {:source "curseforge" :source-id 123
                                                 :ignore? false})) ;; expressly un-ignoring this otherwise-ignored addon

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300
                     :installed-version "@project-version@"
                     :source "curseforge" :source-id 123
                     :ignore? false}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))



;;


(comment
  (deftest install-addon
    (let [fixture-v0 (fixture-path "everyaddon--0-1-2.zip") ;; v0.1 unzips to two directories
          fixture-v1 (fixture-path "everyaddon--1-2-3.zip") ;; v1.2 has just the one directory

          ;; [::version ::download-url]
          ;; [::name ::label]
          addon-v0 {:name "EveryAddon" :label "Every Addon"
                    :version "0.1.2" :download-url "https://example.org"

                    ;; todo: addon/installable needs to be expanded to include these:
                    :url "https://example.org/foo/bar"
                    :source "curseforge" :source-id 1}

          addon-v1 (assoc addon-v0 :version "1.2.3")

          game-track :retail]

      (testing "installing an addon uninstalls the previous addon first"
        (with-running-app
          (let [addon-path (helper/addons-path)
                _ (addon/install-addon addon-v0 addon-path fixture-v0 game-track)
                addon-path-dirs #(->> addon-path fs/list-dir (map fs/base-name) sort)]

            (is (= ["BundledAddon" "EveryAddon"] (addon-path-dirs)))
            (is (= {} (slurp (fs/file addon-path "EveryAddon" ".strongbox.json"))))
            (is (= {} (slurp (fs/file addon-path "BundledAddon" ".strongbox.json"))))

            ;;(addon/install-addon addon-v1 addon-path fixture-v1 game-track)
            ;;(is (= ["EveryAddon"] (addon-path-dirs))))))
            ))))))
