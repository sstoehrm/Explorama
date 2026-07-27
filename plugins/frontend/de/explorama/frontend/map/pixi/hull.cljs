(ns de.explorama.frontend.map.pixi.hull)

(defn- cross [[ox oy] [ax ay] [bx by]]
  (- (* (- ax ox) (- by oy))
     (* (- ay oy) (- bx ox))))

(defn convex-hull
  "Convex hull of [[x y] ...] via Andrew's monotone chain. Duplicates and
   collinear boundary points are dropped. Returns the hull vertices in
   traversal order; degenerate inputs (a point, a segment) come back with
   fewer than 3 points."
  [points]
  (let [pts (vec (sort (distinct points)))]
    (if (<= (count pts) 2)
      pts
      (let [build (fn [ps]
                    (reduce (fn [acc p]
                              (loop [acc acc]
                                (if (and (>= (count acc) 2)
                                         (<= (cross (peek (pop acc)) (peek acc) p) 0))
                                  (recur (pop acc))
                                  (conj acc p))))
                            [] ps))
            lower (build pts)
            upper (build (reverse pts))]
        (vec (concat (pop lower) (pop upper)))))))
