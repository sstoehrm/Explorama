(ns de.explorama.frontend.map.pixi.markers
  (:require [de.explorama.frontend.map.pixi.hull :as hull]
            [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Graphics Sprite Text Container]]))

(def ^:const base-radius 6)

(defn circle-texture
  "Bakes a raster texture for `radius` at 2x the device pixel ratio, not just
   1x (generateTexture defaults to the renderer's own resolution otherwise -
   already devicePixelRatio-aware, see engine/create!'s Application opts, but
   exactly 1:1 for a sprite drawn at scale 1). render-nodes! scales sprites up
   to 1.6x for highlighted markers (and would for any future custom :radius >
   base-radius), which upsamples a 1:1 raster into visible blur; the extra 2x
   headroom keeps that supersampled instead. Logical width/height (2*radius)
   are unaffected by the resolution option, so render-nodes!'s scale math
   needs no change.
   The baked rim restores the OpenLayers marker outline (a gray stroke the
   cutover dropped): the texture is white with a translucent dark inner ring,
   so the sprite tint turns the fill into the marker color and the rim into a
   darker shade of the same hue - one tinted sprite, no second draw call."
  [^js app radius]
  (let [g (Graphics.)
        dpr (or js/window.devicePixelRatio 1)]
    (.beginFill g 0xffffff)
    (.drawCircle g radius radius radius)
    (.endFill g)
    (.lineStyle g #js {:width 1.5 :color 0x000000 :alpha 0.45 :alignment 0})
    (.drawCircle g radius radius radius)
    (.lineStyle g 0)
    (.generateTexture (.-renderer app) g #js {:resolution (* 2 dpr)})))

(defn cluster-radius
  "Bubble radius for a cluster of `count` members; public because hover
   hit-testing (engine.cljs) must match the drawn size."
  [count]
  (+ 10 (min 24 (* 4 (js/Math.log count)))))

(defn- color-segments
  "Distinct member colors (fallback black) with counts, sorted by descending
   count (ties by color value) for stable rendering."
  [members]
  (->> (frequencies (map #(or (:color %) 0x000000) members))
       (sort-by (fn [[color n]] [(- n) color]))))

(defn- node-key [node]
  (if (:cluster? node)
    (let [[cx cy] (:cell node)]
      (str "cluster:" cx ":" cy))
    (:id node)))

(defn render-nodes!
  "Render single markers as tinted sprites and clusters as bubble+count.
   `index` maps node-key -> {:kind :marker|:cluster :obj <displayobject> :node <node>}."
  [^js _app ^js container ^js texture index nodes vpt]
  (let [wanted (set (map node-key nodes))]
    (doseq [[k entry] @index
            :when (not (contains? wanted k))]
      (.removeChild container (:obj entry))
      (.destroy (:obj entry) #js {:children true})
      (swap! index dissoc k))
    (doseq [node nodes
            :let [k (node-key node)
                  [sx sy] (vp/->screen vpt (:lon node) (:lat node))]]
      (if (:cluster? node)
        (let [entry (or (get @index k)
                        (let [c (Container.)
                              g (Graphics.)
                              t (Text. "" (clj->js {:fontSize 11 :fill 0x333333 :fontFamily "sans-serif"}))]
                          (set! (.-anchor.x t) 0.5) (set! (.-anchor.y t) 0.5)
                          (.addChild c g) (.addChild c t)
                          (.addChild container c)
                          (let [e {:kind :cluster :obj c :g g :t t :node node}]
                            (swap! index assoc k e) e)))
              ^js g (:g entry)
              ^js t (:t entry)
              r (cluster-radius (:count node))
              total (:count node)
              segs (color-segments (:members node))]
          (.clear g)
          (.beginFill g 0xffffff 0.92) (.drawCircle g 0 0 r) (.endFill g)
          (loop [segs segs a0 (- (/ js/Math.PI 2))]
            (when (seq segs)
              (let [[color n] (first segs)
                    a1 (+ a0 (* (/ n total) 2 js/Math.PI))]
                (.lineStyle g 5 color 1)
                (.moveTo g (* r (js/Math.cos a0)) (* r (js/Math.sin a0)))
                (.arc g 0 0 r a0 a1)
                (recur (rest segs) a1))))
          (.lineStyle g 0)
          (set! (.-text t) (str (:count node)))
          (set! (.-x (:obj entry)) sx) (set! (.-y (:obj entry)) sy)
          (swap! index assoc-in [k :node] node))
        (let [entry (or (get @index k)
                        (let [s (Sprite. texture)]
                          (set! (.-anchor.x s) 0.5) (set! (.-anchor.y s) 0.5)
                          (.addChild container s)
                          (let [e {:kind :marker :obj s :node node}]
                            (swap! index assoc k e) e)))
              ^js s (:obj entry)
              scale (* (/ (or (:radius node) base-radius) base-radius)
                       (if (:highlighted? node) 1.6 1.0))]
          (set! (.-x s) sx) (set! (.-y s) sy)
          (set! (.-tint s) (or (:color node) 0x000000))
          (set! (.-alpha s) (or (:alpha node) 0.7))
          (set! (.-scale.x s) scale)
          (set! (.-scale.y s) scale)
          (swap! index assoc-in [k :node] node))))))

(defn draw-cluster-hover!
  "Hover preview for a cluster: convex hull of the member positions plus,
   when the cluster has at most max-preview members, each member as a dot in
   its own color at its true position. g is a Graphics cleared each call;
   node nil just clears."
  [^js g node vpt max-preview]
  (.clear g)
  (when node
    (let [members (:members node)
          pts (mapv (fn [{:keys [lon lat]}] (vp/->screen vpt lon lat)) members)
          hull-pts (hull/convex-hull pts)]
      (when (>= (count hull-pts) 2)
        (.lineStyle g 2 0x1f6fb5 0.9)
        (.beginFill g 0x1f6fb5 0.08)
        (let [[x0 y0] (first hull-pts)]
          (.moveTo g x0 y0)
          (doseq [[x y] (rest hull-pts)]
            (.lineTo g x y))
          (.closePath g))
        (.endFill g)
        (.lineStyle g 0))
      (when (<= (count members) max-preview)
        (doseq [[m [sx sy]] (map vector members pts)]
          (.beginFill g (or (:color m) 0x000000) 0.85)
          (.drawCircle g sx sy 4)
          (.endFill g))))))

(defn draw-highlight-rings!
  "Red outline around highlighted single markers. g is a Graphics cleared each call."
  [^js g nodes vpt]
  (.clear g)
  (.lineStyle g 2 0xff0000 1)
  (doseq [{:keys [lon lat radius highlighted? cluster?]} nodes
          :when (and highlighted? (not cluster?))]
    (let [[sx sy] (vp/->screen vpt lon lat)
          r (+ 4 (* (/ (or radius 6) base-radius) base-radius))]
      (.drawCircle g sx sy r)))
  (.lineStyle g 0))
