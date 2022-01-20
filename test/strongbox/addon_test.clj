(ns strongbox.addon-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [me.raynes.fs :as fs]
   [strongbox
    [zip :as zip]
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
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3" :supported-game-tracks [:retail]}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6" :supported-game-tracks [:retail]}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9" :supported-game-tracks [:retail]}]
          expected addon-list]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "addons with groupable data but no groupings are not modified"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "bar" :primary? true}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :supported-game-tracks [:retail]
                       :group-id "baz" :primary? true}]
          expected addon-list]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "addons with groupable data with one marked as the `primary`, group as expected"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :supported-game-tracks [:retail]
                       :group-id "bar" :primary? true}]

          expected [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                     :supported-game-tracks [:retail]
                     :group-id "foo" :primary? true :group-addon-count 2 :group-addons
                     [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}]}

                    {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                     :supported-game-tracks [:retail]
                     :group-id "bar" :primary? true}]]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "synthetic records are created for groupable addons with no primary addon"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :supported-game-tracks [:retail]
                       :group-id "bar" :primary? true}]

          expected [{:name "a1", :dirname "A1", :label "foo (group)", :description "group record for the foo addon" :interface-version 80300 :installed-version "1.2.3"
                     :supported-game-tracks [:retail]
                     :group-id "foo" :primary? false :group-addon-count 2 :group-addons
                     [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}]}

                    {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                     :supported-game-tracks [:retail]
                     :group-id "bar" :primary? true}]]
      (is (= expected (addon/group-addons addon-list)))))

  (testing "if any one addon in a group is ignored, the top-level addon ('all') is also ignored"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? false :ignore? true}]

          expected [{:name "a1"
                     :dirname "A1"
                     :label "A1"
                     :description ""
                     :interface-version 80300
                     :installed-version "1.2.3"

                     :supported-game-tracks [:retail]
                     :group-id "foo"
                     :primary? true
                     :ignore? true
                     :group-addon-count 3
                     :group-addons [{:name "a1",
                                     :dirname "A1",
                                     :label "A1",
                                     :description ""
                                     :interface-version 80300

                                     :supported-game-tracks [:retail]
                                     :installed-version "1.2.3"
                                     :group-id "foo"
                                     :primary? true}
                                    {:name "a2",
                                     :dirname "A2",
                                     :label "A2",
                                     :description ""
                                     :interface-version 80300

                                     :supported-game-tracks [:retail]
                                     :installed-version "4.5.6"
                                     :group-id "foo"
                                     :primary? false}
                                    {:name "a3",
                                     :dirname "A3",
                                     :label "A2",
                                     :description ""
                                     :interface-version 80300

                                     :supported-game-tracks [:retail]
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

          expected [{:name "someaddon",
                     :dirname "SomeAddon",
                     :label "SomeAddon",
                     :description "asdf",
                     :interface-version 80300,

                     :supported-game-tracks [:retail]
                     :installed-version "1.2.3"}]]
      (is (= expected (addon/load-installed-addons addon-dir :retail))))))

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
                    :source-map-list [{:source "curseforge" :source-id 123}]
                    :installed-version "1.2.3"
                    :name "someaddon"
                    :group-id "fdsa"
                    :primary? true
                    :installed-game-track :retail}
          _ (spit some-addon-nfo (utils/to-json nfo-data))

          expected [{;; toc data
                     :name "someaddon"
                     :dirname "SomeAddon"
                     :label "SomeAddon"
                     :description "asdf"
                     :interface-version 80300

                     ;; shared between toc and nfo, nfo wins out
                     :installed-version "1.2.3"

                     ;; unique items from nfo data
                     :source "curseforge"
                     :source-id 123
                     :source-map-list [{:source "curseforge" :source-id 123}]
                     :group-id "fdsa"
                     :installed-game-track :retail
                     :supported-game-tracks [:retail]
                     :primary? true}]]
      (is (= expected (addon/load-installed-addons addon-dir :retail))))))

(deftest load-installed-addons--invalid-nfo-data-not-loaded
  (testing "invalid nfo data is not loaded"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          nfo-path (utils/join some-addon-path nfo/nfo-filename)
          nfo-data {:source nil ;; invalid
                    :source-id 123
                    :installed-version "1.2.3"
                    :name "someaddon"
                    ;; also invalid. the below are all required:
                    ;;:group-id "asdf"
                    ;;:primary? true
                    ;;:installed-game-track :retail
                    }
          _ (spit nfo-path (utils/to-json nfo-data))

          expected [{:name "someaddon",
                     :dirname "SomeAddon",
                     :label "SomeAddon",
                     :description "asdf",
                     :interface-version 80300,
                     :installed-version "1.2.3"
                     :supported-game-tracks [:retail]}]]
      (is (= expected (addon/load-installed-addons addon-dir :retail))))))

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

          expected [{:name "someaddon",
                     :dirname "SomeAddon",
                     :label "SomeAddon",
                     :description "asdf",
                     :interface-version 80300
                     :supported-game-tracks [:retail]
                     :installed-version "@project-version@"
                     :source "curseforge"
                     :source-id 123
                     :source-map-list [{:source "curseforge" :source-id 123}]

                     :ignore? false}]]
      (is (= expected (addon/load-installed-addons addon-dir :retail))))))

(deftest load-installed-addons--multiple-non-identical-toc-data
  (let [fixture (helper/fixture-path "everyaddon--1-2-3--multi-toc--inconsistent.zip")
        game-track :classic
        expected [;; description has been modified in "-Classic" vs "-Vanilla"
                  {:description "Slightly differently does what no other addon does."
                   :dirname "EveryAddon",
                   :installed-version "1.2.3",
                   :interface-version 11307,
                   :label "EveryAddon 1.2.3",
                   :name "everyaddon",
                   :supported-game-tracks [:classic :classic-tbc :retail]}]

        expected-warning "multiple sets of different toc data found for :classic. using first."]
    (zip/unzip-file fixture (helper/install-dir))
    (let [[warning] (logging/buffered-log
                     :warn
                     (is (= expected (addon/-load-installed-addons (helper/install-dir) game-track))))]
      (is (= expected-warning warning)))))

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

          defaults {:name "nom" :label "Nom" :description "" :interface-version 90100 :installed-version "0.1"
                    :supported-game-tracks [:retail]}]
      (doseq [[given error-prefix] cases]
        (let [[error-message]
              (logging/buffered-log :error
                                    (addon/remove-addon install-dir (merge defaults given)))]
          (is (= expected (helper/install-dir-contents)))
          (is (clojure.string/starts-with? error-message error-prefix)))))))

;;

(deftest test-pinned-dir-list
  (testing "directory names of pinned addons are detected"
    (let [addon-list [;; single, unpinned, addon
                      {:name "a1", :dirname "A1", :label "A1", :description "" :installed-version "1.2.3" :interface-version 80300
                       :supported-game-tracks [:retail]
                       :group-id "foo" :primary? true}

                      ;; single, pinned, addon
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :supported-game-tracks [:retail]
                       :group-id "bar" :primary? false :pinned-version "4.5.6"}

                      ;; grouped addon, group members pinned
                      {:name "a3", :dirname "A3", :label "A3", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :supported-game-tracks [:retail]
                       :group-id "baz" :primary? true, :pinned-version "7.8.9"
                       :group-addons [;; addon's contain themselves in `:group-addons`
                                      {:name "a3", :dirname "A3", :label "A3", :description "" :interface-version 80300 :installed-version "7.8.9"
                                       :supported-game-tracks [:retail]
                                       :group-id "baz" :primary? true :pinned-version "7.8.9"}

                                      {:name "a3-sub", :dirname "A3_Sub", :label "A3-Sub", :description "" :interface-version 80300 :installed-version "7.8.9.0"
                                       :supported-game-tracks [:retail]
                                       :group-id "baz" :primary? false :pinned-version "7.8.9"}]}

                      ;; abnormal case.
                      ;; grouped addon, only a group member pinned.
                      ;; todo: support this case and consider both A4 and A4_Sub pinned?
                      ;; is this case possible if an unpinned addon overwrites a pinned one?
                      {:name "a4", :dirname "A4", :label "A4", :description "" :interface-version 80300 :installed-version "0.1.2"
                       :supported-game-tracks [:retail]
                       :group-id "bup" :primary? false
                       :group-addons [;; addon's contain themselves in `:group-addons`
                                      {:name "a4", :dirname "A4", :label "A4", :description "" :interface-version 80300 :installed-version "0.1.2"
                                       :supported-game-tracks [:retail]
                                       :group-id "bup" :primary? false} ;;, :pinned-version "7.8.9"} ;; we're not doing this. should we be doing this?

                                      {:name "a4-sub", :dirname "A4_Sub", :label "A4-Sub", :description "" :interface-version 80300 :installed-version "0.1.2.0"
                                       :supported-game-tracks [:retail]
                                       :group-id "bup" :primary? false :pinned-version "foooooooooooo"}]}]

          expected #{"A2" "A3" "A3_Sub"
                     "A4_Sub"}]

      (is (= expected (addon/pinned-dir-list addon-list)))))

  (testing "empty cases work"
    (let [cases [[nil #{}]
                 [[] #{}]]]
      (doseq [[given expected] cases]
        (is (= expected (addon/pinned-dir-list given)))))))

(deftest test-overwrites-pinned?
  (testing "addon zip files that would extract over a pinned addon are correctly detected"
    (let [downloaded-file (fixture-path "everyaddon--1-2-3.zip")
          addon {:name "EveryAddon",
                 :dirname "EveryAddon",
                 :label "Every Addon",
                 :description ""
                 :interface-version 80300
                 :installed-version "1.2.3"
                 :supported-game-tracks [:retail]
                 :group-id "foo"
                 :primary? true}
          pinned-addon (assoc addon :pinned-version "1.2.3")]
      (is (addon/overwrites-pinned? downloaded-file [pinned-addon]))
      (is (not (addon/overwrites-pinned? downloaded-file [addon]))))))

(deftest test-updateable?
  (testing "an addon's 'updateable' states"
    (let [cases [;; no update available
                 [{:installed-version "1.2.3"} false]

                 ;; installed and available are equal
                 [{:installed-version "1.2.3" :version "1.2.3"} false]

                 ;; ignored
                 [{:installed-version "1.2.3" :version "1.2.4" :ignore? true} false]

                 ;; update available, but pinned to installed version.
                 ;; this may happen when a release drifts off of a curseforge addon's list of latest releases by game version,
                 ;; or `:projectFileId` synthetic `:-unique-name` that we set is changed,
                 ;; or the release is simply deleted I suppose.
                 ;; `catalogue.clj` won't be able to find the pinned release and will use the latest release instead.
                 [{:installed-version "1.2.3" :version "1.2.4" :pinned-version "1.2.3"} false]

                 ;; update possibly available but no `:installed-game-track` present
                 [{:installed-version "1.2.3", :version "1.2.3" :game-track :retail} false]

                 ;; ---

                 ;; update available
                 [{:installed-version "1.2.0" :version "1.2.3"} true]

                 ;; update available, explicitly not being ignored
                 [{:installed-version "1.2.0" :version "1.2.3" :ignore? false} true]

                 ;; update available and pinned version matches available version
                 [{:installed-version "1.2.3" :version "1.2.4" :pinned-version "1.2.4"} true]

                 ;; update available with same version but different game track.
                 ;; happens when addon installed under one game track, the game track is switched and a catalogue match is still found.
                 [{:installed-version "1.2.3", :version "1.2.3"
                   :installed-game-track :classic, :game-track :retail} true]

                 ;; ---

                 ;; same version, different game track and the available game track isn't in list of supported game tracks.
                 ;; happens when addon installed under one game track, the game track is switched and a catalogue match is still found.
                 ;; list of supported-game-tracks doesn't make any difference.
                 [{:installed-version "1.2.3", :version "1.2.3",
                   :installed-game-track :classic, :game-track :retail
                   :supported-game-tracks [:classic]} true]

                 ;; same version, different game track and the available game track *is* in list of supported game tracks.
                 ;; happens when addon supporting multiple game tracks is installed under one game track, the game track is switched and a catalogue match is still found.
                 [{:installed-version "1.2.3", :version "1.2.3",
                   :installed-game-track :classic, :game-track :retail
                   :supported-game-tracks [:classic :retail]} false]]]

      (doseq [[addon expected] cases]
        (is (= expected (addon/updateable? addon)), (str addon " != " expected))))))

(deftest test-ignored?
  (testing "an addon is being ignored if the `:ignore?` flag is present and set to `true`"
    (is (addon/ignored? {:ignore? true}))
    (is (not (addon/ignored? {:ignore? false})))
    (is (not (addon/ignored? {})))))

(deftest test-ignorable?
  (is (addon/ignorable? {:dirname "Foo"}))
  (is (addon/ignorable? {:dirname "Foo" :ignore? false}))
  (is (not (addon/ignorable? {:dirname "Foo" :ignore? true})))
  (is (not (addon/ignorable? {:ignore? true}))) ;; we need something (a dirname) to ignore!
  (is (not (addon/ignorable? {}))))

(deftest test-re-installable?
  (testing "an addon is re-installable if a release matching its installed-version is present"
    (let [addon {:name "a3", :dirname "A3", :label "A3", :description "" :interface-version 80300 :installed-version "1.2.0"
                 :group-id "baz", :primary? true, :download-url "https://example.org/path/to/addon.zip"
                 :source "curseforge" :source-id 123 :version "1.2.0" :game-track :retail
                 :release-list [{:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.3.zip",
                                 :version "1.2.3"}
                                {:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.0.zip",
                                 :version "1.2.0"}]}]
      (is (addon/re-installable? addon)))))

(deftest test-find-release
  (testing "an addon's installed release can be found"
    (let [addon {:name "a3", :dirname "A3", :label "A3", :description "" :interface-version 80300 :installed-version "1.2.0"
                 :group-id "baz", :primary? true, :download-url "https://example.org/path/to/addon.zip"
                 :source "curseforge" :source-id 123 :version "1.2.0" :game-track :retail
                 :release-list [{:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.3.zip",
                                 :version "1.2.3"}
                                {:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.0.zip",
                                 :version "1.2.0"}]}
          expected (get-in addon [:release-list 1])]
      (is (= expected (addon/find-release addon))))))

(deftest test-find-pinned-release
  (testing "an addon's installed release can be found"
    (let [addon {:name "a3", :dirname "A3", :label "A3", :description "" :interface-version 80300 :installed-version "1.2.0"
                 :group-id "baz", :primary? true, :download-url "https://example.org/path/to/addon.zip"
                 :source "curseforge" :source-id 123 :version "1.2.0" :game-track :retail
                 :pinned-version "1.2.3"
                 :release-list [{:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.3.zip",
                                 :version "1.2.3"}
                                {:download-url "https://example.org/path/to/addon.zip"
                                 :game-track :retail,
                                 :interface-version 90000,
                                 :release-label "[WoW 9.0.1] Addon-1.2.0.zip",
                                 :version "1.2.0"}]}
          expected (get-in addon [:release-list 0])]
      (is (= expected (addon/find-pinned-release addon))))))

(deftest test-pinned?
  (testing "an addon is considered 'pinned' if a pinned version is present"
    (is (addon/pinned? {:pinned-version "1.2.3"}))
    (is (not (addon/pinned? {})))))

(deftest test-pinnable?
  (testing "an addon is pinnable if a version of it is installed and it's not being ignored"
    (is (addon/pinnable? {:dirname "Foo" :installed-version "1.2.3"}))
    (is (addon/pinnable? {:dirname "Foo" :installed-version "1.2.3" :ignore? false}))
    (is (not (addon/pinnable? {:dirname "Foo" :installed-version "1.2.3" :ignore? true})))
    (is (not (addon/pinnable? {:dirname "Foo"})))
    (is (not (addon/pinnable? {:installed-version "1.2.3"})))))

(deftest test-unpinnable?
  (is (addon/unpinnable? {:pinned-version "1.2.3"}))
  (is (not (addon/unpinnable? {:pinned-version "1.2.3" :ignore? true})))
  (is (not (addon/unpinnable? {}))))

(deftest implicitly-ignored?
  (testing "evidence of a template in the toc file marks addon as implicitly ignored"
    (let [nom "EveryAddon"
          addon-dir (utils/join (helper/install-dir) nom)
          path (fn [bit]
                 (utils/join addon-dir bit))]
      (fs/mkdir addon-dir)
      (spit (path "EveryAddon.toc") "## Title: Foo\n## Version: @project-version@")
      (is (true? (addon/implicitly-ignored? (helper/install-dir) nom))))))

(deftest implicitly-ignored?--multi-toc
  (testing "evidence of a template in *any* toc file, marks addon as implicitly ignored"
    (let [nom "EveryAddon"
          addon-dir (utils/join (helper/install-dir) nom)
          path (fn [bit]
                 (utils/join addon-dir bit))]
      (fs/mkdir addon-dir)
      (spit (path "EveryAddon.toc") "## Title: Foo\n## Version: 1.2.3")
      (spit (path "EveryAddon-Mainline.toc") "## Title: Foo-Bar\n## Version: @project-version@")
      (is (true? (addon/implicitly-ignored? (helper/install-dir) nom))))))

(deftest switch-source--no-sources-available
  (testing "an addon can switch between sources but only if another source is available."
    (let [new-source-map {:source "wowinterface" :source-id 321}
          addon helper/strongbox-installed-addon] ;; has no source-map-list
      (is (nil? (addon/switch-source! (helper/install-dir) addon new-source-map))))))

(deftest switch-source--no-ignored
  (testing "an addon can switch between sources, but not if it is being ignored."
    (let [new-source-map {:source "wowinterface" :source-id 321}
          addon (merge helper/strongbox-installed-addon
                       {:source-map-list [new-source-map]
                        :ignore? true})
          nfo-data {:source-map-list [new-source-map]
                    :ignore? true}
          expected (merge helper/nfo-data nfo-data)]
      (helper/install-every-addon! nfo-data)
      (is (nil? (addon/switch-source! (helper/install-dir) addon new-source-map)))
      (is (= expected (nfo/read-nfo (helper/install-dir) (:dirname helper/toc-data)))))))

(deftest switch-source--no-pinned
  (testing "an addon can switch between sources, but not if it is pinned."
    (let [new-source-map {:source "wowinterface" :source-id 321}
          addon (merge helper/strongbox-installed-addon
                       {:source-map-list [new-source-map]
                        :pinned-version "123"})
          nfo-data {:source-map-list [new-source-map]
                    :pinned-version "123"}
          expected (merge helper/nfo-data nfo-data)]
      (helper/install-every-addon! nfo-data)
      (is (nil? (addon/switch-source! (helper/install-dir) addon new-source-map)))
      (is (= expected (nfo/read-nfo (helper/install-dir) (:dirname helper/toc-data)))))))

(deftest switch-source
  (testing "an addon can switch between sources"
    (let [new-source-map {:source "wowinterface" :source-id 321}
          ;; attach extra source-map to the toc data so we can switch to it
          addon (merge helper/strongbox-installed-addon
                       {:source-map-list [new-source-map]})
          expected (merge helper/nfo-data
                          new-source-map
                          {:source-map-list [{:source "curseforge" :source-id 1} ;; original is preserved
                                             new-source-map]})] ;; new one is present
      (helper/install-every-addon!)
      (is (nil? (addon/switch-source! (helper/install-dir) addon new-source-map)))
      (is (= expected (nfo/read-nfo (helper/install-dir) (:dirname helper/toc-data)))))))

(deftest merge-toc-nfo--source-map-list1
  (testing "merging toc and nfo with various source maps, including a duplicate, will generate a final, correct version"
    (let [nfo {:source "github" :source-id "123"}
          toc {:source "github" :source-id "123" :source-map-list [{:source "wowinterface" :source-id "321"}]}
          expected {:source "github" :source-id "123"
                    :source-map-list [{:source "github" :source-id "123"}
                                      {:source "wowinterface" :source-id "321"}]}]
      (is (= expected (addon/merge-toc-nfo toc nfo))))))

(deftest merge-toc-nfo--source-map-list2
  (testing "merging toc and empty nfo with results in a correct source-map-list"
    (let [nfo nil
          toc {:source "github" :source-id "123"}
          expected {:source "github" :source-id "123"
                    :source-map-list [{:source "github" :source-id "123"}]}]

      (is (= expected (addon/merge-toc-nfo toc nfo))))))

(deftest merge-lists
  (let [cases [[[nil nil] nil]
               [[[] nil] nil]
               [[nil []] nil]
               [[[] []] nil]

               [[[:foo] [:bar]] [:foo :bar]]
               [[[{:foo :bar} {:bar :baz}] [{:foo :bar}]] [{:foo :bar} {:bar :baz}]]]]

    (doseq [[[a b] expected] cases]
      (is (= expected (addon/merge-lists a b))))))

(deftest extract-source-map-list
  (let [cases [[nil nil]
               [{} nil]

               ;; source+id but no source-map-list
               [{:source-id 123 :source "curseforge"} [{:source-id 123 :source "curseforge"}]]

               ;; source+id+source-map-list
               [{:source-id 123 :source "curseforge" :source-map-list [{:source-id 321 :source "wowinterface"}]}
                [{:source-id 123 :source "curseforge"} {:source "wowinterface", :source-id 321}]]

               ;; source+id+source-map-list w.duplicates
               [{:source-id 123 :source "curseforge" :source-map-list [{:source-id 123 :source "curseforge"}
                                                                       {:source-id 321 :source "wowinterface"}]}
                [{:source-id 123 :source "curseforge"} {:source "wowinterface", :source-id 321}]]

               ;; source+id+source-map-list
               [{:source-id 123 :source "curseforge" :source-map-list [{:source-id 321 :source "wowinterface"}
                                                                       {:source-id 456 :source "tukui"}]}
                [{:source-id 123 :source "curseforge"} {:source "wowinterface", :source-id 321} {:source "tukui", :source-id 456}]]]]

    (doseq [[data expected] cases]
      (is (= expected (addon/extract-source-map-list data))))))

(deftest update-nfo!
  (let [zipfile (fixture-path "everyotheraddon--5-6-7.zip")
        addon {:name "everyotheraddon"
               :label "EveryOtherAddon"
               :version "5.6.7"
               :url "https://group.id/also/never/fetched"
               :source "curseforge"
               :source-id 2
               :download-url "https://path/to/remote/addon.zip"
               :game-track :retail

               :dirname "EveryOtherAddon"
               :group-addons [{:dirname "EveryOtherAddon"}
                              {:dirname "EveryAddon-BundledAddon"}]}

        original-nfo [{:group-id "https://group.id/also/never/fetched",
                       :installed-game-track :retail,
                       :installed-version "5.6.7",
                       :name "everyotheraddon",
                       :primary? false,
                       :source "curseforge",
                       :source-id 2,
                       :source-map-list [{:source "curseforge", :source-id 2}]}
                      {:group-id "https://group.id/also/never/fetched",
                       :installed-game-track :retail,
                       :installed-version "5.6.7",
                       :name "everyotheraddon",
                       :primary? false,
                       :source "curseforge",
                       :source-id 2,
                       :source-map-list [{:source "curseforge", :source-id 2}]}]

        updates {:group-id "foobar"}
        expected-nfo (mapv #(merge % updates) original-nfo)]

    (addon/install-addon addon (helper/install-dir) zipfile)

    ;; sanity checks
    (is (= ["EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
    (is (= original-nfo (addon/-read-nfo (helper/install-dir) addon)))

    ;; updated nfo is returned
    (addon/update-nfo! (helper/install-dir) addon updates)

    ;; updated nfo is written to disk
    (is (= expected-nfo (addon/-read-nfo (helper/install-dir) addon)))))
