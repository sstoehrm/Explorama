(ns de.explorama.frontend.map.pixi.viewport
  (:require [de.explorama.frontend.map.pixi.projection :as proj]))

(defn ->screen
  "Pixel position of lon/lat within viewport vp."
  [{:keys [center zoom width height]} lon lat]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        [px py] (proj/project lon lat)]
    [(+ (* (- px cx) s) (/ width 2))
     (+ (* (- py cy) s) (/ height 2))]))

(defn ->lonlat
  "Inverse of ->screen: lon/lat at screen pixel sx,sy."
  [{:keys [center zoom width height]} sx sy]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        px (+ cx (/ (- sx (/ width 2)) s))
        py (+ cy (/ (- sy (/ height 2)) s))]
    (proj/unproject px py)))

(defn pan
  "Drag the map by dx,dy screen pixels."
  [{:keys [center zoom] :as vp} dx dy]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        ncx (- cx (/ dx s))
        ncy (- cy (/ dy s))]
    (assoc vp :center (proj/unproject ncx ncy))))

(defn- clamp [x lo hi] (-> x (max lo) (min hi)))

(defn zoom-around
  "Change zoom by dz keeping the lon/lat under (sx,sy) fixed."
  [{:keys [zoom width height min-zoom max-zoom] :as vp} dz sx sy]
  (let [[lon lat] (->lonlat vp sx sy)
        nz (clamp (+ zoom dz) min-zoom max-zoom)
        s (proj/world-px nz)
        [px py] (proj/project lon lat)
        ncx (- px (/ (- sx (/ width 2)) s))
        ncy (- py (/ (- sy (/ height 2)) s))]
    (assoc vp :zoom nz :center (proj/unproject ncx ncy))))

(defn fit-extent
  "Center + zoom so [min-lon min-lat max-lon max-lat] fits, with ~10% padding."
  [{:keys [width height min-zoom max-zoom] :as vp} [min-lon min-lat max-lon max-lat]]
  (let [[x1 y1] (proj/project min-lon max-lat)      ; north-west
        [x2 y2] (proj/project max-lon min-lat)      ; south-east
        dx (max (js/Math.abs (- x2 x1)) 1e-9)
        dy (max (js/Math.abs (- y2 y1)) 1e-9)
        s (* 0.9 (min (/ width dx) (/ height dy)))  ; px per world-unit that fits
        zoom (clamp (/ (js/Math.log (/ s 256)) (js/Math.log 2)) min-zoom max-zoom)
        center (proj/unproject (/ (+ x1 x2) 2) (/ (+ y1 y2) 2))]
    (assoc vp :zoom zoom :center center)))
