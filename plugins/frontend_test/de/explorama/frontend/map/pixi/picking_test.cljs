(ns de.explorama.frontend.map.pixi.picking-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.picking :as picking]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(def vpt {:center [10.0 51.0] :zoom 6 :width 800 :height 600
          :min-zoom 1 :max-zoom 19})

(deftest picks-item-under-cursor
  (let [item {:id 1 :lon 10.0 :lat 51.0 :radius 8}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (= 1 (:id (picking/pick [item] vpt sx sy))))
    (is (= 1 (:id (picking/pick [item] vpt (+ sx 5) sy))))))

(deftest misses-when-outside-radius
  (let [item {:id 1 :lon 10.0 :lat 51.0 :radius 8}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (nil? (picking/pick [item] vpt (+ sx 40) sy)))))

(deftest returns-first-hit
  (let [a {:id :a :lon 10.0 :lat 51.0 :radius 10}
        b {:id :b :lon 10.0 :lat 51.0 :radius 10}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (= :a (:id (picking/pick [a b] vpt sx sy))))))
