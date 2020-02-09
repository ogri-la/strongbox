(ns strongbox.nfo-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [utils :as utils]
    [nfo :as nfo]
    [test-helper :as helper]]
   [strongbox.utils :refer [join]]
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
    (let [expected (utils/join (addon-path) nfo/nfo-filename)]
      (is (= expected (nfo/nfo-path (install-dir) addon-dir))))))

(deftest rm-nfo
  (testing "a nfo file is deleted"
    (let [path (utils/join (addon-path) nfo/nfo-filename)]
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

  (testing "an addon with v1 nfo data is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123

                    ;; may not have actually been installed from the retail track, we had to guess
                    ;; this will be updated as the addon is updated
                    :installed-game-track "retail"}
          expected nfo-data]
      (spit (utils/join (addon-path) nfo/nfo-filename) (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "an addon with v1 nfo data AND an ignorable sub-directory is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123

                    ;; may not have actually been installed from the retail track, we had to guess
                    ;; this will be updated as the addon is updated
                    :installed-game-track "retail"}
          expected (assoc nfo-data :ignore? true)]
      (spit (utils/join (ignorable-addon-path) nfo/nfo-filename) (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir)))))

  (testing "an addon with nfo v2 data, an ignorable sub-directory AND an ignore flag, is parsed correctly"
    (let [nfo-data {:installed-version "1.0"
                    :name "someaddon"
                    :group-id "blah"
                    :primary? true
                    :source "wowinterface"
                    :source-id 123

                    ;; user has manually marked this development addon for updates.
                    ;; this is only set by the user, not by the app (for now)
                    :ignore? false

                    ;; may not have actually been installed from the retail track, we had to guess
                    ;; this will be updated as the addon is updated
                    :installed-game-track "retail"}
          expected nfo-data]
      (spit (utils/join (ignorable-addon-path) nfo/nfo-filename) (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir))))))

(deftest upgrade-nfo-data
  (testing "a nfo file can be 'upgraded' (vs updated)"
    (let [given {:version "1.2.3" ;; value is ignored in favour of :installed-version
                 :installed-version "1.2.1"
                 :dirname addon-dir
                 :game-track "classic"
                 :name "EveryAddon"
                 :url "https://foo.bar"
                 :primary? true
                 :source "curseforge"
                 :source-id 321}

          expected {:installed-version "1.2.1"
                    :installed-game-track "classic"
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}
          nfo-file (utils/join (addon-path) nfo/nfo-filename)]
      (nfo/upgrade-nfo (install-dir) given)
      (is (= expected (nfo/read-nfo-file (install-dir) addon-dir)))))

  (testing "a nfo file can be 'upgraded' (vs updated), preserving any ignore flags"
    (let [given {:version "1.2.3" ;; value is ignored in favour of :installed-version
                 :installed-version "1.2.1"
                 :dirname addon-dir
                 :game-track "classic"
                 :name "EveryAddon"
                 :url "https://foo.bar"
                 :primary? true
                 :source "curseforge"
                 :source-id 321
                 :ignore? false}

          expected {:installed-version "1.2.1"
                    :installed-game-track "classic"
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321
                    :ignore? false}
          nfo-file (utils/join (addon-path) nfo/nfo-filename)]
      (nfo/upgrade-nfo (install-dir) given)
      (is (= expected (nfo/read-nfo-file (install-dir) addon-dir))))))

