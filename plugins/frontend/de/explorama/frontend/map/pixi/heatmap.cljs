(ns de.explorama.frontend.map.pixi.heatmap
  "Canvas-2D density heatmap, the `simpleheat`/OpenLayers technique: a
   grayscale accumulation pass (one pre-rendered radial blob stamped per
   point with `globalAlpha` = that point's normalized weight, so
   overlapping points saturate toward opaque via the canvas' default
   source-over compositing) followed by a colorize pass that looks up each
   pixel's accumulated alpha byte in a 256-entry gradient LUT for its
   color, keeping that alpha byte itself as the pixel's final alpha - the
   soft glow falloff. Zero new npm dependencies - Pixi only ever sees the
   finished canvas as a `Texture.from` source."
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(def default-radius
  "Solid-core radius (px) of a single point's blob, before blur."
  5)

(def default-blur
  "Extra soft-falloff radius (px) added around the solid core."
  15)

(def default-gradient
  "OL/simpleheat-parity color ramp, blue -> cyan -> green -> yellow -> red."
  ["#0000ff" "#00ffff" "#00ff00" "#ffff00" "#ff0000"])

(def default-gradient-stops
  "Stop positions (0..1) for default-gradient: evenly spaced 0.2 through
   1.0, simpleheat/leaflet.heat-style (not OL's own default gradient, whose
   stops run i/(n-1) starting at 0)."
  [0.2 0.4 0.6 0.8 1.0])

(defn- build-blob-canvas
  "One offscreen canvas holding a single point's grayscale footprint: opaque
   black out to `radius`, fading linearly to transparent black at
   `radius + blur`. Stamped (via `drawImage` + `globalAlpha`) once per point
   onto the accumulation canvas rather than redrawn from scratch each time -
   the 'pre-rendered blob' half of the simpleheat technique."
  [radius blur]
  (let [r (+ radius blur)
        size (* 2 r)
        canvas (.createElement js/document "canvas")
        ctx (.getContext canvas "2d")
        core-t (if (pos? r) (/ radius r) 0)
        gradient (.createRadialGradient ctx r r 0 r r r)]
    (set! (.-width canvas) size)
    (set! (.-height canvas) size)
    (.addColorStop gradient 0 "rgba(0,0,0,1)")
    (.addColorStop gradient core-t "rgba(0,0,0,1)")
    (.addColorStop gradient 1 "rgba(0,0,0,0)")
    (set! (.-fillStyle ctx) gradient)
    (.fillRect ctx 0 0 size size)
    canvas))

(def ^:private blob-canvas
  "Memoized by [radius blur] - render! runs on the pan/zoom hot path and
   the vast majority of calls use the same (default) radius/blur, so
   rebuilding this canvas from scratch every redraw would be wasted work."
  (memoize build-blob-canvas))

(defn- build-gradient-lut
  "256x1 canvas filled with `gradient`/`gradient-stops` via a linear
   CanvasGradient, read back as a flat RGBA `Uint8ClampedArray` of length
   1024 - a direct density-byte -> color lookup table for the colorize
   pass."
  [gradient gradient-stops]
  (let [canvas (.createElement js/document "canvas")
        ctx (.getContext canvas "2d")
        lut-gradient (.createLinearGradient ctx 0 0 256 0)]
    (set! (.-width canvas) 256)
    (set! (.-height canvas) 1)
    (doseq [[stop color] (map vector gradient-stops gradient)]
      (.addColorStop lut-gradient stop color))
    (set! (.-fillStyle ctx) lut-gradient)
    (.fillRect ctx 0 0 256 1)
    (.-data (.getImageData ctx 0 0 256 1))))

(def ^:private gradient-lut
  "Memoized by [gradient gradient-stops] - same rationale as blob-canvas."
  (memoize build-gradient-lut))

(defn- colorize!
  "In-place: remap every pixel's RGB through `lut` (accumulated density
   byte -> this-pixel's color, 4 bytes/entry in `lut` but only the first 3
   - R/G/B - are used). The pixel's own alpha (the density accumulated by
   the grayscale pass) is left untouched: that per-pixel alpha IS the
   glow's soft falloff, so overwriting it with the LUT's own (opaque)
   alpha would flatten every touched pixel to fully opaque, turning the
   soft blobs into hard-edged discs. Alpha-0 pixels (touched by no blob)
   are additionally skipped outright - `simpleheat`'s `if (j)` guard."
  [^js image-data lut]
  (let [data (.-data image-data)
        n (/ (.-length data) 4)]
    (dotimes [i n]
      (let [idx (* i 4)
            a (aget data (+ idx 3))]
        (when (pos? a)
          (let [j (* a 4)]
            (aset data idx (aget lut j))
            (aset data (+ idx 1) (aget lut (+ j 1)))
            (aset data (+ idx 2) (aget lut (+ j 2)))))))))

(defn render!
  "Redraws `canvas` (sized to `vpt`'s width/height, CSS px 1:1 - so its pixels
   line up with every other stage element's screen coordinates) with a
   density heatmap of `points` (`{:wx :wy :weight}`, world coords, weight
   >=0 - negative weights clamp to 0). `weight` is relative to the layer's
   own max weight, so panning/zooming never changes a point's color; a
   layer whose points are all weight<=0 renders as an empty (fully
   transparent) canvas. Points projecting more than `radius+blur` outside
   the viewport are skipped before drawing - cheap, since `world->screen`
   is the only per-point cost paid for them. `opts` (all optional):
   `:radius :blur :gradient :gradient-stops` - see the `default-*` vars.
   Returns `canvas`."
  ([canvas points vpt] (render! canvas points vpt {}))
  ([canvas points vpt {:keys [radius blur gradient gradient-stops]
                        :or {radius default-radius blur default-blur
                             gradient default-gradient
                             gradient-stops default-gradient-stops}}]
   (let [{:keys [width height]} vpt
         ctx (.getContext canvas "2d")]
     (set! (.-width canvas) width)
     (set! (.-height canvas) height)
     (when (and (pos? width) (pos? height) (seq points))
       (let [max-w (reduce (fn [m {:keys [weight]}] (max m 0 (or weight 0))) 0 points)]
         (when (pos? max-w)
           (let [margin (+ radius blur)
                 blob (blob-canvas radius blur)
                 br (+ radius blur)]
             (doseq [{:keys [wx wy weight]} points
                     :let [[sx sy] (vp/world->screen vpt wx wy)]
                     :when (and (>= sx (- margin)) (<= sx (+ width margin))
                                (>= sy (- margin)) (<= sy (+ height margin)))]
               (set! (.-globalAlpha ctx) (min 1 (/ (max 0 (or weight 0)) max-w)))
               (.drawImage ctx blob (- sx br) (- sy br)))
             (set! (.-globalAlpha ctx) 1)
             (let [image-data (.getImageData ctx 0 0 width height)]
               (colorize! image-data (gradient-lut gradient gradient-stops))
               (.putImageData ctx image-data 0 0))))))
     canvas)))
