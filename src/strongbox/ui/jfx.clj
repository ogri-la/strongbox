(ns strongbox.ui.jfx
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [cljfx.api :as fx]
   [strongbox
    [core :as core]
    [utils :as utils :refer [items]]]
   ))


(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn menu-item
  [label handler & [opts]]
  {:fx/type :menu-item
   :text (str label)})


(defn build-catalogue-menu
  []
  [])

(defn menu
  [label items & [opts]]
  {:fx/type :menu
   :text label
   :items items})

(defn async-handler
  [f]
  (fn []
    (f)))

(defn handler
  [f]
  (fn []
    (f)))

(defn donothing
  [[& args]]
  nil)

;;(def separator (javafx.scene.control.SeparatorMenuItem.))
(def separator (menu-item "----" donothing))


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
  []
  (let [exit-handler (fn [ev]
                       nil)
        
        file-menu [(menu-item "New addon directory" (async-handler wow-dir-picker) {:key "menu N" :mnemonic "n"})
                   (menu-item "Remove addon directory" (async-handler core/remove-addon-dir!))
                   separator
                   (menu-item "Exit" exit-handler {:key "menu Q" :mnemonic "x"})
                   ]

        view-menu [(menu-item "Refresh" (async-handler core/refresh) {:key "F5"})
                   separator
                   (menu-item "Installed" (switch-tab-handler INSTALLED-TAB) {:key "menu I" :mnemonic "i"})
                   (menu-item "Search" (switch-tab-handler SEARCH-TAB) {:key "menu H" :mnemonic "h"})
                   separator]

        catalogue-menu (into (build-catalogue-menu)
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler core/refresh-user-catalogue))
                              ])

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

        help-menu [(menu-item "About strongbox" (handler about-strongbox-dialog))]

        ]

    {:fx/type :menu-bar
     :id "main-menu"
     :menus [(menu "File" file-menu {:mnemonic "F"})
             (menu "View" view-menu {:mnemonic "V"})
             (menu "Catalog" catalogue-menu)
             (menu "Addons" addon-menu {:mnemonic "A"})
             (menu "Import/Export" impexp-menu {:mnemonic "i"})
             (menu "Cache" cache-menu)
             (menu "Help" help-menu)
             ]}))

;; tabber

(defn installed-addons-menu-bar
  []
  (let [update-all-button {:fx/type :button
                           :text "Update all"}
        wow-dir-dropdown {:fx/type :combo-box
                          :items ["/home/torkus/path/to/wine/prefix/World of Warcraft/_retail_/Interface/Addons/"]}
        
        game-track-dropdown {:fx/type :combo-box
                             :items ["retail" "classic"]}
        ]
    {:fx/type :h-box
     :padding 10
     :spacing 10
     :children [update-all-button
                wow-dir-dropdown
                game-track-dropdown
                ]}))

(defn mkcol [label]
  {:fx/type :table-column
   :text label})

(defn installed-addons-table
  []
  {:fx/type :v-box
   :children [{:fx/type :table-view
               :columns (mapv mkcol ["source" "name" "description" "installed" "available" "WoW"])
               :items []}]})


(defn app-notice-logger
  []
  {:fx/type :v-box
   :children [{:fx/type :table-view
               :columns (mapv mkcol ["level" "message"])}]})

(defn installed-addons-pane
  []
  {:fx/type :v-box
   :children [(installed-addons-menu-bar)
              {:fx/type :split-pane
               :orientation :vertical
               :divider-positions [0.5]
               :items [(installed-addons-table)
                       (app-notice-logger)
                       ]}
              ]})
(defn tabber
  []
  {:fx/type :tab-pane
   :tabs [{:fx/type :tab
           :text "installed"
           :closable false
           :content (installed-addons-pane)}
          {:fx/type :tab
           :text "search"
           :closable false}]})


;;

(defn root
  []
  {:fx/type :v-box
   :children [(menu-bar)
              (tabber)
              ]})

(defn start
  []
  (info "starting gui")
  (let [ui {:fx/type :stage
            :showing true
            :title "strongbox"
            :width 1024
            :height 768
            :scene {:fx/type :scene
                    :root (root)}}]
    
    (info "ui" ui)
    (fx/on-fx-thread
     (fx/create-component ui))))

(defn stop
  []
  nil)
