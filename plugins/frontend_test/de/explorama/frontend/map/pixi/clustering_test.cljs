(ns de.explorama.frontend.map.pixi.clustering-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.pixi.clustering :as clustering]))

(def vp {:center [10.0 51.0] :zoom 6 :width 800 :height 600
         :min-zoom 1 :max-zoom 19})

(deftest far-apart-markers-stay-separate
  (let [ms [{:id 1 :lon 6.0 :lat 48.0} {:id 2 :lon 14.0 :lat 54.0}]
        nodes (clustering/cluster ms vp 60)]
    (is (= 2 (count nodes)))
    (is (every? #(false? (:cluster? %)) nodes))))

(deftest near-markers-merge
  (let [ms [{:id 1 :lon 10.000 :lat 51.000}
            {:id 2 :lon 10.001 :lat 51.001}
            {:id 3 :lon 10.002 :lat 51.000}]
        nodes (clustering/cluster ms vp 80)
        cl (first (filter :cluster? nodes))]
    (is (= 1 (count nodes)))
    (is (:cluster? cl))
    (is (= 3 (:count cl)))
    (is (= 3 (count (:members cl))))
    (is (vector? (:cell cl)))
    (is (= 2 (count (:cell cl))))))

(deftest count-total-preserved
  (let [ms (mapv (fn [i] {:id i :lon (+ 10 (* 0.5 (mod i 5))) :lat (+ 51 (* 0.5 (quot i 5)))})
                 (range 20))
        nodes (clustering/cluster ms vp 50)]
    (is (= 20 (reduce + (map :count nodes))))))
