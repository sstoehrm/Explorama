(ns de.explorama.frontend.map.map.impl.pixi.stubs-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.stubs :as stubs]))

(deftest throttle-once-per-key
  (is (true? (stubs/should-notify? #{} [:f1 :heatmap])))
  (is (false? (stubs/should-notify? #{[:f1 :heatmap]} [:f1 :heatmap])))
  (is (true? (stubs/should-notify? #{[:f1 :heatmap]} [:f2 :heatmap]))))
