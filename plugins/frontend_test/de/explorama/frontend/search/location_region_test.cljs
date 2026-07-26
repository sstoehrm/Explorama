(ns de.explorama.frontend.search.location-region-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.projection :as projection]
            [de.explorama.frontend.search.views.components.location-region :as region]))

(deftest corners->values-orderings
  (testing "all 4 corner orderings of the same rectangle sort identically"
    (let [expected [10 20 30 40]]
      (is (= expected (region/corners->values [10 20] [30 40])))
      (is (= expected (region/corners->values [30 40] [10 20])))
      (is (= expected (region/corners->values [10 40] [30 20])))
      (is (= expected (region/corners->values [30 20] [10 40]))))))

(deftest corners->values-degenerate
  (testing "equal corners give a zero-area rectangle without throwing"
    (is (= [15 25 15 25] (region/corners->values [15 25] [15 25])))))

(deftest values->feature-shape
  (testing "feature is a closed polygon over the projected corners, lon-first"
    (let [values [10 20 30 40]
          feature (region/values->feature values)
          ring (:rings feature)
          outer (first ring)
          expected-corners (set (for [lat [10 30]
                                       lng [20 40]]
                                   (projection/project lng lat)))]
      (is (= :polygon (:kind feature)))
      (is (= {} (:properties feature)))
      (is (= 1 (count ring)))
      (is (= 5 (count outer)) "4 corners + closing point")
      (is (= (first outer) (last outer)) "ring is closed")
      (is (= expected-corners (set (butlast outer)))
          "each corner matches (projection/project lng lat), not the swapped axis order"))))

(deftest values->extent-axis-swap
  (testing "extent swaps values' lat-first axes into lon-first for fit-extent"
    (is (= [20 10 40 30] (region/values->extent [10 20 30 40])))))
