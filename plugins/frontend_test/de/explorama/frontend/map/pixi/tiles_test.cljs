(ns de.explorama.frontend.map.pixi.tiles-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.tiles :as tiles]))

(def vp {:center [13.4 52.5] :zoom 5 :width 800 :height 600
         :min-zoom 1 :max-zoom 19})

(deftest tile-url-templating
  (is (= "https://s/5/17/10.png"
         (tiles/tile-url "https://s/{z}/{x}/{y}.png" {:z 5 :x 17 :y 10}))))

(deftest tile-key-format
  (is (= "5/17/10" (tiles/tile-key {:z 5 :x 17 :y 10}))))

(deftest visible-tiles-cover-viewport
  (let [ts (tiles/visible-tiles vp)]
    (testing "all tiles are at integer zoom = floor(:zoom)"
      (is (every? #(= 5 (:z %)) ts)))
    (testing "there is at least one tile and coordinates are in-range"
      (is (pos? (count ts)))
      (is (every? #(<= 0 (:x %) 31) ts))   ; 2^5 - 1
      (is (every? #(<= 0 (:y %) 31) ts)))
    (testing "the tile under the viewport center is included"
      (let [[cx cy] (de.explorama.frontend.map.pixi.projection/project 13.4 52.5)
            n 32
            centre {:z 5 :x (js/Math.floor (* cx n)) :y (js/Math.floor (* cy n))}]
        (is (some #(and (= (:x %) (:x centre)) (= (:y %) (:y centre))) ts))))))
