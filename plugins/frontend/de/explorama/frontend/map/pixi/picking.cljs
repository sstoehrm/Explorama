(ns de.explorama.frontend.map.pixi.picking
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn pick
  "First item whose screen position is within (:radius item) px of (sx,sy)."
  [items vpt sx sy]
  (some (fn [{:keys [lon lat radius] :as item}]
          (let [[ix iy] (vp/->screen vpt lon lat)
                dx (- ix sx) dy (- iy sy)
                r (or radius 8)]
            (when (<= (+ (* dx dx) (* dy dy)) (* r r))
              item)))
        items))
