(ns de.explorama.frontend.map.map.impl.pixi.instance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.pixi.engine :as engine]))

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

(deftest valid-move-to-guard
  (is (true? (inst/valid-move-to? 5 [1 2])))
  (is (false? (inst/valid-move-to? nil [1 2])))
  (is (false? (inst/valid-move-to? 5 nil)))
  (is (false? (inst/valid-move-to? nil nil))))

(deftest push-markers-renders-only-created-ids
  (let [captured (atom ::not-called)
        fake-engine {:state (atom {:batch? true})
                     :callbacks (atom [])}]
    (with-redefs [engine/set-markers! (fn [_ markers] (reset! captured markers))]
      (inst/push-markers!
       {:engine fake-engine
        :marker-data {"a" [["b" "a"] [[50 10]] {:fillColor "#00ff00"}]
                      "b" [["b" "b"] [[51 11]] {:fillColor "#0000ff"}]}
        ;; only "a" has been "created" via the object-manager - "b" is in the
        ;; state-handler's :marker-data cache but must not render (C2 split).
        :created-marker-ids #{"a"}
        :highlighted #{}
        :visible-ids nil}))
    (is (= ["a"] (mapv :id @captured)))))

(deftest push-markers-noop-while-headless
  (let [called? (atom false)]
    (with-redefs [engine/set-markers! (fn [_ _] (reset! called? true))]
      (inst/push-markers! {:engine nil
                            :marker-data {"a" [["b" "a"] [[50 10]] {}]}
                            :created-marker-ids #{"a"}
                            :highlighted #{}
                            :visible-ids nil}))
    (is (false? @called?))))
