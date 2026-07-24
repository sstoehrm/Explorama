(ns de.explorama.frontend.map.pixi.clustering
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn cluster
  "Grid-bin markers by screen cell of size cell-px at the current viewport."
  [markers vpt cell-px]
  (let [cells (group-by (fn [{:keys [lon lat]}]
                          (let [[sx sy] (vp/->screen vpt lon lat)]
                            [(js/Math.floor (/ sx cell-px))
                             (js/Math.floor (/ sy cell-px))]))
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
