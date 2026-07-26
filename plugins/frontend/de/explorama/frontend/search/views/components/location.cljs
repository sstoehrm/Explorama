(ns de.explorama.frontend.search.views.components.location
  (:require [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.frontend.ui-base.components.formular.core :refer [button]]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [de.explorama.frontend.search.config :refer [geo-config]]
            [de.explorama.frontend.search.views.components.location-region :as location-region]
            [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.viewport :as viewport]))

;; ---------------------------------------------------------------------------
;; Tailwind class stacks for the "Map integration (geographical filtering)"
;; rule family. This is the single owner of this rule family; nothing else in
;; the app selects on these classes.
;;
;; `map-input` and `hint-text` are kept as bare literal classes alongside the
;; utility stacks: `styles/src/css/base/_themes.css` still selects
;; `.map-input .hint-text` under `@media (forced-colors: active)` to force
;; Windows High-Contrast-Mode text color, so both literal tokens must stay in
;; the DOM even though normal-mode styling is fully expressed as utilities.
;; `.map-input` always carries the literal "unselected" class -- it marks the
;; passive/collapsed branch of `location-input` (as opposed to the editing
;; overlay), and that branch IS the condition; the `unselected`-driven
;; declarations (`hint-text` opacity, the `::before` overlay tint) are baked
;; in as unconditional utilities on this element rather than behind a
;; synthetic always-true flag.
(def ^:private map-container-classes
  "w-full h-full relative")

(def ^:private map-actions-classes
  "absolute bottom-0 left-0 right-0 m-2.5 flex flex-row justify-between")

(def ^:private map-actions-group-classes
  "flex gap-1")

(def ^:private map-hint-classes
  (str "absolute top-2.5 right-16 bottom-auto left-16 py-[0.5em] px-[0.75em] "
       "rounded-sm text-white bg-[rgba(13.1,14.6,15.3,0.8)] text-xs text-center"))

;; `[&>div]:` targets the mounted engine host div (rendered by
;; `location-react-comp`, one persistent instance shared by both the
;; collapsed thumbnail and the expanded overlay -- see `location-input`,
;; which keeps it at the same child position across both so React never
;; unmounts it (and with it the Pixi engine + WebGL context + tile cache) on
;; a plain collapse/expand toggle). `ease-[ease]` on every transition below:
;; the SCSS-era source's `transition: <props> 120ms;` never named a
;; timing-function, so it defaulted to the CSS initial `ease` -- Tailwind's
;; bare `duration-[120ms]` instead defaults to
;; `--default-transition-timing-function` (a different cubic-bezier curve),
;; so `ease-[ease]` is required to match. This mirrors the established
;; pattern for the same situation elsewhere in this codebase (e.g.
;; woco/presentation/sidebar.cljs, ui_base/formular/collapsible_list.cljs,
;; configuration/views/data_management/geographic_attributes.cljs all use
;; `transition-[prop] duration-[Nms] ease-[ease]` for the same reason).
(def ^:private map-input-classes
  (str "map-input relative border border-gray-300 rounded-xs overflow-hidden shadow-xs "
       "bg-white cursor-pointer transition-[border-color,box-shadow] duration-[120ms] ease-[ease] "
       "group hover:border-gray-400 hover:shadow-sm "
       "before:content-[''] before:absolute before:inset-0 "
       "before:bg-[rgba(255,255,255,0.5)] before:z-[1] "
       "before:transition-[background-color] before:duration-[120ms] before:ease-[ease] "
       "[&>div]:m-auto [&>div]:p-0.5 [&>div]:rounded-lg [&>div]:overflow-hidden "
       "[&_.ol-zoom]:hidden"))

;; opacity-100 baked in (see note above); group-hover:text-gray-900 mirrors
;; a genuine parent-hover-affects-child rule, translated as group/group-hover.
(def ^:private hint-text-classes
  (str "hint-text flex items-center justify-center absolute inset-0 z-[2] opacity-100 "
       "text-gray-800 transition-[color,opacity] duration-[120ms] ease-[ease] group-hover:text-gray-900"))

;; ---------------------------------------------------------------------------
;; Engine wiring

(def ^:private region-layer-id "region")

(def ^:private region-style
  {:stroke-width 2 :stroke-color 0x1f77b4 :stroke-alpha 0.9
   :fill-color 0x1f77b4 :fill-alpha 0.15})

(defn- draw-region! [eng values]
  (engine/add-vector-layer! eng region-layer-id
                             {:features [(location-region/values->feature values)]
                              :style region-style
                              :visible? true}))

(defn- clear-region! [eng]
  (engine/remove-vector-layer! eng region-layer-id))

(defn- valid-region [values]
  (let [values (filterv number? values)]
    (when (= 4 (count values)) values)))

(defn- sync-layer!
  "Redraw the region layer for `values`, or clear it when `values` isn't a
   valid stored region. Never touches the viewport -- used by Cancel, which
   reverts the drawn region but must not also re-fit the view."
  [eng values]
  (if-let [v (valid-region values)]
    (draw-region! eng v)
    (clear-region! eng)))

(defn- sync-region!
  "Fit the viewport to `values` and draw it (or clear the layer when there's
   nothing valid to show), without moving the view otherwise. Used at boot
   and every time the widget re-expands, since the engine is now a single
   persistent instance -- see the `[&>div]:` comment above -- rather than
   something reconstructed on every reopen."
  [eng values]
  (when-let [v (valid-region values)]
    (engine/set-viewport! eng (viewport/fit-extent (engine/get-viewport eng)
                                                    (location-region/values->extent v))))
  (sync-layer! eng values))

(defn- new-engine-instance
  "Boots the Pixi engine on `canvas` (once, for the widget's whole lifetime)
   and wires up the two-click rectangle draw flow. `init-value` seeds the
   initial view/region per `sync-region!`. Returns `{:engine :canvas
   :pointermove-handler :cancel-draw!}` for `location-react-comp`'s unmount
   cleanup and the action buttons' draw-abort handling."
  [canvas rect-state internal-state init-value]
  (let [max-zoom (or (:maxZoom geo-config) 18)
        eng (engine/create!
             {:canvas canvas
              :tile-template (get-in geo-config [:source :url])
              :max-zoom max-zoom
              :preserve-drawing-buffer? false
              :do-panning? (fn [_evt] (not @rect-state))
              ;; Historical OL default was center [0 0] zoom 0 (fully
              ;; zoomed out world view); the engine's zoom floor is
              ;; min-zoom 1, so zoom 1 is the closest equivalent.
              :viewport {:center [0 0] :zoom 1 :min-zoom 1 :max-zoom max-zoom}})
        draw-start (atom nil)
        pointermove-handler
        (fn [e]
          (when (and @rect-state @draw-start)
            (let [corner-a @draw-start
                  rect (.getBoundingClientRect canvas)
                  sx (- (.-clientX e) (.-left rect))
                  sy (- (.-clientY e) (.-top rect))
                  [lon lat] (viewport/->lonlat (engine/get-viewport eng) sx sy)]
              (draw-region! eng (location-region/corners->values corner-a [lat lon])))))]
    (.addEventListener canvas "pointermove" pointermove-handler)
    (engine/on-pick eng
                    (fn [_node evt]
                      (when @rect-state
                        (let [rect (.getBoundingClientRect canvas)
                              sx (- (.-clientX evt) (.-left rect))
                              sy (- (.-clientY evt) (.-top rect))
                              [lon lat] (viewport/->lonlat (engine/get-viewport eng) sx sy)]
                          (if-let [corner-a @draw-start]
                            (let [values (location-region/corners->values corner-a [lat lon])]
                              (draw-region! eng values)
                              (reset! draw-start nil)
                              (reset! internal-state values)
                              (reset! rect-state false))
                            (reset! draw-start [lat lon]))))))
    (sync-region! eng init-value)
    {:engine eng :canvas canvas :pointermove-handler pointermove-handler
     :cancel-draw! (fn [] (reset! draw-start nil))}))

(defn- resize-soon!
  "engine/resize! on the next animation frame (host size changed and the
   engine measures the host element); `then`, if given, runs right after
   with the engine, e.g. to re-fit the view once the resize has landed."
  ([instance] (resize-soon! instance nil))
  ([instance then]
   (js/requestAnimationFrame
    (fn []
      (when-let [eng (:engine @instance)]
        (engine/resize! eng)
        (when then (then eng)))))))

(defn- location-react-comp [dom-id instance alter-state init-value rect-state internal-state]
  (let [canvas-ref (atom nil)]
    (reagent/create-class
     {:display-name dom-id
      :reagent-render
      (fn []
        [:div {:id dom-id
               :style (merge {:position "relative"}
                              (if @alter-state
                                {:width "100%" :height "100%"}
                                {:width 244 :height 50}))}
         [:canvas {:ref (fn [el] (reset! canvas-ref el))
                   :style {:position "absolute" :top 0 :left 0 :right 0 :bottom 0}}]])
      :component-did-mount
      (fn [_]
        (reset! instance
                (new-engine-instance @canvas-ref rect-state internal-state init-value)))
      :should-component-update
      (fn [_ _ _] false)
      :component-will-unmount
      (fn [_]
        (let [{:keys [engine canvas pointermove-handler]} @instance]
          (when canvas
            (.removeEventListener canvas "pointermove" pointermove-handler))
          (when engine
            (engine/destroy! engine))))})))

(defn location-input [{:keys [path frame-id on-change extra-style child]}]
  (let [dom-id (str path frame-id "-loc")
        instance (atom nil)
        rect-state (reagent/atom false)
        alter-state (reagent/atom false)
        internal-state (atom nil)
        ui-selection (re-frame/subscribe [:de.explorama.frontend.search.views.formdata/ui-selection path])]
    (fn [_]
      (let [expanded? @alter-state
            passive-label @(re-frame/subscribe [::i18n/translate :search-location-select])
            {apply :search-location-apply
             cancel :search-location-cancel
             hint :search-location-hint
             select-location-tooltip :search-select-location-tooltip
             reset-selection-tooltip :search-reset-location-selection-tooltip}
            @(re-frame/subscribe [::i18n/translate-multi
                                  :search-location-apply
                                  :search-location-cancel
                                  :search-location-hint
                                  :search-select-location-tooltip
                                  :search-reset-location-selection-tooltip])]
        [:<>
         ;; `location-react-comp` sits at the SAME child position (index 0)
         ;; of the SAME outer :div element regardless of `expanded?` -- only
         ;; that div's own class/style/on-click and its LATER siblings
         ;; change. React's reconciliation matches same-type elements at the
         ;; same position without remounting, so collapsing/expanding is a
         ;; pure style change; the engine (and its WebGL context + tile
         ;; cache) survives the toggle. See `sync-region!`/`resize-soon!`
         ;; below for how a stored region is kept in sync across reopens now
         ;; that boot-time seeding alone (`new-engine-instance`'s
         ;; `sync-region!` call) no longer re-runs on every expand.
         [:div {:class (if expanded?
                         (str "overlay " map-container-classes)
                         map-input-classes)
                :style (when expanded? extra-style)
                :on-click (when-not expanded?
                            (fn []
                              (reset! alter-state true)
                              (resize-soon! instance
                                            (fn [eng]
                                              (sync-region! eng (or @internal-state @ui-selection))))))}
          [location-react-comp
           dom-id instance alter-state (or @internal-state @ui-selection)
           rect-state internal-state]
          (when-not expanded?
            [:span {:class hint-text-classes} passive-label])
          (when expanded?
            [:div {:class map-actions-classes}
             [:div {:class map-actions-group-classes}
              [button {:title select-location-tooltip
                       :variant (if @rect-state
                                  :primary
                                  :secondary)
                       :disabled? @rect-state
                       :start-icon :select
                       :on-click (fn []
                                   (when-let [inst @instance]
                                     ((:cancel-draw! inst)))
                                   (reset! rect-state true))}]
              [button {:title reset-selection-tooltip
                       :variant :secondary
                       :start-icon :reset
                       :on-click (fn []
                                   (re-frame/dispatch [:de.explorama.frontend.search.views.formdata/add-data-for-attr path :ui-selection nil])
                                   (re-frame/dispatch [:de.explorama.frontend.search.views.formdata/add-data-for-attr path :values nil])
                                   (reset! rect-state false)
                                   (reset! internal-state nil)
                                   (when-let [inst @instance]
                                     ((:cancel-draw! inst))
                                     (clear-region! (:engine inst))))}]]
             [:div {:class map-actions-group-classes}
              [button {:label apply
                       :disabled? (not @internal-state)
                       :variant :secondary
                       :start-icon :check
                       :on-click
                       (fn []
                         (let [values @internal-state]
                           (reset! alter-state false)
                           (reset! rect-state false)
                           (when-let [inst @instance]
                             ((:cancel-draw! inst)))
                           (re-frame/dispatch [:de.explorama.frontend.search.views.formdata/add-data-for-attr path :ui-selection values])
                           (re-frame/dispatch [:de.explorama.frontend.search.views.formdata/add-data-for-attr path :values values])
                           (on-change)
                           (resize-soon! instance)))}]
              [button {:label cancel
                       :variant :secondary
                       :start-icon :close
                       :on-click
                       (fn []
                         (reset! rect-state false)
                         (let [stored @ui-selection]
                           (reset! internal-state stored)
                           (when-let [inst @instance]
                             ((:cancel-draw! inst))
                             (sync-layer! (:engine inst) stored)))
                         (reset! alter-state false)
                         (on-change)
                         (resize-soon! instance))}]]])
          (when (and expanded? @rect-state)
            [:div {:class map-hint-classes} hint])]
         child]))))
