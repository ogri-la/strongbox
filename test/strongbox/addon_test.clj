(ns strongbox.addon-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [strongbox
    [logging :as logging]
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
      (is (= expected (addon/group-addons addon-list)))))

  (testing "if any one addon in a group is ignored, the top-level addon ('all') is also ignored"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :group-id "foo" :primary? false :ignore? true}]

          expected [{:name "a1"
                     :dirname "A1"
                     :label "A1"
                     :description ""
                     :interface-version 80300
                     :installed-version "1.2.3"
                     :group-id "foo"
                     :primary? true
                     :ignore? true
                     :group-addon-count 3
                     :group-addons [{:name "a1",
                                     :dirname "A1",
                                     :label "A1",
                                     :description ""
                                     :interface-version 80300
                                     :installed-version "1.2.3"
                                     :group-id "foo"
                                     :primary? true}
                                    {:name "a2",
                                     :dirname "A2",
                                     :label "A2",
                                     :description ""
                                     :interface-version 80300
                                     :installed-version "4.5.6"
                                     :group-id "foo"
                                     :primary? false}
                                    {:name "a3",
                                     :dirname "A3",
                                     :label "A2",
                                     :description ""
                                     :interface-version 80300
                                     :installed-version "7.8.9"
                                     :group-id "foo"
                                     :primary? false
                                     :ignore? true}]}]]
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

(deftest load-installed-addons--invalid-nfo-data-not-loaded
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
                    ;; also invalid. the below are all required:
                    ;;:group-id "asdf"
                    ;;:primary? true
                    ;;:installed-game-track :retail
                    }
          _ (spit some-addon-nfo (utils/to-json nfo-data))

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))

(deftest load-installed-addons--explicit-nfo-ignore
  (testing "ignore flag in nfo data overrides any ignore flag in toc data"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          ;; the `@project-version@` will make the .toc file add the `:ignore? true` flag.
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: @project-version@")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          _ (spit some-addon-nfo (utils/to-json {:source "curseforge" :source-id 123
                                                 :ignore? false})) ;; expressly un-ignoring this otherwise-ignored addon

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300
                     :installed-version "@project-version@"
                     :source "curseforge" :source-id 123
                     :ignore? false}]]
      (is (= expected (addon/load-installed-addons addon-dir))))))

(deftest remove-addon--malign-addon-data
  (testing "uninstalling an addon whose `:dirname` value is corrupted (somehow) shouldn't affect data outside of the addon dir"
    (let [install-dir (helper/install-dir)

          cases [[{:dirname "./"} "directory is outside the current installation dir, not removing"]
                 [{:dirname "../"} "directory is outside the current installation dir, not removing"]
                 [{:dirname "../../"} "directory is outside the current installation dir, not removing"]

                 ;; basename is called on install-dir so you get a DNE error in these cases
                 [{:dirname "/root"} "addon not removed, path is not a directory"]
                 [{:dirname "~/Desktop"} "addon not removed, path is not a directory"]
                 [{:dirname install-dir} "addon not removed, path is not a directory"]]

          _ (fs/touch (utils/join install-dir "somefile"))
          _ (fs/mkdir (utils/join install-dir "somedir"))

          expected (helper/install-dir-contents)

          defaults {:name "nom" :label "Nom" :description "" :interface-version 90100 :installed-version "0.1"}]
      (doseq [[given error-prefix] cases]
        (let [[error-message]
              (logging/buffered-log :error
                                    (addon/remove-addon install-dir (merge defaults given)))]
          (is (= expected (helper/install-dir-contents)))
          (is (clojure.string/starts-with? error-message error-prefix)))))))
