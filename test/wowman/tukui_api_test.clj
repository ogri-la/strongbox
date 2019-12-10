(ns wowman.tukui-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [tukui-api :as tukui-api]
    [test-helper :as helper :refer [fixture-path]]]))

(deftest parse-addons
  (testing "parsing retail/'live' addons"
    (let [fixture (format "[%s]" (slurp (fixture-path "tukui--addon-details.json")))

          fake-routes {tukui-api/summary-list-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected [{:description "Add roleplaying fields to ElvUI to create RP UIs.",
                     :category-list ["Roleplay"],
                     :game-track-list ["retail"],
                     :updated-date "2019-07-29T20:48:25Z",
                     :name "-rp-tags",
                     :source "tukui",
                     :alt-name "rptags",
                     :label "[rp:tags]",
                     :download-count 2838,
                     :source-id 98,
                     :uri "https://www.tukui.org/addons.php?id=98"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-retail-summaries))))))

  (testing "parsing classic addons"
    (let [fixture (format "[%s]" (slurp (fixture-path "tukui--classic-addon-details.json")))
          fake-routes {tukui-api/classic-summary-list-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected [{:description "BenikUI is an external ElvUI Classic mod, adding different frame style and new features like detatched portraits and dashboards.",
                     :category-list ["Plugins: ElvUI"],
                     :game-track-list ["classic"],
                     :updated-date "2019-10-27T23:32:28Z",
                     :name "benikui-classic",
                     :source "tukui-classic",
                     :alt-name "benikuiclassic",
                     :label "BenikUI Classic",
                     :download-count 24490,
                     :source-id 13,
                     :uri "https://www.tukui.org/classic-addons.php?id=13"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-classic-summaries))))))

  (testing "parsing tukui/elvui addons proper"
    (let [fixture (slurp (fixture-path "tukui--elvui-addon-proper.json"))
          fake-routes {tukui-api/elvui-proper-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected {:description "A user interface designed around user-friendliness with extra features that are not included in the standard ui",
                    :category-list ["Full UI Replacements"],
                    :game-track-list ["classic" "retail"],
                    :updated-date "2019-12-05T00:00:00Z",
                    :name "elvui",
                    :source "tukui",
                    :alt-name "elvui",
                    :label "ElvUI",
                    :download-count 2147483000, ;; 2 kajillion
                    :source-id -2,
                    :uri "https://www.tukui.org/download.php?ui=elvui"}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-elvui-summary)))))))

(deftest expand-summaries
  (testing "expanding regular addon"
    (let [fixture (slurp (fixture-path "tukui--addon-details.json"))

          source-id 98

          fake-routes {(format tukui-api/summary-url source-id)
                       {:get (fn [req] {:status 200 :body fixture})}}

          addon-summary {:description "Add roleplaying fields to ElvUI to create RP UIs.",
                         :category-list ["Roleplay"],
                         :game-track-list ["retail"],
                         :updated-date "2019-07-29T20:48:25Z",
                         :name "-rp-tags",
                         :source "tukui",
                         :alt-name "rptags",
                         :label "[rp:tags]",
                         :download-count 2838,
                         :source-id 98,
                         :uri "https://www.tukui.org/addons.php?id=98"}

          expected (merge addon-summary
                          {:download-uri "https://www.tukui.org/addons.php?download=98"
                           :version "0.960"
                           :interface-version 80200})

          game-track "retail"]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/expand-summary addon-summary game-track))))))

  (testing "expanding proper addon"
    (let [fixture (slurp (fixture-path "tukui--elvui-addon-proper.json"))

          fake-routes {tukui-api/elvui-proper-url
                       {:get (fn [req] {:status 200 :body fixture})}}

          addon-summary {:description "A user interface designed around user-friendliness with extra features that are not included in the standard ui",
                         :category-list ["Full UI Replacements"],
                         :game-track-list ["classic" "retail"],
                         :updated-date "2019-12-05T00:00:00Z",
                         :name "elvui",
                         :source "tukui",
                         :alt-name "elvui",
                         :label "ElvUI",
                         :download-count 2147483000,
                         :source-id -2,
                         :uri "https://www.tukui.org/download.php?ui=elvui"}

          expected (merge addon-summary
                          {:download-uri "https://www.tukui.org/downloads/elvui-11.26.zip"
                           :version "11.26"
                           :interface-version 80200})

          game-track "retail"]

      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/expand-summary addon-summary game-track)))))))
