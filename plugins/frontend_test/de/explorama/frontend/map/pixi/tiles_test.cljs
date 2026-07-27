(ns de.explorama.frontend.map.pixi.tiles-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.tiles :as tiles]
            [de.explorama.frontend.map.pixi.projection :as proj]
            ["pixi.js-legacy" :refer [Container utils]]))

(def vp {:center [13.4 52.5] :zoom 5 :width 800 :height 600
         :min-zoom 1 :max-zoom 19})

(deftest tile-url-templating
  (is (= "https://s/5/17/10.png"
         (tiles/tile-url "https://s/{z}/{x}/{y}.png" {:z 5 :x 17 :y 10}))))

(deftest tile-key-format
  (is (= "5/17/10" (tiles/tile-key {:z 5 :x 17 :y 10}))))

(deftest visible-tiles-cover-viewport
  (let [ts (tiles/visible-tiles vp)]
    (testing "all tiles are at integer zoom = floor(:zoom)"
      (is (every? #(= 5 (:z %)) ts)))
    (testing "there is at least one tile and fetch coordinates are in-range"
      (is (pos? (count ts)))
      (is (every? #(<= 0 (:tx %) 31) ts))   ; 2^5 - 1
      (is (every? #(<= 0 (:y %) 31) ts)))
    (testing "the tile under the viewport center is included"
      (let [[cx cy] (proj/project 13.4 52.5)
            n 32
            centre {:z 5 :x (js/Math.floor (* cx n)) :y (js/Math.floor (* cy n))}]
        (is (some #(and (= (:x %) (:x centre)) (= (:tx %) (:x centre)) (= (:y %) (:y centre))) ts))))))

(deftest world-wraps-horizontally
  (testing "antimeridian viewport unwraps placement columns but wraps fetch columns"
    (let [vpt {:center [179.5 0] :zoom 3 :width 1200 :height 400
               :min-zoom 1 :max-zoom 19}
          ts (tiles/visible-tiles vpt)
          n 8]
      (is (some #(or (>= (:x %) n) (< (:x %) 0)) ts))
      (is (every? #(= (:tx %) (mod (:x %) n)) ts))
      (is (every? #(<= 0 (:tx %) (dec n)) ts))
      (let [pairs (map (juxt :x :y) ts)]
        (is (= (count pairs) (count (distinct pairs)))))))
  (testing "old duplicate-tiles-at-low-zoom quirk is gone"
    (let [vpt {:center [0 0] :zoom 1 :width 1200 :height 400
               :min-zoom 1 :max-zoom 19}
          ts (tiles/visible-tiles vpt)
          pairs (map (juxt :x :y) ts)]
      (is (= (count pairs) (count (distinct pairs)))))))

(deftest tile-error-evicts-poisoned-cache-entry
  (let [container (Container.)
        cache (atom {})
        ends (atom 0)
        template {:type :xyz :url "http://tile-error-test.invalid/{z}/{x}/{y}.png"}
        vpt {:center [0 0] :zoom 1 :width 256 :height 256
             :min-zoom 1 :max-zoom 19}]
    (tiles/render-tiles! container cache template vpt
                         {:on-load-start! (fn [])
                          :on-load-end! #(swap! ends inc)})
    (let [{:keys [^js sprite]} (first (vals @cache))
          ^js base (.-baseTexture (.-texture sprite))
          url (first (.-textureCacheIds (.-texture sprite)))]
      (is (some? (aget (.-TextureCache utils) url)))
      (.emit base "error" base)
      (testing "error fires load-end exactly once and evicts the cache entry"
        (is (= 1 @ends))
        (is (nil? (aget (.-TextureCache utils) url)))
        (.emit base "error" base)
        (is (= 1 @ends))))))

(deftest evicting-inflight-tile-fires-load-end
  (let [container (Container.)
        cache (atom {})
        ends (atom 0)
        template {:type :xyz :url "http://tile-evict-test.invalid/{z}/{x}/{y}.png"}
        vpt {:center [0 0] :zoom 1 :width 256 :height 256
             :min-zoom 1 :max-zoom 19}]
    (tiles/render-tiles! container cache template vpt
                         {:on-load-start! (fn [])
                          :on-load-end! #(swap! ends inc)})
    (let [n (count @cache)]
      (is (pos? n))
      (tiles/clear-tiles! container cache)
      (testing "every pending load ends when its tile is torn down mid-flight"
        (is (= n @ends))
        (is (empty? @cache))))))
