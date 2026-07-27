(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.tile-source :as tile-source]
            [de.explorama.frontend.map.pixi.markers :as markers]
            [de.explorama.frontend.map.pixi.clustering :as clustering]
            [de.explorama.frontend.map.pixi.picking :as picking]
            [de.explorama.frontend.map.pixi.settle :as settle]
            [de.explorama.frontend.map.pixi.geo :as geo]
            [de.explorama.frontend.map.pixi.vector-layer :as vector-layer]
            [de.explorama.frontend.map.pixi.arrows :as arrows]
            [de.explorama.frontend.map.pixi.heatmap :as heatmap]
            ["pixi.js-legacy" :refer [Application Container Graphics Sprite Texture]]))

(def ^:private arrow-color 0x1b1c1e)
(def ^:private arrow-alpha 0.4)
(def ^:private arrow-hover-color 0x10a3a3)
(def ^:private arrow-hover-alpha 1.0)
(def ^:private arrow-hover-tolerance-sq
  "6px hover tolerance, squared - point-segment-dist-sq returns squared
   distances so callers never pay for a sqrt."
  36)

(defn- notify [engine]
  (let [{:keys [state callbacks]} engine]
    (when-not (:batch? @state)
      (let [v (:viewport @state)]
        (doseq [f @callbacks] (f v))))))

(defn- alive?
  "Guard for public mutators: after destroy! the pixi app and its display
   objects are gone, so a late setter call (e.g. from a queued re-frame
   event racing a frame close) must no-op instead of notifying render
   callbacks into destroyed objects."
  [engine]
  (not (:destroyed? @(:state engine))))

(defn on-change! [engine f]
  (swap! (:callbacks engine) conj f))

(defn on-pick [engine f]
  (swap! (:pick-callbacks engine) conj f))

(defn get-viewport [engine] (:viewport @(:state engine)))

(defn set-viewport! [engine v]
  (when (alive? engine)
    (swap! (:state engine) assoc :viewport v)
    (notify engine)))

(defn get-markers [engine] (:markers @(:state engine)))

(defn set-markers! [engine markers]
  (when (alive? engine)
    (swap! (:state engine) assoc :markers markers)
    (notify engine)))

(defn fit-markers! [engine]
  (let [{:keys [markers]} @(:state engine)]
    (when (and (alive? engine) (seq markers))
      (let [lons (map :lon markers) lats (map :lat markers)
            bbox [(apply min lons) (apply min lats) (apply max lons) (apply max lats)]]
        (swap! (:state engine) update :viewport vp/fit-extent bbox)
        (notify engine)))))

(defn set-cluster!
  "When bool is false, subsequent renders show every marker as a single node
   (no grid-clustering)."
  [engine bool]
  (when (alive? engine)
    (swap! (:state engine) assoc :cluster? bool)
    (notify engine)))

(defn set-visible-ids!
  "ids-or-nil: set of marker :id to show, or nil to show all. Filtering happens
   before clustering, so hidden markers never contribute to a cluster count."
  [engine ids-or-nil]
  (when (alive? engine)
    (swap! (:state engine) assoc :visible-ids ids-or-nil)
    (notify engine)))

(defn set-highlighted!
  "id-set: set of marker :id to render highlighted (bigger + red outline ring)."
  [engine id-set]
  (when (alive? engine)
    (swap! (:state engine) assoc :highlighted-ids (or id-set #{}))
    (notify engine)))

(defn add-vector-layer!
  "Register (or replace, keeping its stacking position) a vector layer under
   id. features are as produced by geo/parse-features; style/style-fn per
   vector-layer/draw!. Invisible by default - callers opt in via
   set-vector-layer-visible!."
  [engine id {:keys [features style style-fn visible?] :or {visible? false}}]
  (when (alive? engine)
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
      (notify engine))))

(defn remove-vector-layer!
  [engine id]
  (let [{:keys [state]} engine
        {:keys [vector-container vector-layers]} @state
        ^js g (:graphics (get vector-layers id))]
    (when (and g (alive? engine))
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
  (when (and (alive? engine)
             (get-in @(:state engine) [:vector-layers id]))
    (swap! (:state engine) update-in [:vector-layers id] assoc :visible? visible?)
    (notify engine)))

(defn- draw-arrow-layer!
  "Clears and redraws `g` with one filled polygon per arrow (arrows/
   arrow-polygon + drawPolygon), target end first per that fn's contract, a
   fixed source-end width of 2. The hovered arrow (matched by :id against
   `hovered-id`, or nothing when nil) is drawn last, in the hover color, so
   it visually wins over any overlapping neighbor."
  [^js g arrows-data vpt hovered-id]
  (.clear g)
  (let [draw! (fn [{:keys [fw tw weight]} hover?]
                (let [[sx sy] (vp/world->screen vpt (first fw) (second fw))
                      [tx ty] (vp/world->screen vpt (first tw) (second tw))
                      verts (arrows/arrow-polygon [tx ty] [sx sy] weight 2)]
                  (when verts
                    (.beginFill g (if hover? arrow-hover-color arrow-color)
                                (if hover? arrow-hover-alpha arrow-alpha))
                    (.drawPolygon g (into-array verts))
                    (.endFill g))))
        hovered (when hovered-id (some #(when (= (:id %) hovered-id) %) arrows-data))]
    (doseq [arrow arrows-data :when (not= (:id arrow) hovered-id)]
      (draw! arrow false))
    (when hovered
      (draw! hovered true))))

(defn add-arrow-layer!
  "Register (or replace, keeping its stacking position) an arrow layer under
   id. arrows is `[{:id :fw [wx wy] :tw [wx wy] :weight :original
   :attribute}]` (world coords). One Graphics per layer, appended to the
   vector container - after any vector (polygon/line/point) layers already
   added, so arrows draw on top of them - and redrawn on every viewport
   change alongside vector layers. Invisible by default - callers opt in
   via set-arrow-layer-visible!."
  [engine id {:keys [arrows visible?] :or {visible? false}}]
  (when (alive? engine)
    (let [{:keys [state]} engine
          {:keys [vector-container arrow-layers]} @state
          ^js g (or (:graphics (get arrow-layers id))
                    (let [g (Graphics.)]
                      (.addChild vector-container g)
                      g))]
      (swap! state assoc-in [:arrow-layers id] {:graphics g :arrows arrows :visible? visible?})
      (notify engine))))

(defn remove-arrow-layer!
  [engine id]
  (let [{:keys [state]} engine
        {:keys [vector-container arrow-layers]} @state
        ^js g (:graphics (get arrow-layers id))]
    (when (and g (alive? engine))
      (.removeChild vector-container g)
      (.destroy g)
      (swap! state update :arrow-layers dissoc id)
      (notify engine))))

(defn set-arrow-layer-visible!
  [engine id visible?]
  (when (and (alive? engine)
             (get-in @(:state engine) [:arrow-layers id]))
    (swap! (:state engine) update-in [:arrow-layers id] assoc :visible? visible?)
    (notify engine)))

(defn add-heatmap-layer!
  "Register (or replace, keeping its canvas/texture/sprite) a heatmap layer
   under id. points is `[{:wx :wy :weight}]` (world coords). One Sprite per
   layer, backed by its own offscreen canvas + `Texture.from` that canvas,
   inserted at the BOTTOM of the vector container (addChildAt 0) so heatmaps
   never obscure polygon/line/point/arrow layers. The canvas is (re)painted
   by heatmap/render! on every viewport change while visible - see
   create!'s heatmap on-change! callback. Invisible by default - callers
   opt in via set-heatmap-layer-visible!."
  [engine id {:keys [points visible?] :or {visible? false}}]
  (when (alive? engine)
    (let [{:keys [state]} engine
          {:keys [vector-container heatmap-layers]} @state
          existing (get heatmap-layers id)
          {:keys [canvas texture sprite]}
          (or existing
              (let [canvas (.createElement js/document "canvas")
                    texture (.from Texture canvas)
                    sprite (Sprite. texture)]
                (set! (.-x sprite) 0)
                (set! (.-y sprite) 0)
                (.addChildAt vector-container sprite 0)
                {:canvas canvas :texture texture :sprite sprite}))]
      (swap! state assoc-in [:heatmap-layers id]
             {:canvas canvas :texture texture :sprite sprite :points points :visible? visible?})
      (notify engine))))

(defn remove-heatmap-layer!
  [engine id]
  (let [{:keys [state]} engine
        {:keys [vector-container heatmap-layers]} @state
        {:keys [sprite texture]} (get heatmap-layers id)]
    (when (and sprite (alive? engine))
      (.removeChild vector-container sprite)
      (.destroy sprite)
      (.destroy texture true)
      (swap! state update :heatmap-layers dissoc id)
      (notify engine))))

(defn set-heatmap-layer-visible!
  [engine id visible?]
  (when (and (alive? engine)
             (get-in @(:state engine) [:heatmap-layers id]))
    (swap! (:state engine) update-in [:heatmap-layers id] assoc :visible? visible?)
    (notify engine)))

(defn on-hover-arrow!
  "f is invoked as (f hit evt) whenever the hovered arrow changes: hit is
   {:layer-id .. :arrow ..} or nil. See install-events!'s pointermove
   handler for the hit-test."
  [engine f]
  (swap! (:hover-arrow-callbacks engine) conj f))

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
  "Swap the tile source (a tile-source desc or a plain xyz URL template
   string - see tile-source/normalize; nil means no tiles), clamp the
   current viewport zoom to max-zoom, drop all cached tile sprites (they
   belong to the old source) and notify."
  [engine template max-zoom]
  (when (alive? engine)
    (let [{:keys [state]} engine
          source (tile-source/normalize template)]
      (swap! state (fn [s]
                     (-> s
                         (assoc :tile-template source :max-zoom max-zoom)
                         (update-in [:viewport :zoom] min max-zoom))))
      (let [{:keys [tile-container tile-cache]} @state]
        (when tile-cache
          (tiles/clear-tiles! tile-container tile-cache)))
      (notify engine))))

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
  (when (alive? engine)
    (let [{:keys [app state]} engine
          canvas (.-view app)
          host (or (.-parentElement canvas) canvas)
          w (.-clientWidth host)
          h (.-clientHeight host)]
      (.resize (.-renderer app) w h)
      (swap! state update :viewport assoc :width w :height h)
      (notify engine))))

(defn destroy!
  "Idempotent: remove the DOM listeners installed by install-events!, cancel
   any pending wheel-gesture-end debounce timer, destroy heatmap textures
   explicitly (app.destroy's `:children true` destroys stage children -
   Graphics, Sprites - but not the BaseTexture wrapping each heatmap
   canvas, which would otherwise leak), destroy the Pixi app, mark
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
      (doseq [[_ {:keys [texture]}] (:heatmap-layers @state)]
        (when texture (.destroy texture true)))
      ;; detach pending tile-texture listeners (they sit on pixi's global
      ;; URL cache and would otherwise outlive this engine) and drop the
      ;; engine-local marker texture
      (let [{:keys [tile-container tile-cache marker-texture]} @state]
        (when tile-cache (tiles/clear-tiles! tile-container tile-cache))
        (when marker-texture (.destroy marker-texture true)))
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
      ;; capturing `fired` via reset! inside the swap! fn is safe only
      ;; because cljs atoms never retry the swap fn (single-threaded) -
      ;; on the JVM this would need a different shape
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

(defn- nearest-arrow
  "Closest arrow to screen point [sx sy] within tol-sq (squared px), across
   every visible layer in arrow-layers (`{id {:arrows [...] :visible?}}`),
   or nil when nothing is close enough. Endpoints are projected to screen
   per candidate via world->screen (arrows carry world coords; the engine
   has no cached screen positions to reuse). Returns
   `{:layer-id .. :arrow ..}`."
  [arrow-layers viewport [sx sy] tol-sq]
  (reduce
   (fn [best [layer-id {:keys [arrows visible?]}]]
     (if-not visible?
       best
       (reduce
        (fn [best {:keys [fw tw] :as arrow}]
          (let [[ax ay] (vp/world->screen viewport (first fw) (second fw))
                [bx by] (vp/world->screen viewport (first tw) (second tw))
                d (arrows/point-segment-dist-sq [sx sy] [ax ay] [bx by])]
            (if (and (<= d tol-sq) (or (nil? best) (< d (:d best))))
              {:d d :layer-id layer-id :arrow arrow}
              best)))
        best arrows)))
   nil arrow-layers))

(defn- render-hover! [engine]
  (let [{:keys [state app]} engine
        {:keys [marker-container marker-texture node-index nodes hovered viewport]} @state
        highlighted (mapv (fn [n]
                             (assoc n :highlighted?
                                    (or (:highlighted? n)
                                        (and (not (:cluster? n)) (= (:id n) hovered)))))
                           nodes)]
    (markers/render-nodes! app marker-container marker-texture node-index highlighted viewport)))

(defn- hover-preview-limit [state-map]
  (let [v (:max-hover-preview state-map)
        v (if (implements? IDeref v) @v v)]
    (or v 100)))

(defn- render-cluster-hover! [engine]
  (let [{:keys [state]} engine
        {:keys [cluster-hover-g nodes hovered-cluster viewport]} @state
        node (when hovered-cluster
               (some #(when (and (:cluster? %) (= (:cell %) hovered-cluster)) %) nodes))]
    (markers/draw-cluster-hover! cluster-hover-g node viewport
                                 (hover-preview-limit @state))))

(defn- hovered-cluster-cell
  "The :cell of the cluster bubble under screen point [sx sy], hit-testing
   against the same radius render-nodes! draws, or nil."
  [nodes viewport sx sy]
  (some (fn [n]
          (when (:cluster? n)
            (let [[nx ny] (vp/->screen viewport (:lon n) (:lat n))
                  r (markers/cluster-radius (:count n))
                  dx (- nx sx) dy (- ny sy)]
              (when (<= (+ (* dx dx) (* dy dy)) (* r r))
                (:cell n)))))
        nodes))

(defn fit-members!
  "Fit the viewport to the bounding box of `members` (marker maps with
   :lon/:lat), e.g. a cluster's constituent markers. No-op when members is
   empty."
  [engine members]
  (when (and (seq members) (alive? engine))
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
               {:keys [nodes viewport hovered arrow-layers hovered-arrow]} @state
               picked (picking/pick (map #(assoc % :radius 8) nodes) viewport sx sy)
               hovered-id (when (and picked (not (:cluster? picked))) (:id picked))]
           (when (not= hovered-id hovered)
             (swap! state assoc :hovered hovered-id)
             (render-hover! engine))
           ;; cluster hover only when no single marker is hovered - hull +
           ;; member preview, matched by grid cell so the same bubble stays
           ;; hovered across redraws.
           (let [next-cell (when-not hovered-id
                             (hovered-cluster-cell nodes viewport sx sy))]
             (when (not= next-cell (:hovered-cluster @state))
               (swap! state assoc :hovered-cluster next-cell)
               (render-cluster-hover! engine)))
           ;; arrow hover only when no marker is hovered - a hit while a
           ;; marker is hovered would fight it for the same tooltip real
           ;; estate, so hovered-id truthy forces hit to nil here, clearing
           ;; any stale arrow hover.
           (let [hit (when-not hovered-id (nearest-arrow arrow-layers viewport [sx sy] arrow-hover-tolerance-sq))
                 next-hovered-arrow (when hit [(:layer-id hit) (:id (:arrow hit))])]
             (when (not= next-hovered-arrow hovered-arrow)
               (swap! state assoc :hovered-arrow next-hovered-arrow)
               (doseq [f @(:hover-arrow-callbacks engine)]
                 (f (when hit {:layer-id (:layer-id hit) :arrow (:arrow hit)}) e))
               (notify engine))))))
     #js {:passive true})
    (let [end (fn [e]
                (when (and (= "pointerleave" (.-type e))
                           (or (:hovered @state) (:hovered-cluster @state)))
                  (swap! state assoc :hovered nil :hovered-cluster nil)
                  (render-hover! engine)
                  (render-cluster-hover! engine))
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
                              ;; a second click on the same marker within
                              ;; 300ms suppresses the single pick even when
                              ;; on-dbl-pick is nil - intentional, so a nil
                              ;; consumer doesn't get two spurious single
                              ;; picks out of one double-click gesture
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
  "tile-template is a tile-source desc, a plain xyz URL template string, or
   nil for no tiles - see tile-source/normalize."
  [{:keys [canvas viewport tile-template max-zoom on-viewport-change
           on-dbl-pick do-panning? on-gesture-end preserve-drawing-buffer?
           max-hover-preview]}]
  (let [tile-template (tile-source/normalize tile-template)
        w (.-clientWidth canvas)
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
        cluster-hover-g (Graphics.)
        highlight-g (Graphics.)
        state (atom {:viewport (assoc viewport :width w :height h)
                     :tile-container tile-container
                     :vector-container vector-container
                     :vector-layers {}
                     :vector-layer-order []
                     :arrow-layers {}
                     :heatmap-layers {}
                     :hovered-arrow nil
                     :marker-container marker-container
                     :tile-template tile-template
                     :markers []
                     :marker-texture (markers/circle-texture app markers/base-radius)
                     :cluster-cell-px 60
                     :node-index (atom {})
                     :nodes []
                     :hovered nil
                     :hovered-cluster nil
                     :cluster-hover-g cluster-hover-g
                     :max-hover-preview max-hover-preview
                     :cluster? true
                     :visible-ids nil
                     :highlighted-ids #{}
                     :batch? false
                     :max-zoom max-zoom
                     :destroyed? false})
        engine {:app app :state state
                :callbacks (atom [])
                :pick-callbacks (atom [])
                :hover-arrow-callbacks (atom [])
                :listeners (atom [])
                :wheel-timer (atom nil)
                :settle (atom (settle/new-state))}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) vector-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) cluster-hover-g)
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
                  (let [{:keys [arrow-layers hovered-arrow]} @state
                        [hovered-layer-id hovered-arrow-id] hovered-arrow]
                    (doseq [[id {:keys [graphics arrows visible?]}] arrow-layers]
                      (if visible?
                        (draw-arrow-layer! graphics arrows vpt
                                           (when (= id hovered-layer-id) hovered-arrow-id))
                        (.clear graphics))))))
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [heatmap-layers]} @state]
                    (doseq [[_ {:keys [canvas texture sprite points visible?]}] heatmap-layers]
                      (set! (.-visible sprite) (boolean visible?))
                      (when visible?
                        (heatmap/render! canvas points vpt)
                        (.update (.-baseTexture texture)))))))
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
                    (markers/draw-highlight-rings! highlight-g nodes vpt)
                    ;; keep the cluster-hover preview glued to its bubble
                    ;; across pan/zoom; a zoom re-bins the grid, so a cell
                    ;; that no longer exists clears the hover instead of
                    ;; ghosting at the old position
                    (when-let [hc (:hovered-cluster @state)]
                      (when-not (some #(and (:cluster? %) (= (:cell %) hc)) nodes)
                        (swap! state assoc :hovered-cluster nil)))
                    (render-cluster-hover! engine))))
    (when on-viewport-change
      (on-change! engine on-viewport-change))
    (notify engine)
    engine))
