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
    (try
      (main/start {:ui :cli})
      (let [;; will trigger a refresh. call it here before it affects our crafted state
            _ (core/set-addon-dir! (str fs/*cwd*))

            ;; add catalog to app state
            addon-summary-list (utils/load-json-file (fixture-path "import--dummy-catalog.json"))
            _ (swap! core/state assoc :addon-summary-list addon-summary-list)

            ;; our list of addons to import
            output-path (fixture-path "import--exports.json")

            ;; modified curseforge addon files to generate fake links
            every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
            every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

            every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
            every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon.json"))

            fake-routes {;; every-addon
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
                         {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-other-addon-zip-file)})}}

            expected [{:description "Does what no other addon does, slightly differently",
                       :update? false,
                       :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                       :installed-version "v8.2.0-v1.13.2-7135.139",
                       :name "everyaddon",
                       :source "curseforge",
                       :source-id 1
                       :interface-version 70000,
                       :label "EveryAddon 1.2.3",
                       :dirname "EveryAddon",
                       :primary? true}
                      {:description "Does what every addon does, just better",
                       :update? false,
                       :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                       :installed-version "v8.2.0-v1.13.2-7135.139",
                       :name "everyotheraddon",
                       :source "curseforge",
                       :source-id 2
                       :interface-version 70000,
                       :label "EveryOtherAddon 4.5.6",
                       :dirname "EveryOtherAddon",
                       :primary? true}]]
        (with-fake-routes-in-isolation fake-routes
          (core/import-exported-file output-path)
          ;; TODO: this refresh isn't able to match the installed addons to the dummy catalog!
          (core/refresh) ;; re-read the installation directory
          (is (= expected (core/get-state :installed-addon-list)))))

      (finally
        (main/stop)))))
