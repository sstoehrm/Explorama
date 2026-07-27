(ns de.explorama.frontend.map.pixi.tiles
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.pixi.projection :as proj]
            [de.explorama.frontend.map.pixi.tile-source :as tile-source]
            [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Sprite Texture BaseTexture]]))

(defn tile-url [template {:keys [z x y]}]
  (-> template
      (str/replace "{z}" (str z))
      (str/replace "{x}" (str x))
      (str/replace "{y}" (str y))))

(defn tile-key [{:keys [z x y]}]
  (str z "/" x "/" y))

(defn- clamp [v lo hi] (-> v (max lo) (min hi)))

(defn visible-tiles
  "Integer-zoom tiles covering the viewport. :x is left UNWRAPPED (may be < 0
   or >= 2^z) so each copy of the world gets its own placement column and
   cache entry - render-tiles! places sprites using this unwrapped :x and the
   basemap repeats horizontally across the antimeridian. :tx is :x wrapped
   into [0, 2^z) and is what the tile is actually fetched with."
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
      {:z z :x x :tx (mod x n) :y (clamp y 0 (dec n))})))

(def ^:private max-cached-tiles 256)

(defn- tile-sprite
  "on-load-start!/on-load-end! (optional) let a caller track in-flight tile
   texture loads: on-load-start! fires immediately when the texture's
   baseTexture is genuinely pending - not yet :valid AND not :destroyed. A
   destroyed base is a stale cache entry left behind by a previous failed
   load: pixi caches BaseTextures by URL, a failed one stays :valid false
   forever and never re-emits \"loaded\"/\"error\", so counting it as a fresh
   load-start would leave :pending stuck above zero forever (render-done
   permanently dead). on-load-end! fires exactly once, whether the texture
   finishes loading or errors out (guarded so a texture that could somehow
   emit both events only counts as one load). On error, the failed
   Texture/BaseTexture are additionally evicted from pixi's caches so a
   future revisit of the same URL (e.g. panning back over an out-of-coverage
   tile) performs a genuine re-fetch with fresh, observable events instead of
   latching onto the poisoned cache entry.
   `source` is a normalized tile-source desc (see tile-source/normalize) -
   the engine normalizes on the way in, so this fn never re-normalizes.
   Returns a cache entry `{:sprite s :cleanup! f-or-nil}`: cleanup! fires the
   pending load-end (once-guarded, so the settle counter can't get stuck when
   a tile is evicted mid-flight) and detaches both listeners from the shared
   BaseTexture - without it, an evicted or destroyed engine leaves .once
   listeners lingering on pixi's global URL cache."
  [source tile on-load-start! on-load-end!]
  (let [url (tile-source/source-url source {:z (:z tile) :x (:tx tile) :y (:y tile)})
        tex (.from Texture url)
        base (.-baseTexture tex)
        s (Sprite. tex)
        cleanup!
        (when (and on-load-start! (not (.-valid base)) (not (.-destroyed base)))
          (on-load-start!)
          (let [done? (atom false)
                end! (fn []
                       (when (compare-and-set! done? false true)
                         (on-load-end!)))
                on-error! (fn []
                            (end!)
                            (.removeFromCache Texture url)
                            (.removeFromCache BaseTexture url))]
            (.once base "loaded" end!)
            (.once base "error" on-error!)
            (fn []
              (end!)
              (.off base "loaded" end!)
              (.off base "error" on-error!))))]
    (set! (.-anchor.x s) 0)
    (set! (.-anchor.y s) 0)
    {:sprite s :cleanup! cleanup!}))

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
   `cache` is an atom map of tile-key -> sprite. No-ops when `template` is nil.
   `opts` (optional) may carry `{:on-load-start! f :on-load-end! f}`, threaded
   through to newly-created sprites so a caller can track in-flight loads."
  ([^js container cache template vpt] (render-tiles! container cache template vpt nil))
  ([^js container cache template vpt {:keys [on-load-start! on-load-end!]}]
   (when template
     (let [evict! (fn [k {:keys [^js sprite cleanup!]}]
                    (when cleanup! (cleanup!))
                    (.removeChild container sprite)
                    (.destroy sprite)
                    (swap! cache dissoc k))
           wanted (visible-tiles vpt)
           wanted-keys (set (map tile-key wanted))]
       ;; remove tiles no longer visible (simple cap-based eviction)
       (doseq [[k entry] @cache
               :when (not (contains? wanted-keys k))]
         (evict! k entry))
       ;; add/reposition visible tiles
       (doseq [tile wanted
               :let [k (tile-key tile)]]
         (let [entry (or (get @cache k)
                         (let [e (tile-sprite template tile on-load-start! on-load-end!)]
                           (.addChild container (:sprite e))
                           (swap! cache assoc k e)
                           e))]
           (place-tile! (:sprite entry) vpt tile)))
       (when (> (count @cache) max-cached-tiles)
         (doseq [[k entry] (take (- (count @cache) max-cached-tiles) @cache)]
           (evict! k entry)))))))

(defn clear-tiles!
  "Detach every cached tile's pending texture listeners and destroy its
   sprite (used when the tile template changes and on engine destroy)."
  [^js container cache]
  (doseq [[k {:keys [^js sprite cleanup!]}] @cache]
    (when cleanup! (cleanup!))
    (.removeChild container sprite)
    (.destroy sprite)
    (swap! cache dissoc k)))

(defn attach-tile-layer!
  "on-change is engine/on-change! passed in to avoid a cyclic require.
   Re-reads :tile-template from state on every callback invocation (rather than
   capturing it once) so set-tile-template! takes effect; exposes the sprite
   cache atom in state as :tile-cache so the engine can clear it.
   opts (optional): {:on-load-start! f :on-load-end! f}, forwarded to
   render-tiles! so the engine can track in-flight tile loads for its
   render-done signal."
  ([engine on-change] (attach-tile-layer! engine on-change nil))
  ([engine on-change opts]
   (let [{:keys [state]} engine
         {:keys [tile-container]} @state
         cache (atom {})]
     (swap! state assoc :tile-cache cache)
     (on-change engine (fn [vpt] (render-tiles! tile-container cache (:tile-template @state) vpt opts))))))
