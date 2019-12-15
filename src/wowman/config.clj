(ns wowman.config
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [spec-tools.core :as spec-tools]
   [wowman
    [specs :as sp]
    [utils :as utils]]))

(comment "stateless configuration handling. no side effects allowed")

(def default-cfg
  {;; final config, result of merging :file-opts and :cli-opts
   :addon-dir-list []
   :debug? false ;; todo, remove
   :selected-catalog :short})

;; todo: remove 'legacy' here.
;; it's really convenient to pass in an :install-dir from the CLI and have it handled by this


(defn handle-legacy-install-dir
  [cfg]
  (let [install-dir (:install-dir cfg)
        addon-dir-list (->> cfg :addon-dir-list (map :addon-dir) vec)
        stub {:addon-dir install-dir :game-track "retail"}
        ;; add stub to addon-dir-list if install-dir isn't nil and doesn't match anything already present
        cfg (if (and install-dir
                     (not (utils/in? install-dir addon-dir-list)))
              (update-in cfg [:addon-dir-list] conj stub)
              cfg)]
      ;; finally, ensure :install-dir is absent from whatever we return
    (dissoc cfg :install-dir)))

(defn-spec configure ::sp/user-config
  "handles the user configurable bit of the app. command line args override args from from the config file."
  [file-opts map?, cli-opts map?] ;;, addon-dir-list sequential?]
  (debug "loading file config:" file-opts)
  (let [cfg (merge default-cfg file-opts)
        cfg (handle-legacy-install-dir cfg)

        ;; doesn't support optional :opt keysets
        cfg (spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer)
        valid? (s/valid? ::sp/user-config cfg)]

    (when-not valid?
      (warn "configuration from saved settings is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

    (debug "loading runtime config:" cli-opts)
    (let [cfg (if valid? cfg default-cfg)
          final-cfg (merge cfg cli-opts)
          ;; :install-dir may be re-introduced at this point. handle it exactly as we did above
          final-cfg (handle-legacy-install-dir final-cfg)
          final-cfg (spec-tools/coerce ::sp/user-config final-cfg spec-tools/strip-extra-keys-transformer)
          valid? (s/valid? ::sp/user-config cfg)]

      (when-not valid?
        (warn "configuration from command line args is invalid and will be ignored:" (s/explain-str ::sp/user-config cfg)))

      (if valid? final-cfg cfg))))

(defn load-settings
  "returns a map that can be merged over the default state template"
  [cli-opts file-opts etag-db]
  (let [cfg (configure file-opts cli-opts)
        final-state {:cfg cfg,
                     :cli-opts cli-opts,
                     :file-opts file-opts,
                     :etag-db etag-db
                     :selected-addon-dir (->> cfg :addon-dir-list (map :addon-dir) first)}]

    final-state))

;;

(st/instrument)
