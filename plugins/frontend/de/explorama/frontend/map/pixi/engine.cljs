(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.markers :as markers]
            [de.explorama.frontend.map.pixi.clustering :as clustering]
            [de.explorama.frontend.map.pixi.picking :as picking]
            [de.explorama.frontend.map.pixi.settle :as settle]
            [de.explorama.frontend.map.pixi.geo :as geo]
            [de.explorama.frontend.map.pixi.vector-layer :as vector-layer]
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

(defn add-vector-layer!
  "Register (or replace, keeping its stacking position) a vector layer under
   id. features are as produced by geo/parse-features; style/style-fn per
   vector-layer/draw!. Invisible by default - callers opt in via
   set-vector-layer-visible!."
  [engine id {:keys [features style style-fn visible?] :or {visible? false}}]
  (let [{:keys [state]} engine
        {:keys [vector-container vector-layers]} @state
        ^js g (or (:graphics (get vector-layers id))
                  (let [g (Graphics.)]
                    (.addChild vector-container g)
                    g))]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:vector-layers id]
                                 {:graphics g :features features
                                  :style style :style-fn style-fn :visible? visible?})
                       (update :vector-layer-order
                               (fn [order] (if (some #{id} order) order (conj order id)))))))
    (notify engine)))

(defn remove-vector-layer!
  [engine id]
  (let [{:keys [state]} engine
        {:keys [vector-container vector-layers]} @state
        ^js g (:graphics (get vector-layers id))]
    (when g
      (.removeChild vector-container g)
      (.destroy g)
      (swap! state (fn [s]
                     (-> s
                         (update :vector-layers dissoc id)
                         (update :vector-layer-order
                                 (fn [order] (vec (remove #{id} order)))))))
      (notify engine))))

(defn set-vector-layer-visible!
  [engine id visible?]
  (when (get-in @(:state engine) [:vector-layers id])
    (swap! (:state engine) update-in [:vector-layers id] assoc :visible? visible?)
    (notify engine)))

(defn pick-vector-feature
  "Topmost visible polygon feature under screen point sx,sy, or nil. Iterates
   layers last-added-first and features within a layer in reverse order, so
   a feature drawn later (visually on top) wins. A feature the layer's
   style-fn renders invisible (returns nil for it) is not rendered and so
   is not clickable either - matches draw!'s own skip semantics."
  [engine sx sy]
  (let [{:keys [vector-layers vector-layer-order viewport]} @(:state engine)
        [wx wy] (vp/screen->world viewport sx sy)]
    (some (fn [id]
            (let [{:keys [features visible? style-fn]} (get vector-layers id)]
              (when visible?
                (some (fn [feature]
                        (when (and (geo/point-in-feature? feature wx wy)
                                   (or (nil? style-fn) (some? (style-fn feature))))
                          {:layer-id id :feature feature}))
                      (rseq (vec features))))))
          (rseq (vec vector-layer-order)))))

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
  "Re-read the host element's client size, resize the Pixi renderer to match,
   update the viewport's :width/:height and notify.

   Measuring the canvas itself doesn't work: pixi.js-legacy's `autoDensity`
   sets the canvas element's own inline style width/height (in px) at boot and
   on every resize, so canvas.clientWidth/Height just echo pixi's last-set
   value back - the canvas never stretches with its container. The parent
   element (the frame body div we mounted the canvas into) is the thing that
   actually tracks the container's real size."
  [engine]
  (let [{:keys [app state]} engine
        canvas (.-view app)
        host (or (.-parentElement canvas) canvas)
        w (.-clientWidth host)
        h (.-clientHeight host)]
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

(defn note-load-start!
  "Mark an async load (a tile fetch, or a vector-layer geometry fetch - see
   object_manager.cljs's Esri resolution) as in flight, so on-render-done!
   listeners wait for it. Public so adapters outside this ns (which don't
   have their own settle atom) can thread their own async work through the
   same render-done signal; pair with note-load-end!."
  [engine]
  (when-not (:destroyed? @(:state engine))
    (swap! (:settle engine) settle/note-load-start)))

(defn note-load-end!
  "Counterpart to note-load-start! - see its docstring."
  [engine]
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
    ;; Middle/right mouse buttons are the default panning config (see
    ;; map/config.cljs do-panning?), so without suppressing the native
    ;; context menu a right-drag pan pops the browser menu on release.
    ;; Registered through the same add!/listeners bookkeeping as every other
    ;; canvas listener, so destroy! removes it too.
    (add! "contextmenu" (fn [e] (.preventDefault e)) #js {:passive false})
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
        vector-container (Container.)
        marker-container (Container.)
        highlight-g (Graphics.)
        state (atom {:viewport (assoc viewport :width w :height h)
                     :tile-container tile-container
                     :vector-container vector-container
                     :vector-layers {}
                     :vector-layer-order []
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
        engine {:app app :state state
                :callbacks (atom [])
                :pick-callbacks (atom [])
                :listeners (atom [])
                :wheel-timer (atom nil)
                :settle (atom (settle/new-state))}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) vector-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) highlight-g)
    (install-events! engine canvas {:do-panning? do-panning?
                                     :on-gesture-end on-gesture-end
                                     :on-dbl-pick on-dbl-pick})
    (tiles/attach-tile-layer! engine on-change!
                               {:on-load-start! #(note-load-start! engine)
                                :on-load-end! #(note-load-end! engine)})
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [vector-layers vector-layer-order]} @state]
                    (doseq [id vector-layer-order
                            :let [{:keys [graphics features style style-fn visible?]}
                                  (get vector-layers id)]]
                      (if visible?
                        (vector-layer/draw! graphics features vpt {:style style :style-fn style-fn})
                        (.clear graphics))))))
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture node-index
                                cluster-cell-px cluster? visible-ids highlighted-ids]} @state
                        ms (into []
                                 (comp (filter #(or (nil? visible-ids) (contains? visible-ids (:id %))))
                                       ;; OR with the incoming :highlighted? -
                                       ;; style.cljs already bakes the adapter's
                                       ;; highlight-set into each marker before
                                       ;; it reaches the engine; re-assoc'ing
                                       ;; unconditionally from highlighted-ids
                                       ;; here would clobber that.
                                       (map #(assoc % :highlighted?
                                                    (or (:highlighted? %)
                                                        (contains? highlighted-ids (:id %))))))
                                 markers)
                        nodes (if cluster?
                                (clustering/cluster ms (:viewport @state) cluster-cell-px)
                                (mapv #(assoc % :cluster? false :count 1) ms))]
                    (swap! state assoc :nodes nodes)
                    (markers/render-nodes! app marker-container marker-texture node-index nodes vpt)
                    (markers/draw-highlight-rings! highlight-g nodes vpt))))
    (when on-viewport-change
      (on-change! engine on-viewport-change))
    (notify engine)
    engine))
