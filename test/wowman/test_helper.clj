(ns wowman.test-helper
  (:require
   [envvar.core :refer [env with-env]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [wowman
    [core :as core]
    [utils :refer [join]]]))

(def fixture-dir (-> "test/fixtures" fs/absolute fs/normalized str))

(defn fixture-path
  [filename]
  (join fixture-dir filename))

(defn temp-path
  [filename]
  (-> filename fs/absolute fs/normalized str))

(defn fixture-tempcwd
  "each test is executed in a new and self-contained location, accessible as fs/*cwd*
  if the app is started:
  * an empty catalog is downloaded
  * fake wowman version data is downloaded"
  [f]
  (let [temp-dir-path (fs/temp-dir "wowman.main-test.")
        fake-routes {;; catalog
                     core/remote-catalog
                     ;; return dummy data. we can do this because the catalog isn't loaded/parsed/validated
                     ;; until the UI (gui or cli) tells it to via a later call to `refresh`
                     {:get (fn [req] {:status 200 :body "{}"})}

                     ;; latest wowman version
                     "https://api.github.com/repos/ogri-la/wowman/releases/latest"
                     {:get (fn [req] {:status 200 :body "{\"tag_name\": \"0.0.0\"}"})}}]
    (try
      (with-fake-routes-in-isolation fake-routes
        (with-env [:xdg-data-home (join temp-dir-path "data")
                   :xdg-config-home (join temp-dir-path "config")]
          (with-cwd temp-dir-path
            (debug "created temp working directory" fs/*cwd*)
            (f))))
      (finally
        (debug "destroying temp working directory" temp-dir-path) ;; "with contents" (vec (file-seq fs/*cwd*)))
        (fs/delete-dir temp-dir-path)))))

;; usage:
;; (:require [wowman.helper :as helper])
;; (use-fixtures :each helper/fixture-tempcwd)
