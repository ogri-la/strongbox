(ns strongbox.curseforge-api-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [curseforge-api :as curseforge-api]]))

(deftest parse-user-string
  (let [cases [["https://www.curseforge.com/wow/addons/deadly-boss-mods"
                "https://www.curseforge.com/wow/addons/deadly-boss-mods"]

               ["https://www.curseforge.com/wow/addons/deadly-boss-mods/files"
                "https://www.curseforge.com/wow/addons/deadly-boss-mods"]

               ["https://www.curseforge.com/wow/addons/deadly-boss-mods/relations/dependencies"
                "https://www.curseforge.com/wow/addons/deadly-boss-mods"]

               ["https://www.curseforge.com/wow/addons/deadly-boss-mods/files/3345671"
                "https://www.curseforge.com/wow/addons/deadly-boss-mods"]

               ;; invalid cases
               ["https://www.curseforge.com" nil]
               ["https://www.curseforge.com/wow" nil]
               ["https://www.curseforge.com/wow/addons" nil]
               ["https://www.curseforge.com" nil]
               ["https://www.curseforge.com/wowee/hoo/boy" nil]]]

    (doseq [[given expected] cases]
      (is (= expected (curseforge-api/parse-user-string given))))))
