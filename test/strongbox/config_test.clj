(ns strongbox.config-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [me.raynes.fs :as fs]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path]]
    [config :as config]]))

(use-fixtures :each helper/fixture-tempcwd)

(defn temp-addon-dirs
  "creates a set of addon directories that match those in the `user-config-x.x.json` fixtures"
  [f]
  (let [abs-path-list (mapv #(utils/join (fs/tmpdir) (str ".strongbox-" %)) ["foo" "bar"])
        _ (mapv fs/mkdir abs-path-list)]
    (try
      (f)
      (finally
        (mapv fs/delete-dir abs-path-list)))))

(use-fixtures :once temp-addon-dirs)

(deftest handle-install-dir
  (testing ":install-dir in user config is converted to an :addon-dir"
    (let [install-dir (str fs/*cwd*)
          cfg {:install-dir install-dir :addon-dir-list []}
          expected {:addon-dir-list [{:addon-dir install-dir :game-track "retail"}]}]
      (is (= expected (config/handle-install-dir cfg)))))

  (testing "if both `:install-dir` and `:addon-dir-list` exist, `:install-dir` is appended 
           to `:addon-dir-list` and then dropped"
    (let [install-dir1 "/foo"
          install-dir2 "/bar"

          addon-dir1 {:addon-dir install-dir1 :game-track "retail"}
          addon-dir2 {:addon-dir install-dir2 :game-track "retail"}

          cfg {:install-dir install-dir2
               :addon-dir-list [addon-dir1]}

          expected {:addon-dir-list [addon-dir1 addon-dir2]}]
      (is (= expected (config/handle-install-dir cfg))))))

(deftest merge-config
  (testing "called with no overrides gives us whatever is in the state template"
    (let [expected config/default-cfg
          file-opts {}
          cli-opts {}]
      (is (= expected (config/merge-config file-opts cli-opts)))))

  (testing "file overrides are preserved and unknown keys are removed"
    (let [cli-opts {}
          file-opts {:foo "bar" ;; unknown
                     :selected-catalogue :full} ;; known
          expected (assoc config/default-cfg :selected-catalogue :full)]
      (is (= expected (config/merge-config file-opts cli-opts)))))

  (testing "cli overrides are preserved and unknown keys are removed"
    (let [cli-opts {:foo "bar"
                    :selected-catalogue :full}
          file-opts {}
          expected (assoc config/default-cfg :selected-catalogue :full)]
      (is (= expected (config/merge-config file-opts cli-opts)))))

  (testing "cli options override file options"
    (let [cli-opts {:selected-catalogue :short}
          file-opts {:selected-catalogue :full}
          expected (assoc config/default-cfg :selected-catalogue :short)]
      (is (= expected (config/merge-config file-opts cli-opts))))))

(deftest invalid-addon-dirs-in-cfg
  (testing "missing directories don't nuke entire config"
    (let [cli-opts {}
          file-opts {:addon-dir-list [{:addon-dir (str fs/*cwd*) ;; exists
                                       :game-track "classic"}
                                      {:addon-dir "/does/not/exist"
                                       :game-track "retail"}]}
          expected (assoc config/default-cfg
                          :addon-dir-list [{:addon-dir (str fs/*cwd*)
                                            :game-track "classic"}])]
      (is (= expected (config/merge-config file-opts cli-opts))))))

(deftest load-settings-from-file
  (testing "a standard config file circa 0.9 is loaded and parsed as expected"
    (let [cli-opts {}
          cfg-file (fixture-path "user-config-0.9.json")
          etag-db-file (fixture-path "empty-map.json")

          expected {:cfg {:gui-theme :light ;; new in 0.11
                          :selected-catalogue :short ;; new in 0.10
                          ;; :debug? true ;; removed in 0.12
                          ;; new
                          :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                           {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    ;; new
                    :selected-addon-dir "/tmp/.strongbox-bar" ;; defaults to first entry

                    :cli-opts {}
                    :file-opts {:debug? true
                                :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                                 {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :etag-db {}}]

      (is (= expected (config/load-settings cli-opts cfg-file etag-db-file)))))

  (testing "a standard config file circa 0.10 is loaded and parsed as expected"
    (let [cli-opts {}
          cfg-file (fixture-path "user-config-0.10.json")
          etag-db-file (fixture-path "empty-map.json")

          expected {:cfg {:gui-theme :light ;; new in 0.11
                          :selected-catalogue :full ;; new in 0.10
                          ;; :debug? true ;; removed in 0.12
                          :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                           {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :selected-addon-dir "/tmp/.strongbox-bar"

                    :cli-opts {}
                    :file-opts {:selected-catalogue :full
                                :debug? true
                                :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                                 {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :etag-db {}}]

      (is (= expected (config/load-settings cli-opts cfg-file etag-db-file)))))

  (testing "a standard config file circa 0.11 is loaded and parsed as expected"
    (let [cli-opts {}
          cfg-file (fixture-path "user-config-0.11.json")
          etag-db-file (fixture-path "empty-map.json")

          expected {:cfg {:gui-theme :dark ;; new in 0.11
                          :selected-catalogue :full ;; new in 0.10
                          ;;:debug? true ;; removed in 0.12
                          :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                           {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :selected-addon-dir "/tmp/.strongbox-bar"

                    :cli-opts {}
                    :file-opts {:gui-theme :dark
                                :selected-catalogue :full
                                :debug? true
                                :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                                 {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :etag-db {}}]
      (is (= expected (config/load-settings cli-opts cfg-file etag-db-file)))))

  (testing "a standard config file circa 0.12 is loaded and parsed as expected"
    (let [cli-opts {}
          cfg-file (fixture-path "user-config-0.12.json")
          etag-db-file (fixture-path "empty-map.json")

          expected {:cfg {:gui-theme :dark ;; new in 0.11
                          :selected-catalogue :full ;; new in 0.10
                          ;;:debug? true ;; removed in 0.12
                          :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                           {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :selected-addon-dir "/tmp/.strongbox-bar"

                    :cli-opts {}
                    :file-opts {:gui-theme :dark
                                :selected-catalogue :full
                                :addon-dir-list [{:addon-dir "/tmp/.strongbox-bar", :game-track "retail"}
                                                 {:addon-dir "/tmp/.strongbox-foo", :game-track "classic"}]}
                    :etag-db {}}]
      (is (= expected (config/load-settings cli-opts cfg-file etag-db-file))))))
