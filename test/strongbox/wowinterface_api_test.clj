(ns strongbox.wowinterface-api-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [test-helper :refer [fixture-path]]
    [wowinterface-api :as wowinterface-api]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(deftest expand-summary
  (testing "expand-summary correctly extracts and adds additional properties"
    (let [given {:url "https://www.wowinterface.com/downloads/info25079",
                 :name "rotation-master",
                 :label "Rotation Master",
                 :updated-date "2019-07-29T21:37:00Z",
                 :download-count 80,
                 :category-list #{"dummy"}
                 :source "wowinterface"
                 :source-id 25079
                 :game-track-list ["retail"]}

          game-track "retail"

          expected {:url "https://www.wowinterface.com/downloads/info25079"
                    :name "rotation-master"
                    :label "Rotation Master"
                    :updated-date "2019-07-29T21:37:00Z"
                    :download-count 80
                    :category-list #{"dummy"}
                    :source "wowinterface"
                    :source-id 25079
                    :game-track-list [game-track]

                    :download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=25079"
                    :version "1.2.3"}

          fixture (slurp (fixture-path "wowinterface-api--addon-details.json"))
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/25079.json"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (wowinterface-api/expand-summary given game-track)))))))
