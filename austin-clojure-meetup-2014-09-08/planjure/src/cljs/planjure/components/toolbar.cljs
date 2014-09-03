(ns planjure.components.toolbar
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :refer [put! chan <!]]
            [planjure.utils :as utils]
            [planjure.plan :as plan]
            [planjure.appstate :as appstate]
            [planjure.history :as history]))

(def algorithms {:dijkstra {:name "Dijkstra" :fn (utils/time-f plan/dijkstra)}
                 :dfs      {:name "Depth-first" :fn (utils/time-f plan/dfs)}})

;; TODO 1: WRITE CHECKBOX COMPONENT HERE
;;
;; (defn checkbox-component ...)

(defn item-selector-component [cursor owner]
  (reify
    om/IInitState
    (init-state [_]
      {})

    om/IRenderState
    ;; configuration-chan - Config chan to push into.
    ;; tool-kind - The tool kind as specified by the appstate key. e.g.
    ;;             :world-size.
    ;; tool-name - Name of specific tool for this selector. e.g. :small.
    ;; tool-text - Display text.
    ;; is-disabled-fn - Optional fn that returns true when selector should be
    ;;                  disabled.
    (render-state [_ {:keys [configuration-chan tool-kind tool-name tool-text is-disabled-fn]}]
      (let [selected-css (when (= tool-name (tool-kind cursor)) "selected")
            disabled-css (when (and is-disabled-fn (is-disabled-fn)) "disabled")
            css-class (str selected-css " " disabled-css)]
        (dom/span
          #js {:className (str "item-selector " css-class)
               :onClick #(put! configuration-chan {:kind :tool-selector :tool-kind tool-kind :value tool-name})}
          tool-text)))))

(defn editor-component [cursor owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [configuration-chan]}]
      (dom/div
        nil
        (apply dom/div
               #js {:className "button-row"}
               (for [[brush-tool-name {:keys [text]}] (:brush-options cursor)]
                 (om/build item-selector-component
                           {:brush (:brush cursor)}
                           {:init-state {:configuration-chan configuration-chan
                                         :tool-kind :brush
                                         :tool-name brush-tool-name
                                         :tool-text text}})))
        (apply dom/div
               #js {:className "button-row"}
               (for [[size-name {:keys [text]}] (:brush-size-options cursor)]
                 (om/build item-selector-component
                           {:brush-size (:brush-size cursor)}
                           {:init-state {:configuration-chan configuration-chan
                                         :tool-kind :brush-size
                                         :tool-name size-name
                                         :tool-text text}})))))))

(defn size-component [cursor owner]
  (reify
    om/IInitState
    (init-state [_] {})

    om/IRenderState
    (render-state [_ {:keys [configuration-chan]}]
      (let [selected-size (:world-size cursor)]
        (apply dom/div nil
               (for [[size-name {:keys [text] :as size-opts}] (:world-size-options cursor)]
                 (om/build item-selector-component
                           {:world-size (:world-size cursor)}
                           {:init-state {:configuration-chan configuration-chan
                                         :tool-kind :world-size
                                         :tool-name size-name
                                         :tool-text text}})))))))

(defn statistics-component [cursor owner]
  (reify
    om/IInitState
    (init-state [_]
      {})

    om/IWillMount
    (will-mount [_] nil)

    om/IDidMount
    (did-mount [_] nil)

    om/IRender
    (render [_]
      (dom/div
        nil
        (dom/div
          #js {:className :running-time}
          (dom/div nil (str (/ (cursor :last-run-time) 1000) " seconds"))
          (dom/div nil (name (cursor :brush))))))))

(defn toolbar-component [app-state owner]
  (reify
    om/IInitState
    (init-state [_]
      {:plan-chan appstate/plan-chan
       :configuration-chan (chan)})

    om/IWillMount
    (will-mount [_]
      (let [configuration-chan (om/get-state owner :configuration-chan)]
        (go
          (while true
            (let [[v ch] (alts! [appstate/plan-chan configuration-chan])]
              (println v)
              (when (= ch appstate/plan-chan)
                (let [algo-fn ((algorithms (:algo @app-state)) :fn)
                      result (algo-fn (:world @app-state) (:setup @app-state))]
                  ;; Need to @app-state above because cursors (in this case,
                  ;; app-state) are not guaranteed to be consistent outside of the
                  ;; React.js render/render-state cycles.
                  (om/update! app-state :last-run-time (result :time))
                  (om/update! app-state :visited (:visited (result :return)))
                  (om/update! app-state :path (:path (result :return)))))
              (when (= ch configuration-chan)
                (case (:kind v)
                  :algorithm
                  (om/update! app-state :algo (:value v))

                  :tool-selector
                  (case (:tool-kind v)
                    :world-size
                    (let [world-size (:value v)
                          world-num-tiles (get-in @app-state [:world-size-options world-size :size])
                          last-row-col (dec world-num-tiles)]
                      (om/update! app-state :world-size world-size)
                      (om/update! app-state [:setup :finish] [last-row-col last-row-col])
                      (om/update! app-state :path [])
                      (appstate/update-world-state! app-state (plan/random-world world-num-tiles world-num-tiles))
                      (history/reset))

                    :history
                    (case (:value v)
                      :undo
                      (history/undo)
                      :redo
                      (history/redo))

                    ;; Default case
                    (om/update! app-state (:tool-kind v) (:value v)))

                  ;; TODO 3: HANDLE CHECKBOX
                  )))))))

    om/IDidMount
    (did-mount [this] nil)

    om/IRenderState
    (render-state [this {:keys [configuration-chan]}]
      (dom/div
        nil
        (dom/div
          nil
          (dom/div #js {:className "section-title"} "World")
          (dom/div #js {:className "section-wrapper"}
            (om/build size-component
              {:world-size (:world-size app-state) :world-size-options (:world-size-options app-state)}
              {:init-state {:configuration-chan configuration-chan}})))

        (dom/div
          nil
          (dom/div #js {:className "section-title"} "Editor")
          (dom/div #js {:className "section-wrapper"}
            (om/build editor-component
                      {:brush (:brush app-state)
                       :brush-options (:brush-options app-state)
                       :brush-size (:brush-size app-state)
                       :brush-size-options (:brush-size-options app-state)}
                      {:init-state {:configuration-chan configuration-chan}})))

        (dom/div
          nil
          (dom/div #js {:className "section-title"} "History")
          (dom/div #js {:className "section-wrapper"}
                   (dom/div
                     #js {:className "button-row"}
                     (om/build item-selector-component
                               ;; Pass in :world so that button gets
                               ;; enable/disabled on world changes
                               {:history (:history app-state) :world (:world app-state)}
                               {:init-state {:configuration-chan configuration-chan
                                             :tool-kind :history
                                             :tool-name :undo
                                             :tool-text "Undo"
                                             :is-disabled-fn (complement history/undoable)}})
                     (om/build item-selector-component
                               ;; Pass in :world so that button gets
                               ;; enable/disabled on world changes
                               {:history (:history app-state) :world (:world app-state)}
                               {:init-state {:configuration-chan configuration-chan
                                             :tool-kind :history
                                             :tool-name :redo
                                             :tool-text "Redo"
                                             :is-disabled-fn (complement history/redoable)}}))))
        (dom/div
          nil
          (dom/div #js {:className "section-title"} "Algorithm")
          (dom/div #js {:className "section-wrapper"}
            (dom/div
              nil
              (apply dom/select #js {:id "algorithm"
                                     ; use name to convert keyword to string. Easier to deal
                                     ; with strings in DOM, instead of keywords.
                                     :value (name (:algo app-state))
                                     :onChange #(put! (om/get-state owner :configuration-chan)
                                                      ; Grab event's event.target.value, which
                                                      ; will be the selected option.
                                                      ; See: http://facebook.github.io/react/docs/forms.html#why-select-value
                                                      {:kind :algorithm :value (keyword (.. % -target -value))})}

                     ;; Value is the string version of the key name.
                     ;; Display text is the name of the algorithm.
                     (map #(dom/option #js {:value (name (first %))} (:name (last %))) algorithms))

              ;; This uses core.async to communicate between components (namely, on
              ;; click, update the path state).
              ;; https://github.com/swannodette/om/wiki/Basic-Tutorial#intercomponent-communication
              ;; This grabs plan-chan channel, and puts "plan!" in the channel. The
              ;; world canvas component listens to this global channel to plan new paths.
              (dom/button #js {:onClick #(put! (om/get-state owner :plan-chan) "plan!")} "Plan Path"))

            ;; TODO 2: CHECKBOXES GO HERE
            ;; (dom/div ...)
            ))
        (dom/div
          nil
          (dom/div #js {:className "section-title"} "Statistics")
          (dom/div #js {:className "section-wrapper"}
            (om/build statistics-component
                      {:last-run-time (:last-run-time app-state)
                       :brush (:brush app-state)})))))))
