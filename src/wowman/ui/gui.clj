(ns wowman.ui.gui
  (:require
   [wowman
    [core :as core :refer [get-state state-bind state-binds]]
    [logging :as logging]
    [curseforge :as curseforge]
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
    ;;[dev :as ssdebug]
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
  (core/-install-update-these (map curseforge/expand-summary (get-state :selected-search)))
  (switch-tab INSTALLED-TAB)
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
                            :success-fn (handler core/remove-selected))]
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
        update-selected-button (button "Update selected" (handler core/install-update-selected))
        reinstall-button (button "Re-install selected" (async-handler core/re-install-selected))
        reinstall-all-button (button "Re-install all" (async-handler core/re-install-all))

        delete-button (button "Delete selected" (async-handler remove-selected-handler))

        wow-dir-button (button "WoW directory" (async-handler picker))

        wow-dir-label (ss/label :id :wow-dir-lbl :text (or (get-state :cfg :install-dir) "No directory"))
        wow-dir-label-fn (fn [state]
                           (ss/value! wow-dir-label (get-in state [:cfg :install-dir])))]
    (state-bind [:cfg :install-dir] wow-dir-label-fn)
    (ss/vertical-panel
     :items [(ss/flow-panel :align :left :items [refresh-button wow-dir-button wow-dir-label])
             (ss/flow-panel :align :left :items [update-all-button update-selected-button reinstall-all-button reinstall-button delete-button])])))

(defn entry-to-map
  "converts a RowFilter.Entry object to a simple map"
  [entry-obj]
  (let [mdl (.getModel entry-obj)] ;; TableModel
    (into {} (mapv (fn [idx] [(.getColumnName mdl idx) (.getValue entry-obj idx)]) (range 0 (.getColumnCount mdl))))))

(defn installed-addons-panel
  []
  (let [debug-mode (get-state :cfg :debug?)

        debug-cols [:group-id :primary? :addon-id]

        tblmdl (sstbl/table-model :columns [{:key :name :text "addon-id"}
                                            :group-id
                                            :primary?
                                            {:key :label :text "name"}
                                            :description
                                            {:key :updated-date :text "updated"}
                                            {:key :installed-version :text "installed"}
                                            {:key :version :text "available"}
                                            :update?
                                            {:key :interface-version :text "version"}
                                            {:key :category-list :text "categories"}]
                                  :rows [])
        grid (x/table-x :id :tbl-installed-addons :model tblmdl)

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
                          (when datestr
                            (proxy-super setValue (-> datestr clojure.instant/read-instant-date (utils/fmt-date "yyyy-MM-dd"))))))
        _ (.setCellRenderer (.getColumn (.getColumnModel grid) 5) date-renderer)

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

        watch-for-unsteady-addons (fn [_] ;;state]
                                    (let [unsteady (get-state :unsteady-addons)
                                          ;;unsteady (:unstead-addons state) ;; doesn't work for some reason. should it?
                                          ]
                                      (when-not (empty? unsteady)
                                        (debug "unsteady addons:" unsteady)
                                        (ss/invoke-now
                                         (if-let [idx (first (find-row-by-value grid "addon-id" (first unsteady)))]
                                           (select-one grid idx) ;; if the rows are hidden you won't be able to see it
                                           (warn "failed to find value" (first unsteady) "in column 'addon-id'"))))))]

    ;; 'hide' debug columns. affects table only, data/table model is unaffected
    (when-not debug-mode
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
  (let [install-button (button "install selected" (async-handler search-results-install-handler))
        search-input (ss/text :columns 40 :listen [:key-released search-input-handler])]
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

(defn start-ui
  []
  (let [root->splitter->tabber (ss/tabbed-panel
                                :id :tabber
                                :tabs [(tab "installed" (installed-panel))
                                       (tab "search" (search-panel))])

        root->splitter (ss/top-bottom-split root->splitter->tabber (notice-logger))
        _ (.setResizeWeight root->splitter 0.8)

        root (ss/vertical-panel :id :root
                                :items [root->splitter])

        ;;

        newui (ss/frame
               :title "WoW addon updater"
               :size [640 :by 480]
               :content (mig/mig-panel
                         :constraints (if (core/debugging?)
                                        ["debug,flowy"]
                                        ["flowy"])
                         :items [[root "height 100%, width 100%:100%:100%"]])
               :on-close :dispose)

        file-menu [(ss/action :name "Installed" :key "menu I" :mnemonic "i" :handler (switch-tab-handler INSTALLED-TAB))
                   (ss/action :name "Search" :key "menu H" :mnemonic "h" :handler (switch-tab-handler SEARCH-TAB))
                   (ss/action :name "Load settings" :handler (handler core/load-settings))
                   (ss/action :name "Save settings" :key "menu S" :mnemonic "s" :handler (handler core/save-settings))
                   (ss/action :name "Exit" :key "menu Q" :mnemonic "x" :handler (handler #(ss/dispose! newui)))]

        addon-menu [(ss/action :name "Update all" :key "menu U" :mnemonic "u" :handler (handler core/install-update-all))
                    (ss/action :name "Re-install all" :handler (handler core/re-install-all))]

        menu (ss/menubar :items [(ss/menu :text "File" :mnemonic "F" :items file-menu)
                                 (ss/menu :text "Addons" :mnemonic "A" :items addon-menu)])
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
