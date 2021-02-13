(ns strongbox.nfo-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [logging :as logging]
    [utils :as utils :refer [join]]
    [nfo :as nfo]
    [test-helper :as helper]]
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

(deftest nfo-path
  (testing "path to the nfo file is generated correctly"
    (let [expected (utils/join (addon-path) nfo/nfo-filename)]
      (is (= expected (nfo/nfo-path (install-dir) addon-dir))))))

(deftest write-nfo-data
  (testing "valid nfo data is written to a file"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (is (fs/exists? (nfo/nfo-path (install-dir) addon-dir)))))

  (testing "valid nfo data has extraneous keys pruned before being written to a file"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321

                    :foo "bar!"}
          expected (dissoc nfo-data :foo)]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (is (= expected (nfo/read-nfo (install-dir) addon-dir))))))

(deftest read-nfo
  (testing "an addon with no nfo data returns nothing"
    (let [expected nil]
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "invalid nfo data returns `nil` and the nfo file is deleted"
    (let [invalid-nfo-data [{} [] 1 {:foo "bar"} "null"]
          expected nil]
      (doseq [nfo-data invalid-nfo-data]
        (spit (utils/join (addon-path) nfo/nfo-filename) (utils/to-json nfo-data))
        (is (= expected (nfo/read-nfo (install-dir) addon-dir)))
        (is (not (fs/exists? (nfo/read-nfo (install-dir) addon-dir)))))))

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
                    :installed-game-track :retail}
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
                    :installed-game-track :retail}
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
                    :installed-game-track :retail}
          expected nfo-data]
      (spit (utils/join (ignorable-addon-path) nfo/nfo-filename) (utils/to-json nfo-data))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir))))))

(deftest rm-nfo-file
  (testing "a nfo file is deleted"
    (let [path (utils/join (addon-path) nfo/nfo-filename)]
      (fs/touch path)
      (is (fs/exists? path))
      (nfo/rm-nfo-file path)
      (is (not (fs/exists? path)))))

  (testing "a non-nfo file is preserved"
    (let [path (utils/join (addon-path) "SomeAddon.toc")]
      (fs/touch path)
      (is (fs/exists? path))
      (nfo/rm-nfo-file path)
      (is (fs/exists? path)))))

(deftest rm-nfo*
  (let [nfo-data {:installed-version "1.2.1"
                  :installed-game-track :classic
                  :name "EveryAddon"
                  :group-id "https://foo.bar"
                  :primary? true
                  :source "curseforge"
                  :source-id 321}]

    (testing "handles nil nfo data (for when nfo data doesn't exist)"
      (let [expected nil]
        (is (= expected (nfo/rm-nfo* nil "https://foo.bar")))))

    (testing "returning an empty list ain't a prob, bob"
      (let [expected []]
        (is (= expected (nfo/rm-nfo* nfo-data "https://foo.bar")))))

    (testing "removing a nfo entry that isn't present"
      (let [expected [nfo-data]]
        (is (= expected (nfo/rm-nfo* nfo-data "https://bar.baz")))))))

(deftest ignore-dir
  (testing "an addon directory is ignored if it contains an svc-type sub directory"
    (doseq [ignorable-dir nfo/ignorable-dir-set
            :let [path (utils/join (addon-path) ignorable-dir)]]
      (try
        (fs/mkdirs path)
        (is (nfo/version-controlled? (addon-path)))
        (finally
          (fs/delete-dir (addon-path)))))))

(deftest update-nfo-data
  (testing "nfo data can be updated"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}

          updates {:source "wowinterface"}

          expected (merge nfo-data updates)]

      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (nfo/update-nfo (install-dir) addon-dir updates)
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "invalid data is not updated"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}

          updates {:installed-game-track :foo}

          expected nfo-data
          expected-log ["new nfo data is invalid and won't be written to file"]]

      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (is (= expected-log
             (logging/buffered-log :error
                                   (nfo/update-nfo (install-dir) addon-dir updates))))
      (is (= expected (nfo/read-nfo (install-dir) addon-dir))))))

(deftest update-nfo-data-with-ignore-flags
  (testing ""
    (let [expected {:ignore? true}]
      (nfo/ignore (install-dir) addon-dir)
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "nfo data that is *just* an `ignore?` flag is deleted when set to `nil`"
    (let [nfo-data {:ignore? true}]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (nfo/clear-ignore (install-dir) addon-dir)
      (is (not (fs/exists? (nfo/nfo-path (install-dir) addon-dir))))))

  (testing "implicitly ignored addons with no other nfo data have the `ignore?` flag set to `false` rather than cleared."
    (let [expected {:ignore? false}]
      (nfo/clear-ignore (install-dir) ignorable-addon-dir)
      (is (fs/exists? (nfo/nfo-path (install-dir) ignorable-addon-dir)))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir)))))

  (testing "implicitly ignored addons with an explicit `ignore?` flag set to `false` will clear the flag as expected"
    (let [nfo-data {:ignore? false}]
      (nfo/write-nfo (install-dir) ignorable-addon-dir nfo-data)
      (nfo/clear-ignore (install-dir) ignorable-addon-dir)
      (is (not (fs/exists? (nfo/nfo-path (install-dir) ignorable-addon-dir))))))

  ;; note: should this behave like an ignore-flag-only nfo file and have it's `ignore?` flag set to `false` ?
  ;; we have no way of knowing if the addon is being ignored via toc data at this point in the program
  (testing "implicitly ignored addons with regular nfo data without an explicit `ignore?` flag are treated the same"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}
          expected-raw nfo-data ;; no change in file
          expected (assoc nfo-data :ignore? true)] ;; back to being implicitly ignored
      (nfo/write-nfo (install-dir) ignorable-addon-dir nfo-data)
      (nfo/clear-ignore (install-dir) ignorable-addon-dir)
      (is (= expected-raw (nfo/read-nfo-file (install-dir) ignorable-addon-dir)))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir)))))

  (testing "implicitly ignored addons with regular nfo data and an explicit `ignore?` flag are treated the same"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321
                    :ignore? false} ;; explicit ignore flag
          expected-raw (dissoc nfo-data :ignore?)
          expected (assoc nfo-data :ignore? true)] ;; back to being implicitly ignored
      (nfo/write-nfo (install-dir) ignorable-addon-dir nfo-data)
      (nfo/clear-ignore (install-dir) ignorable-addon-dir)
      (is (= expected-raw (nfo/read-nfo-file (install-dir) ignorable-addon-dir)))
      (is (= expected (nfo/read-nfo (install-dir) ignorable-addon-dir))))))

(deftest pin-addon
  (testing "a pinned addon can be pinned to a specific version"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}
          expected (assoc nfo-data :pinned-version "a.b.c")]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (nfo/pin (install-dir) addon-dir "a.b.c")
      (is (= expected (nfo/read-nfo (install-dir) addon-dir))))))

(deftest unpin-addon
  (testing "a pinned addon can be 'unpinned'"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321
                    :pinned-version "1.2.1"}
          expected (dissoc nfo-data :pinned-version)]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (nfo/unpin (install-dir) addon-dir)
      (is (= expected (nfo/read-nfo (install-dir) addon-dir)))))

  (testing "an unpinned addon can be safely 'unpinned' without issue"
    (let [nfo-data {:installed-version "1.2.1"
                    :installed-game-track :classic
                    :name "EveryAddon"
                    :group-id "https://foo.bar"
                    :primary? true
                    :source "curseforge"
                    :source-id 321}
          expected nfo-data]
      (nfo/write-nfo (install-dir) addon-dir nfo-data)
      (nfo/unpin (install-dir) addon-dir)
      (is (= expected (nfo/read-nfo (install-dir) addon-dir))))))

