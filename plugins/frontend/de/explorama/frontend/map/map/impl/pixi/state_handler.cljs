(ns de.explorama.frontend.map.map.impl.pixi.state-handler
  (:require [clojure.set :as set]
            [goog.string :as gstring]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.config :as config]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.map.impl.pixi.popup-content :as popup-content]
            [de.explorama.frontend.map.map.impl.pixi.stubs :as stubs]
            [de.explorama.frontend.map.map.protocol.state-handler :as proto]
            [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.viewport :as vp]
            [taoensso.timbre :refer-macros [warn]]))

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
          ;; Positional-only inline styles (depend on the current pick/viewport);
          ;; the popup's visual chrome (background/border/border-radius/
          ;; box-shadow) lives in .map-popup in geomap_domain.css instead, so
          ;; it stays theme-aware (dark-mode vars) rather than pinned to a
          ;; fixed light-mode look.
          [:div.map-popup {:style {:position "absolute" :left sx :top sy :z-index 20
                                    :pointer-events "auto"
                                    :transform "translate(-50%, calc(-100% - 12px))"
                                    :max-width "320px"}}
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
  "The current base layer's tile-source desc (see
   de.explorama.frontend.map.pixi.tile-source/normalize) - {:type
   :xyz|:wms|:esri :url .. :wms-layers ..}, translated from the config's
   \"default\"/\"tms\"/\"wms\"/\"esri\" :type string (see
   de.explorama.shared.map.config/default-layers), plus the config's
   :max-zoom/:min-zoom/:attribution passed through unchanged so callers can
   read viewport/attribution settings off the same map. nil when no
   base-layers are registered at all - callers treat that as \"no tiles\"."
  [state-map]
  (let [base-layers (:base-layers state-map)
        {:keys [type tilemap-server-url wms-layers] :as desc}
        (get base-layers (:current-base-layer state-map))]
    (when desc
      (merge (select-keys desc [:max-zoom :min-zoom :attribution])
             (case type
               ("default" "tms") {:type :xyz :url tilemap-server-url}
               "wms" {:type :wms :url tilemap-server-url :wms-layers wms-layers}
               "esri" {:type :esri :url tilemap-server-url})))))

(defn- apply-move-to! [engine zoom [lat lon]]
  (engine/set-viewport! engine (assoc (engine/get-viewport engine) :center [lon lat] :zoom zoom)))

(defn- clamp-zoom-to-max!
  "After a fit-markers!/[:fit-data] zoom-to-data, clamp the resulting viewport
   zoom to (:move-data-max-zoom extra-fns) (an atom, default 10) - fit-markers!
   itself is left with pure fit-to-bbox semantics, this is adapter-side policy."
  [engine extra-fns]
  (when-let [max-zoom-fn (:move-data-max-zoom extra-fns)]
    (let [max-zoom @(max-zoom-fn)
          {:keys [zoom] :as viewport} (engine/get-viewport engine)]
      (when (and max-zoom zoom (> zoom max-zoom))
        (engine/set-viewport! engine (assoc viewport :zoom max-zoom))))))

(defn- apply-pending-op! [engine extra-fns [op-name zoom position]]
  (case op-name
    :move-to (when (inst/valid-move-to? zoom position)
               (apply-move-to! engine zoom position))
    :fit-data (do (engine/fit-markers! engine)
                  (clamp-zoom-to-max! engine extra-fns))
    nil))

(defn- ensure-engine!
  "Idempotent lazy boot: creates the canvas + popup/attribution overlay DOM
   nodes, the pixi engine and wires picking, only once the frame's body DOM
   node actually exists (i.e. not headless and the frame is mounted).
   Returns the engine, or nil while it can't boot yet."
  [{:keys [frame-id extra-fns state popup-state tick engine-ref]}]
  (or (:engine @state)
      (let [container (.getElementById js/document (config/frame-body-dom-id frame-id))]
        ;; The container's existence is authoritative for "can boot now" -
        ;; :headless? (recorded for other bookkeeping) must never block boot
        ;; once the frame is actually mounted, otherwise a frame that starts
        ;; headless and later becomes visible never gets a canvas.
        (when container
          (let [computed-position (.-position (js/getComputedStyle container))]
            (when (= computed-position "static")
              (set! (.. container -style -position) "relative")))
          (let [canvas (create-dom-node! container "canvas"
                                          "position:absolute; inset:0;")
                popup-div (create-dom-node! container "div"
                                             "position:absolute; inset:0; pointer-events:none;")
                attribution-div (create-dom-node!
                                  container "div"
                                  (str "position:absolute; right:4px; bottom:2px;"
                                       "font-size:10px; background:rgba(255,255,255,.7);"
                                       "padding:0 4px; z-index:5;"))
                desc (resolve-base-layer-desc @state)
                eng (engine/create!
                     {:canvas canvas
                      :tile-template desc
                      :max-zoom (or (:max-zoom desc) 19)
                      :preserve-drawing-buffer? true
                      :viewport {:center [0 0] :zoom 2
                                 :min-zoom (or (:min-zoom desc) 1)
                                 :max-zoom (or (:max-zoom desc) 19)}
                      ;; The engine calls do-panning? with the raw PointerEvent,
                      ;; but extra-fns' :do-panning? (de.explorama.frontend.map.map.config/do-panning?)
                      ;; expects a mouse-button NUMBER (1/2/3, see config.cljs);
                      ;; adapt here. e.button is 0/1/2-based (left/middle/right).
                      :do-panning? (fn [e]
                                     (if-let [f (:do-panning? extra-fns)]
                                       (f (inc (.-button e)))
                                       true))
                      :on-gesture-end #(when-let [f (:track-view-position-change extra-fns)]
                                         (f true))
                      :on-dbl-pick (fn [node evt]
                                     (when-let [f (:marker-dbl-clicked extra-fns)]
                                       (when-not (:cluster? node)
                                         (f evt (:event-id node)))))})]
            (set! (.-innerHTML attribution-div) (or (:attribution desc) ""))
            ;; Right/middle drag-panning is the default mouse config, so
            ;; without this the browser's native context menu pops on every
            ;; right-drag pan gesture. Registered via the engine's own
            ;; install-events! listener bookkeeping (the `add!` helper there),
            ;; so destroy! removes it along with the rest - cleaner than
            ;; tracking a second adapter-side listener/cleanup pair here.
            (engine/on-pick eng
                            (fn [node evt]
                              (cond
                                (and node (not (:cluster? node)))
                                ;; select-event?/context-menu-event? (mouse
                                ;; button preferences) are not yet consulted
                                ;; here - deferred, tracked as a follow-up issue.
                                (if (and evt (.-ctrlKey evt))
                                  (when-let [f (:highlight-event extra-fns)]
                                    ;; marker-id string - OL passed the feature's
                                    ;; "id" property here, not the popup event-id.
                                    (f (:id node) [(:lat node) (:lon node)]))
                                  (when-let [f (:marker-clicked extra-fns)]
                                    (let [{:keys [center zoom]} (engine/get-viewport eng)
                                          [lon lat] center]
                                      ;; :event-id (the [bucket id] tuple) is
                                      ;; correct here - popups/details-view key
                                      ;; off it, mirroring the OL behaviour.
                                      (f (:event-id node) (:color-hex node)
                                         [(:lat node) (:lon node)]
                                         {:center [lat lon] :zoom zoom}))))

                                ;; No marker/cluster hit - fall back to vector
                                ;; layers (overlayers + area coloring), topmost
                                ;; visible feature under the click wins.
                                (nil? node)
                                (let [viewport (engine/get-viewport eng)
                                      rect (.getBoundingClientRect canvas)
                                      sx (- (.-clientX evt) (.-left rect))
                                      sy (- (.-clientY evt) (.-top rect))]
                                  (when-let [{:keys [layer-id feature]} (engine/pick-vector-feature eng sx sy)]
                                    (let [kind (get-in @state [:vector-layers layer-id :kind])
                                          [lon lat] (vp/->lonlat viewport sx sy)
                                          {:keys [center zoom]} viewport
                                          [vlon vlat] center
                                          ;; geo/parse-features already dissocs
                                          ;; "geometry" off :properties.
                                          fire! (case kind
                                                  :overlayer (:overlayer-feature-clicked extra-fns)
                                                  :area (:area-feature-clicked extra-fns)
                                                  nil)]
                                      (when fire!
                                        (fire! layer-id (:properties feature)
                                               [lat lon] {:center [vlat vlon] :zoom zoom})))))

                                :else nil)))
            ;; Movement-arrow hover tooltip (OL parity - arrows had hover-only
            ;; interaction, no click). Reuses the shared popup overlay -
            ;; :source :click/:hover tracks which kind is showing so a
            ;; click-opened popup (display-popup, above) both takes
            ;; precedence (a hover hit while it's open is suppressed
            ;; entirely, never overwriting it) and is never stomped by a
            ;; hover-nil once the pointer leaves the arrow.
            (engine/on-hover-arrow!
             eng
             (fn [hit evt]
               (if hit
                 (when-not (= :click (:source @popup-state))
                   (let [{:keys [arrow]} hit
                         {:keys [attribute original]} arrow
                         rect (.getBoundingClientRect canvas)
                         sx (- (.-clientX evt) (.-left rect))
                         sy (- (.-clientY evt) (.-top rect))
                         [lon lat] (vp/->lonlat (engine/get-viewport eng) sx sy)
                         {label-fn :attribute-label localize-num-fn :localize-number} extra-fns
                         label (if label-fn (label-fn attribute) attribute)
                         value (if (number? original)
                                 (if localize-num-fn (localize-num-fn original) original)
                                 (str original))
                         html (str (gstring/htmlEscape (str label)) ": "
                                   (gstring/htmlEscape (str value)))]
                     (reset! popup-state {:lat lat :lon lon :html html :source :hover})))
                 (when (= :hover (:source @popup-state))
                   (reset! popup-state nil)))))
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
            (inst/register-staged-vector-layers! @state eng)
            (engine/set-cluster! eng (:cluster? @state))
            (engine/set-visible-ids! eng (:visible-ids @state))
            (doseq [f (:pending-render-listeners @state)]
              (engine/on-render-done! eng f))
            (swap! state assoc :pending-render-listeners [])
            (let [[state' pending] (inst/drain-pending @state)]
              (reset! state state')
              (doseq [op pending] (apply-pending-op! eng extra-fns op)))
            eng)))))

;;;; Protocol-method bodies -------------------------------------------------

(defn- resize-if-mismatched! [state engine]
  ;; Measure the canvas' parent (the frame body container), not the canvas
  ;; itself: pixi.js-legacy's autoDensity pins the canvas element's own
  ;; inline style width/height on every renderer resize, so canvas
  ;; clientWidth/Height just echo the last-set value back and never reflect
  ;; the container actually changing size (see engine.cljs resize!).
  (when-let [canvas (get-in @state [:dom :canvas])]
    (let [host (or (.-parentElement canvas) canvas)
          cw (.-clientWidth host)
          ch (.-clientHeight host)
          {:keys [width height]} (engine/get-viewport engine)]
      ;; Skip while the container is unmeasured (e.g. display:none) - a 0x0
      ;; resize would just zero out the renderer/viewport. Measuring the host
      ;; (rather than the pixi-pinned canvas) means a frame that booted while
      ;; minimized (0x0) correctly resizes once it's shown again.
      (when (and (pos? cw) (pos? ch)
                 (or (not= cw width) (not= ch height)))
        (engine/resize! engine)))))

(defn- render-map [ctx]
  (when-let [engine (ensure-engine! ctx)]
    (resize-if-mismatched! (:state ctx) engine)
    (engine/request-render! engine)))

(defn- one-time-render-done-listener [{:keys [state]} f]
  (if-let [engine (:engine @state)]
    (engine/on-render-done! engine f)
    (swap! state update :pending-render-listeners (fnil conj []) f)))

(defn- move-to [{:keys [state]} zoom position]
  (when (inst/valid-move-to? zoom position)
    (if-let [engine (:engine @state)]
      (apply-move-to! engine zoom position)
      (swap! state inst/enqueue-pending [:move-to zoom position]))))

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

(defn- move-to-data [{:keys [state extra-fns]}]
  (if-let [engine (:engine @state)]
    (do (engine/fit-markers! engine)
        (clamp-zoom-to-max! engine extra-fns))
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

(defn- vector-layer-kind [state id]
  (get-in @state [:vector-layers id :kind]))

(defn- feature-layer-kind? [state feature-layer-id]
  (contains? #{:area :movement :heatmap} (vector-layer-kind state feature-layer-id)))

(defn- display-feature-layer [{:keys [frame-id state]} feature-layer-id]
  (if (feature-layer-kind? state feature-layer-id)
    (do (inst/set-vector-layer-visible! state feature-layer-id true)
        (swap! state update :active-feature-layers (fnil conj #{}) feature-layer-id))
    ;; Not a real (:area/:movement/:heatmap) vector layer - some other,
    ;; genuinely unknown feature-layer type; still stubbed (see
    ;; object-manager create-feature-layer). The kind check (rather than
    ;; plain registry containment) also keeps this from ever toggling an
    ;; :overlayer entry if id spaces were to collide.
    (stubs/notify-unavailable! frame-id :feature-layer)))

(defn- hide-feature-layer [{:keys [state]} feature-layer-id]
  (case (vector-layer-kind state feature-layer-id)
    :area (do (inst/set-vector-layer-visible! state feature-layer-id false)
              (swap! state update :active-feature-layers disj feature-layer-id))
    (:movement :heatmap) (do (inst/clear-feature-layer-data! state feature-layer-id)
                             (inst/set-vector-layer-visible! state feature-layer-id false)
                             (swap! state update :active-feature-layers disj feature-layer-id))
    nil))

(defn- remove-feature-layer [{:keys [state]} feature-layer-id]
  (when (feature-layer-kind? state feature-layer-id)
    (inst/remove-vector-layer! state feature-layer-id)
    (swap! state update :active-feature-layers disj feature-layer-id)))

(defn- clear-feature-layers [{:keys [state]}]
  (let [feature-ids (into [] (keep (fn [[id {:keys [kind]}]]
                                     (when (#{:area :movement :heatmap} kind) id)))
                          (:vector-layers @state))]
    (doseq [id feature-ids] (inst/remove-vector-layer! state id))
    (swap! state assoc :active-feature-layers #{} :stub-feature-layers #{})))

(defn- list-active-feature-layers [{:keys [state]}]
  (:active-feature-layers @state))

(defn- display-overlayer [{:keys [state]} overlayer-id]
  (when (= :overlayer (vector-layer-kind state overlayer-id))
    (inst/set-vector-layer-visible! state overlayer-id true)
    (swap! state update :active-overlayers (fnil conj #{}) overlayer-id)))

(defn- hide-overlayer [{:keys [state]} overlayer-id]
  (when (= :overlayer (vector-layer-kind state overlayer-id))
    (inst/set-vector-layer-visible! state overlayer-id false)
    (swap! state update :active-overlayers disj overlayer-id)))

(defn- list-active-overlayers [{:keys [state]}]
  (:active-overlayers @state))

(defn- switch-base-layer [{:keys [frame-id state]} base-layer-id]
  (let [config-desc (get (:base-layers @state) base-layer-id)]
    (if (nil? config-desc)
      (warn "switch-base-layer: unknown base-layer id, keeping current layer"
            {:frame-id frame-id :base-layer-id base-layer-id})
      (do (swap! state assoc :current-base-layer base-layer-id)
          (when-let [engine (:engine @state)]
            (let [desc (resolve-base-layer-desc @state)]
              (engine/set-tile-template! engine desc (or (:max-zoom desc) 19))
              (when-let [attribution-div (get-in @state [:dom :attribution-div])]
                (set! (.-innerHTML attribution-div) (or (:attribution desc) "")))))))))

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
        ;; :source :click - never cleared by a hover-arrow-nil callback (see
        ;; ensure-engine!'s on-hover-arrow! wiring), only by an explicit
        ;; hide-popup or another display-popup/hover popup replacing it.
        (reset! popup-state {:lat lat :lon lon :html html :source :click})
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
