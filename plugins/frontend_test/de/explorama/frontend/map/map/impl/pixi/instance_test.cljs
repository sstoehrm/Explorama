(ns de.explorama.frontend.map.map.impl.pixi.instance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]))

(deftest pending-ops-fifo
  (let [s (-> {:pending []}
              (inst/enqueue-pending [:a 1])
              (inst/enqueue-pending [:b 2]))
        [s' ops] (inst/drain-pending s)]
    (is (= [[:a 1] [:b 2]] ops))
    (is (empty? (:pending s')))))

(deftest registry-roundtrip
  (let [a (inst/new-instance-state "f-1" {})]
    (inst/register! "f-1" a)
    (is (identical? a (inst/lookup "f-1")))
    (inst/unregister! "f-1")
    (is (nil? (inst/lookup "f-1")))))
