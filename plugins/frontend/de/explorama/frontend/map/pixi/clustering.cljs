(ns de.explorama.frontend.map.pixi.clustering
  (:require [de.explorama.frontend.map.pixi.projection :as proj]))

(defn- cluster* [markers zoom cell-px]
  (let [s (proj/world-px zoom)
        cells (group-by (fn [{:keys [lon lat]}]
                          (let [[px py] (proj/project lon lat)]
                            [(js/Math.floor (/ (* px s) cell-px))
                             (js/Math.floor (/ (* py s) cell-px))]))
                        markers)]
    (mapv (fn [[[cx cy] ms]]
            (if (= 1 (count ms))
              (assoc (first ms) :cluster? false :count 1)
              (let [n (count ms)]
                {:cluster? true
                 :count n
                 :lon (/ (reduce + (map :lon ms)) n)
                 :lat (/ (reduce + (map :lat ms)) n)
                 :members (vec ms)
                 :cell [cx cy]})))
          cells)))

(defn cluster
  "Grid-bin markers by world-space cell of size cell-px at the viewport's zoom.
   Cells are anchored at the world origin, so panning never changes binning;
   only zoom does. A non-positive cell-px degrades to no clustering instead
   of dividing by zero."
  [markers {:keys [zoom]} cell-px]
  (if-not (pos? cell-px)
    (mapv #(assoc % :cluster? false :count 1) markers)
    (cluster* markers zoom cell-px)))
