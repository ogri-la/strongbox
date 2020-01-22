(ns wowman.nfo-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [utils :as utils]
    [nfo :as nfo]
    [test-helper :as helper]]
   [wowman.utils :refer [join]]
   [me.raynes.fs :as fs]))

(def addon-dir "SomeAddon")
(def ignorable-addon-dir "SomeOtherAddon")

(defn install-dir
  []
  (str fs/*cwd*))

(defn addon-path
  []
  (utils/join fs/*cwd* addon-dir))

(defn ignorable-addon-path
  []
  (utils/join fs/*cwd* ignorable-addon-dir))

;;

(defn fixture-someaddon
  [f]
  (fs/mkdir addon-dir)
  (fs/mkdirs (utils/join ignorable-addon-dir ".git"))
  (f))

(use-fixtures :each helper/fixture-tempcwd fixture-someaddon)

;;

(deftest ignore-dir
  (testing "an addon directory is ignored if it contains an svc-type sub directory"
    (doseq [ignorable-dir nfo/ignorable-dir-set
            :let [path (utils/join (addon-path) ignorable-dir)]]
      (try
        (fs/mkdirs path)
        (is (nfo/ignore? (addon-path)))
        (finally
          (fs/delete-dir (addon-path)))))))

(deftest nfo-path
  (testing "path to the nfo file is generated correctly"
    (let [expected (utils/join (addon-path) ".wowman.json")]
      (is (= expected (nfo/nfo-path (install-dir) addon-dir))))))

(deftest rm-nfo
  (testing "a nfo file is deleted"
    (let [path (utils/join (addon-path) ".wowman.json")]
      (fs/touch path)
      (is (fs/exists? path))
      (nfo/rm-nfo path)
      (is (not (fs/exists? path)))))

  (testing "a non-nfo file is preserved"
    (let [path (utils/join (addon-path) "SomeAddon.toc")]
      (fs/touch path)
      (is (fs/exists? path))
      (nfo/rm-nfo path)
      (is (fs/exists? path)))))

(deftest read-nfo
  (testing "an addon with no nfo data returns nothing"
    (let [expected nil]
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "an addon with no nfo data but an ignorable sub-directory returns the 'ignore flag'"
    (let [expected {:ignore? true}]
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir)))))

  (testing "an addon with nfo data is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123}
          expected nfo-data]
      (spit (utils/join (addon-path) ".wowman.json") (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "an addon with nfo data AND an ignorable sub-directory is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123}
          expected (assoc nfo-data :ignore? true)]
      (spit (utils/join (ignorable-addon-path) ".wowman.json") (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir)))))

  (testing "an addon with nfo data, an ignorable sub-directory AND an ignore flag, is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123

                    ;; update me! destroy any changes to my work!
                    ;; this is only ever set by the user, not by the app.
                    :ignore? false}
          expected (assoc nfo-data :ignore? false)]
      (spit (utils/join (ignorable-addon-path) ".wowman.json") (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir))))))

