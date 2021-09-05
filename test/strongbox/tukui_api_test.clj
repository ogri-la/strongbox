(ns strongbox.tukui-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [tukui-api :as tukui-api]
    [test-helper :as helper :refer [fixture-path]]]))

(deftest parse-addons
  (testing "parsing retail/'live' addons"
    (let [fixture (slurp (fixture-path "tukui--addon-details.json"))

          fake-routes {tukui-api/summary-list-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected [{:description "Add roleplaying fields to ElvUI to create RP UIs.",
                     :tag-list [:roleplay]
                     :game-track-list [:retail],
                     :updated-date "2019-07-29T20:48:25Z",
                     :name "-rp-tags",
                     :source "tukui",
                     :label "[rp:tags]",
                     :download-count 2838,
                     :source-id 98,
                     :url "https://www.tukui.org/addons.php?id=98"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-retail-summaries)))))))

(deftest parse-addons--classic
  (testing "parsing classic addons"
    (let [fixture (slurp (fixture-path "tukui--classic-addon-details.json"))
          fake-routes {tukui-api/classic-summary-list-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected [{:description "BenikUI is an external ElvUI Classic mod, adding different frame style and new features like detatched portraits and dashboards.",
                     :tag-list [:elvui :plugins]
                     :game-track-list [:classic],
                     :updated-date "2019-10-27T23:32:28Z",
                     :name "benikui-classic",
                     :source "tukui-classic",
                     :label "BenikUI Classic",
                     :download-count 24490,
                     :source-id 13,
                     :url "https://www.tukui.org/classic-addons.php?id=13"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-classic-summaries)))))))

(deftest parse-addons--classic-tbc
  (testing "parsing classic (the burning crusade) addons"
    (let [fixture (slurp (fixture-path "tukui--classic-tbc-addon-details.json"))
          fake-routes {tukui-api/classic-tbc-summary-list-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected [{:description "A visual interface replacement. It restyles the default interface, while adding many useful features.",
                     :download-count 15,
                     :game-track-list [:classic-tbc],
                     :label "vUI",
                     :name "vui",
                     :source "tukui-classic-tbc",
                     :source-id 6,
                     :tag-list [:interfaces],
                     :updated-date "2021-04-26T19:18:02Z",
                     :url "https://www.tukui.org/classic-tbc-addons.php?id=6"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-classic-tbc-summaries)))))))

(deftest parse-addons--proper
  (testing "parsing tukui/elvui addons proper"
    (let [fixture (slurp (fixture-path "tukui--elvui-addon-proper.json"))
          fake-routes {tukui-api/elvui-proper-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected {:description "A user interface designed around user-friendliness with extra features that are not included in the standard ui",
                    :tag-list [:ui]
                    :game-track-list [:classic :retail],
                    :updated-date "2019-12-05T00:00:00Z",
                    :name "elvui",
                    :source "tukui",
                    :label "ElvUI",
                    :download-count 2147483000, ;; 2 kajillion
                    :source-id -2,
                    :url "https://www.tukui.org/download.php?ui=elvui"}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-elvui-summary)))))))

(deftest expand-summaries
  (testing "expanding regular addon"
    (let [fixture (slurp (fixture-path "tukui--addon-details.json"))

          source-id 98
          game-track :retail

          fake-routes {(format tukui-api/summary-list-url source-id)
                       {:get (fn [req] {:status 200 :body fixture})}}

          addon-summary {:description "Add roleplaying fields to ElvUI to create RP UIs.",
                         :tag-list [:roleplay],
                         :game-track-list [:retail],
                         :updated-date "2019-07-29T20:48:25Z",
                         :name "-rp-tags",
                         :source "tukui",
                         :label "[rp:tags]",
                         :download-count 2838,
                         :source-id source-id,
                         :url "https://www.tukui.org/addons.php?id=98"}

          expected [{:download-url "https://www.tukui.org/addons.php?download=98"
                     :version "0.960"
                     :interface-version 80200
                     :game-track game-track}]]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/expand-summary addon-summary game-track))))))

  (testing "expanding addon proper"
    (let [fixture (slurp (fixture-path "tukui--elvui-addon-proper.json"))

          fake-routes {tukui-api/elvui-proper-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          game-track :retail

          addon-summary {:description "A user interface designed around user-friendliness with extra features that are not included in the standard ui",
                         :tag-list [:ui]
                         :game-track-list [:classic :retail],
                         :updated-date "2019-12-05T00:00:00Z",
                         :name "elvui",
                         :source "tukui",
                         :label "ElvUI",
                         :download-count 2147483000,
                         :source-id -2,
                         :url "https://www.tukui.org/download.php?ui=elvui"}

          expected [{:download-url "https://www.tukui.org/downloads/elvui-11.26.zip"
                     :version "11.26"
                     :interface-version 80200
                     :game-track game-track}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/expand-summary addon-summary game-track)))))))

(deftest download-addon-404
  (testing "regular addon fetch that yields a 404 returns nil"
    (let [addon-summary {:description "A user interface designed around user-friendliness with extra features that are not included in the standard ui",
                         :tag-list [:ui]
                         :game-track-list [:classic :retail],
                         :updated-date "2019-12-05T00:00:00Z",
                         :name "elvui",
                         :source "tukui",
                         :label "ElvUI",
                         :download-count 2147483000,
                         :source-id -2,
                         :url "https://www.tukui.org/download.php?ui=elvui"}

          game-track :retail

          fake-routes {tukui-api/elvui-proper-url
                       {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (nil? (tukui-api/expand-summary addon-summary game-track)))))))

(deftest expanding-addon--missing-patch
  (testing "2020-11-21 the tukui addon proper was found to be missing it's `:patch` key resulting in a NPE attempting to convert it to game version"
    (let [fixture (slurp (fixture-path "tukui--addon-details-missing-patch.json"))

          source-id 98

          game-track :retail

          fake-routes {(format tukui-api/summary-list-url source-id)
                       {:get (fn [req] {:status 200 :body fixture})}}

          addon-summary {:description "Add roleplaying fields to ElvUI to create RP UIs.",
                         :tag-list [:roleplay],
                         :game-track-list [:retail],
                         :updated-date "2019-07-29T20:48:25Z",
                         :name "-rp-tags",
                         :source "tukui",
                         :label "[rp:tags]",
                         :download-count 2838,
                         :source-id source-id,
                         :url "https://www.tukui.org/addons.php?id=98"}

          expected [{:download-url "https://www.tukui.org/addons.php?download=98"
                     ;; :interface-version ... ;; elided
                     :version "0.960"
                     :game-track game-track}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/expand-summary addon-summary game-track)))))))

(deftest parse-user-string
  (let [cases [;; retail
               ["https://www.tukui.org/download.php?ui=tukui" -1] ;; tukui retail download
               ["https://www.tukui.org/download.php?ui=elvui" -2] ;; elvui retail download
               ["https://www.tukui.org/addons.php?id=38" 38]

               ;; classic
               ["https://www.tukui.org/classic-addons.php?id=1" 1] ;; tukui classic download
               ["https://www.tukui.org/classic-addons.php?id=2" 2] ;; elvui classic download
               ["https://www.tukui.org/classic-addons.php?id=14" 14]

               ;; classic tbc
               ["https://www.tukui.org/classic-tbc-addons.php?id=1" 1] ;; tukui tbc download
               ["https://www.tukui.org/classic-tbc-addons.php?id=2" 2] ;; elvui tbc download
               ["https://www.tukui.org/classic-tbc-addons.php?id=21" 21]

               ;; contrived cases
               ["https://www.tukui.org/download.php?ui=TUKUI" -1] ;; case insensitive
               ["https://www.tukui.org/classic-tbc-addons.php?id=1&id=2&id=3" 1] ;; multiple identical params (use first)
               ["https://www.tukui.org/classic-tbc-addons.php?foo=bar&id=21&baz=bup" 21] ;; multiple params
               ["https://tukui.org/classic-tbc-addons.php?id=21" 21] ;; no 'www'

               ;; invalid cases
               ["https://www.tukui.org/addons.php?id=foo" nil]
               ["https://www.tukui.org/classic-addons.php?id=foo" nil]
               ["https://www.tukui.org/classic-tbc-addons.php?id=foo" nil]]]

    (doseq [[given expected] cases]
      (is (= expected (tukui-api/parse-user-string given))))))

(deftest make-url
  (let [cases [;; retail tukui addon
               [{:name "foo" :source-id 123 :interface-version 90000} "https://www.tukui.org/addons.php?id=123"]
               ;; classic tukui addon
               [{:name "foo" :source-id 123 :interface-version 10000} "https://www.tukui.org/classic-addons.php?id=123"]
               ;; classic-tbc tukui addon
               [{:name "foo" :source-id 123 :interface-version 20000} "https://www.tukui.org/classic-tbc-addons.php?id=123"]

               ;; tukui retail proper url
               [{:name "tukui" :source-id -1 :interface-version 90000} "https://www.tukui.org/download.php?ui=tukui"]
               ;; elvui retail proper url
               [{:name "elvui" :source-id -2 :interface-version 90000} "https://www.tukui.org/download.php?ui=elvui"]

               ;; tukui classic url
               [{:name "tukui" :source-id 1 :interface-version 10000} "https://www.tukui.org/classic-addons.php?id=1"]
               ;; elvui classic url
               [{:name "tukui" :source-id 2 :interface-version 10000} "https://www.tukui.org/classic-addons.php?id=2"]

               ;; ..etc

               ;; dodgy url
               [{:name "foo" :source-id -99 :interface-version 90000} "https://www.tukui.org/download.php?ui=foo"]
               [{:source-id 1 :interface-version 10000} "https://www.tukui.org/classic-addons.php?id=1"]

               ;; bad urls
               [{} nil]
               [{:source-id 1} nil]
               [{:source-id -1 :interface-version 90000} nil]]]

    (doseq [[given expected] cases]
      (is (= expected (tukui-api/make-url given)), (str "failed on given: " given)))))
