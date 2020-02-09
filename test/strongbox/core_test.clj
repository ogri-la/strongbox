(ns strongbox.core-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [envvar.core :refer [with-env]]
   [me.raynes.fs :as fs]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [zip :as zip]
    [main :as main]
    [nfo :as nfo]
    [catalogue :as catalogue]
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path slurp-fixture helper-data-dir with-running-app]]
    [core :as core]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest app-must-be-started
  (testing "application must be started before state can be accessed"
    (is (thrown? RuntimeException
                 (core/get-state :installed-addon-list)))))

(deftest addon-dir-handling
  (let [[dir1 dir2 dir3 dir4] (mapv (fn [path]
                                      (let [path (utils/join fs/*cwd* path)]
                                        (fs/mkdir path)
                                        path)) ["foo" "bar" "baz" "_classic_"])]
    (with-running-app

      ;; big long stateful test

      (testing "add-addon-dir! adds an addon dir with a default game track of 'retail'"
        (core/add-addon-dir! dir1 "retail")
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! idempotence"
        (core/add-addon-dir! dir1 "retail")
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! just adds the dir, doesn't set it as selected"
        (is (= nil (core/get-state :selected-addon-dir))))

      (testing "set-addon-dir! sets the addon directory as selected and is also idempotent"
        (core/set-addon-dir! dir1)
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list)))
        (is (= dir1 (core/get-state :selected-addon-dir))))

      (testing "remove-addon-dir! without args removes the currently selected addon-dir and ensures it's no longer selected"
        (core/remove-addon-dir!)
        (is (= [] (core/get-state :cfg :addon-dir-list)))
        (is (= nil (core/get-state :selected-addon-dir))))

      (testing "remove-addon-dir! without args won't do anything stupid when there is nothing to remove"
        (core/remove-addon-dir!))

      (testing "remove-addon-dir! with args is idempotent"
        (core/set-addon-dir! dir1)
        (core/set-addon-dir! dir2)
        (core/remove-addon-dir! dir2)
        (core/remove-addon-dir! dir2) ;; repeat
        (is (= dir1 (core/get-state :selected-addon-dir)))
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "addon-dir-map, without args, returns the currently selected addon-dir"
        (is (= {:addon-dir dir1 :game-track "retail"} (core/addon-dir-map)))
        (core/set-addon-dir! dir2)
        (is (= {:addon-dir dir2 :game-track "retail"} (core/addon-dir-map)))
        (is (= {:addon-dir dir1 :game-track "retail"} (core/addon-dir-map dir1))))

      (testing "addon-dir-map returns nil if map cannot be found"
        (is (= nil (core/addon-dir-map dir3))))

      (testing "set-game-track! changes the game track of the given addon dir"
        (core/set-game-track! "classic" dir1)
        (is (= {:addon-dir dir1 :game-track "classic"} (core/addon-dir-map dir1))))

      (testing "set-game-track! without addon-dir, changes the game track of the currently selected addon dir"
        (core/set-game-track! "classic")
        (is (= {:addon-dir dir2 :game-track "classic"} (core/addon-dir-map dir2))))

      ;;

      (testing "set-game-track! changes default path to 'classic' if detected in addon-dir"
        (core/set-addon-dir! dir4)
        (is (= {:addon-dir dir4 :game-track "classic"} (core/addon-dir-map dir4)))))))

(deftest catalogue
  (let [[short-catalogue full-catalogue] (->> core/-state-template :catalogue-source-list (take 2))]
    (with-running-app
      (testing "by default we have at least 2 (short and full composite) + N (source) catalogs available to us"
        (is (> (count (core/get-state :catalogue-source-list)) 2)))

      (testing "core/get-catalogue-source returns the requested catalogue if found"
        (is (= short-catalogue (core/get-catalogue-source :short))))

      (testing "core/get-catalogue-source, without args, returns the currently selected catalogue"
        (is (= short-catalogue (core/get-catalogue-source))))

      (testing "core/get-catalogue-source returns nil if it can't find the requested catalogue"
        (is (= nil (core/get-catalogue-source :foo))))

      (testing "core/set-catalogue-source! always returns nil, even when it successfully completes"
        (is (= nil (core/set-catalogue-source! :foo)))
        (is (= short-catalogue (core/get-catalogue-source)))

        (is (= nil (core/set-catalogue-source! :full)))
        (is (= full-catalogue (core/get-catalogue-source))))

      (testing "core/catalogue-local-path returns the expected path to the catalogue file on the filesystem"
        (is (= (utils/join fs/*cwd* helper-data-dir "short-catalogue.json") (core/catalogue-local-path short-catalogue)))
        (is (= (utils/join fs/*cwd* helper-data-dir "full-catalogue.json") (core/catalogue-local-path full-catalogue))))

      (testing "core/find-catalogue-local-path just needs a catalogue :name"
        (is (= (utils/join fs/*cwd* helper-data-dir "short-catalogue.json") (core/find-catalogue-local-path :short))))

      (testing "core/find-catalogue-local-path returns nil if the given catalogue can't be found"
        (is (= nil (core/find-catalogue-local-path :foo)))))))

(deftest paths
  (with-running-app
    (testing "all path keys are using a known suffix"
      (doseq [key (keys (core/paths))]
        (is (some #{"dir" "file" "url"} (clojure.string/split (name key) #"\-")))))

    (testing "all paths to files and directories are absolute"
      (let [files+dirs (filter (fn [[k v]] (or (ends-with? k "-dir")
                                               (ends-with? k "-file")))
                               (core/paths))]
        (doseq [[key path] files+dirs]
          (is (-> path (starts-with? "/")) (format "path %s is not absolute: %s" key path)))))

    (testing "all remote paths are using https"
      (let [remote-paths (filter (fn [[k v]] (ends-with? k "-url")) (core/paths))]
        (doseq [[key path] remote-paths]
          (is (-> path (starts-with? "https://")) (format "remote path %s is not using HTTPS: %s" key path))))))

  (testing "paths that cannot be written to raise a runtime exception"
    (let [app-state (core/start {})]
      (with-env [:xdg-data-home "/foo", :xdg-config-home "/bar"]
        (core/stop app-state)
        (is (thrown? RuntimeException (core/start {})))))))

(deftest generate-path-map
  (testing "XDG data paths can be overridden with environment variables"
    (with-env [:xdg-data-home "/foo", :xdg-config-home "/home/layday/.config"]
      (let [paths (core/generate-path-map)]
        (is (= "/foo/strongbox" (:data-dir paths)))
        (is (= "/home/layday/.config/strongbox" (:config-dir paths))))))

  (testing "XDG data paths are ignored if present, but empty"
    (with-env [:xdg-data-home "", :xdg-config-home ""]
      (let [paths (core/generate-path-map)
            expected-config-dir (-> core/default-config-dir utils/expand-path)
            expected-data-dir (-> core/default-data-dir utils/expand-path)]
        (is (= expected-data-dir (:data-dir paths)))
        (is (= expected-config-dir (:config-dir paths)))))))

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

(deftest export-installed-addon-list
  (testing "exported addon data is correct"
    (let [addon-list (slurp-fixture "import-export--installed-addons-list.edn")
          expected [{:name "adibags" :source "curseforge" :game-track "retail"}
                    {:name "noname"} ;; an addon whose name is not present in the catalogue (umatched)
                    {:name "carbonite" :source "curseforge" :game-track "retail"}]]
      (is (= expected (core/export-installed-addon-list addon-list)))))

  (testing "export ignores ignored addons"
    (let [addon-list (vec (slurp-fixture "import-export--installed-addons-list.edn"))
          addon-list (assoc-in addon-list [0 :ignore?] true)
          expected [{:name "noname"} ;; an addon whose name is not present in the catalogue (umatched)
                    {:name "carbonite" :source "curseforge" :game-track "retail"}]]
      (is (= expected (core/export-installed-addon-list addon-list))))))

(deftest export-catalogue-addon-list
  (testing "exported addon list data is correct"
    (let [catalogue (slurp-fixture "import-export--user-catalogue.json")
          expected (slurp-fixture "import-export--user-catalogue-export.json")]
      (is (= expected (core/export-catalogue-addon-list catalogue))))))

(deftest import-exported-addon-list-file-v1
  (testing "an export can be imported"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon.json"))

          addon-summary-list (utils/load-json-file (fixture-path "import-export--dummy-catalogue.json"))

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json (catalogue/new-catalogue addon-summary-list))})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body every-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}

                       ;; every-other-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/2"
                       {:get (fn [req] {:status 200 :body every-other-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-other-addon-zip-file)})}}]
      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; our list of addons to import
                output-path (fixture-path "import-export--export-v1.json")

                expected [{:description "Does what no other addon does, slightly differently",
                           :category-list ["Bags & Inventory"],
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track "retail"
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 80000,
                           :download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :url "https://www.curseforge.com/wow/addons/everyaddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryAddon",
                           :primary? true,
                           :matched? true}

                          {:description "Does what every addon does, just better",
                           :category-list ["Professions" "Map & Minimap"],
                           :update? false,
                           :updated-date "2019-07-03T07:11:47Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track "retail"
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 80200,
                           :download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :url "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true}]]

            (core/import-exported-file output-path)
            (core/refresh) ;; re-read the installation directory
            (is (= expected (core/get-state :installed-addon-list)))))))))

(deftest import-exported-addon-list-file-v2
  (testing "an export can be imported AND per-addon game track preferences are preserved"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon-classic.json"))

          addon-summary-list (utils/load-json-file (fixture-path "import-export--dummy-catalogue.json"))

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json (catalogue/new-catalogue addon-summary-list))})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body every-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}

                       ;; every-other-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/2"
                       {:get (fn [req] {:status 200 :body every-other-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-other-addon-zip-file)})}}]
      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; our list of addons to import
                output-path (fixture-path "import-export--export-v2.json")

                expected [{:description "Does what no other addon does, slightly differently",
                           :category-list ["Bags & Inventory"],
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track "retail"
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 80000,
                           :download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :url "https://www.curseforge.com/wow/addons/everyaddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryAddon",
                           :primary? true,
                           :matched? true}

                          {:description "Does what every addon does, just better",
                           :category-list ["Professions" "Map & Minimap"],
                           :update? false,
                           :updated-date "2019-07-03T07:11:47Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track "classic"
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 11300, ;; changed
                           :download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :url "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true}]]

            (core/import-exported-file output-path)
            (core/set-game-track! "retail") ;; unnecessary, 'retail' is default, explicitness
            (core/refresh) ;; re-read the installation directory
            (is (= (first expected) (first (core/get-state :installed-addon-list))))

            ;; bit of a hack. the second expected addon won't be expanded properly after the refresh
            ;; because it's a classic addon and the addon dir is 'retail'. so we change the addon dir
            ;; and then test the second one matches.
            (core/set-game-track! "classic")
            (core/refresh)
            (is (= (second expected) (second (core/get-state :installed-addon-list))))))))))

(deftest check-for-addon-update
  (testing "the key :update? is set on an addon when there is a difference between the installed version of an addon and it's matching catalogue version"

    (let [;; we start off with a list of these called a catalogue. it's downloaded from github
          catalogue {:category-list ["Auction House & Vendors"],
                     :download-count 1
                     :label "Every Addon"
                     :name "every-addon",
                     :source "curseforge",
                     :source-id 0
                     :updated-date "2012-09-20T05:32:00Z",
                     :url "https://www.curseforge.com/wow/addons/every-addon"}

          ;; this is subset of the data the remote addon host (like curseforge) serves us
          api-result {:latestFiles [{:downloadUrl "https://example.org/foo"
                                     :displayName "v8.10.00"
                                     :gameVersionFlavor "retail",
                                     :fileDate "2001-01-03T00:00:00.000Z",
                                     :releaseType 1,
                                     :exposeAsAlternative nil}]}
          alt-api-result (assoc-in api-result [:latestFiles 0 :displayName] "v8.20.00")

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json (catalogue/new-catalogue [catalogue]))})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/0"
                       {:get (fn [req] {:status 200 :body (utils/to-json api-result)})}

                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body (utils/to-json alt-api-result)})}}]

      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; a collection of these are scraped from the installed addons
                toc {:name "every-addon"
                     :label "Every Addon"
                     :description "foo"
                     :dirname "EveryAddon"
                     :interface-version 70000
                     :installed-version "v8.10.00"}

                ;; and optionally these from .strongbox.json if we installed the addon
                nfo {:installed-version "v8.10.00",
                     :installed-game-track "retail"
                     :name "every-addon",
                     :group-id "doesntmatter"
                     :primary? true,
                     :source "curseforge"
                     :source-id 0}

                ;; the nfo data is simply merged over the top of the scraped toc data
                toc (merge toc nfo)

                ;; we then attempt to match this 'toc+nfo' to an addon in the catalogue
                catalogue-match (core/-db-match-installed-addons-with-catalogue [toc])

                ;; this is a m:n match and we typically get back heaps of results
                ;; in this case we have a catalogue of 1 and are not interested in how the addon was matched (:final)
                toc-addon (-> catalogue-match first :final) ;; :update? will be false
                alt-toc-addon (assoc toc-addon :source-id 1) ;; :update? will be true

                ;; and what we 'expand' that data into
                api-xform {:download-url "https://example.org/foo",
                           :version "v8.10.00"}
                alt-api-xform (assoc api-xform :version "v8.20.00")

                ;; after calling `check-for-update` we expect the result to be the merged sum of the below parts
                expected (merge toc-addon api-xform {:update? false})
                alt-expected (merge alt-toc-addon alt-api-xform {:update? true})]

            (is (= expected (core/check-for-update toc-addon)))
            (is (= alt-expected (core/check-for-update alt-toc-addon)))))))))

;; todo: install classic addon into retail game track

(deftest db-row-wrangling
  (testing "converting a tab separated list back into an actual list works as expected"
    (let [cases [[nil []]
                 ["" []]
                 ["|" []]
                 ["foo" ["foo"]]
                 ["bar|baz" ["bar" "baz"]]]]

      (doseq [[given expected] cases]
        (is (= expected (core/db-split-category-list given))))))

  (testing "game track fields are turned back into a list"
    (let [cases [[{} {}]
                 [{:retail-track true} {:game-track-list ["retail"]}]
                 [{:classic-track true} {:game-track-list ["classic"]}]
                 [{:retail-track true, :classic-track true} {:game-track-list ["retail" "classic"]}]
                 [{:retail-track false, :classic-track true} {:game-track-list ["classic"]}]
                 [{:retail-track false, :classic-track false} {}]

                 ;; order is deterministic
                 [{:classic-track true, :retail-track true} {:game-track-list ["retail" "classic"]}]]]
      (doseq [[given expected] cases]
        (is (= expected (core/db-gen-game-track-list given)))))))


;;


(def toc
  "local addon .toc file"
  {:name "everyaddon",
   :description "Does what no other addon does, slightly differently"
   :dirname "EveryAddon",
   :label "EveryAddon 1.2.3",
   :interface-version 70000,
   :installed-version "1.2.3"})

(def addon-summary
  "catalogue of summaries"
  {:label "EveryAddon",
   :name  "everyaddon",
   :description  "Does what no other addon does, slightly differently"
   :category-list  ["Auction & Economy", "Data Broker"],
   :source "curseforge"
   :source-id 1
   :created-date  "2009-02-08T13:30:30Z",
   :updated-date  "2016-09-08T14:18:33Z",
   :url "https://www.example.org/wow/addons/everyaddon"})

(def addon
  "remote addon detail"
  (merge addon-summary
         {:download-count 1
          :interface-version  70000,
          :download-url  "https://www.example.org/wow/addons/everyaddon/download/123456/file",
          :version  "1.2.3"}))

(deftest install-addon
  (testing "installing an addon"
    (let [install-dir (str fs/*cwd*)
          ;; move dummy addon file into place so there is no cache miss
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (utils/cp (fixture-path fname) install-dir)
          test-only? false
          file-list (core/install-addon addon install-dir test-only?)]

      (testing "addon directory created, single file written (.strongbox.json nfo file)"
        (is (= (count file-list) 1))
        (is (fs/exists? (first file-list))))))

  (testing "trial installation of a good addon"
    (let [install-dir (utils/join (str fs/*cwd*) "addons-dir")
          ;; move dummy addon file into place so there is no cache miss
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (fs/mkdir install-dir)
          _ (utils/cp (fixture-path fname) install-dir)

          test-only? true
          result (core/install-addon addon install-dir test-only?)]
      (is result) ;; success

      ;; ensure nothing was actually unzipped
      (is (not (fs/exists? (utils/join install-dir "EveryAddon"))))))

  (testing "trial installation of a bad addon"
    (let [install-dir (utils/join (str fs/*cwd*) "addons-dir")
          ;; move dummy addon file into place so there is no cache miss
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (fs/mkdir install-dir)
          _ (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname)) ;; hoho, so evil

          test-only? true
          result (core/install-addon addon install-dir test-only?)]
      (is (not result)) ;; failure

      ;; ensure nothing was actually unzipped
      (is (not (fs/exists? (utils/join install-dir "EveryAddon")))))))

(deftest install-bad-addon
  (testing "installing a bad addon"
    (let [install-dir (str fs/*cwd*)
          fname (core/downloaded-addon-fname (:name addon) (:version addon))]
      (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname)) ;; hoho, so evil
      (is (= (core/install-addon addon install-dir) nil))
      (is (= (count (fs/list-dir install-dir)) 0))))) ;; bad zip file deleted

;;

(deftest load-installed-addons
  (testing "regular .toc file can be loaded"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"}]]
      (is (= expected (core/-load-installed-addons addon-dir)))))

  (testing "toc data and nfo data are mooshed together as expected"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          _ (spit some-addon-nfo (utils/to-json {:source "curseforge" :source-id 123}))

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"
                     :source "curseforge" :source-id 123}]]
      (is (= expected (core/-load-installed-addons addon-dir)))))

  (testing "invalid nfo data is not loaded"
    (let [addon-dir (str fs/*cwd*)
          some-addon-path (utils/join addon-dir "SomeAddon")
          _ (fs/mkdirs some-addon-path)

          some-addon-toc (utils/join some-addon-path "SomeAddon.toc")
          _ (spit some-addon-toc "## Title: SomeAddon\n## Description: asdf\n## Interface: 80300\n## Version: 1.2.3")

          some-addon-nfo (utils/join some-addon-path nfo/nfo-filename)
          ;; `source` here is invalid
          _ (spit some-addon-nfo (utils/to-json {:source "vault-111" :source-id 123 :ignore? false}))

          expected [{:name "someaddon", :dirname "SomeAddon", :label "SomeAddon", :description "asdf", :interface-version 80300, :installed-version "1.2.3"}]]
      (is (= expected (core/-load-installed-addons addon-dir)))))

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
      (is (= expected (core/-load-installed-addons addon-dir))))))

(deftest group-addons
  (testing "addons with nothing to group on are not modified"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"}]
          expected addon-list]
      (is (= expected (core/group-addons addon-list)))))

  (testing "addons with groupable data but no groupings are not modified"
    (let [addon-list [{:name "a1", :dirname "A1", :label "A1", :description "" :interface-version 80300 :installed-version "1.2.3"
                       :group-id "foo" :primary? true}
                      {:name "a2", :dirname "A2", :label "A2", :description "" :interface-version 80300 :installed-version "4.5.6"
                       :group-id "bar" :primary? true}
                      {:name "a3", :dirname "A3", :label "A2", :description "" :interface-version 80300 :installed-version "7.8.9"
                       :group-id "baz" :primary? true}]
          expected addon-list]
      (is (= expected (core/group-addons addon-list)))))

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
      (is (= expected (core/group-addons addon-list)))))

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
      (is (= expected (core/group-addons addon-list))))))


;;


(deftest re-download-catalogue-on-bad-data
  (testing "catalogue data is re-downloaded if it can't be read"
    (let [;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "dummy-catalogue--single-entry.json"))})}}]
      (with-running-app
        (core/refresh)

        ;; this is the guard to the `db-load-catalogue` fn
        ;; catalogue fixture in test-helper is an empty map, this should always return false
        (is (not (core/db-catalogue-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalogue-source) core/catalogue-local-path (spit ""))

        ;; the catalogue will be re-requested, this time we've swapped out the fixture with one with a single entry
        (with-fake-routes-in-isolation fake-routes
          (core/db-load-catalogue))

        (is (core/db-catalogue-loaded?))))))

(deftest re-download-catalogue-on-bad-data-2
  (testing "`db-load-catalogue` doesn't fail catastrophically when re-downloaded json is still bad"
    (let [;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body "borked json"})}}]
      (with-running-app
        (core/refresh)

        ;; this is the guard to the `db-load-catalogue` fn
        ;; catalogue fixture in test-helper is an empty map, this should always return false
        (is (not (core/db-catalogue-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalogue-source) core/catalogue-local-path (spit ""))

        ;; the catalogue will be re-requested, this time the remote file is also corrupt
        (with-fake-routes-in-isolation fake-routes
          (core/db-load-catalogue))

        (is (not (core/db-catalogue-loaded?)))))))

;;

(deftest add-user-addon-to-user-catalogue
  (testing "user addon is successfully added to the user catalogue, creating it if it doesn't exist"
    (let [user-addon {:url "https://github.com/Aviana/HealComm"
                      :updated-date "2019-10-09T17:40:01Z"
                      :source "github"
                      :source-id "Aviana/HealComm"
                      :label "HealComm"
                      :name "healcomm"
                      :download-count 30946
                      :category-list []}

          expected (merge (catalogue/new-catalogue [])
                          {;; hack, catalogue/format-catalogue-data orders the addon summary make them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file)))))))

  (testing "adding addons to the user catalogue is idempotent"
    (let [user-addon {:url "https://github.com/Aviana/HealComm"
                      :updated-date "2019-10-09T17:40:01Z"
                      :source "github"
                      :source-id "Aviana/HealComm"
                      :label "HealComm"
                      :name "healcomm"
                      :download-count 30946
                      :category-list []}

          expected (merge (catalogue/new-catalogue [])
                          {;; hack, catalogue/format-catalogue-data orders the addon summary make them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))

(deftest add+install-user-addon!
  (testing "user addon is successfully addon to the user catalogue from just a github url"
    (let [every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}

                       "https://github.com/Aviana/HealComm/releases/download/2.04/HealComm.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url "https://github.com/Aviana/HealComm"

          install-dir (utils/join fs/*cwd* "addon-dir")
          _ (fs/mkdir install-dir)

          expected-addon-dir (utils/join install-dir "EveryAddon")

          expected-user-catalogue [{:category-list [],
                                    :game-track-list [],
                                    :updated-date "2019-10-09T17:40:04Z",
                                    :name "healcomm",
                                    :source "github",
                                    :label "HealComm",
                                    :download-count 30946,
                                    :source-id "Aviana/HealComm",
                                    :url "https://github.com/Aviana/HealComm"}]]
      (with-running-app
        (core/set-addon-dir! install-dir)
        (with-fake-routes-in-isolation fake-routes
          (core/add+install-user-addon! user-url)

          ;; addon was found and added to user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file)))))

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir)))))))

(deftest moosh-addons
  (testing "addons are mooshed correctly when a match is found in the db"
    (let [toc {:name "everyaddon"
               :label "EveryAddon"
               :description "Toc Description"
               :dirname "EveryAddon"
               :interface-version 70000
               :installed-version "1.2.3"}

          addon-summary {:name "everyaddon"
                         :label "EveryAddon"
                         :category-list []
                         :updated-date "2001-01-01"
                         :download-count 123
                         :source "wowinterface"
                         :source-id 1
                         :url "https://www.wowinterface.com/downloads/info1"

                         ;; wowinterface and tukui don't have descriptions in their api
                         ;; the database will return a field with `nil` if the addon-summary
                         ;; was inserted without one
                         :description nil}

          expected {:name "everyaddon"
                    :label "EveryAddon"
                    :description "Toc Description"
                    :dirname "EveryAddon"
                    :interface-version 70000
                    :installed-version "1.2.3"

                    :category-list []
                    :updated-date "2001-01-01"
                    :download-count 123
                    :source "wowinterface"
                    :source-id 1
                    :url "https://www.wowinterface.com/downloads/info1"

                    ;; optional, lets the GUI know we have a match that can be checked for updates
                    :matched? true}]
      (is (= expected (core/moosh-addons toc addon-summary))))))

(deftest upgrade-nfo-files
  (testing "addons without a .toc file are not upgraded"
    (let [every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          install-dir (utils/join fs/*cwd* "addons")

          _ (fs/mkdir install-dir)
          _ (zip/unzip-file every-addon-zip-file install-dir)

          expected-dirname "EveryAddon"
          addon-path (utils/join install-dir expected-dirname)
          expected-nfo-file (utils/join addon-path nfo/nfo-filename)]

      (with-running-app
        (core/set-addon-dir! install-dir)
        (is (not (fs/exists? expected-nfo-file))) ;; no nfo file to upgrade
        (core/upgrade-nfo-files)
        (is (not (fs/exists? expected-nfo-file)))))) ;; no nfo written

  (testing "addons with malformed .toc files even after upgrading are deleted"
    (let [every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          install-dir (utils/join fs/*cwd* "addons")

          _ (fs/mkdir install-dir)
          _ (zip/unzip-file every-addon-zip-file install-dir)

          expected-dirname "EveryAddon"
          addon-path (utils/join install-dir expected-dirname)
          expected-nfo-file (utils/join addon-path nfo/nfo-filename)]
      (with-running-app
        (core/set-addon-dir! install-dir)
        ;; create a nfo file whose contents ensure that even after an upgrade attempt
        ;; it is still invalid
        (spit expected-nfo-file (utils/to-json {}))
        (is (fs/exists? expected-nfo-file)) ;; bad nfo exists
        (core/upgrade-nfo-files)
        (is (not (fs/exists? expected-nfo-file))))))) ;; bad nfo removed

