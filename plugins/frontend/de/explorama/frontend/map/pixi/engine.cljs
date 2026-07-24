(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.markers :as markers]
            [de.explorama.frontend.map.pixi.clustering :as clustering]
            ["pixi.js-legacy" :refer [Application Container Graphics]]))

(defn- notify [engine]
  (let [{:keys [state callbacks]} engine
        v (:viewport @state)]
    (doseq [f @callbacks] (f v))))

(defn on-change! [engine f]
  (swap! (:callbacks engine) conj f))

(defn get-viewport [engine] (:viewport @(:state engine)))

(defn set-viewport! [engine v]
  (swap! (:state engine) assoc :viewport v)
  (notify engine))

(defn get-markers [engine] (:markers @(:state engine)))

(defn set-markers! [engine markers]
  (swap! (:state engine) assoc :markers markers)
  (notify engine))

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
       (when (= 1 (count @pointers))
         (reset! dragging [(.-clientX e) (.-clientY e)])
         (reset! press [(.-clientX e) (.-clientY e)])))
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
           (notify engine))))
     #js {:passive true})
    (let [end (fn [e]
                (let [was-single? (= 1 (count @pointers))]
                  (swap! pointers dissoc (.-pointerId e))
                  (when (not= 2 (count @pointers)) (reset! pinch-dist nil))
                  (when (and was-single? @press)
                    (let [[px py] @press
                          moved (js/Math.hypot (- (.-clientX e) px) (- (.-clientY e) py))]
                      (when (< moved 4)
                        (try-cluster-click engine canvas (.-clientX e) (.-clientY e)))))
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
                     :node-index (atom {})})
        engine {:app app :state state :debug debug :callbacks (atom [])}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) debug)
    (install-events! engine canvas)
    (tiles/attach-tile-layer! engine on-change!)
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture node-index cluster-cell-px]} @state
                        nodes (clustering/cluster markers vpt cluster-cell-px)]
                    (markers/render-nodes! app marker-container marker-texture node-index nodes vpt))))
    (on-change! engine (fn [_] (draw-debug-grid! engine)))
    (when on-viewport-change
      (on-change! engine on-viewport-change))
    (notify engine)
    engine))
