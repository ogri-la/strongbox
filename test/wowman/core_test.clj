(ns wowman.core-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [envvar.core :refer [with-env]]
   [me.raynes.fs :as fs]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [main :as main]
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path temp-path]]
    [core :as core]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest handle-legacy-install-dir
  (testing ":install-dir in user config is converted correctly"
    (let [install-dir (str fs/*cwd*)
          cfg {:install-dir install-dir :addon-dir-list []}
          expected {:addon-dir-list [{:addon-dir install-dir :game-track "retail"}]}]
      (is (= expected (core/handle-legacy-install-dir cfg)))))

  (testing ":install-dir in user config is appended to existing list correctly"
    (let [install-dir (str fs/*cwd*)
          install-dir2 "/tmp"

          addon-dir1 {:addon-dir install-dir :game-track "retail"}
          addon-dir2 {:addon-dir install-dir2 :game-track "retail"}

          cfg {:install-dir install-dir2
               :addon-dir-list [addon-dir1]}

          expected {:addon-dir-list [addon-dir1 addon-dir2]}]
      (is (= expected (core/handle-legacy-install-dir cfg))))))

(deftest paths
  (testing "all path keys are using suffix"
    (doseq [key (keys (core/paths))]
      (is (some #{"dir" "file" "uri"} (clojure.string/split (name key) #"\-")))))

  (testing "all paths to files and directories are absolute"
    (let [files+dirs (filter (fn [[k v]] (or (ends-with? k "-dir")
                                             (ends-with? k "-file")))
                             (core/paths))]
      (doseq [[key path] files+dirs]
        (is (-> path (starts-with? "/")) (format "path %s is not absolute: %s" key path)))))

  (testing "all remote paths are using https"
    (let [remote-paths (filter (fn [[k v]] (ends-with? k "-uri")) (core/paths))]
      (doseq [[key path] remote-paths]
        (is (-> path (starts-with? "https://")) (format "remote path %s is not using HTTPS: %s" key path)))))

  (testing "XDG data paths can be overridden with environment variables"
    (with-env [:xdg-data-home "/foo", :xdg-config-home "/bar"]
      (is (= "/foo" (:data-dir (core/paths))))
      (is (= "/bar" (:config-dir (core/paths)))))))

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

(deftest export-installed-addon-list
  (testing "exported data looks as expected"
    (let [addon-list (read-string (slurp "test/fixtures/export--installed-addons-list.edn"))
          output-path (temp-path "exports.json")
          _ (core/export-installed-addon-list output-path addon-list)
          expected [{:name "adibags" :source "curseforge"}
                    {:name "noname"} ;; an addon whose name is not present in the catalog (umatched)
                    {:name "carbonite" :source "curseforge"}]]
      (is (= expected (utils/load-json-file output-path))))))

(deftest import-exported-addon-list-file
  (testing "an export can be imported"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon.json"))

          addon-summary-list (utils/load-json-file (fixture-path "import--dummy-catalog.json"))

          fake-routes {;; catalog
                       "https://github.com/ogri-la/wowman-data/releases/download/daily/catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json {:addon-summary-list addon-summary-list})})}

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
        (try
          (main/start {:ui :cli, :install-dir (str fs/*cwd*)})

          (let [;; our list of addons to import
                output-path (fixture-path "import--exports.json")

                expected [{:description "Does what no other addon does, slightly differently",
                           :category-list ["Bags & Inventory"],
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 11300,
                           :download-uri "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :alt-name "everyaddon",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :uri "https://www.curseforge.com/wow/addons/everyaddon",
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
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 11300,
                           :download-uri "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :alt-name "everyotheraddon",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :uri "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true}]]

            (core/import-exported-file output-path)
            (core/refresh) ;; re-read the installation directory
            (is (= expected (core/get-state :installed-addon-list))))

          (finally
            (main/stop)))))))

(deftest check-for-update
  (testing "the key :update? is set on an addon when there is a difference between the installed version of an addon and it's matching catalog verison"

    (let [;; we start off with a list of these called a catalog. it's downloaded from github
          catalog {:category-list ["Auction House & Vendors"],
                   :download-count 1
                   :label "Every Addon"
                   :name "every-addon",
                   :source "curseforge",
                   :source-id 0
                   :updated-date "2012-09-20T05:32:00Z",
                   :uri "https://www.curseforge.com/wow/addons/every-addon"}

          ;; this is subset of the data the remote addon host (curseforge in this case) serves us
          api-result {:latestFiles [{:downloadUrl "https://example.org/foo"
                                     :displayName "v8.10.00"
                                     :gameVersionFlavor "wow_retail",
                                     :fileDate "2001-01-03T00:00:00.000Z",
                                     :releaseType 1,
                                     :exposeAsAlternative nil}]}
          alt-api-result (assoc-in api-result [:latestFiles 0 :displayName] "v8.20.00")

          fake-routes {;; catalog
                       "https://github.com/ogri-la/wowman-data/releases/download/daily/catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json {:addon-summary-list [catalog]})})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/0"
                       {:get (fn [req] {:status 200 :body (utils/to-json api-result)})}

                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body (utils/to-json alt-api-result)})}}]

      (with-fake-routes-in-isolation fake-routes
        (try
          ;; init the app, which downloads the catalog and loads the db
          (main/start {:ui :cli, :install-dir (str fs/*cwd*)})

          (let [;; a collection of these are scraped from the installed addons
                toc {:name "every-addon"
                     :label "Every Addon"
                     :description "foo"
                     :dirname "EveryAddon"
                     :interface-version 70000
                     :installed-version "v8.10.00"}

                ;; and optionally these from .wowman.json if we installed the addon
                nfo {:installed-version "v8.10.00",
                     :name "every-addon",
                     :group-id "doesntmatter"
                     :primary? true,
                     :source "curseforge"
                     :source-id 0}

                ;; the nfo data is simply merged over the top of the scraped toc data
                toc (merge toc nfo)

                ;; we then attempt to match this 'toc+nfo' to an addon in the catalog
                catalog-match (core/-db-match-installed-addons-with-catalog [toc] [catalog])

                ;; this is a m:n match and we typically get back heaps of results
                ;; in this case we have a catalog of 1 and are not interested in how the addon was matched (:final)
                toc-addon (-> catalog-match first :final) ;; :update? will be false
                alt-toc-addon (assoc toc-addon :source-id 1) ;; :update? will be true

                ;; and what we 'expand' that data into
                api-xform {:download-uri "https://example.org/foo",
                           :version "v8.10.00"}
                alt-api-xform (assoc api-xform :version "v8.20.00")

                ;; after calling `check-for-update` we expect the result to be the merged sum of the below parts
                expected (merge toc-addon api-xform {:update? false})
                alt-expected (merge alt-toc-addon alt-api-xform {:update? true})]

            (is (= expected (core/check-for-update toc-addon)))
            (is (= alt-expected (core/check-for-update alt-toc-addon))))

          (finally
            (main/stop)))))))


;; todo: install classic addon into retail game track
