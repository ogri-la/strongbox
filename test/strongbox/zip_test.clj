(ns strongbox.zip-test
  (:require
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [utils :as utils :refer [join]]
    [zip :as zip]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "strongbox.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

(deftest valid-zip-file?
  (testing "detects basic problems with zip files"
    (is (false? (zip/valid-zip-file? "test/fixtures/bad-empty.zip")))
    (is (false? (zip/valid-zip-file? "test/fixtures/bad-truncated.zip")))
    (is (true? (zip/valid-zip-file? "test/fixtures/empty.zip")))
    (is (true? (zip/valid-zip-file? "test/fixtures/everyaddon--1-2-3.zip")))))

(deftest valid-addon-zip-file?
  (testing "valid addon zip files are a subset of valid zip files"
    (is (false? (zip/valid-addon-zip-file? "test/fixtures/bad-empty.zip")))
    (is (false? (zip/valid-addon-zip-file? "test/fixtures/bad-truncated.zip")))
    (is (false? (zip/valid-addon-zip-file? "test/fixtures/empty.zip")) "contains top-level file") ;; it's not quite empty
    (is (false? (zip/valid-addon-zip-file? "test/fixtures/everyaddon--1-2-3--non-addon-tld.zip")) "contains non-addon top-level directories")

    (is (true? (zip/valid-addon-zip-file? "test/fixtures/everyaddon--1-2-3.zip")))))

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

(deftest suspicious-subdirs
  (testing "`:zipfile/entry-list` are grouped by their first three characters and sorted largest to smallest group"
    (testing "standard case: single addon (foo), nothing suspicious at all"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? false :level 2 :toplevel? false :path "foo/bar"}
                             {:dir? true :level 2 :toplevel? false :path "foo/baz/bup/"}]
            expected [[{:dir? true :level 1 :toplevel? true :path "foo/"}]]]
        (is (= (zip/prefix-groups zipfile-entries) expected))))

    (testing "standard case: multiple addons (foo, foo-bar, foo-baz), shared prefix (foo), nothing suspicious at all"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-bar/"}
                             {:dir? false :level 2 :toplevel? false :path "foo-bar/bup"}
                             {:dir? true :level 2 :toplevel? false :path "foo-baz/bup/"}]
            expected [[{:dir? true :level 1 :toplevel? true :path "foo/"}
                       {:dir? true :level 1 :toplevel? true :path "foo-bar/"}]]]
        (is (= (zip/prefix-groups zipfile-entries) expected))))

    (testing "standard case: multiple addons (foo, foo-bar, foo-baz, bup), but including an inconsistently named addon (bup)"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "bup/"}
                             {:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-bar/"}
                             {:dir? true :level 2 :toplevel? false :path "foo-baz/bup/"}]

            expected [[{:dir? true :level 1 :toplevel? true :path "foo/"}
                       {:dir? true :level 1 :toplevel? true :path "foo-bar/"}]
                      [{:dir? true :level 1 :toplevel? true :path "bup/"}]]]
        (is (= (zip/prefix-groups zipfile-entries) expected))))

    (testing "awkward case: multiple addons (foo, bar), no common group. leave deeper analysis to something else"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "bar/"}]
            expected [[{:dir? true :level 1 :toplevel? true :path "foo/"}]
                      [{:dir? true :level 1 :toplevel? true :path "bar/"}]]]
        (is (= (zip/prefix-groups zipfile-entries) expected)))))

  (testing "`:zipfile/entry-list` with top-level directories with inconsistent prefixes are detected (else nil)"
    (testing "standard case: single addon (foo), nothing supicious at all"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? false :level 2 :toplevel? false :path "foo/bar"}
                             {:dir? true :level 2 :toplevel? false :path "foo/baz/bup/"}]]
        (is (nil? (zip/inconsistently-prefixed zipfile-entries)))))

    (testing "standard case: multiple addons (foo, foo-bar, foo-baz), shared prefix (foo), nothing suspicious at all"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-bar/"}
                             {:dir? false :level 2 :toplevel? false :path "foo-bar/bup"}
                             {:dir? true :level 2 :toplevel? false :path "foo-baz/bup/"}]]
        (is (nil? (zip/inconsistently-prefixed zipfile-entries)))))

    (testing "standard case: multiple addons (foo, foo-bar, foo-baz, bup), but including an inconsistently named addon (bup)"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "bup/"}
                             {:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-bar/"}
                             {:dir? true :level 2 :toplevel? false :path "foo-baz/bup/"}]
            expected ["bup"]]
        (is (= (zip/inconsistently-prefixed zipfile-entries) expected))))

    (testing "awkward case: multiple addons (foo, foo-bar, foo-baz, bup, bup-bap), but including an inconsistently named group (bup, bup-bar)"
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "bup/"}
                             {:dir? true :level 1 :toplevel? true :path "bup-bar/"}
                             {:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-bar/"}
                             {:dir? true :level 1 :toplevel? true :path "foo-baz/"}]
            expected ["bup" "bup-bar"]]
        (is (= (zip/inconsistently-prefixed zipfile-entries) expected))))

    (testing "ambiguous case: multiple addons (foo, bar), no common group."
      (let [zipfile-entries [{:dir? true :level 1 :toplevel? true :path "foo/"}
                             {:dir? true :level 1 :toplevel? true :path "bar/"}]]
        (is (nil? (zip/inconsistently-prefixed zipfile-entries)))))

    (testing "real world case: auctioneer"
      (let [zipfile-entries [{:dir? true, :level 1, :toplevel? true, :path "Auc-Advanced/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Filter-Basic/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-ScanData/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Stat-Histogram/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Stat-iLevel/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Stat-Purchased/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Stat-Simple/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Stat-StdDev/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Auc-Util-FixAH/"}
                             {:dir? true, :level 1, :toplevel? true, :path "BeanCounter/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Enchantrix/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Informant/"}
                             {:dir? true, :level 1, :toplevel? true, :path "SlideBar/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Stubby/"}
                             {:dir? true, :level 1, :toplevel? true, :path "!Swatter/"}]
            expected ["BeanCounter"
                      "Enchantrix"
                      "Informant"
                      "SlideBar"
                      "Stubby"
                      "!Swatter"]]
        (is (= (zip/inconsistently-prefixed zipfile-entries) expected))))

    (testing "real world case: altoholic, where the number of non-altoholic addons exceed the main addon"
      (let [zipfile-entries [{:dir? true, :level 1, :toplevel? true, :path "DataStore_Stats/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Talents/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Achievements/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Agenda/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Auctions/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Characters/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Containers/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Crafts/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Currencies/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Garrisons/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Inventory/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Mails/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Pets/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Quests/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Reputations/"}
                             {:dir? true, :level 1, :toplevel? true, :path "DataStore_Spells/"}

                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Achievements/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Agenda/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Characters/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Grids/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Guild/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Search/"}
                             {:dir? true, :level 1, :toplevel? true, :path "Altoholic_Summary/"}]

            ;; detecting this somehow would be nice ...
            ;; expected ["DataStore_Stats"
            ;;           "DataStore_Talents"
            ;;           "DataStore"
            ;;           "DataStore_Achievements"
            ;;           "DataStore_Agenda"
            ;;           "DataStore_Auctions"
            ;;           "DataStore_Characters"
            ;;           "DataStore_Containers"
            ;;           "DataStore_Crafts"
            ;;           "DataStore_Currencies"
            ;;           "DataStore_Garrisons"
            ;;           "DataStore_Inventory"
            ;;           "DataStore_Mails"
            ;;           "DataStore_Pets"
            ;;           "DataStore_Quests"
            ;;           "DataStore_Reputations"
            ;;           "DataStore_Spells"]

            ;; but in reality there were too many false positives when the size
            ;; of the groups exceeded the magnitude (3)
            expected nil]

        (is (= (zip/inconsistently-prefixed zipfile-entries) expected))))))

