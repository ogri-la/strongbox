(ns strongbox.ui.jfx
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [cljfx.api :as fx]
   [strongbox
    [core :as core]]))

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn menu-item
  [label handler & [_]]
  {:fx/type :menu-item
   :text (str label)})

(defn build-catalogue-menu
  []
  [])

(defn menu
  [label items & [_]]

  {:fx/type :menu
   :text label
   :items items})

(defn async
  ([f]
   (async f []))
  ([f arg-list]
   (future
     (try
       (apply f arg-list)
       (catch RuntimeException re
         (error re "unhandled exception in thread"))))))

(defn async-handler
  [f]
  (fn [& args]
    (async f args)))

(defn handler
  [f]
  (fn []
    (f)))

(defn donothing
  [[& _]]
  nil)

(def separator {:fx/type fx/ext-instance-factory
                :create #(javafx.scene.control.SeparatorMenuItem.)})

(def wow-dir-picker donothing)
(def import-addon-handler donothing)
(def import-addon-list-handler donothing)
(def export-addon-list-handler donothing)
(def export-user-catalogue-handler donothing)
(def about-strongbox-dialog donothing)

(defn switch-tab-handler
  [tab-idx]
  nil)

(defn menu-bar
  [{:keys [_]}]
  (let [exit-handler (fn [ev]
                       nil)

        file-menu [(menu-item "New addon directory" (async-handler wow-dir-picker) {:key "menu N" :mnemonic "n"})
                   (menu-item "Remove addon directory" (async-handler core/remove-addon-dir!))
                   separator
                   (menu-item "Exit" exit-handler {:key "menu Q" :mnemonic "x"})]

        view-menu [(menu-item "Refresh" (async-handler core/refresh) {:key "F5"})
                   separator
                   (menu-item "Installed" (switch-tab-handler INSTALLED-TAB) {:key "menu I" :mnemonic "i"})
                   (menu-item "Search" (switch-tab-handler SEARCH-TAB) {:key "menu H" :mnemonic "h"})
                   separator]

        catalogue-menu (into (build-catalogue-menu)
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler core/refresh-user-catalogue))])

        addon-menu [(menu-item "Update all" (async-handler core/install-update-all) {:key "menu U" :mnemonic "u"})
                    (menu-item "Re-install all" (async-handler core/re-install-all))]

        impexp-menu [(menu-item "Import addon from Github" (handler import-addon-handler))
                     separator
                     (menu-item "Import addon list" (async-handler import-addon-list-handler))
                     (menu-item "Export addon list" (async-handler export-addon-list-handler))
                     (menu-item "Export Github addon list" (async-handler export-user-catalogue-handler))]

        cache-menu [(menu-item "Clear http cache" (async-handler core/delete-http-cache!))
                    (menu-item "Clear addon zips" (async-handler core/delete-downloaded-addon-zips!))
                    (menu-item "Clear catalogues" (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (menu-item "Clear log files" (async-handler core/delete-log-files!))
                    (menu-item "Clear all" (async-handler core/clear-all-temp-files!))
                    separator
                    (menu-item "Delete WowMatrix.dat files" (async-handler core/delete-wowmatrix-dat-files!))
                    (menu-item "Delete .wowman.json files" (async-handler (comp core/refresh core/delete-wowman-json-files!)))
                    (menu-item "Delete .strongbox.json files" (async-handler (comp core/refresh core/delete-strongbox-json-files!)))]

        help-menu [(menu-item "About strongbox" (handler about-strongbox-dialog))]]

    {:fx/type :menu-bar
     :id "main-menu"
     :menus [(menu "File" file-menu {:mnemonic "F"})
             (menu "View" view-menu {:mnemonic "V"})
             (menu "Catalogue" catalogue-menu)
             (menu "Addons" addon-menu {:mnemonic "A"})
             (menu "Import/Export" impexp-menu {:mnemonic "i"})
             (menu "Cache" cache-menu)
             (menu "Help" help-menu)]}))

;; tabber

(defn installed-addons-menu-bar
  [{:keys [state]}]
  (let [;; temporary
        refresh-button {:fx/type :button
                        :text "Refresh"
                        :on-action (async-handler (fn [_]
                                                    (core/refresh)))}
        update-all-button {:fx/type :button
                           :text "Update all"}

        addon-dir-map-list (-> state :cfg :addon-dir-list (or []))
        selected-addon-dir (-> state :cfg :selected-addon-dir)
        selected-game-track (or (core/get-game-track selected-addon-dir) "")

        wow-dir-dropdown {:fx/type :combo-box
                          :value selected-addon-dir
                          :on-value-changed (async-handler (fn [new-addon-dir]
                                                             (core/set-addon-dir! new-addon-dir)))
                          :items (mapv :addon-dir addon-dir-map-list)}

        game-track-dropdown {:fx/type :combo-box
                             :value (name selected-game-track)
                             :on-value-changed (async-handler (fn [new-game-track]
                                                                (core/set-game-track! (keyword new-game-track))))
                             :items ["retail" "classic"]}]
    {:fx/type :h-box
     :padding 10
     :spacing 10
     :children [refresh-button
                update-all-button
                wow-dir-dropdown
                game-track-dropdown]}))

(defn table-column
  [column-data]
  (let [column-data (if (string? column-data) {:text column-data} column-data)
        column-name (:text column-data)

        default-cvf (fn [row] (get row (keyword column-name)))
        new-cvf (:cell-value-factory column-data)
        final-cvf (if (and (some? new-cvf)
                           (keyword? (:cell-value-factory column-data)))
                    ;; keywords have to be wrapped in a function to be coerced to a javafx callback!
                    #(get % (:cell-value-factory column-data))
                    (or new-cvf default-cvf))
        final-cvf {:cell-value-factory final-cvf}

        default {:fx/type :table-column
                 :visible true
                 :min-width 80}]
    (merge default column-data final-cvf)))

(defn table-cell
  [k v]
  {k v})

(defn source-to-href-fn
  "if a source for the addon can be derived, return a hyperlink"
  [row]
  (when-let [source (:url row)]
    (let [url (java.net.URL. source)]
      (case (.getHost url)
        "www.curseforge.com" "curseforge"
        "www.wowinterface.com" "wowinterface"
        "github.com" "github"
        "www.tukui.org" (if (= (.getPath url) "/classic-addons.php")
                          "tukui-classic"
                          "tukui")
        "???"))))

(defn installed-addons-table
  [{:keys [state]}]
  (let [row-list (or (:installed-addon-list state) [])
        column-list [{:text "source" :min-width 100 :max-width 110 :cell-value-factory source-to-href-fn}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 500 :cell-value-factory (fn [row] (:label row))}
                     {:text "description" :pref-width 700 :cell-value-factory :description}
                     {:text "installed" :max-width 150 :cell-value-factory :installed-version}
                     {:text "available" :max-width 150 :cell-value-factory :version}
                     {:text "WoW" :max-width 100 :cell-value-factory :interface-version}]]
    {:fx/type :v-box
     :children [{:fx/type :table-view
                 :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
                 :selection-mode :multiple
                 :pref-height 999.0
                 :columns (mapv table-column column-list)
                 :items row-list}]}))

(defn app-notice-logger
  []
  {:fx/type :v-box
   :children [{:fx/type :table-view
               :columns (mapv table-column ["level" "message"])}]})

(defn installed-addons-pane
  [{:keys [state]}]
  {:fx/type :v-box
   :children [{:fx/type installed-addons-menu-bar :state state}
              {:fx/type :split-pane
               :orientation :vertical
               :divider-positions [0.65]
               :items [{:fx/type installed-addons-table :state state}
                       (app-notice-logger)]}]})

(defn tabber
  [{:keys [state]}]
  {:fx/type :tab-pane
   :tabs [{:fx/type :tab
           :text "installed"
           :closable false
           :content {:fx/type installed-addons-pane :state state}}
          {:fx/type :tab
           :text "search"
           :closable false}]})

;;

(defn root
  [state]
  (info "rendering root")
  {:fx/type :stage
   :showing true
   :title "strongbox"
   :width 1024
   :height 768
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type menu-bar :state state}
                             {:fx/type tabber :state state}]}}})

(defn start
  []
  (info "starting gui")
  (let [middleware (fn [state]
                     (root state))
        renderer (fx/create-renderer
                  :middleware (fx/wrap-map-desc middleware))]

    (fx/mount-renderer core/state renderer)

    (future
      (core/refresh))

    renderer))

(defn stop
  []
  nil)
