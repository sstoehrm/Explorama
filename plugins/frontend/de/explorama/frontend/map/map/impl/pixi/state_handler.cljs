(ns de.explorama.frontend.map.map.impl.pixi.state-handler
  (:require [clojure.set :as set]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.config :as config]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.map.impl.pixi.popup-content :as popup-content]
            [de.explorama.frontend.map.map.impl.pixi.stubs :as stubs]
            [de.explorama.frontend.map.map.protocol.state-handler :as proto]
            [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

;;;; Popup DOM overlay ---------------------------------------------------
;;
;; `popup-state` (what to show, or nil) and `tick` (bumped on every engine
;; on-change!, i.e. every pan/zoom/marker update) are both reagent atoms so
;; this component re-renders whenever either the popup content or the
;; viewport changes - the latter is needed purely to recompute the on-screen
;; [sx sy] position of a popup anchored to a lon/lat.

(defn- popup-view [popup-state tick engine-ref hide-fn]
  (fn []
    @tick
    (when-let [{:keys [lat lon html]} @popup-state]
      (when-let [e @engine-ref]
        (let [[sx sy] (vp/->screen (engine/get-viewport e) lon lat)]
          [:div.map-popup {:style {:position "absolute" :left sx :top sy :z-index 20
                                    :pointer-events "auto"
                                    :transform "translate(-50%, calc(-100% - 12px))"
                                    :background "#fff" :border "1px solid #888"
                                    :border-radius "4px" :max-width "320px"
                                    :box-shadow "0 1px 4px rgba(0,0,0,.3)"}}
           [:div {:style {:position "absolute" :top 2 :right 6 :cursor "pointer"}
                  :on-click #(hide-fn)} "×"]
           [:div {:dangerouslySetInnerHTML {:__html html}}]])))))

;;;; Engine boot -----------------------------------------------------------

(defn- create-dom-node! [container tag css-text]
  (let [el (.createElement js/document tag)]
    (set! (.-cssText (.-style el)) css-text)
    (.appendChild container el)
    el))

(defn- resolve-base-layer-desc
  "The current base-layer description, falling back to the first
   \"default\"/\"tms\" layer (notifying once) when the current one is a
   \"wms\"/\"esri\" type the Pixi renderer can't draw yet. May return nil
   (e.g. no base-layers registered at all) - callers treat that as
   \"no tiles\"."
  [frame-id state-map]
  (let [base-layers (:base-layers state-map)
        desc (get base-layers (:current-base-layer state-map))]
    (if (contains? #{"wms" "esri"} (:type desc))
      (do (stubs/notify-unavailable! frame-id :base-layer-type)
          (some (fn [d] (when (contains? #{"default" "tms"} (:type d)) d))
                (vals base-layers)))
      desc)))

(defn- apply-move-to! [engine zoom [lat lon]]
  (engine/set-viewport! engine (assoc (engine/get-viewport engine) :center [lon lat] :zoom zoom)))

(defn- apply-pending-op! [engine [op-name zoom position]]
  (case op-name
    :move-to (apply-move-to! engine zoom position)
    :fit-data (engine/fit-markers! engine)
    nil))

(defn- ensure-engine!
  "Idempotent lazy boot: creates the canvas + popup/attribution overlay DOM
   nodes, the pixi engine and wires picking, only once the frame's body DOM
   node actually exists (i.e. not headless and the frame is mounted).
   Returns the engine, or nil while it can't boot yet."
  [{:keys [frame-id extra-fns state popup-state tick engine-ref]}]
  (or (:engine @state)
      (let [container (.getElementById js/document (config/frame-body-dom-id frame-id))]
        (when (and (not (:headless? @state)) container)
          (let [canvas (create-dom-node! container "canvas"
                                          "position:absolute; inset:0;")
                popup-div (create-dom-node! container "div"
                                             "position:absolute; inset:0; pointer-events:none;")
                attribution-div (create-dom-node!
                                  container "div"
                                  (str "position:absolute; right:4px; bottom:2px;"
                                       "font-size:10px; background:rgba(255,255,255,.7);"
                                       "padding:0 4px; z-index:5;"))
                desc (resolve-base-layer-desc frame-id @state)
                eng (engine/create!
                     {:canvas canvas
                      :tile-template (:tilemap-server-url desc)
                      :max-zoom (or (:max-zoom desc) 19)
                      :preserve-drawing-buffer? true
                      :viewport {:center [0 0] :zoom 2
                                 :min-zoom (or (:min-zoom desc) 1)
                                 :max-zoom (or (:max-zoom desc) 19)}
                      :do-panning? (:do-panning? extra-fns)
                      :on-gesture-end #(when-let [f (:track-view-position-change extra-fns)]
                                         (f true))
                      :on-dbl-pick (fn [node evt]
                                     (when-let [f (:marker-dbl-clicked extra-fns)]
                                       (when-not (:cluster? node)
                                         (f evt (:event-id node)))))})]
            (set! (.-innerHTML attribution-div) (or (:attribution desc) ""))
            (engine/on-pick eng
                            (fn [node evt]
                              (when (and node (not (:cluster? node)))
                                (if (and evt (.-ctrlKey evt))
                                  (when-let [f (:highlight-event extra-fns)]
                                    (f (:event-id node) [(:lat node) (:lon node)]))
                                  (when-let [f (:marker-clicked extra-fns)]
                                    (f (:event-id node) (:color-hex node)
                                       [(:lat node) (:lon node)]
                                       {:center [(:lat node) (:lon node)]
                                        :zoom (:zoom (engine/get-viewport eng))}))))))
            (engine/on-change! eng (fn [_] (swap! tick inc)))
            (reset! engine-ref eng)
            (rdom/render [popup-view popup-state tick engine-ref
                          (fn [] (when-let [f (:hide-popup extra-fns)] (f)))]
                         popup-div)
            (swap! state assoc
                   :engine eng
                   :headless? false
                   :dom {:canvas canvas :popup-div popup-div :attribution-div attribution-div})
            (inst/push-markers! @state)
            (engine/set-cluster! eng (:cluster? @state))
            (engine/set-visible-ids! eng (:visible-ids @state))
            (doseq [f (:pending-render-listeners @state)]
              (engine/on-render-done! eng f))
            (swap! state assoc :pending-render-listeners [])
            (let [[state' pending] (inst/drain-pending @state)]
              (reset! state state')
              (doseq [op pending] (apply-pending-op! eng op)))
            eng)))))

;;;; Protocol-method bodies -------------------------------------------------

(defn- render-map [ctx]
  (when-let [engine (ensure-engine! ctx)]
    (engine/request-render! engine)))

(defn- one-time-render-done-listener [{:keys [state]} f]
  (if-let [engine (:engine @state)]
    (engine/on-render-done! engine f)
    (swap! state update :pending-render-listeners (fnil conj []) f)))

(defn- move-to [{:keys [state]} zoom position]
  (if-let [engine (:engine @state)]
    (apply-move-to! engine zoom position)
    (swap! state inst/enqueue-pending [:move-to zoom position])))

(defn- view-position [{:keys [state]}]
  (if-let [engine (:engine @state)]
    (let [{:keys [center zoom]} (engine/get-viewport engine)
          [lon lat] center]
      {:center [lat lon] :zoom zoom})
    (let [last-move (last (filter #(= :move-to (first %)) (:pending @state)))]
      (if last-move
        (let [[_ zoom position] last-move]
          {:center position :zoom zoom})
        {:center [0 0] :zoom 2}))))

(defn- select-cluster-with-marker [{:keys [state]} marker-id]
  (when-let [engine (:engine @state)]
    (when-let [node (some (fn [n]
                            (when (and (:cluster? n)
                                       (some #(= (:id %) marker-id) (:members n)))
                              n))
                          (:nodes @(:state engine)))]
      (engine/fit-members! engine (:members node)))))

(defn- move-to-marker [{:keys [state extra-fns] :as ctx} marker-id]
  (when-let [entry (get (:marker-data @state) marker-id)]
    (let [[_ location] entry
          [lat lon] (first location)
          max-zoom-fn (:move-data-max-zoom extra-fns)
          fallback-zoom (if max-zoom-fn @(max-zoom-fn) 10)
          current-zoom (:zoom (view-position ctx))
          target-zoom (max (or current-zoom fallback-zoom) fallback-zoom)]
      (move-to ctx target-zoom [lat lon])
      (when (:cluster? @state)
        (select-cluster-with-marker ctx marker-id)))))

(defn- move-to-data [{:keys [state]}]
  (if-let [engine (:engine @state)]
    (engine/fit-markers! engine)
    (swap! state inst/enqueue-pending [:fit-data])))

(defn- set-marker-data [{:keys [state]} marker-data]
  (swap! state assoc :marker-data marker-data)
  (inst/push-markers! @state))

(defn- get-marker-data [{:keys [state]}]
  (:marker-data @state))

(defn- display-marker-cluster [{:keys [state] :as ctx}]
  (swap! state assoc :cluster? true)
  (when-let [engine (:engine @state)]
    (engine/set-cluster! engine true))
  (move-to-data ctx))

(defn- display-markers [{:keys [state] :as ctx}]
  (swap! state assoc :cluster? false)
  (when-let [engine (:engine @state)]
    (engine/set-cluster! engine false))
  (move-to-data ctx))

(defn- update-marker-styles [{:keys [state]} _to-update-ids]
  (inst/push-markers! @state))

(defn- marker-higlighted? [{:keys [state]} marker-id]
  (contains? (:highlighted @state) marker-id))

(defn- list-highlighted-marker [{:keys [state]}]
  (seq (:highlighted @state)))

(defn- highlight-marker [{:keys [state]} marker-id]
  (swap! state update :highlighted (fnil conj #{}) marker-id)
  (inst/push-markers! @state))

(defn- de-highlight-marker [{:keys [state]} marker-id]
  (swap! state update :highlighted disj marker-id)
  (inst/push-markers! @state))

(defn- temp-hide-marker-layer [{:keys [state]}]
  (when-let [engine (:engine @state)]
    (engine/begin-batch! engine)))

(defn- restore-temp-hidden-marker-layer [{:keys [state]}]
  (when-let [engine (:engine @state)]
    (engine/end-batch! engine)))

(defn- hide-markers-with-id [{:keys [state]} ids]
  (let [all-ids (set (keys (:marker-data @state)))
        visible (set/difference all-ids (set ids))]
    (swap! state assoc :visible-ids visible)
    (when-let [engine (:engine @state)]
      (engine/set-visible-ids! engine visible))
    (inst/push-markers! @state)))

(defn- display-all-markers [{:keys [state]}]
  (swap! state assoc :visible-ids nil)
  (when-let [engine (:engine @state)]
    (engine/set-visible-ids! engine nil))
  (inst/push-markers! @state))

(defn- cache-event-data [{:keys [state]} event-id event-data]
  (swap! state assoc-in [:event-cache event-id] event-data))

(defn- cached-event-data [{:keys [state]} event-id]
  (get-in @state [:event-cache event-id]))

(defn- set-feature-data [{:keys [state]} feature-data]
  (swap! state assoc :feature-data feature-data))

(defn- get-feature-data [{:keys [state]} feature-layer-id]
  (get-in @state [:feature-data feature-layer-id]))

(defn- set-filtered-feature-data [{:keys [state]} feature-data]
  (swap! state assoc :filtered-feature-data feature-data))

(defn- get-filtered-feature-data [{:keys [state]} feature-layer-id]
  (get-in @state [:filtered-feature-data feature-layer-id]))

(defn- display-feature-layer [{:keys [frame-id]} _feature-layer-id]
  (stubs/notify-unavailable! frame-id :feature-layer))

(defn- hide-feature-layer [_ctx _feature-layer-id])
(defn- remove-feature-layer [_ctx _feature-layer-id])
(defn- clear-feature-layers [_ctx])
(defn- list-active-feature-layers [_ctx] [])

(defn- display-overlayer [{:keys [frame-id]} _overlayer-id]
  (stubs/notify-unavailable! frame-id :overlayer))

(defn- hide-overlayer [_ctx _overlayer-id])
(defn- list-active-overlayers [_ctx] [])

(defn- switch-base-layer [{:keys [frame-id state]} base-layer-id]
  (let [desc (get (:base-layers @state) base-layer-id)]
    (if (contains? #{"wms" "esri"} (:type desc))
      (stubs/notify-unavailable! frame-id :base-layer-type)
      (do (swap! state assoc :current-base-layer base-layer-id)
          (when-let [engine (:engine @state)]
            (engine/set-tile-template! engine (:tilemap-server-url desc) (or (:max-zoom desc) 19))
            (when-let [attribution-div (get-in @state [:dom :attribution-div])]
              (set! (.-innerHTML attribution-div) (or (:attribution desc) ""))))))))

(defn- resize-map [{:keys [state]}]
  (when-let [engine (:engine @state)]
    (engine/resize! engine)))

(defn- display-popup [{:keys [state popup-state extra-fns]} [lat lon]
                       {:keys [data title-color title-attributes display-attributes]}]
  (when (:engine @state)
    (let [{localize-num-fn :localize-number
           attribute-label-fn :attribute-label} extra-fns
          html (popup-content/gen-popup-content localize-num-fn
                                                 attribute-label-fn
                                                 title-color
                                                 data
                                                 title-attributes
                                                 display-attributes)]
      (if (seq html)
        (reset! popup-state {:lat lat :lon lon :html html})
        (reset! popup-state nil)))))

(defn- hide-popup [{:keys [popup-state]}]
  (reset! popup-state nil))

(defn- destroy-instance [{:keys [frame-id state popup-state]}]
  (when-let [popup-div (get-in @state [:dom :popup-div])]
    (rdom/unmount-component-at-node popup-div))
  (reset! popup-state nil)
  (doseq [node (vals (:dom @state))]
    (when node (.remove node)))
  (when-let [engine (:engine @state)]
    (engine/destroy! engine))
  (inst/unregister! frame-id))

;;;; deftype ----------------------------------------------------------------

(deftype PixiStateHandler [ctx]
  proto/mapStateHandler
  (render-map [_]
    (render-map ctx))
  (one-time-render-done-listener [_ listener-fn]
    (one-time-render-done-listener ctx listener-fn))
  (move-to-data [_]
    (move-to-data ctx))
  (move-to-marker [_ marker-id]
    (move-to-marker ctx marker-id))

  (set-marker-data [_ marker-data]
    (set-marker-data ctx marker-data))
  (get-marker-data [_]
    (get-marker-data ctx))
  (display-marker-cluster [_]
    (display-marker-cluster ctx))
  (display-markers [_]
    (display-markers ctx))
  (update-marker-styles [_ to-update-ids]
    (update-marker-styles ctx to-update-ids))
  (marker-higlighted? [_ marker-id]
    (marker-higlighted? ctx marker-id))
  (list-highlighted-marker [_]
    (list-highlighted-marker ctx))
  (highlight-marker [_ marker-id]
    (highlight-marker ctx marker-id))
  (de-highlight-marker [_ marker-id]
    (de-highlight-marker ctx marker-id))

  (temp-hide-marker-layer [_]
    (temp-hide-marker-layer ctx))
  (restore-temp-hidden-marker-layer [_]
    (restore-temp-hidden-marker-layer ctx))

  (hide-markers-with-id [_ marker-ids]
    (hide-markers-with-id ctx marker-ids))
  (display-all-markers [_]
    (display-all-markers ctx))

  (cache-event-data [_ event-id event-data]
    (cache-event-data ctx event-id event-data))
  (cached-event-data [_ event-id]
    (cached-event-data ctx event-id))

  (set-feature-data [_ feature-data]
    (set-feature-data ctx feature-data))
  (get-feature-data [_ feature-layer-id]
    (get-feature-data ctx feature-layer-id))
  (set-filtered-feature-data [_ feature-data]
    (set-filtered-feature-data ctx feature-data))
  (get-filtered-feature-data [_ feature-layer-id]
    (get-filtered-feature-data ctx feature-layer-id))

  (display-feature-layer [_ feature-layer-id]
    (display-feature-layer ctx feature-layer-id))
  (hide-feature-layer [_ feature-layer-id]
    (hide-feature-layer ctx feature-layer-id))
  (remove-feature-layer [_ feature-layer-id]
    (remove-feature-layer ctx feature-layer-id))
  (clear-feature-layers [_]
    (clear-feature-layers ctx))
  (list-active-feature-layers [_]
    (list-active-feature-layers ctx))

  (display-overlayer [_ overlayer-id]
    (display-overlayer ctx overlayer-id))
  (list-active-overlayers [_]
    (list-active-overlayers ctx))
  (hide-overlayer [_ overlayer-id]
    (hide-overlayer ctx overlayer-id))

  (switch-base-layer [_ base-layer-id]
    (switch-base-layer ctx base-layer-id))

  (resize-map [_]
    (resize-map ctx))
  (move-to [_ zoom position]
    (move-to ctx zoom position))
  (view-position [_]
    (view-position ctx))

  (select-cluster-with-marker [_ marker-id]
    (select-cluster-with-marker ctx marker-id))

  (display-popup [_ position content-desc]
    (display-popup ctx position content-desc))
  (hide-popup [_]
    (hide-popup ctx))

  (destroy-instance [_]
    (destroy-instance ctx)))

(defn create-instance [frame-id object-manager extra-fns]
  (let [state (or (inst/lookup frame-id)
                  (let [s (inst/new-instance-state frame-id extra-fns)]
                    (inst/register! frame-id s)
                    s))
        ctx {:frame-id frame-id
             :object-manager object-manager
             :extra-fns extra-fns
             :state state
             :popup-state (r/atom nil)
             :tick (r/atom 0)
             :engine-ref (atom nil)}]
    (->PixiStateHandler ctx)))
