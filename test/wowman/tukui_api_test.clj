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
                    ;;:interface-version 80200,
                    :alt-name "rptags",
                    :label "[rp:tags]",
                    :download-count 2838,
                    :source-id 98,
                    :uri "https://www.tukui.org/addons.php?id=98"}]
          ]
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
                     ;;:interface-version 11300,
                     :alt-name "benikuiclassic",
                     :label "BenikUI Classic",
                     :download-count 24490,
                     :source-id 13,
                     :uri "https://www.tukui.org/classic-addons.php?id=13"}]
          ]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (tukui-api/download-classic-summaries)))))))
