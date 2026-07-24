(ns de.explorama.frontend.map.pixi.viewport-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-4))

(def base {:center [13.4 52.5] :zoom 6 :width 800 :height 600
           :min-zoom 1 :max-zoom 19})

(deftest center-is-screen-centre
  (testing "the viewport center projects to the middle of the canvas"
    (let [[sx sy] (vp/->screen base 13.4 52.5)]
      (is (close? sx 400))
      (is (close? sy 300)))))

(deftest screen-lonlat-round-trip
  (testing "->lonlat inverts ->screen"
    (let [[lon lat] (vp/->lonlat base 250 175)
          [sx sy] (vp/->screen base lon lat)]
      (is (close? sx 250))
      (is (close? sy 175)))))

(deftest pan-shifts-center
  (testing "panning right by 100px moves the map content right (center goes west)"
    (let [v2 (vp/pan base 100 0)]
      ;; the lon/lat previously at screen 300,300 is now at 400,300
      (let [ll (vp/->lonlat base 300 300)
            [sx _] (vp/->screen v2 (first ll) (second ll))]
        (is (close? sx 400))))))

(deftest zoom-around-keeps-point-fixed
  (testing "zooming around a screen point keeps that point's lon/lat under the cursor"
    (let [px 600 py 200
          ll (vp/->lonlat base px py)
          v2 (vp/zoom-around base 1 px py)
          [sx sy] (vp/->screen v2 (first ll) (second ll))]
      (is (= 7 (:zoom v2)))
      (is (close? sx px))
      (is (close? sy py)))))

(deftest zoom-clamped
  (testing "zoom respects max-zoom"
    (is (= 19 (:zoom (vp/zoom-around (assoc base :zoom 19) 5 400 300))))))

(deftest fit-extent-centers-and-fits
  (testing "fit-extent centers the bbox on screen and fits it inside the viewport"
    (let [v2 (vp/fit-extent base [10.0 50.0 16.0 54.0])
          [nwx nwy] (vp/->screen v2 10.0 54.0)   ; north-west corner
          [sex sey] (vp/->screen v2 16.0 50.0)]  ; south-east corner
      ;; bbox is centered: corners symmetric about the canvas center (800x600)
      (is (close? (+ nwx sex) 800))
      (is (close? (+ nwy sey) 600))
      ;; bbox fits inside the viewport (10% padding leaves margin)
      (is (<= 0 nwx sex 800))
      (is (<= 0 nwy sey 600))
      ;; lon midpoint is still the geographic midpoint (Mercator x is linear in lon)
      (is (close? (first (:center v2)) 13.0))
      (is (<= (:zoom v2) (:max-zoom base)))
      ;; the binding (vertical) axis fills exactly 90% of the viewport
      (is (< (js/Math.abs (- (- sey nwy) 540)) 1.0)))))
