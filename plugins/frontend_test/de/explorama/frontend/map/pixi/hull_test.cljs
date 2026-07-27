(ns de.explorama.frontend.map.pixi.hull-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.hull :as hull]))

(deftest convex-hull-test
  (testing "square with interior point drops the interior"
    (is (= #{[0 0] [4 0] [4 4] [0 4]}
           (set (hull/convex-hull [[0 0] [4 0] [4 4] [0 4] [2 2]])))))
  (testing "collinear points reduce to the two extremes"
    (is (= #{[0 0] [4 4]}
           (set (hull/convex-hull [[0 0] [1 1] [2 2] [4 4]])))))
  (testing "duplicates are ignored"
    (is (= #{[0 0] [2 0] [1 3]}
           (set (hull/convex-hull [[0 0] [2 0] [1 3] [0 0] [2 0]])))))
  (testing "degenerate inputs pass through"
    (is (= [[1 2]] (hull/convex-hull [[1 2] [1 2]])))
    (is (= [[0 0] [1 1]] (hull/convex-hull [[1 1] [0 0]])))
    (is (= [] (hull/convex-hull []))))
  (testing "hull vertices come back in traversal order (no self-crossing)"
    (let [h (hull/convex-hull [[0 0] [4 0] [4 4] [0 4]])
          rotated (vec (take 4 (drop-while #(not= % [0 0]) (concat h h))))]
      (is (= 4 (count h)))
      (is (contains? #{[[0 0] [4 0] [4 4] [0 4]]
                       [[0 0] [0 4] [4 4] [4 0]]}
                     rotated)))))
