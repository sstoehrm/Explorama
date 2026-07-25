(ns de.explorama.frontend.map.pixi.style-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.pixi.style :as style]))

(deftest hex-parsing
  (is (= 0xd62728 (style/hex->int "#d62728")))
  (is (= 0x1f77b4 (style/hex->int "1f77b4")))
  (is (= 0x000000 (style/hex->int nil)))
  (is (= 0x000000 (style/hex->int "nope"))))

(def entry ["ev-1" [["bucket" "ev-1"]
                    [[52.5 13.4] [48.1 11.6]]
                    {:fillColor "#ff0000" :radius 8 :fillOpacity 0.5}]])

(deftest marker-conversion
  (let [m (style/marker-data->marker entry)]
    (is (= "ev-1" (:id m)))
    (is (= ["bucket" "ev-1"] (:event-id m)))
    (is (= 13.4 (:lon m)))   ; first pair, [lat lon] -> lon
    (is (= 52.5 (:lat m)))
    (is (= 0xff0000 (:color m)))
    (is (= "#ff0000" (:color-hex m)))
    (is (= 8 (:radius m)))
    (is (= 0.5 (:alpha m)))
    (is (false? (:highlighted? m)))))

(deftest conversion-defaults-and-nil
  (is (nil? (style/marker-data->marker ["x" [["b" "x"] [] {}]])))
  (let [m (style/marker-data->marker ["x" [["b" "x"] [[1 2]] {}]])]
    (is (= 6 (:radius m)))
    (is (= 0.7 (:alpha m)))))

(deftest bulk-conversion-highlight-and-visibility
  (let [data {"a" [["b" "a"] [[50 10]] {:fillColor "#00ff00"}]
              "c" [["b" "c"] [[51 11]] {:fillColor "#0000ff"}]}
        ms (style/markers-map->engine-markers data #{["b" "c"]} nil)
        visible (style/markers-map->engine-markers data #{} #{"a"})]
    (is (= 2 (count ms)))
    (is (true? (:highlighted? (first (filter #(= "c" (:id %)) ms)))))
    (is (= ["a"] (mapv :id visible)))))
