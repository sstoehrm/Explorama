(ns de.explorama.frontend.map.pixi.tiles
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.pixi.projection :as proj]
            [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Sprite Texture]]))

(defn tile-url [template {:keys [z x y]}]
  (-> template
      (str/replace "{z}" (str z))
      (str/replace "{x}" (str x))
      (str/replace "{y}" (str y))))

(defn tile-key [{:keys [z x y]}]
  (str z "/" x "/" y))

(defn- clamp [v lo hi] (-> v (max lo) (min hi)))

(defn visible-tiles
  "Integer-zoom tiles covering the viewport."
  [{:keys [zoom width height] :as vpt}]
  (let [z (js/Math.floor zoom)
        n (js/Math.pow 2 z)
        corners [(vp/->lonlat vpt 0 0)
                 (vp/->lonlat vpt width 0)
                 (vp/->lonlat vpt 0 height)
                 (vp/->lonlat vpt width height)]
        txs (map (fn [[lon lat]] (first (proj/project lon lat))) corners)
        tys (map (fn [[lon lat]] (second (proj/project lon lat))) corners)
        minx (js/Math.floor (* (apply min txs) n))
        maxx (js/Math.floor (* (apply max txs) n))
        miny (js/Math.floor (* (apply min tys) n))
        maxy (js/Math.floor (* (apply max tys) n))]
    (for [x (range minx (inc maxx))
          y (range miny (inc maxy))]
      {:z z :x (mod x n) :y (clamp y 0 (dec n))})))

(def ^:private max-cached-tiles 256)

(defn- tile-sprite [template tile]
  (let [tex (.from Texture (tile-url template tile))
        s (Sprite. tex)]
    (set! (.-anchor.x s) 0)
    (set! (.-anchor.y s) 0)
    s))

(defn- place-tile! [^js sprite vpt tile]
  (let [z (:z tile)
        n (js/Math.pow 2 z)
        [lon lat] (proj/unproject (/ (:x tile) n) (/ (:y tile) n)) ; NW corner
        [sx sy] (vp/->screen vpt lon lat)
        size (* 256 (js/Math.pow 2 (- (:zoom vpt) z)))]
    (set! (.-x sprite) sx)
    (set! (.-y sprite) sy)
    (set! (.-width sprite) size)
    (set! (.-height sprite) size)))

(defn render-tiles!
  "Ensure sprites for visible tiles exist in `container`, positioned for `vpt`.
   `cache` is an atom map of tile-key -> sprite. No-ops when `template` is nil."
  [^js container cache template vpt]
  (when template
    (let [wanted (visible-tiles vpt)
          wanted-keys (set (map tile-key wanted))]
      ;; remove tiles no longer visible (simple cap-based eviction)
      (doseq [[k ^js sprite] @cache
              :when (not (contains? wanted-keys k))]
        (.removeChild container sprite)
        (.destroy sprite)
        (swap! cache dissoc k))
      ;; add/reposition visible tiles
      (doseq [tile wanted
              :let [k (tile-key tile)]]
        (let [sprite (or (get @cache k)
                         (let [s (tile-sprite template tile)]
                           (.addChild container s)
                           (swap! cache assoc k s)
                           s))]
          (place-tile! sprite vpt tile)))
      (when (> (count @cache) max-cached-tiles)
        (doseq [[k ^js sprite] (take (- (count @cache) max-cached-tiles) @cache)]
          (.removeChild container sprite)
          (.destroy sprite)
          (swap! cache dissoc k))))))

(defn clear-tiles!
  "Destroy every cached tile sprite (used when the tile template changes)."
  [^js container cache]
  (doseq [[k ^js sprite] @cache]
    (.removeChild container sprite)
    (.destroy sprite)
    (swap! cache dissoc k)))

(defn attach-tile-layer!
  "on-change is engine/on-change! passed in to avoid a cyclic require.
   Re-reads :tile-template from state on every callback invocation (rather than
   capturing it once) so set-tile-template! takes effect; exposes the sprite
   cache atom in state as :tile-cache so the engine can clear it."
  [engine on-change]
  (let [{:keys [state]} engine
        {:keys [tile-container]} @state
        cache (atom {})]
    (swap! state assoc :tile-cache cache)
    (on-change engine (fn [vpt] (render-tiles! tile-container cache (:tile-template @state) vpt)))))
