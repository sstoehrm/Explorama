(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.markers :as markers]
            [de.explorama.frontend.map.pixi.clustering :as clustering]
            [de.explorama.frontend.map.pixi.picking :as picking]
            [de.explorama.frontend.map.pixi.settle :as settle]
            ["pixi.js-legacy" :refer [Application Container Graphics]]))

(defn- notify [engine]
  (let [{:keys [state callbacks]} engine]
    (when-not (:batch? @state)
      (let [v (:viewport @state)]
        (doseq [f @callbacks] (f v))))))

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

(defn set-cluster!
  "When bool is false, subsequent renders show every marker as a single node
   (no grid-clustering)."
  [engine bool]
  (swap! (:state engine) assoc :cluster? bool)
  (notify engine))

(defn set-visible-ids!
  "ids-or-nil: set of marker :id to show, or nil to show all. Filtering happens
   before clustering, so hidden markers never contribute to a cluster count."
  [engine ids-or-nil]
  (swap! (:state engine) assoc :visible-ids ids-or-nil)
  (notify engine))

(defn set-highlighted!
  "id-set: set of marker :id to render highlighted (bigger + red outline ring)."
  [engine id-set]
  (swap! (:state engine) assoc :highlighted-ids (or id-set #{}))
  (notify engine))

(defn begin-batch!
  "Suppress notify (and therefore re-render) until end-batch! is called."
  [engine]
  (swap! (:state engine) assoc :batch? true))

(defn end-batch!
  "Resume notify and run exactly one notify for everything changed since
   begin-batch!."
  [engine]
  (swap! (:state engine) assoc :batch? false)
  (notify engine))

(defn set-tile-template!
  "Swap the tile URL template, clamp the current viewport zoom to max-zoom,
   drop all cached tile sprites (they belong to the old template) and notify."
  [engine template max-zoom]
  (let [{:keys [state]} engine]
    (swap! state (fn [s]
                   (-> s
                       (assoc :tile-template template :max-zoom max-zoom)
                       (update-in [:viewport :zoom] min max-zoom))))
    (let [{:keys [tile-container tile-cache]} @state]
      (when tile-cache
        (tiles/clear-tiles! tile-container tile-cache)))
    (notify engine)))

(defn resize!
  "Re-read the canvas' client size, resize the Pixi renderer to match, update
   the viewport's :width/:height and notify."
  [engine]
  (let [{:keys [app state]} engine
        canvas (.-view app)
        w (.-clientWidth canvas)
        h (.-clientHeight canvas)]
    (.resize (.-renderer app) w h)
    (swap! state update :viewport assoc :width w :height h)
    (notify engine)))

(defn destroy!
  "Idempotent: remove the DOM listeners installed by install-events!, cancel
   any pending wheel-gesture-end debounce timer, destroy the Pixi app, mark
   state :destroyed?."
  [engine]
  (let [{:keys [app state listeners wheel-timer]} engine]
    (when-not (:destroyed? @state)
      (let [canvas (.-view app)]
        (doseq [[type f opts] @listeners]
          (.removeEventListener canvas type f opts)))
      (when-let [t @wheel-timer]
        (js/clearTimeout t)
        (reset! wheel-timer nil))
      (.destroy app false #js {:children true})
      (swap! state assoc :destroyed? true))))

(defn- settle-op!
  "Runs `op` (a settle/note-* fn returning [state' fired]) over the engine's
   settle atom and, if any listeners fired, invokes them asynchronously.
   No-ops entirely on a destroyed engine, and re-checks :destroyed? inside the
   setTimeout callback too, since destroy! can happen synchronously between
   the swap! and the deferred callback running."
  [engine op]
  (when-not (:destroyed? @(:state engine))
    (let [settle-atom (:settle engine)
          result (atom nil)]
      (swap! settle-atom (fn [s] (let [[s' fired] (op s)] (reset! result fired) s')))
      (when-let [fired (seq @result)]
        (js/setTimeout (fn []
                          (when-not (:destroyed? @(:state engine))
                            (doseq [f fired] (f))))
                        0)))))

(defn on-render-done!
  "One-shot: f is invoked (asynchronously) the next time the map settles - i.e.
   the next time a render has been requested (via request-render!) and no
   tile loads are in flight."
  [engine f]
  (swap! (:settle engine) settle/add-listener f))

(defn request-render!
  "Marks that a render was requested. If the map is already settled (no tile
   loads pending), fires any on-render-done! listeners asynchronously;
   otherwise they fire once the pending loads finish."
  [engine]
  (settle-op! engine settle/note-render))

(defn- note-load-start! [engine]
  (when-not (:destroyed? @(:state engine))
    (swap! (:settle engine) settle/note-load-start)))

(defn- note-load-end! [engine]
  (settle-op! engine settle/note-load-end))

(defn- render-hover! [engine]
  (let [{:keys [state app]} engine
        {:keys [marker-container marker-texture node-index nodes hovered viewport]} @state
        highlighted (mapv (fn [n]
                             (assoc n :highlighted?
                                    (or (:highlighted? n)
                                        (and (not (:cluster? n)) (= (:id n) hovered)))))
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

(defn fit-members!
  "Fit the viewport to the bounding box of `members` (marker maps with
   :lon/:lat), e.g. a cluster's constituent markers. No-op when members is
   empty."
  [engine members]
  (when (seq members)
    (let [{:keys [state]} engine
          lons (map :lon members)
          lats (map :lat members)
          bbox [(apply min lons) (apply min lats)
                (apply max lons) (apply max lats)]]
      (swap! state update :viewport vp/fit-extent bbox)
      (notify engine))))

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
                  (fit-members! engine (:members node))
                  true))))
          @node-index)))

(defn- install-events! [engine canvas {:keys [do-panning? on-gesture-end on-dbl-pick]}]
  (let [{:keys [state listeners wheel-timer]} engine
        dragging (atom nil)
        pointers (atom {})
        pinch-dist (atom nil)
        press (atom nil)
        last-pick (atom [nil 0])
        add! (fn [type f opts]
               (.addEventListener canvas type f opts)
               (swap! listeners conj [type f opts]))]
    (add!
     "wheel"
     (fn [e]
       (.preventDefault e)
       (let [rect (.getBoundingClientRect canvas)
             sx (- (.-clientX e) (.-left rect))
             sy (- (.-clientY e) (.-top rect))
             dz (cond
                  (pos? (.-deltaY e)) -1
                  (neg? (.-deltaY e)) 1
                  :else 0)]
         (when-not (zero? dz)
           (swap! state update :viewport vp/zoom-around dz sx sy)
           (notify engine)
           (when (and on-gesture-end (not (:destroyed? @state)))
             (when-let [t @wheel-timer] (js/clearTimeout t))
             (reset! wheel-timer
                     (js/setTimeout (fn [] (reset! wheel-timer nil) (on-gesture-end)) 400))))))
     #js {:passive false})
    (add!
     "pointerdown"
     (fn [e]
       (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)])
       (if (= 1 (count @pointers))
         (do (when (or (nil? do-panning?) (do-panning? e))
               (reset! dragging [(.-clientX e) (.-clientY e)]))
             (reset! press [(.-clientX e) (.-clientY e)]))
         ;; a second pointer joined mid-gesture (pinch) - this can no
         ;; longer resolve to a single-pointer "click"
         (reset! press nil)))
     #js {:passive true})
    (add!
     "pointermove"
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
                (let [was-single? (= 1 (count @pointers))
                      was-dragging? (boolean @dragging)
                      was-pinching? (boolean @pinch-dist)]
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
                                                    (:viewport @state) sx sy)
                              now (js/Date.now)
                              pick-id (when (and picked (not (:cluster? picked))) (:id picked))
                              [last-id last-t] @last-pick
                              dbl? (and (some? pick-id)
                                        (= pick-id last-id)
                                        (< (- now last-t) 300))]
                          (if dbl?
                            (do (reset! last-pick [nil 0])
                                (when on-dbl-pick (on-dbl-pick picked e)))
                            (do (reset! last-pick [pick-id now])
                                (doseq [f @(:pick-callbacks engine)] (f picked e))
                                ;; a picked single marker wins outright; otherwise fall
                                ;; back to the (larger-radius) cluster hit-test so
                                ;; clicks just outside picking's fixed 8px radius but
                                ;; inside a cluster bubble still zoom.
                                (when (or (nil? picked) (:cluster? picked))
                                  (try-cluster-click engine canvas (.-clientX e) (.-clientY e)))))))))
                  (reset! press nil)
                  (case (count @pointers)
                    0 (reset! dragging nil)
                    1 (reset! dragging (first (vals @pointers)))
                    nil)
                  (when (and on-gesture-end
                             (zero? (count @pointers))
                             (or was-dragging? was-pinching?))
                    (on-gesture-end))))]
      (add! "pointerup" end #js {:passive true})
      (add! "pointerleave" end #js {:passive true})
      (add! "pointercancel" end #js {:passive true}))))

(defn create!
  [{:keys [canvas viewport tile-template max-zoom on-viewport-change
           on-dbl-pick do-panning? on-gesture-end preserve-drawing-buffer?]}]
  (let [w (.-clientWidth canvas)
        h (.-clientHeight canvas)
        app-opts (cond-> {:autoStart true
                          :width w :height h
                          :backgroundColor 0xEAEAEA
                          :antialias true
                          :resolution (or js/window.devicePixelRatio 1)
                          :autoDensity true
                          :view canvas}
                   preserve-drawing-buffer? (assoc :preserveDrawingBuffer true))
        app (Application. (clj->js app-opts))
        tile-container (Container.)
        marker-container (Container.)
        highlight-g (Graphics.)
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
                     :hovered nil
                     :cluster? true
                     :visible-ids nil
                     :highlighted-ids #{}
                     :batch? false
                     :max-zoom max-zoom
                     :destroyed? false})
        engine {:app app :state state :debug debug
                :callbacks (atom [])
                :pick-callbacks (atom [])
                :listeners (atom [])
                :wheel-timer (atom nil)
                :settle (atom (settle/new-state))}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) highlight-g)
    (.addChild (.-stage app) debug)
    (install-events! engine canvas {:do-panning? do-panning?
                                     :on-gesture-end on-gesture-end
                                     :on-dbl-pick on-dbl-pick})
    (tiles/attach-tile-layer! engine on-change!
                               {:on-load-start! #(note-load-start! engine)
                                :on-load-end! #(note-load-end! engine)})
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture node-index
                                cluster-cell-px cluster? visible-ids highlighted-ids]} @state
                        ms (into []
                                 (comp (filter #(or (nil? visible-ids) (contains? visible-ids (:id %))))
                                       (map #(assoc % :highlighted? (contains? highlighted-ids (:id %)))))
                                 markers)
                        nodes (if cluster?
                                (clustering/cluster ms (:viewport @state) cluster-cell-px)
                                (mapv #(assoc % :cluster? false :count 1) ms))]
                    (swap! state assoc :nodes nodes)
                    (markers/render-nodes! app marker-container marker-texture node-index nodes vpt)
                    (markers/draw-highlight-rings! highlight-g nodes vpt))))
    (on-change! engine (fn [_] (draw-debug-grid! engine)))
    (when on-viewport-change
      (on-change! engine on-viewport-change))
    (notify engine)
    engine))
