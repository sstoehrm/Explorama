(ns de.explorama.frontend.map.pixi.tiles
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.pixi.projection :as proj]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

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
