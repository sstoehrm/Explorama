(ns de.explorama.frontend.map.pixi.arrows-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.arrows :as arrows]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-9))

(defn- pairs [verts] (vec (partition 2 verts)))

(deftest horizontal-arrow-shape
  (let [verts (arrows/arrow-polygon [100 0] [0 0] 8 2)
        pts (pairs verts)
        [[x0 y0] [x1 y1] [x2 y2] [x3 y3] [x4 y4] [x5 y5] [x6 y6]] (sort-by first pts)]
    (testing "one filled 7-vertex barbed-arrow polygon"
      (is (= 7 (count pts))))
    (testing "head sits exactly at the target end"
      (is (close? x6 100))
      (is (close? y6 0)))
    (testing "head base sits head-length back from the target (max 6 (* 1.8 w-target)) = 14.4"
      (is (close? x2 85.6))
      (is (close? x3 85.6))
      (is (close? x4 85.6))
      (is (close? x5 85.6)))
    (testing "taper: half-width at the source end is (max 1 (/ w-source 2)) = 1"
      (is (close? x0 0))
      (is (close? x1 0))
      (is (close? (min y0 y1) -1))
      (is (close? (max y0 y1) 1)))
    (testing "at the head base, two magnitudes are present: the shaft-neck half-width (max 1 (/ w-target 2)) = 4 and the barb half-width (max 3 w-target) = 8"
      (is (= [4 4 8 8] (sort (map #(js/Math.abs %) [y2 y3 y4 y5])))))
    (testing "symmetric about the arrow's own axis (y = 0, the line source->target)"
      (doseq [[x y] pts]
        (is (some (fn [[ox oy]] (and (close? ox x) (close? oy (- y)))) pts))))))

(deftest vertical-arrow-shape
  (let [verts (arrows/arrow-polygon [0 100] [0 0] 8 2)
        pts (pairs verts)
        [[x0 y0] [x1 y1] [x2 y2] [x3 y3] [x4 y4] [x5 y5] [x6 y6]] (sort-by second pts)]
    (testing "one filled 7-vertex barbed-arrow polygon"
      (is (= 7 (count pts))))
    (testing "head sits exactly at the target end"
      (is (close? x6 0))
      (is (close? y6 100)))
    (testing "head base sits head-length back from the target"
      (is (close? y2 85.6))
      (is (close? y3 85.6))
      (is (close? y4 85.6))
      (is (close? y5 85.6)))
    (testing "taper: half-width at the source end is 1, at the head base neck 4 and barb 8"
      (is (close? y0 0))
      (is (close? y1 0))
      (is (close? (min x0 x1) -1))
      (is (close? (max x0 x1) 1))
      (is (= [4 4 8 8] (sort (map #(js/Math.abs %) [x2 x3 x4 x5])))))
    (testing "symmetric about the arrow's own axis (x = 0, the line source->target)"
      (doseq [[x y] pts]
        (is (some (fn [[ox oy]] (and (close? oy y) (close? ox (- x)))) pts))))))

(deftest minimum-floors-applied
  (testing "zero weights are clamped to the documented minimums at every station, not shrunk to zero/negative"
    (let [verts (arrows/arrow-polygon [10 0] [0 0] 0 0)
          pts (pairs verts)
          [[x0 y0] [x1 y1] [x2 y2] [x3 y3] [x4 y4] [x5 y5] [x6 _y6]] (sort-by first pts)]
      (testing "source half-width floor (max 1 (/ w-source 2)) = 1"
        (is (close? x0 0)) (is (close? x1 0))
        (is (close? (js/Math.abs y0) 1))
        (is (close? (js/Math.abs y1) 1)))
      (testing "head length floor (max 6 (* 1.8 0)) = 6, unclamped here since (* 0.6 10) = 6 too -> head base at x = 4"
        (is (close? x2 4)) (is (close? x3 4)) (is (close? x4 4)) (is (close? x5 4)))
      (testing "at the head base: shaft-neck half-width floor (max 1 (/ w-target 2)) = 1 and barb half-width floor (max 3 w-target) = 3"
        (is (= [1 1 3 3] (sort (map #(js/Math.abs %) [y2 y3 y4 y5])))))
      (testing "tip is exactly at the target"
        (is (close? x6 10))))))

(deftest zero-length-segment-is-degenerate
  (testing "coincident source and target has no direction to draw along - nil, not a crash"
    (is (nil? (arrows/arrow-polygon [5 5] [5 5] 8 2)))))

(deftest short-segment-quad-fallback
  (testing "segments under 8px total length skip the head entirely: a plain 4-vertex tapered quad, no vertex extending past the source or target along the axis"
    (let [verts (arrows/arrow-polygon [3 0] [0 0] 40 40)
          pts (pairs verts)
          xs (map first pts)]
      (is (= 4 (count pts)))
      (is (every? #(<= 0 % 3) xs))
      (testing "tapers from the source half-width to the target-shaft half-width, both (max 1 (/ w 2)) = 20 here"
        (is (every? #(close? 20 (js/Math.abs (second %))) pts))))))

(deftest self-intersection-regression-clamps-head-length
  (testing "reviewer repro: w-target 20, w-source 40 on a 10px segment - the naive head length (max 6 (* 1.8 20)) = 36 would place the head base behind the source (bowtie); it must clamp to (* 0.6 10) = 6, head base at x = 4"
    (let [verts (arrows/arrow-polygon [10 0] [0 0] 20 40)
          pts (pairs verts)
          xs (map first pts)]
      (is (= 7 (count pts)))
      (is (some #(close? % 4) xs))
      (testing "no vertex sits behind the source or past the target along the axis"
        (is (every? #(<= 0 % 10) xs))))))

(deftest point-segment-dist-sq-cases
  (testing "a point on the segment interior is distance 0"
    (is (== 0 (arrows/point-segment-dist-sq [5 0] [0 0] [10 0]))))
  (testing "a point off to the side projects perpendicularly onto the interior"
    (is (== 9 (arrows/point-segment-dist-sq [5 3] [0 0] [10 0]))))
  (testing "points exactly at the endpoints are distance 0"
    (is (== 0 (arrows/point-segment-dist-sq [0 0] [0 0] [10 0])))
    (is (== 0 (arrows/point-segment-dist-sq [10 0] [0 0] [10 0]))))
  (testing "beyond the a-end, t clamps to 0 (distance to a)"
    (is (== 25 (arrows/point-segment-dist-sq [-5 0] [0 0] [10 0]))))
  (testing "beyond the b-end, t clamps to 1 (distance to b)"
    (is (== 25 (arrows/point-segment-dist-sq [15 0] [0 0] [10 0]))))
  (testing "a zero-length segment measures point-to-point distance"
    (is (== 25 (arrows/point-segment-dist-sq [3 4] [0 0] [0 0])))))
