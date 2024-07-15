(ns strongbox.jfx-test
  (:require
   ;;[clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.jfx :as jfx]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [main :as main]
    [core :as core]
    [test-helper :as helper :refer [fixture-path with-running-app with-running-app+opts]]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest gui-init
  (testing "the gui can be started and stopped"
    (with-running-app+opts {:ui :gui}
      (is (core/get-state :gui-showing?))
      ;; give time for the init to finish
      (Thread/sleep 1000))))

(deftest href-to-hyperlink
  (testing "urls are converted to component descriptions. bad urls are safely handled"
    (let [bad-text {:fx/type :text :text ""}
          cases [[{} bad-text]
                 ;;[{:url ""} bad-text] ;; caught by spec
                 ;;[{:url "http"} bad-text] ;; caught by spec
                 [{:url "http://"} bad-text]
                 [{:url "http://foo"} bad-text]
                 [{:url "http://foo.bar"} bad-text]

                 ;; needs both a `:url` (destination) and a `:source` (label)
                 [{:url "https://www.curseforge.com/foo/bar"} bad-text]

                 [{:url "https://www.curseforge.com/foo/bar" :source "curseforge"} {:fx/type :hyperlink :text "↪ curseforge"}]
                 [{:url "https://www.wowinterface.com/foo/bar" :source "wowinterface"} {:fx/type :hyperlink :text "↪ wowinterface"}]
                 [{:url "https://github.com/teelolws/Altoholic-Classic" :source "github"} {:fx/type :hyperlink :text "↪ github"}]

                 [{:url "https://www.tukui.org/addons.php" :source "tukui"} {:fx/type :hyperlink :text "↪ tukui"}]
                 [{:url "https://www.tukui.org/classic-addons.php" :source "tukui-classic"} {:fx/type :hyperlink :text "↪ tukui-classic"}]
                 [{:url "https://www.tukui.org/classic-tbc-addons.php" :source "tukui-classic-tbc"} {:fx/type :hyperlink :text "↪ tukui-classic-tbc"}]

                 [{:url "https://www.tukui.org/foo/bar" :source "tukui"} {:fx/type :hyperlink :text "↪ tukui"}]]]

      (doseq [[given expected] cases]
        (is (= expected (dissoc (jfx/href-to-hyperlink given) :on-action)))))))

(deftest table-column
  (testing "table-column data is converted to component descriptions"
    (let [cases [[{} {:fx/type :table-column, :min-width 80, :style-class ["table-cell" "column"]}]
                 [{:text "foo"} {:fx/type :table-column, :text "foo", :min-width 80, :style-class ["table-cell" "foo-column"]}]
                 [{:style-class ["foo"]} {:fx/type :table-column, :min-width 80, :style-class ["table-cell" "column" "foo"]}]]]
      (doseq [[given expected] cases]
        (is (= expected (dissoc (jfx/make-table-column given) :cell-value-factory)))))))

(deftest about-strongbox
  (testing "'about' dialog is correct and new version text is correctly hidden"
    (with-running-app
      (let [expected
            {:id "about-dialog"
             :children [{:text "strongbox", :fx/type :label, :id "about-pane-title"}
                        {:text (str "version " (core/strongbox-version)), :fx/type :text}
                        {:text "version 0.0.0 is now available to download!",
                         :visible false,
                         :managed false,
                         :fx/type :text}
                        {:text "https://github.com/ogri-la/strongbox",
                         :fx/type :hyperlink
                         :id "about-pane-hyperlink"}
                        {:text "AGPL v3", :fx/type :text}],
             :fx/type :v-box}

            actual (jfx/-about-strongbox-dialog)
            actual (update-in actual [:children 3] dissoc :on-action)]
        (is (= expected actual))))))

(deftest build-column-menu
  (let [expected [{:fx/type :menu-item, :text "default"}
                  {:fx/type :menu-item, :text "skinny"}
                  {:fx/type :menu-item, :text "fat"}
                  jfx/separator
                  {:fx/type :check-menu-item, :text "starred", :selected false}
                  {:fx/type :check-menu-item, :text "browse", :selected false}
                  {:fx/type :check-menu-item, :text "source", :selected true}
                  {:fx/type :check-menu-item, :text "ID", :selected true}
                  {:fx/type :check-menu-item, :text "other sources", :selected false}
                  {:fx/type :check-menu-item, :text "name", :selected false}
                  {:fx/type :check-menu-item, :text "description", :selected false}
                  {:fx/type :check-menu-item, :text "tags", :selected false}
                  {:fx/type :check-menu-item, :text "created", :selected false}
                  {:fx/type :check-menu-item, :text "updated", :selected false}
                  {:fx/type :check-menu-item, :text "size", :selected false}
                  {:fx/type :check-menu-item, :text "installed version", :selected false}
                  {:fx/type :check-menu-item, :text "available version", :selected false}
                  {:fx/type :check-menu-item, :text "installed+available version", :selected false}
                  {:fx/type :check-menu-item, :text "game version (WoW)", :selected false}
                  {:fx/type :check-menu-item, :text "über button", :selected false}]

        selected-columns [:foo :bar :baz :source :source-id]

        actual (jfx/build-column-menu selected-columns)
        actual (mapv #(dissoc % :on-action) actual)]
    (is (= expected actual))))

(deftest expand
  (let [given {:a :b
               [:c :d] {:e :f}
               :g {[:h :i] {:j :k}}}

        expected {:a :b
                  :c {:e :f}
                  :d {:e :f}
                  :g {:h {:j :k}
                      :i {:j :k}}}]
    (is (= expected (jfx/expand given)))))

(deftest addon-as-text-for-installed
  (testing "empty"
    (let [given {}
          expected ""]
      (is (= expected (jfx/addon-as-text-for-installed given)))))

  (testing "default"
    (let [given helper/addon
          expected "EveryAddon

Version 1.2.3

\"Does what no other addon does, slightly differently\"

Installed from curseforge

Supports Retail

Last updated 8 years ago (2016-09-08)"]
      (is (= expected (jfx/addon-as-text-for-installed given)))))

  (testing "with overrides"
    (let [overrides {:supported-game-tracks nil
                     :updated-date nil}
          given (merge helper/addon overrides)
          expected "EveryAddon

Version 1.2.3

\"Does what no other addon does, slightly differently\"

Installed from curseforge

Supports 

Last updated  ()"]
      (is (= expected (jfx/addon-as-text-for-installed given))))))

(deftest addon-as-text-for-catalogue
  (testing "empty"
    (let [given {}
          expected ""]
      (is (= expected (jfx/addon-as-text-for-catalogue given)))))

  (testing "default"
    (let [given helper/addon-summary
          expected "EveryAddon

\"Does what no other addon does, slightly differently\"

Available from curseforge

Supports 

Last updated 8 years ago (2016-09-08)"]
      (is (= expected (jfx/addon-as-text-for-catalogue given)))))

  (testing "with overrides"
    (let [overrides {:supported-game-tracks nil
                     :updated-date nil}
          given (merge helper/addon-summary overrides)
          expected "EveryAddon

\"Does what no other addon does, slightly differently\"

Available from curseforge

Supports 

Last updated  ()"]
      (is (= expected (jfx/addon-as-text-for-catalogue given))))))

(deftest addon-game-version-list-string
  (testing ""
    (let [cases [[nil nil]
                 [{} nil]
                 [{:interface-version-list []} ""]
                 [{:interface-version-list [1]} ""]
                 [{:interface-version-list [10000]} "1.0.0"]
                 [{:interface-version-list [10000, 100000]} "1.0.0 | 10.0.0"]
                 [{:interface-version-list [10000, 100000 110000]} "1.0.0 | 10.0.0 | 11.0.0"]
                 ;; duplicates are removed (11000 => 1.0.0)
                 [{:interface-version-list [10000, 11000]} "1.0.0"]]]
      (doseq [[given expected] cases]
        (is (= expected (jfx/addon-game-version-list-string given)))))))
