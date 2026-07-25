(ns de.explorama.frontend.map.pixi.vector-layer
  "Draws a `geo/parse-features` feature collection into a single Pixi
   Graphics object, re-rendering the whole thing in screen space on every
   viewport change: a full clear+redraw per call, no incremental diffing.
   Cheap for the feature counts this is used with; very large collections
   may need a caching/diffing strategy instead."
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn- screen-points
  "Ring (`[[wx wy] ...]` world coords) -> flat js array of screen pixels
   `#js [sx1 sy1 sx2 sy2 ...]`, as `drawPolygon`/`drawPolygon`-style Pixi
   calls expect."
  [vpt ring]
  (into-array (mapcat (fn [[wx wy]] (vp/world->screen vpt wx wy)) ring)))

(defn- resolve-style [feature style style-fn]
  (if style-fn
    (style-fn feature)
    style))

(defn- draw-polygon!
  [^js g {:keys [rings]} vpt {:keys [stroke-width stroke-color stroke-alpha
                                      fill-color fill-alpha]}]
  (let [[outer & holes] rings]
    (when (seq outer)
      (.beginFill g (or fill-color 0x000000) (if fill-color (or fill-alpha 1) 0))
      (.lineStyle g (or stroke-width 0) (or stroke-color 0x000000) (or stroke-alpha 1))
      (.drawPolygon g (screen-points vpt outer))
      (doseq [hole holes :when (seq hole)]
        (.beginHole g)
        (.drawPolygon g (screen-points vpt hole))
        (.endHole g))
      (.endFill g))))

(defn- draw-line!
  [^js g {:keys [rings]} vpt {:keys [stroke-width stroke-color stroke-alpha]}]
  (.lineStyle g (or stroke-width 1) (or stroke-color 0x000000) (or stroke-alpha 1))
  (doseq [ring rings :when (seq ring)]
    (let [[[wx0 wy0] & rest-coords] ring
          [sx0 sy0] (vp/world->screen vpt wx0 wy0)]
      (.moveTo g sx0 sy0)
      (doseq [[wx wy] rest-coords
              :let [[sx sy] (vp/world->screen vpt wx wy)]]
        (.lineTo g sx sy))))
  (.lineStyle g 0))

(defn- draw-point!
  [^js g {:keys [rings]} vpt {:keys [fill-color fill-alpha]}]
  (.lineStyle g 0)
  (.beginFill g (or fill-color 0x000000) (or fill-alpha 1))
  (doseq [ring rings
          [wx wy] ring
          :let [[sx sy] (vp/world->screen vpt wx wy)]]
    (.drawCircle g sx sy 4))
  (.endFill g))

(defn draw!
  "Clears `g` and redraws `features` (as produced by `geo/parse-features`)
   into it for viewport `vpt`. `opts` is `{:keys [style style-fn]}`: when
   `style-fn` is given it is called per-feature and a nil result skips that
   feature entirely; otherwise every feature uses the static `style` map.
   Style keys (all optional): `:stroke-width :stroke-color :stroke-alpha
   :fill-color :fill-alpha` - no fill is drawn when `:fill-color` is nil."
  [^js g features vpt {:keys [style style-fn]}]
  (.clear g)
  (doseq [feature features
          :let [st (resolve-style feature style style-fn)]
          :when st]
    (case (:kind feature)
      :polygon (draw-polygon! g feature vpt st)
      :line (draw-line! g feature vpt st)
      :point (draw-point! g feature vpt st)
      nil)))
