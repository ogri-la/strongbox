(ns wowman.ui.gui
  (:require
   [wowman
    [core :as core :refer [get-state state-bind state-binds]]
    [logging :as logging]
    [specs :as sp]
    [utils :as utils]]
   [clojure.instant]
   [clojure.core.async :as async]
   [clojure.string :refer [lower-case starts-with? trim]]
   [slugify.core :refer [slugify]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [seesaw
    [invoke :as ssi]
    [chooser :as chooser]
    [mig :as mig]
    ;;[dev :refer [show-options show-events]]
    [color]
    [swingx :as x]
    [core :as ss]
    [table :as sstbl]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]))

(s/def ::content-pane #(instance? java.awt.Component %))

(defn items
  [& lst]
  (vec (remove nil? (flatten lst))))

;;
;;
;;

(defn select-ui
  [& path]
  (if-let [ui (get-state :gui)]
    (ss/select ui (vec path))
    (throw (RuntimeException. "attempted to access an uninitialised GUI"))))

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn tab
  [title body]
  {:title title
   :content body})

(defn button
  [label onclick]
  (ss/button :id (-> label slugify (str "-btn") keyword) ;; ":refresh-btn", ":update-all-btn"
             :text label
             :listen [:action onclick]))

(defn menu-item
  [label onclick]
  (ss/menu-item :text label
                :listen [:action onclick]))

(defn dynamic-menu-item
  "like a regular menu-item, but it's label changes"
  [initial-label & {:keys [watch callback onclick]}]
  (let [mi (menu-item initial-label onclick)
        actual-callback (fn [state]
                          (ss/text! mi (callback state)))]
    (state-bind watch actual-callback)
    mi))

(defn disabled-button
  [initial-label & {:keys [watch callback onclick]}]
  (let [button (ss/button :id (-> initial-label slugify (str "-btn") keyword) ;; ":refresh-btn", ":update-all-btn"
                          :text initial-label
                          :listen [:action onclick]
                          :enabled? false)
        actual-callback (fn [state]
                          (ss/config! button :enabled? (callback (get-in state watch))))]
    (state-bind watch actual-callback)
    button))

(defn donothing
  "the does-nothing event handler"
  [_]
  nil)

(comment "unused"
         (defn-spec click-button nil?
           [btn-id keyword?]
           (ss/invoke-later
            (.doClick (select-ui btn-id)))))

(defn handler [& fl]
  (fn [_]
    (doseq [f fl] (f))))

(defn async-handler
  "like `handler`, but each function is executed inside a `go` block instead of sequentially"
  [& fl]
  (fn [_]
    (doseq [f fl]
      (async/go (f)))))

(defn selected-rows-handler
  "calls given `f` with last event when selection has stopped adjusting"
  [f]
  (fn [ev]
    ;; https://docs.oracle.com/javase/7/docs/api/javax/swing/event/ListSelectionEvent.html#getValueIsAdjusting()
    (when-not (.getValueIsAdjusting ev)
      (f ev))))

(defn insert-all
  [grid rows]
  (ss/invoke-now
   (sstbl/clear! grid)
   (debug "grid" (ss/id-of grid) "num rows" (count rows) "num grid rows" (sstbl/row-count grid))
   (dorun (map-indexed (fn [idx row]
                         (debug "inserting row" (:name row) "at idx" idx)
                         (sstbl/insert-at! grid idx row)) rows))
   ;; packs the columns to something less-default
   (.packAll grid)))

(defn insert-one
  [grid row]
  (ss/invoke-later
   (sstbl/insert-at! grid 0 row)))

(defn-spec select-one nil?
  [grid #(instance? javax.swing.JTable %), idx int?]
  (debug "selecting row" idx)
  (try
    (.setSelectionInterval (.getSelectionModel grid) idx idx)
    (catch java.lang.IllegalArgumentException iae
      (warn (format "failed to select row %s: %s" idx (.getMessage iae))))))

(comment "unused, for now"
         (defn select-many
           [grid & indicies]
           (ss/invoke-later
            (let [s-model (.getSelectionModel grid)]
              (select-one grid (first indicies))
              (doseq [idx (rest indicies)]
                (.addSelectionInterval s-model idx idx))))))

(defn -find-column-by-label
  "returns the index of the column for the given label. remember, column positions can change!"
  [mdl label]
  (let [matches? (fn [idx]
                   (when (= (.getColumnName mdl idx) label)
                     idx))
        columns (range 0 (.getColumnCount mdl))]
    (first (remove nil? (map matches? columns)))))

(defn find-row-by-value
  "returns the first set of coordinates [row col] for a match of `row-value` in column with given `column-label` or `nil` if not found."
  [grid column-label row-value]
  (let [col-idx (-find-column-by-label (.getModel grid) column-label)
        matches? (fn [row-idx]
                   (when (= (.getValueAt (.getModel grid) row-idx col-idx) row-value)
                     [row-idx col-idx]))
        rows (range 0 (.getRowCount grid))]
    (first (remove nil? (map matches? rows)))))

;;

(defn switch-tab [idx] (.setSelectedIndex (select-ui :#tabber) idx))
(defn switch-tab-handler [idx] (fn [_] (switch-tab idx)))

;;

;; TODO: remove
(defn-spec refresh nil?
  []
  ;; here be dragons.
  ;; this queues the function and discards further calls to function if function call is still pending
  ;; this is close to atomicity of an operation without rollback
  (let [signal (ssi/signaller* core/refresh)]
    (signal))
  nil)

(defn-spec tbl-selected-rows (s/or :ok ::sp/list-of-maps, :nothing-selected nil?)
  "returns a list of rows that are currently selected for given table"
  [tbl keyword?]
  (let [tbl (select-ui tbl)]
    (sstbl/value-at tbl (ss/selection tbl {:multi? true}))))

(defn-spec installed-addons-selection-handler nil?
  [_ ::sp/gui-event]
  (let [selected-rows (tbl-selected-rows :#tbl-installed-addons)]
    (debug (count selected-rows) "selected, " (count (filter :update? selected-rows)) "updatable")
    (swap! core/state assoc :selected-installed selected-rows))
  nil)

(defn-spec search-results-selection-handler nil?
  [_ ::sp/gui-event]
  (let [selected-rows (tbl-selected-rows :#tbl-search-addons)]
    (debug (count selected-rows) "selected")
    (swap! core/state assoc :selected-search selected-rows))
  nil)

(defn-spec search-results-install-handler nil?
  []
  ;; this works ~ok~ for one or two files, but many at once and we get what looks like a freeze as the summary is expanded
  ;; what might be better is: switch tab; for each selected, expand summary, install addon, load-installed-addons; refresh;
  ;; there will be a plodding step-wise update which is better than a blank screen and apparent hang
  ;; `load-installed-addons` will simply re-visit the list of installed addons and update the state with the bare toc contents.
  ;;(core/-install-update-these (map curseforge/expand-summary (get-state :selected-search)))
  (switch-tab INSTALLED-TAB)
  (doseq [selected (get-state :selected-search)]
    (-> selected core/expand-summary-wrapper vector core/-install-update-these)
    (core/load-installed-addons))
  (refresh))

(defn-spec remove-selected-handler nil?
  []
  (when-let [selected (get-state :selected-installed)]
    (let [header [[(format "Deleting %s:" (count selected)) ""]]
          labels (mapv (fn [row] [(str " - " (-> row :name)) ""]) selected)

          content (into header labels)
          content (interleave content (repeat [:separator "growx, wrap"]))

          dialog (ss/dialog :content (mig/mig-panel :items content)
                            :resizable? false
                            :type :warning
                            :option-type :ok-cancel
                            :default-option :no
                            :success-fn (async-handler core/remove-selected))]
      (-> dialog ss/pack! ss/show!)
      nil)))

(defn configure-app-panel
  []
  (let [picker (fn []
                 (when-let [dir (chooser/choose-file :type "select" :selection-mode :dirs-only)]
                   (core/set-install-dir! (str dir))
                   (core/save-settings)))
        ;; important! release the event thread using async-handler else updates during process won't be shown until complete
        refresh-button (button "Refresh" (async-handler core/refresh))
        update-all-button (button "Update all" (async-handler core/install-update-all))

        wow-dir-button (button "WoW directory" (async-handler picker))

        wow-dir-label (ss/label :id :wow-dir-lbl :text (or (get-state :cfg :install-dir) "No directory"))
        wow-dir-label-fn (fn [state]
                           (ss/value! wow-dir-label (get-in state [:cfg :install-dir])))]
    (state-bind [:cfg :install-dir] wow-dir-label-fn)
    (ss/vertical-panel
     :items [(ss/flow-panel :align :left :items [refresh-button update-all-button wow-dir-button wow-dir-label])])))

(defn entry-to-map
  "converts a RowFilter.Entry object to a simple map"
  [entry-obj]
  (let [mdl (.getModel entry-obj)] ;; TableModel
    (into {} (mapv (fn [idx] [(.getColumnName mdl idx) (.getValue entry-obj idx)]) (range 0 (.getColumnCount mdl))))))

(defn installed-addons-panel-column-widths
  "this sucks"
  [grid]
  (let [default-min-width 80 ;; solid default for all columns
        min-width-map {"WoW" 45} ;; these can be a little smaller
        max-width-map {"installed" 200
                       "available" 200
                       "updated" 100}
        pre-width-map {"WoW" 50
                       "updated" 100}] ;; we would like these a little larger, if possible
    (doseq [column (.getColumns grid)]
      (info "got column" (.getTitle column))
      (.setMinWidth column (get min-width-map (.getTitle column) default-min-width))
      (when-let [max-width (get max-width-map (.getTitle column))]
        (.setMaxWidth column max-width))
      (when-let [pre-width (get pre-width-map (.getTitle column))]
        (.setPreferredWidth column pre-width)))))

(defn installed-addons-panel
  []
  (let [debug-cols [:group-id :primary? :addon-id :update?] ;; only visible when debugging
        tblmdl (sstbl/table-model :columns [{:key :name :text "addon-id"}
                                            :group-id
                                            :primary?
                                            {:key :label :text "name"}
                                            :description
                                            {:key :installed-version :text "installed"}
                                            {:key :version :text "available"}
                                            {:key :updated-date :text "updated"}
                                            :update?
                                            {:key :interface-version :text "WoW"}
                                            {:key :category-list :text "categories"}]
                                  :rows [])

        popup-menu-items [(dynamic-menu-item ""
                                             :watch [:selected-installed]
                                             :callback (fn [state]
                                                         (let [selected-rows (tbl-selected-rows :#tbl-installed-addons)]
                                                           (format "%s selected, %s updateable"
                                                                   (count selected-rows)
                                                                   (count (filter :update? selected-rows)))))
                                             :onclick donothing)
                          (ss/separator)
                          (menu-item "Update" (async-handler core/install-update-selected))
                          (menu-item "Re-install" (async-handler core/re-install-selected))
                          (ss/separator)
                          (menu-item "Delete" (async-handler remove-selected-handler))]
        popup-menu (ss/popup :items popup-menu-items)

        grid (x/table-x :id :tbl-installed-addons
                        :model tblmdl
                        ;;:auto-resize :all-columns ;; crazy, don't use this
                        ;;:column-margin 10 ;; messes with selected line
                        :popup popup-menu)

        ;; this is before render and before hiding and before user has had a chance to move or resize columns
        _ (installed-addons-panel-column-widths grid)

        predicate (proxy [org.jdesktop.swingx.decorator.HighlightPredicate] []
                    (isHighlighted [renderer adapter]
                      (let [update-column 8
                            ;;update-column (.viewToModel adapter update-column) ;; not working?
                            value (.getValue adapter update-column)]
                        (true? value))))

        highlighter (org.jdesktop.swingx.decorator.ColorHighlighter.
                     predicate
                     (seesaw.color/color :darkkhaki)
                     nil) ;; no change in foreground colours
        _ (.addHighlighter grid highlighter)

        ;; filter known non-primary addons from the results
        table-sorter (.getRowSorter grid)
        row-filter (proxy [javax.swing.RowFilter] []
                     (include [entry]
                       (let [entry-map (entry-to-map entry)
                             primary? (get entry-map "primary?")
                             ;; true if primary? key not-nil and primary? is true, otherwise true
                             ;; BUG: this will cause all addons in a group to be hidden when the primary isn't known, like dbm
                             include? (or (and
                                           (not (nil? primary?))
                                           (Boolean. primary?))
                                          true)]
                         include?)))
        ;; disabled, addon grouping now happens in fs/installed-addons
        ;;_ (.setRowFilter table-sorter row-filter)

        date-renderer (proxy [javax.swing.table.DefaultTableCellRenderer] []
                        (setValue [datestr]
                          (proxy-super setValue (if-not datestr ""
                                                        (-> datestr clojure.instant/read-instant-date (utils/fmt-date "yyyy-MM-dd"))))))
        _ (.setCellRenderer (.getColumn (.getColumnModel grid) 7) date-renderer)

        interface-version-renderer (proxy [javax.swing.table.DefaultTableCellRenderer] []
                                     (setValue [iface-version]
                                       (when iface-version
                                         (proxy-super setValue (-> iface-version str utils/interface-version-to-game-version)))))
        _ (.setCellRenderer (.getColumn (.getColumnModel grid) 9) interface-version-renderer)

        ;; refreshes installed addons table
        update-rows-fn (fn [state]
                         (debug "refreshing installed addons table")
                         ;; don't use core/get-state inside listeners!
                         (insert-all grid (:installed-addon-list state)))

        watch-for-unsteady-addons (fn [state]
                                    (let [unsteady (:unsteady-addons state)]
                                      (when-not (empty? unsteady)
                                        (debug "unsteady addons:" unsteady)
                                        (ss/invoke-now
                                         (if-let [idx (first (find-row-by-value grid "addon-id" (first unsteady)))]
                                           (select-one grid idx) ;; if the rows are hidden you won't be able to see it
                                           (warn "failed to find value" (first unsteady) "in column 'addon-id'"))))))]

    ;; 'hide' debug columns. affects table only, data/table model is unaffected
    ;; todo: switch to setVisible:
    ;; https://pirlwww.lpl.arizona.edu/resources/guide/software/SwingX/org/jdesktop/swingx/table/TableColumnExt.html#setVisible(boolean)
    (when-not (core/debugging?)
      (doseq [col debug-cols]
        (.removeColumn grid (.getColumn grid (-> col name str)))))

    (ss/listen grid :selection (selected-rows-handler installed-addons-selection-handler))

    (state-bind [:installed-addon-list] update-rows-fn)
    (state-bind [:unsteady-addons] watch-for-unsteady-addons)
    (ss/scrollable grid)))

;;

(defn installed-panel
  []
  (mig/mig-panel
   :constraints ["wrap 1"]
   :items [[(configure-app-panel) "width 100%:100%"]
           [(installed-addons-panel) "height 100%, width 99%::"]]))

(defn-spec search-input-handler nil?
  [ev ::sp/gui-event]
  (let [text-field-value (ss/value (.getSource ev))]
    (swap! core/state assoc :search-field-input text-field-value))
  nil)

(defn search-input-panel
  []
  (let [install-button (disabled-button "install selected"
                                        :watch [:selected-search]
                                        :callback (comp not empty?)
                                        :onclick (async-handler search-results-install-handler))
        search-input (ss/text :id :search-input-txt :columns 40 :listen [:key-released search-input-handler])]
    (ss/flow-panel :align :left :items ["search" search-input install-button])))

(defn search-rows
  [rows uinput]
  (let [uinput (-> uinput (or "") trim lower-case)
        search-fn (fn [row]
                    (when (:label row)
                      (starts-with? (-> row :label lower-case) uinput)))]
    (if (empty? uinput)
      rows
      (filter search-fn rows))))

(defn search-results-panel
  []
  (let [tblmdl (sstbl/table-model :columns [{:key :label :text "Name"}
                                            :description
                                            {:key :category-list :text "categories"}
                                            {:key :updated-date :text "updated"}]
                                  :rows [])

        grid (x/table-x :id :tbl-search-addons :model tblmdl)

        label-idx (atom (set []))
        update-label-idx (fn [_]
                           (reset! label-idx (set (remove nil? (map :label (get-state :installed-addon-list))))))
        _ (state-bind [:installed-addon-list] update-label-idx)

        predicate (proxy [org.jdesktop.swingx.decorator.HighlightPredicate] []
                    (isHighlighted [renderer adapter]
                      (let [label-column 0
                            value (.getValue adapter label-column)]
                        (contains? @label-idx value))))

        highlighter (org.jdesktop.swingx.decorator.ColorHighlighter.
                     predicate
                     (seesaw.color/color :darkkhaki)
                     nil) ;; no change in foreground colours
        _ (.addHighlighter grid highlighter)

        date-renderer (proxy [javax.swing.table.DefaultTableCellRenderer] []
                        (setValue [datestr]
                          (when datestr
                            (proxy-super setValue (-> datestr clojure.instant/read-instant-date (utils/fmt-date "yyyy-MM-dd"))))))

        _ (.setCellRenderer (.getColumn (.getColumnModel grid) 3) date-renderer)

        watch-these [[:addon-summary-list]
                     [:search-field-input]]

        cap 250 ;; jxtable + autopack. more rows and searching becomes noticibly laggy
        update-rows-fn (fn [state]
                         (let [known-addons (search-rows (:addon-summary-list state) (:search-field-input state))]
                           (insert-all grid (take cap known-addons))))]

    (ss/listen grid :selection (selected-rows-handler search-results-selection-handler))
    (state-binds watch-these update-rows-fn)
    (ss/scrollable grid)))

(defn search-panel
  []
  (mig/mig-panel
   :constraints ["wrap 1"]
   :items [[(search-input-panel) "width 100%:100%"]
           [(search-results-panel) "height 100%, width 99%::"]]))

(defn notice-logger
  []
  (let [mdl (sstbl/table-model :columns [:level :message])
        grid (x/table-x :id :log-window
                        :model mdl
                        :show-grid? false)

        gui-logger (fn [data]
                     (let [{:keys [timestamp_ msg_ level]} data
                           formatted-output-str (force (format "%s - %s" (force timestamp_) (force msg_)))]
                       (insert-one grid {:level level :message formatted-output-str})))

        ;; this all sucks. mig for tables would be super nice

        column-mdl (.getColumnModel grid)
        level-col (.getColumn column-mdl 0)

        cell-renderer (javax.swing.table.DefaultTableCellRenderer.)
        _ (.setHorizontalAlignment cell-renderer javax.swing.SwingConstants/CENTER)
        _ (.setCellRenderer level-col cell-renderer)

        level-width 50]

    (logging/add-appender :gui gui-logger {:timestamp-opts {:pattern "HH:mm:ss"}})

    ;; more suckage

    ;; level
    (.setMinWidth level-col level-width)
    (.setMaxWidth level-col (* level-width 2))
    (.setPreferredWidth level-col (* level-width 1.5))

    ;; hide header when not debugging
    (when-not (core/debugging?)
      (.setTableHeader grid nil))

    (ss/scrollable grid)))

(defn-spec switch-search-tab-handler nil?
  [_ ::sp/gui-event]
  (let [tabber-component (select-ui :#tabber)]
    (.setSelectedIndex tabber-component 1)
    nil))

(defn mk-tabber
  []
  (let [tabber (ss/tabbed-panel
                :id :tabber
                :tabs [(tab "installed" (installed-panel))
                       (tab "search" (search-panel))])]

    (ss/listen tabber :selection (fn [e]
                                   (let [tab-label (-> (ss/selection tabber) :title)]
                                     (when (= tab-label "search")
                                       (ss/request-focus! (select-ui :#search-input-txt))))))
    tabber))

(defn start-ui
  []
  (let [root->splitter (ss/top-bottom-split (mk-tabber) (notice-logger))
        _ (.setResizeWeight root->splitter 0.8)

        root (ss/vertical-panel :id :root
                                :items [root->splitter])

        ;;

        newui (ss/frame
               :title "wowman"
               :size [640 :by 480]
               :content (mig/mig-panel
                         :constraints (if (core/debugging?)
                                        ["debug,flowy"]
                                        ["flowy"])
                         :items [[root "height 100%, width 100%:100%:100%"]])
               :on-close :dispose)

        file-menu (items
                   (ss/action :name "Installed" :key "menu I" :mnemonic "i" :handler (switch-tab-handler INSTALLED-TAB))
                   (ss/action :name "Search" :key "menu H" :mnemonic "h" :handler (switch-tab-handler SEARCH-TAB))
                   (when (core/debugging?) ;; these have never been useful outside of dev
                     (ss/action :name "Load settings" :handler (handler core/load-settings))
                     (ss/action :name "Save settings" :key "menu S" :mnemonic "s" :handler (handler core/save-settings)))
                   :separator
                   (ss/action :name "Exit" :key "menu Q" :mnemonic "x" :handler (handler #(ss/dispose! newui))))

        addon-menu [(ss/action :name "Update all" :key "menu U" :mnemonic "u" :handler (async-handler core/install-update-all))
                    (ss/action :name "Re-install all" :handler (async-handler core/re-install-all))]

        cache-menu [(ss/action :name "Clear cache" :handler (async-handler core/delete-cache))
                    (ss/action :name "Clear addon zips" :handler (async-handler core/delete-downloaded-addon-zips))
                    (ss/action :name "Clear all" :handler (async-handler core/clear-all-temp-files))
                    :separator
                    (ss/action :name "Delete WowMatrix.dat files" :handler (async-handler core/delete-wowmatrix-dat-files))
                    (ss/action :name "Delete .wowman.json files" :handler (async-handler core/delete-wowman-json-files))]

        menu (ss/menubar :items [(ss/menu :text "File" :mnemonic "F" :items file-menu)
                                 (ss/menu :text "Addons" :mnemonic "A" :items addon-menu)
                                 (ss/menu :text "Cache" :items cache-menu)])
        _ (.setJMenuBar newui menu)

        init (fn [_]
               ;; prevents an empty grey screen from appearing while addon summaries are downloaded
               (async/go (core/refresh))
               _)]

    (ss/invoke-later
     (-> newui ss/pack! ss/show! init))

    newui))

(defn start
  []
  (info "starting gui")
  (swap! core/state assoc :gui (start-ui)))

(defn stop
  []
  (info "stopping gui")
  (try
    (ss/dispose! (get-state :gui))
    (catch RuntimeException re
      (warn "failed to stop state:" (.getMessage re)))))

(st/instrument)
