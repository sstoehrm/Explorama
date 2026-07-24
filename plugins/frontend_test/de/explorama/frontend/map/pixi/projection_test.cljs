(ns de.explorama.frontend.map.pixi.projection-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.projection :as proj]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-6))

(deftest project-center
  (testing "lon/lat 0,0 maps to the middle of the world square"
    (let [[x y] (proj/project 0 0)]
      (is (close? x 0.5))
      (is (close? y 0.5)))))

(deftest project-edges
  (testing "lon +180 -> x 1.0, lon -180 -> x 0.0"
    (is (close? (first (proj/project 180 0)) 1.0))
    (is (close? (first (proj/project -180 0)) 0.0)))
  (testing "max mercator lat -> y ~0, min -> y ~1"
    (is (close? (second (proj/project 0 85.05112878)) 0.0))
    (is (close? (second (proj/project 0 -85.05112878)) 1.0))))

(deftest round-trip
  (testing "unproject inverts project"
    (doseq [[lon lat] [[13.4 52.5] [-73.9 40.7] [0 0] [100 -33]]]
      (let [[x y] (proj/project lon lat)
            [lon2 lat2] (proj/unproject x y)]
        (is (close? lon lon2))
        (is (close? lat lat2))))))

(deftest world-size
  (testing "world width doubles per zoom level"
    (is (= 256 (proj/world-px 0)))
    (is (= 512 (proj/world-px 1)))
    (is (= 1024 (proj/world-px 2)))))

(deftest lat-clamped
  (testing "latitudes beyond the mercator limit are clamped"
    (is (close? (proj/clamp-lat 90) 85.05112878))
    (is (close? (proj/clamp-lat -90) -85.05112878))))
