(ns strongbox.ui.jfx
  (:require
   [me.raynes.fs :as fs]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [cljfx.ext.table-view :as fx.ext.table-view]
   [cljfx
    [api :as fx]]
   [cljfx.css :as css]
   ;;[clojure.core.cache :as cache]
   [strongbox
    [logging :as logging]
    [utils :as utils]
    [core :as core]])
  (:import
   [javafx.util Callback]
   [javafx.scene.control TableRow]
   [javafx.scene.control TextInputDialog Alert Alert$AlertType ButtonType]
   [javafx.stage FileChooser DirectoryChooser]
   [javafx.application Platform]
   [javafx.event ActionEvent]
   [javafx.scene Node]))

(def style
  (css/register
   ::style
   (let [text-color "black"
         table-border-colour "#bbb"]

     {".root"
      {:-fx-padding 0
       ;;:-fx-base "#fefefe"
       ;;:-fx-base "white"
       ;;:-fx-accent "#0096C9"

       ;; backgrounds
       ;;:-fx-accent "transparent"
       :-fx-accent "lightsteelblue"}

      ".text"
      {:-fx-font-smoothing-type "gray"}

      ".context-menu" {:-fx-effect "None"}
      ".combo-box-base" {:-fx-padding "1px"
                         :-fx-background-radius "0"}

      ".button" {:-fx-background-radius "0"
                 :-fx-text-fill text-color
                 ;; vector values are space-separated
                 :-fx-padding ["6px" "17px"]
                 ;; nested string key defines new selector: `.button:hover`
                 ":hover" {:-fx-text-fill :black}}

      ;;".label" {:-fx-text-fill text-color
      ;;          :-fx-wrap-text true}

      ;; tabber

      ".tab-pane > .tab-header-area > .headers-region > .tab "
      {:-fx-background-radius "0"
       ;;:-fx-padding "3px 20px"
       }

      ;; common tables

      ".table-view"
      {:-fx-table-cell-border-color table-border-colour
       :-fx-font-size ".9em"}

      ".table-view .column-header"
      {;;:-fx-background-color "#ddd" ;; flat colour
       :-fx-font-size "1em"
       :-fx-font-weight "Normal"
       :-fx-font-family "Sans"}

      ".table-view .table-row-cell"
      {:-fx-border-insets "-1 -1 0 -1"
       :-fx-border-color table-border-colour

       ":hover" {:-fx-background-color "#eee"}
       ":selected" {:-fx-background-color "-fx-selection-bar"}

       ":odd" {:-fx-background-color "white"}
       ":odd:hover" {:-fx-background-color "#eee"}
       ":odd:selected" {:-fx-background-color "-fx-selection-bar"}
       ":odd:selected:hover" {:-fx-background-color "-fx-selection-bar"}

       ".unsteady" {:-fx-background-color "lightsteelblue"}}


      ;; installed-addons table

      ".table-view#installed-addons"
      {" .updateable"
       {:-fx-background-color "lemonchiffon"
        ;; selected updateable addons are do not look any different
        ":selected" {:-fx-background-color "-fx-selection-bar"}}

       " .ignored .table-cell"
       {:-fx-text-fill "#aaa"}

       " .wow-column" {:-fx-alignment "center"}}


      ;; notice-logger

      ".table-view#notice-logger"
      {" .warn" {:-fx-background-color "lemonchiffon"
                 ":selected" {:-fx-background-color "-fx-selection-bar"}}
       " .error" {:-fx-background-color "tomato"
                  " .text" {:-fx-text-fill "blue"}
                  ":selected" {:-fx-background-color "-fx-selection-bar"}}

       " #level" {:-fx-alignment "center"
                  :-fx-border-width "0"}

       ;; hide column headers
       " > .column-header-background"
       {:-fx-max-height 0
        :-fx-pref-height 0
        :-fx-min-height 0}

       " .table-row-cell"
       {:-fx-border-color "white"}

       :-fx-font-family "monospace"}


      ;; search

      ".table-view#search-addons"
      {" .description" {:-fx-pref-width 700}
       " .tags" {:-fx-min-width 230 :-fx-max-width 450}
       " .updated" {:-fx-min-width 85 :-fx-max-width 120 :-fx-pref-width 100}
       " .downloads" {:-fx-min-width 100 :-fx-max-width 120}}


      ;; common table fields

      ".table-view .source-column"
      {:-fx-alignment "center-left"
       :-fx-padding "-2 0 0 0" ;; hyperlinks are just a little bit off .. weird.
       " .hyperlink:visited" {:-fx-underline "false"}
       " .hyperlink, .hyperlink:hover" {:-fx-underline "false"
                                        :-fx-text-fill "blue"}}})))

;;


(defn file-chooser
  [^ActionEvent event & [opt-map]]
  (let [opt-map (or opt-map {})
        default-open-type :open
        open-type (get opt-map :type default-open-type)
        ;; valid for a menu-item
        window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        chooser (doto (FileChooser.)
                  (.setTitle "Open File"))]
    (when-let [file-obj @(fx/on-fx-thread
                          (case open-type
                            :save (.showSaveDialog chooser window)
                            (.showOpenDialog chooser window)))]
      file-obj)))

(defn dir-chooser
  [^ActionEvent event]
  (let [;; valid for a menu-item
        window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        chooser (doto (DirectoryChooser.)
                  (.setTitle "Select Directory"))]
    (when-let [dir @(fx/on-fx-thread
                     (.showDialog chooser window))]
      (str dir))))

(comment "this works, but where does the value go? to an event listener?"
         (defn text-input
           [prompt]
           @(fx/on-fx-thread
             (fx/create-component
              {:fx/type :text-input-dialog
               ;;:prompt-text prompt
               :header-text "header text" ;;prompt
               :showing true
               :content-text "content text"
               :title "title"}))))

(defn text-input
  [event prompt]
  (let [;;window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        widget (doto (TextInputDialog.)
                 (.setTitle "Title")
                 (.setHeaderText "Header Text")
                 (.setContentText "content text"))
        optional-val @(fx/on-fx-thread
                       (.showAndWait widget))]
    (when (and (.isPresent optional-val)
               (not (empty? (.get optional-val))))
      (.get optional-val))))

(defn alert
  [event msg & [opt-map]]
  (let [window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        alert-type-map {:warning Alert$AlertType/WARNING
                        :error Alert$AlertType/ERROR
                        :confirm Alert$AlertType/CONFIRMATION
                        :info Alert$AlertType/INFORMATION}
        alert-type-key (get opt-map :type, :info)
        alert-type (get alert-type-map alert-type-key)
        widget (doto (Alert. alert-type)
                 (.setTitle (:title opt-map))
                 (.setHeaderText (:header opt-map))
                 (.setContentText msg)
                 (.initOwner window))]
    (when (:content opt-map)
      (.setContent (.getDialogPane widget) (:content opt-map)))
    @(fx/on-fx-thread (.showAndWait widget))))

;;

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn menu-item
  [label handler & [_]]
  {:fx/type :menu-item
   :text (str label)
   :on-action handler})

(defn build-catalogue-menu
  [selected-catalogue catalogue-addon-list]
  (when catalogue-addon-list
    (let [rb (fn [{:keys [label name]}]
               {:fx/type :radio-menu-item
                :text label
                :selected (= selected-catalogue name)
                :toggle-group {:fx/type fx/ext-get-ref
                               :ref ::catalogue-toggle-group}
                :on-action (fn [_]
                             (core/set-catalogue-location! name)
                             (core/save-settings))})]
      (mapv rb catalogue-addon-list))))

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

;; handlers

(defn async-event-handler
  "wraps `f`, calling it with any given `args` later"
  [f]
  (fn [& args]
    (async f args)))

(defn async-handler
  "same as `async-handler` but calls `f` and ignores `args`"
  [f]
  (fn [& _]
    (async f)))

(defn event-handler
  "wraps `f`, calling it with any given `args`.
  useful for debugging, otherwise just use the function directly"
  [f]
  (fn [& args]
    (apply f args)))

(defn handler
  "wraps `f`, calling it but ignores any args"
  [f]
  (fn [& _]
    (f)))

(defn donothing
  [& [_]]
  nil)

(defn wow-dir-picker
  [ev]
  (when-let [dir (dir-chooser ev)]
    (when (fs/directory? dir)
      ;; doesn't appear possible to select a non-directory with javafx
      (core/set-addon-dir! dir)
      (core/save-settings))))

;; todo: reconcile this with the on-close-request handler in the stage
(defn exit-handler
  [_]
  (when-not (core/get-state :in-repl?)
    (System/exit 0)))

(defn switch-tab-handler
  [tab-idx]
  (fn [event]
    (let [node ^Node (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getRoot)
          tabber-obj (first (.lookupAll node "#tabber"))]
      (.select (.getSelectionModel tabber-obj) tab-idx))))

(defn import-addon-handler
  "imports an addon by parsing a URL"
  [event]
  (let [addon-url (text-input event "Enter URL of addon")

        fail-msg "Failed. URL must be:
  * valid
  * originate from github.com
  * addon uses 'releases'
  * latest release has a packaged 'asset'
  * asset must be a .zip file
  * zip file must be structured like an addon"
        failure #(alert event fail-msg {:type :error})

        warn-msg "Failed. Addon successfully added to catalogue but could not be installed."
        warning #(alert event warn-msg {:type :warning})]
    (when addon-url
      (if-let [result (core/add+install-user-addon! addon-url)]
        (when-not (contains? result :download-url)
          (warning))
        (failure))))
  nil)

(defn import-addon-list-handler
  "prompts user with a file selection dialogue then imports a list of addons from the selected file"
  [event]
  (when-let [;; todo: json file filter
             file-obj (file-chooser event)]
    (core/import-exported-file (-> file-obj .getAbsolutePath str))
    (core/refresh)
    nil))

(defn export-addon-list-handler
  "prompts user with a file selection dialogue then writes the current directory of addons to the selected file"
  [event]
  (when-let [;; todo: json filters
             file-obj (file-chooser event {:type :save})]
    (core/export-installed-addon-list-safely (-> file-obj .getAbsolutePath str))
    nil))

(defn export-user-catalogue-handler
  "prompts user with a file selection dialogue then writes the user catalogue to selected file"
  [event]
  (when-let [;; todo: json filters
             file-obj (file-chooser event {:type :save})]
    (core/export-user-catalogue-addon-list-safely (-> file-obj .getAbsolutePath str))
    nil))

(defn about-strongbox-dialog
  [event]
  (let [content (fx/create-component {:fx/type :v-box
                                      :children [{:fx/type :text
                                                  :text "strongbox"}
                                                 {:fx/type :text
                                                  :text (format "version %s" (core/strongbox-version))}
                                                 {:fx/type :hyperlink
                                                  :text "https://github.com/ogri-la/strongbox"}
                                                 {:fx/type :text
                                                  :text "AGPL v3"}]})]
    (alert event "" {:type :info :content (fx/instance content)})
    nil))

(defn remove-selected-confirmation-handler
  [event]
  (when-let [selected (core/get-state :selected-installed)]
    (if (utils/any (mapv :ignore? selected))
      (alert event "Selection contains ignored addons. Stop ignoring them and then delete." {:type :error})

      (let [label-list (mapv (fn [row]
                               {:fx/type :text
                                :text (str " - " (:name row))}) selected)
            content (fx/create-component {:fx/type :v-box
                                          :children (into [{:fx/type :text
                                                            :text (format "Deleting %s:" (count selected))}] label-list)})
            result (alert event "" {:content (fx/instance content)
                                    :type :confirm})]
        (when (= (.get result) ButtonType/OK)
          (core/remove-selected))))))

;;

(def separator {:fx/type fx/ext-instance-factory
                :create #(javafx.scene.control.SeparatorMenuItem.)})

(defn menu-bar
  [{:keys [fx/context]}]
  (let [file-menu [(menu-item "New addon directory" (async-event-handler wow-dir-picker) {:key "menu N" :mnemonic "n"})
                   (menu-item "Remove addon directory" (async-handler core/remove-addon-dir!))
                   separator
                   (menu-item "Exit" exit-handler {:key "menu Q" :mnemonic "x"})]

        view-menu [(menu-item "Refresh" (async-handler core/refresh) {:key "F5"})
                   separator
                   (menu-item "Installed" (switch-tab-handler INSTALLED-TAB) {:key "menu I" :mnemonic "i"})
                   (menu-item "Search" (switch-tab-handler SEARCH-TAB) {:key "menu H" :mnemonic "h"})
                   ;; separator
                   ;; todo: build-theme-menu
                   ]

        catalogue-menu (into (build-catalogue-menu
                              (fx/sub-val context get-in [:app-state :cfg :selected-catalogue])
                              (fx/sub-val context get-in [:app-state :cfg :catalogue-location-list]))
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler core/refresh-user-catalogue))])

        addon-menu [(menu-item "Update all" (async-handler core/install-update-all) {:key "menu U" :mnemonic "u"})
                    (menu-item "Re-install all" (async-handler core/re-install-all))]

        impexp-menu [(menu-item "Import addon from Github" (event-handler import-addon-handler))
                     separator
                     (menu-item "Import addon list" (async-event-handler import-addon-list-handler))
                     (menu-item "Export addon list" (async-event-handler export-addon-list-handler))
                     (menu-item "Export Github addon list" (async-event-handler export-user-catalogue-handler))]

        cache-menu [(menu-item "Clear http cache" (async-handler core/delete-http-cache!))
                    (menu-item "Clear addon zips" (async-handler core/delete-downloaded-addon-zips!))
                    (menu-item "Clear catalogues" (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (menu-item "Clear log files" (async-handler core/delete-log-files!))
                    (menu-item "Clear all" (async-handler core/clear-all-temp-files!))
                    separator
                    (menu-item "Delete WowMatrix.dat files" (async-handler core/delete-wowmatrix-dat-files!))
                    (menu-item "Delete .wowman.json files" (async-handler (comp core/refresh core/delete-wowman-json-files!)))
                    (menu-item "Delete .strongbox.json files" (async-handler (comp core/refresh core/delete-strongbox-json-files!)))]

        help-menu [(menu-item "About strongbox" about-strongbox-dialog)]]

    {:fx/type fx/ext-let-refs
     :refs {::catalogue-toggle-group {:fx/type :toggle-group}}
     :desc {:fx/type :menu-bar
            :id "main-menu"
            :menus [(menu "File" file-menu {:mnemonic "F"})
                    (menu "View" view-menu {:mnemonic "V"})
                    (menu "Catalogue" catalogue-menu)
                    (menu "Addons" addon-menu {:mnemonic "A"})
                    (menu "Import/Export" impexp-menu {:mnemonic "i"})
                    (menu "Cache" cache-menu)
                    (menu "Help" help-menu)]}}))

(defn installed-addons-menu-bar
  [{:keys [fx/context]}]
  (let [;; temporary
        refresh-button {:fx/type :button
                        :text "Refresh"
                        :on-action (async-handler core/refresh)}
        update-all-button {:fx/type :button
                           :text "Update all"
                           :on-action (async-handler core/install-update-all)}

        addon-dir-map-list (or (fx/sub-val context get-in [:app-state :cfg :addon-dir-list]) [])
        selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        selected-game-track (core/get-game-track selected-addon-dir)

        wow-dir-dropdown {:fx/type :combo-box
                          :value selected-addon-dir
                          :on-value-changed (async-event-handler
                                             (fn [new-addon-dir]
                                               ;; dosync doesn't work here, stop trying it
                                               (core/set-addon-dir! new-addon-dir)
                                               (println "done setting addon dir")))
                          :items (mapv :addon-dir addon-dir-map-list)}

        game-track-dropdown {:fx/type :combo-box
                             :value (-> selected-game-track (or "") name)
                             :on-value-changed (async-event-handler
                                                (fn [new-game-track]
                                                  (core/set-game-track! (keyword new-game-track))
                                                  (core/refresh)))
                             :items ["retail" "classic"]}

        ;; todo: add upgrade strongbox button
        ]
    {:fx/type :h-box
     :padding 10
     :spacing 10
     :children [refresh-button
                update-all-button
                wow-dir-dropdown
                game-track-dropdown]}))

(defn table-column
  [column-data]
  (let [column-name (:text column-data)

        default-cvf (fn [row] (get row (keyword column-name)))
        new-cvf (:cell-value-factory column-data)
        final-cvf (if (and (some? new-cvf)
                           (keyword? (:cell-value-factory column-data)))
                    ;; keywords have to be wrapped in a function to be coerced to a javafx callback!
                    #(get % (:cell-value-factory column-data))
                    (or new-cvf default-cvf))
        final-cvf {:cell-value-factory final-cvf}

        final-style {:style-class (into ["table-cell"
                                         (clojure.string/lower-case (str column-name "-column"))]
                                        (get column-data :style-class))}

        default {:fx/type :table-column
                 :min-width 80}]
    (merge default column-data final-cvf final-style)))

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

(defn href-to-hyperlink
  [row]
  (let [href (source-to-href-fn row)
        widget {:fx/type :hyperlink
                :on-action (handler #(utils/browse-to (:url row)))
                :text (when href
                        (str "↪ " href))}]
    (-> widget fx/create-component fx/instance)))

(defn installed-addons-table
  [{:keys [fx/context]}]
  ;;(dosync
  ;; (fx/sub-val context get-in [:app-state :selected-addon-dir])
  ;; (fx/sub-val context get-in [:app-state :installed-addon-list]))

  (let [_ (fx/sub-val context get-in [:app-state :unsteady-addons])
        ;;_ (fx/sub-val context get-in [:app-state :selected-addon-dir])
        row-list (fx/sub-val context get-in [:app-state :installed-addon-list])

        iface-version (fn [row]
                        (some-> row :interface-version str utils/interface-version-to-game-version))

        column-list [{:text "source" :min-width 110 :pref-width 120 :max-width 160 :cell-value-factory href-to-hyperlink}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 500 :cell-value-factory :label}
                     {:text "description" :pref-width 700 :cell-value-factory :description}
                     {:text "installed" :max-width 150 :cell-value-factory :installed-version}
                     {:text "available" :max-width 150 :cell-value-factory :version}
                     {:text "WoW" :max-width 100 :cell-value-factory iface-version}]]
    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :multiple
             ;; unlike gui.clj, we have access to the original data here
             :on-selected-items-changed core/select-addons*}
     :desc {:fx/type :table-view
            :id "installed-addons"
            :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :row-factory {:fx/cell-type :table-row
                          :describe (fn [row]
                                      {:style-class
                                       (remove nil?
                                               ["table-row-cell" ;; :style-class actually *replaces* the list of classes
                                                (when (:update? row) "updateable")
                                                (when (:ignore? row) "ignored")
                                                (when (core/unsteady? row) "unsteady")])})}
            :columns (mapv table-column column-list)
            :context-menu {:fx/type :context-menu
                           :items [(menu-item "Update" (async-handler core/install-update-selected))
                                   (menu-item "Re-install" (async-handler core/re-install-selected))
                                   separator
                                   (menu-item "Ignore" (async-handler core/ignore-selected))
                                   (menu-item "Stop ignoring" (async-handler core/clear-ignore-selected))
                                   separator
                                   (menu-item "Delete" remove-selected-confirmation-handler)]}
            :items (or row-list [])}}))

(defn notice-logger
  [{:keys [fx/context]}]
  (let [log-message-list (fx/sub-val context :log-message-list)
        log-message-list (reverse log-message-list) ;; nfi how to programmatically change column sort order
        column-list [{:id "level" :text "level" :max-width 100 :cell-value-factory (comp name :level)}
                     {:text "message" :pref-width 500 :cell-value-factory :message}]]
    {:fx/type :table-view
     :id "notice-logger"
     :selection-mode :multiple
     :row-factory {:fx/cell-type :table-row
                   :describe (fn [row]
                               {:style-class ["table-row-cell" (name (:level row))]})}
     :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
     :columns (mapv table-column column-list)
     :items (or log-message-list [])}))

(defn installed-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type installed-addons-menu-bar}
              {:fx/type installed-addons-table}]})

(defn search-addons-table
  [{:keys [fx/context]}]
  (let [addon-list (fx/sub-val context get-in [:app-state :search-results])
        column-list [{:text "source" :min-width 110 :pref-width 120 :max-width 160 :cell-value-factory href-to-hyperlink}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 450 :cell-value-factory :label}
                     {:text "description" :pref-width 700 :cell-value-factory :description}
                     {:text "tags" :pref-width 380 :min-width 230 :max-width 450 :cell-value-factory (comp str :tag-list)}
                     {:text "updated" :min-width 85 :max-width 120 :pref-width 100 :cell-value-factory (comp #(utils/safe-subs % 10)  :updated-date)}
                     {:text "downloads" :min-width 100 :max-width 120 :cell-value-factory :download-count}]]
    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :multiple
             ;; unlike gui.clj, we have access to the original data here. and it's an ordered/map ...?
             :on-selected-items-changed core/select-addons-search*}
     :desc {:fx/type :table-view
            :id "search-addons"
            :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :columns (mapv table-column column-list)
            :items addon-list}}))

(defn search-addons-search-field
  [_]
  {:fx/type :h-box
   :padding 10
   :spacing 10
   :children [{:fx/type :text-field
               :prompt-text "search"
               :on-text-changed (fn [v]
                                  (swap! core/state assoc :search-field-input v))}
              {:fx/type :button
               :text "install selected"
               :on-action (async-handler (fn []
                                           ;;(switch-tab INSTALLED-TAB)
                                           ;;(doseq [selected (get-state :selected-search)]
                                           ;;  (some-> selected core/expand-summary-wrapper vector core/-install-update-these)
                                           ;;  (core/load-installed-addons))
                                           ;;(ss/selection! (select-ui :#tbl-search-addons) nil) ;; deselect rows in search table
                                           ;;(core/refresh))
                                           (println "installing ...")))}
              {:fx/type :button
               :text "random"
               :on-action (fn [_]
                            (swap! core/state assoc :search-field-input
                                   (if (nil? (:search-field-input @core/state)) "" nil)))}]})

(defn search-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type search-addons-search-field}
              {:fx/type search-addons-table}]})

(defn tabber
  [_]
  {:fx/type :tab-pane
   :id "tabber"
   :tabs [{:fx/type :tab
           :text "installed"
           :closable false
           :content {:fx/type installed-addons-pane}}
          {:fx/type :tab
           :text "search"
           :closable false
           :content {:fx/type search-addons-pane}}]})

;;

(defn root
  [{:keys [fx/context]}]
  (fx/sub-val context get :style) ;; todo: remove outside of dev?
  {:fx/type :stage
   :showing true
   :on-close-request (fn [ev]
                       ;; called on ctrl-c
                       ;;(println "got ev" ev)
                       ;;(println (bean ev))
                       (when-not (core/get-state :in-repl?)
                         (System/exit 0)))

   :title "strongbox"
   :width 1024
   :height 768
   :scene {:fx/type :scene
           :stylesheets [(::css/url style)]
           :root {:fx/type :v-box
                  :children [{:fx/type menu-bar}
                             {:fx/type :split-pane
                              :orientation :vertical
                              :divider-positions [0.65]
                              :items [{:fx/type tabber}
                                      {:fx/type notice-logger}]}]}}})

(defn init-notice-logger!
  [gui-state]
  (let [gui-logger (fn [log-data]
                     (let [{:keys [timestamp_ msg_ level]} log-data
                           formatted-output-str (force (format "%s - %s" (force timestamp_) (force msg_)))]
                       (swap! gui-state fx/swap-context update-in [:log-message-list] conj {:level level :message formatted-output-str})))]
    (logging/add-appender! :gui gui-logger {:timestamp-opts {:pattern "HH:mm:ss"}})))

(defn start
  []
  (info "starting gui")
  (let [state-template {:app-state nil,
                        :log-message-list []
                        :style style}
        gui-state (atom (fx/create-context state-template)) ;; cache/lru-cache-factory))

        _ (add-watch #'style :refresh-app (fn [_ _ _ _]
                                            (swap! gui-state fx/swap-context assoc :style style)))

        update-gui-state (fn [new-state]
                           ;;@(fx/on-fx-thread ;; doesn't work
                           ;;(future ;; also doesn't work (why would it?)
                           (swap! gui-state fx/swap-context assoc :app-state new-state))


        ;; when :selected-addon-dir changes the app state is updated then this watcher is triggered, updating the gui state.
        ;; a watcher in the *app* looking at :selected-addon-dir is also triggered. it causes a refresh.
        ;; a refresh causes many changes to app state, each change causes a change to the *gui* state.

        ;; the installed-addon-list-table fn will update itself from the *gui* state when the installed-addon-list data changes.


        _ (core/state-bind [] update-gui-state)

        ;; async search. should be able to get this effect with idiomatic cljs use
        ;; todo: stick this in core?
        save-search-results (fn [new-state]
                              (future
                                (swap! core/state assoc :search-results (core/db-search (:search-field-input new-state)))))
        _ (core/state-bind [:search-field-input] save-search-results)

        renderer (fx/create-renderer
                  :middleware (comp
                               fx/wrap-context-desc
                               (fx/wrap-map-desc (fn [_] {:fx/type root})))

                  ;; magic :(

                  :opts {:fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                      ;; For functions in `:fx/type` values, pass
                                                      ;; context from option map to these functions
                                                      (fx/fn->lifecycle-with-context %))})

        ;; on first load, because the catalogue hasn't been loaded
        ;; and because the search-field-input doesn't change,
        ;; and because the search component isn't re-rendered,
        ;; fake a change to get something to appear
        bump-search (fn []
                      (when-not (:search-field-input core/state)
                        (swap! core/state assoc :search-field-input "")))]

    (fx/mount-renderer gui-state renderer)
    (init-notice-logger! gui-state)

    (future
      (core/refresh)
      (bump-search))

    renderer))

(defn stop
  []
  (info "stopping gui") ;; nothing needs to happen ... yet?
  nil)
