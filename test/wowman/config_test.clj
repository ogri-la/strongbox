(ns wowman.config-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [me.raynes.fs :as fs]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [config :as config]]))

(deftest handle-install-dir
  (testing ":install-dir in user config is converted correctly"
    (let [install-dir (str fs/*cwd*)
          cfg {:install-dir install-dir :addon-dir-list []}
          expected {:addon-dir-list [{:addon-dir install-dir :game-track "retail"}]}]
      (is (= expected (config/handle-install-dir cfg)))))

  (testing ":install-dir in user config is appended to existing list correctly"
    (let [install-dir (str fs/*cwd*)
          install-dir2 "/tmp"

          addon-dir1 {:addon-dir install-dir :game-track "retail"}
          addon-dir2 {:addon-dir install-dir2 :game-track "retail"}

          cfg {:install-dir install-dir2
               :addon-dir-list [addon-dir1]}

          expected {:addon-dir-list [addon-dir1 addon-dir2]}]
      (is (= expected (config/handle-install-dir cfg))))))

(deftest configure
  (testing "called with no overrides gives us whatever is in the state template"
    (let [;;expected (:cfg core/-state-template)
          expected config/default-cfg
          file-opts {}
          cli-opts {}]
      (is (= expected (config/configure file-opts cli-opts)))))

  (testing "file overrides are preserved and foreign keys are removed"
    (let [cli-opts {}
          file-opts {:foo "bar" ;; unknown
                     :debug? true}
          expected (assoc config/default-cfg :debug? true)]
      (is (= expected (config/configure file-opts cli-opts)))))

  (testing "cli overrides are preserved and foreign keys are removed"
    (let [cli-opts {:foo "bar"
                    :debug? true}
          file-opts {}
          expected (assoc config/default-cfg :debug? true)]
      (is (= expected (config/configure file-opts cli-opts)))))

  (testing "cli overrides file overrides"
    (let [cli-opts {:debug? true}
          file-opts {:debug? false}
          expected (assoc config/default-cfg :debug? true)]
      (is (= expected (config/configure file-opts cli-opts))))))

(deftest invalid-config
  (testing "missing directories don't nuke entire config"
    (let [user-file-config {:addon-dir-list [{:addon-dir (str fs/*cwd*) ;; exists
                                              :game-track "classic"}
                                             {:addon-dir "/does/not/exist"
                                              :game-track "retail"}]
                            :debug? false
                            :selected-catalog :short}

          expected-config {:addon-dir-list [{:addon-dir (str fs/*cwd*) ;; exists
                                             :game-track "classic"}]
                           :debug? false
                           :selected-catalog :short}

          cli-opts {}]
      (is (= expected-config (config/configure user-file-config cli-opts))))))
