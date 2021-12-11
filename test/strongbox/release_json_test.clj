(ns strongbox.release-json-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [release-json :as release-json]
    ;;[test-helper :refer [fixture-path slurp-fixture]]
    ]
   ;;[clj-http.fake :refer [with-fake-routes-in-isolation]]
   ))

(deftest release-json-game-tracks
  (let [cases [[[{:filename "Foo.zip" :metadata [{:flavor "bcc"}]}]
                {"Foo.zip" [:classic-tbc]}]

               [[{:filename "Foo.zip" :metadata [{:flavor "bcc"}
                                                 {:flavor "mainline"}
                                                 {:flavor "vanilla"}]}]
                {"Foo.zip" [:classic :classic-tbc :retail]}]]]

    (doseq [[given expected] cases]
      (is (= expected (release-json/release-json-game-tracks given))))))

