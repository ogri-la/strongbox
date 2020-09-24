(ns strongbox.jfx-test
  (:require
   ;;[clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.ui.jfx :as jfx]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [main :as main]
    [core :as core]
    [test-helper :as helper :refer [fixture-path with-running-app with-running-app+opts]]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest gui-init
  (testing "the gui can be started and stopped"
    (with-running-app+opts {:ui :gui2}
      (is (core/get-state :gui-showing?))
      ;; give time for the init to finish
      (Thread/sleep 1000))))

(deftest href-to-hyperlink
  (testing "urls are converted to component descriptions. bad urls are safely handled"
    (let [bad-text {:fx/type :text :text ""}
          cases [[{} bad-text]
                 [{:url ""} bad-text]
                 [{:url "http"} bad-text]
                 [{:url "http://"} bad-text]
                 [{:url "http://foo"} bad-text]
                 [{:url "http://foo.bar"} bad-text]

                 [{:url "https://www.curseforge.com/foo/bar"} {:fx/type :hyperlink :text "↪ curseforge"}]
                 [{:url "https://www.wowinterface.com/foo/bar"} {:fx/type :hyperlink :text "↪ wowinterface"}]
                 [{:url "https://www.github.com/foo/bar"} {:fx/type :hyperlink :text "↪ github"}]
                 [{:url "https://www.tukui.org/foo/bar"} {:fx/type :hyperlink :text "↪ tukui"}]
                 [{:url "https://www.tukui.org/classic-addons.php"} {:fx/type :hyperlink :text "↪ tukui-classic"}]]]
      (doseq [[given expected] cases]
        (is (= expected (dissoc (jfx/-href-to-hyperlink given) :on-action)))))))

(deftest table-column
  (testing "table-column data is converted to component descriptions"
    (let [cases [[{} {:fx/type :table-column, :min-width 80, :style-class ["table-cell" "-column"]}]
                 [{:text "foo"} {:fx/type :table-column, :text "foo", :min-width 80, :style-class ["table-cell" "foo-column"]}]
                 [{:style-class ["foo"]} {:fx/type :table-column, :min-width 80, :style-class ["table-cell" "-column" "foo"]}]]]
      (doseq [[given expected] cases]
        (is (= expected (dissoc (jfx/table-column given) :cell-value-factory)))))))

(deftest about-strongbox
  (testing "'about' dialog is correct and new version text is correctly hidden"
    (with-running-app
      (let [expected 
            {:children [{:text "strongbox", :fx/type :text}
                        {:text "version 3.0.0-unreleased", :fx/type :text}
                        {:text "version 0.0.0 is now available to download!",
                         :visible false,
                         :fx/type :text}
                        {:text "https://github.com/ogri-la/strongbox",
                         :fx/type :hyperlink}
                        {:text "AGPL v3", :fx/type :text}],
             :fx/type :v-box}]
        (is (= expected (jfx/-about-strongbox-dialog)))))))
