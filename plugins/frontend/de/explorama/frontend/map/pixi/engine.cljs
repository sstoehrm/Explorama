(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.markers :as markers]
            [de.explorama.frontend.map.pixi.clustering :as clustering]
            [de.explorama.frontend.map.pixi.picking :as picking]
            ["pixi.js-legacy" :refer [Application Container Graphics]]))

(defn- notify [engine]
  (let [{:keys [state callbacks]} engine
        v (:viewport @state)]
    (doseq [f @callbacks] (f v))))

(defn on-change! [engine f]
  (swap! (:callbacks engine) conj f))

(defn on-pick [engine f]
  (swap! (:pick-callbacks engine) conj f))

(defn get-viewport [engine] (:viewport @(:state engine)))

(defn set-viewport! [engine v]
  (swap! (:state engine) assoc :viewport v)
  (notify engine))

(defn get-markers [engine] (:markers @(:state engine)))

(defn set-markers! [engine markers]
  (swap! (:state engine) assoc :markers markers)
  (notify engine))

(defn fit-markers! [engine]
  (let [{:keys [markers]} @(:state engine)]
    (when (seq markers)
      (let [lons (map :lon markers) lats (map :lat markers)
            bbox [(apply min lons) (apply min lats) (apply max lons) (apply max lats)]]
        (swap! (:state engine) update :viewport vp/fit-extent bbox)
        (notify engine)))))

(defn- render-hover! [engine]
  (let [{:keys [state app]} engine
        {:keys [marker-container marker-texture node-index nodes hovered viewport]} @state
        highlighted (mapv (fn [n]
                             (assoc n :highlighted?
                                    (and (not (:cluster? n)) (= (:id n) hovered))))
                           nodes)]
    (markers/render-nodes! app marker-container marker-texture node-index highlighted viewport)))

(defn- draw-debug-grid! [engine]
  (let [{:keys [state debug]} engine
        {:keys [viewport]} @state
        {:keys [width height]} viewport]
    (.clear debug)
    (.lineStyle debug 1 0x999999 0.5)
    ;; crosshair at centre
    (.moveTo debug (/ width 2) 0) (.lineTo debug (/ width 2) height)
    (.moveTo debug 0 (/ height 2)) (.lineTo debug width (/ height 2))))

(defn- try-cluster-click [engine canvas cx cy]
  (let [{:keys [state]} engine
        {:keys [viewport node-index]} @state
        rect (.getBoundingClientRect canvas)
        sx (- cx (.-left rect))
        sy (- cy (.-top rect))]
    (some (fn [[_ entry]]
            (when (= :cluster (:kind entry))
              (let [node (:node entry)
                    [nx ny] (vp/->screen viewport (:lon node) (:lat node))
                    r 24
                    dx (- nx sx) dy (- ny sy)]
                (when (<= (+ (* dx dx) (* dy dy)) (* r r))
                  (let [lons (map :lon (:members node))
                        lats (map :lat (:members node))
                        bbox [(apply min lons) (apply min lats)
                              (apply max lons) (apply max lats)]]
                    (swap! state update :viewport vp/fit-extent bbox)
                    (notify engine)
                    true)))))
          @node-index)))

(defn- install-events! [engine canvas]
  (let [{:keys [state]} engine
        dragging (atom nil)
        pointers (atom {})
        pinch-dist (atom nil)
        press (atom nil)]
    (.addEventListener
     canvas "wheel"
     (fn [e]
       (.preventDefault e)
       (let [rect (.getBoundingClientRect canvas)
             sx (- (.-clientX e) (.-left rect))
             sy (- (.-clientY e) (.-top rect))
             dz (if (pos? (.-deltaY e)) -1 1)]
         (swap! state update :viewport vp/zoom-around dz sx sy)
         (notify engine)))
     #js {:passive false})
    (.addEventListener
     canvas "pointerdown"
     (fn [e]
       (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)])
       (if (= 1 (count @pointers))
         (do (reset! dragging [(.-clientX e) (.-clientY e)])
             (reset! press [(.-clientX e) (.-clientY e)]))
         ;; a second pointer joined mid-gesture (pinch) - this can no
         ;; longer resolve to a single-pointer "click"
         (reset! press nil)))
     #js {:passive true})
    (.addEventListener
     canvas "pointermove"
     (fn [e]
       (when (contains? @pointers (.-pointerId e))
         (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)]))
       (cond
         ;; two-finger pinch zoom
         (= 2 (count @pointers))
         (let [[[ax ay] [bx by]] (vals @pointers)
               d (js/Math.hypot (- ax bx) (- ay by))
               rect (.getBoundingClientRect canvas)
               mx (- (/ (+ ax bx) 2) (.-left rect))
               my (- (/ (+ ay by) 2) (.-top rect))]
           (when-let [pd @pinch-dist]
             (let [dz (/ (- d pd) 200)]
               (swap! state update :viewport vp/zoom-around dz mx my)
               (notify engine)))
           (reset! pinch-dist d))
         ;; single-pointer drag pan
         @dragging
         (let [[lx ly] @dragging
               dx (- (.-clientX e) lx)
               dy (- (.-clientY e) ly)]
           (reset! dragging [(.-clientX e) (.-clientY e)])
           (swap! state update :viewport vp/pan dx dy)
           (notify engine))
         ;; not dragging/pinching - hover hit-test for highlight
         :else
         (let [rect (.getBoundingClientRect canvas)
               sx (- (.-clientX e) (.-left rect))
               sy (- (.-clientY e) (.-top rect))
               {:keys [nodes viewport hovered]} @state
               picked (picking/pick (map #(assoc % :radius 8) nodes) viewport sx sy)
               hovered-id (when (and picked (not (:cluster? picked))) (:id picked))]
           (when (not= hovered-id hovered)
             (swap! state assoc :hovered hovered-id)
             (render-hover! engine)))))
     #js {:passive true})
    (let [end (fn [e]
                (let [was-single? (= 1 (count @pointers))]
                  (swap! pointers dissoc (.-pointerId e))
                  (when (not= 2 (count @pointers)) (reset! pinch-dist nil))
                  (when (and was-single? @press)
                    (let [[px py] @press
                          moved (js/Math.hypot (- (.-clientX e) px) (- (.-clientY e) py))]
                      (when (< moved 4)
                        (let [rect (.getBoundingClientRect canvas)
                              sx (- (.-clientX e) (.-left rect))
                              sy (- (.-clientY e) (.-top rect))
                              picked (picking/pick (map #(assoc % :radius 8) (:nodes @state))
                                                    (:viewport @state) sx sy)]
                          (doseq [f @(:pick-callbacks engine)] (f picked))
                          ;; a picked single marker wins outright; otherwise fall
                          ;; back to the (larger-radius) cluster hit-test so
                          ;; clicks just outside picking's fixed 8px radius but
                          ;; inside a cluster bubble still zoom.
                          (when (or (nil? picked) (:cluster? picked))
                            (try-cluster-click engine canvas (.-clientX e) (.-clientY e)))))))
                  (reset! press nil)
                  (case (count @pointers)
                    0 (reset! dragging nil)
                    1 (reset! dragging (first (vals @pointers)))
                    nil)))]
      (.addEventListener canvas "pointerup" end #js {:passive true})
      (.addEventListener canvas "pointerleave" end #js {:passive true})
      (.addEventListener canvas "pointercancel" end #js {:passive true}))))

(defn create! [{:keys [canvas viewport tile-template on-viewport-change]}]
  (let [w (.-clientWidth canvas)
        h (.-clientHeight canvas)
        app (Application. (clj->js {:autoStart true
                                    :width w :height h
                                    :backgroundColor 0xEAEAEA
                                    :antialias true
                                    :resolution (or js/window.devicePixelRatio 1)
                                    :autoDensity true
                                    :view canvas}))
        tile-container (Container.)
        marker-container (Container.)
        debug (Graphics.)
        state (atom {:viewport (assoc viewport :width w :height h)
                     :tile-container tile-container
                     :marker-container marker-container
                     :tile-template tile-template
                     :markers []
                     :marker-texture (markers/circle-texture app markers/base-radius)
                     :cluster-cell-px 60
                     :node-index (atom {})
                     :nodes []
                     :hovered nil})
        engine {:app app :state state :debug debug
                :callbacks (atom [])
                :pick-callbacks (atom [])}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) debug)
    (install-events! engine canvas)
    (tiles/attach-tile-layer! engine on-change!)
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture node-index cluster-cell-px]} @state
                        nodes (clustering/cluster markers vpt cluster-cell-px)]
                    (swap! state assoc :nodes nodes)
                    (markers/render-nodes! app marker-container marker-texture node-index nodes vpt))))
    (on-change! engine (fn [_] (draw-debug-grid! engine)))
    (when on-viewport-change
      (on-change! engine on-viewport-change))
    (notify engine)
    engine))
