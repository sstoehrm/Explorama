(ns de.explorama.frontend.map.map.impl.pixi.movement-heatmap-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.pixi.projection :as proj]))

;;;; arrow-desc->world -------------------------------------------------------

(deftest arrow-desc->world-projects-lat-lon-endpoints
  (testing "from/to are [lat lon] - projected lon-first via projection/project"
    (let [desc {:id "a-b" :from [52.5 13.4] :to [48.1 11.6]
                :weight 4 :original 4 :attribute "number-of-events"}
          result (inst/arrow-desc->world desc)]
      (is (= {:id "a-b"
              :fw (proj/project 13.4 52.5)
              :tw (proj/project 11.6 48.1)
              :weight 4 :original 4 :attribute "number-of-events"}
             result)))))

(deftest arrow-desc->world-nil-on-missing-endpoint
  (is (nil? (inst/arrow-desc->world {:id "x" :from nil :to [48.1 11.6] :weight 1})))
  (is (nil? (inst/arrow-desc->world {:id "x" :from [52.5 13.4] :to nil :weight 1})))
  (is (nil? (inst/arrow-desc->world {:id "x" :from nil :to nil :weight 1}))))

;;;; heatmap-point->world ----------------------------------------------------

(deftest heatmap-point->world-local-extrema-uses-single-attribute-key
  (let [desc {:lat 52.5 :lng 13.4 "number-of-events" 7}
        result (inst/heatmap-point->world desc :local)]
    (is (= (proj/project 13.4 52.5) [(:wx result) (:wy result)]))
    (is (= 7 (:weight result)))))

(deftest heatmap-point->world-local-extrema-fallback-when-missing-or-non-numeric
  (is (= 1 (:weight (inst/heatmap-point->world {:lat 1 :lng 2} :local))))
  (is (= 1 (:weight (inst/heatmap-point->world {:lat 1 :lng 2 "attr" "not-a-number"} :local)))))

(deftest heatmap-point->world-global-extrema-always-weight-1
  (is (= 1 (:weight (inst/heatmap-point->world {:lat 1 :lng 2 "attr" 99} :global)))))

;;;; heatmap-extrema -----------------------------------------------------------

(deftest heatmap-extrema-local-when-configured
  (is (= :local (inst/heatmap-extrema {:extrema :local}))))

(deftest heatmap-extrema-defaults-to-global
  (is (= :global (inst/heatmap-extrema {:extrema :global})))
  (is (= :global (inst/heatmap-extrema {})))
  (is (= :global (inst/heatmap-extrema nil))))
