(ns de.explorama.frontend.map.map.impl.pixi.area-join-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]))

(deftest area-style-fn-matching-feature
  (let [feature-properties {"COUNTRY" [:str "#country"]}
        data-set {{"COUNTRY" "Germany"} {:color "#ff0000" :opacity 0.7}}
        style-fn (inst/area-style-fn feature-properties data-set)
        feature {:properties {"COUNTRY" "Germany"}}]
    (is (= {:fill-color 0xff0000
            :fill-alpha 0.7
            :stroke-width 1
            :stroke-color 0x4b4f53
            :stroke-alpha 0.9}
           (style-fn feature)))))

(deftest area-style-fn-non-matching-feature
  (let [feature-properties {"COUNTRY" [:str "#country"]}
        data-set {{"COUNTRY" "Germany"} {:color "#ff0000" :opacity 0.7}}
        style-fn (inst/area-style-fn feature-properties data-set)
        feature {:properties {"COUNTRY" "France"}}]
    (is (nil? (style-fn feature)))))

(deftest area-style-fn-default-opacity
  (let [feature-properties {"COUNTRY" [:str "#country"]}
        data-set {{"COUNTRY" "Germany"} {:color "#00ff00"}}
        style-fn (inst/area-style-fn feature-properties data-set)
        feature {:properties {"COUNTRY" "Germany"}}]
    (is (= 0.5 (:fill-alpha (style-fn feature))))))

(deftest area-style-fn-ignores-properties-outside-config
  ;; select-keys restricts the join to feature-properties-config's keys, so an
  ;; extra unrelated property on the feature (e.g. a "geometry"-adjacent
  ;; attribute geo/parse-features left untouched) doesn't break the match.
  (let [feature-properties {"COUNTRY" [:str "#country"]}
        data-set {{"COUNTRY" "Germany"} {:color "#0000ff" :opacity 0.3}}
        style-fn (inst/area-style-fn feature-properties data-set)
        feature {:properties {"COUNTRY" "Germany" "POPULATION" "83000000"}}]
    (is (some? (style-fn feature)))))

(deftest resolve-area-config-looks-up-by-feature-layer-id
  ;; The static per-layer-*type* config (extra-fns' :feature-layer-config) is
  ;; keyed by :feature-layer-id, NOT the per-instance :layer-id a particular
  ;; layer occurrence is addressed by (see backend overlayers.cljc's
  ;; feature-coloring-layer-calc, which emits both keys on the same map, and
  ;; render_helper.cljs's show-feature-layer-popup-fn-wrapper, which looks the
  ;; static config up the same way for popups). get-config here is keyed by
  ;; BOTH ids with distinguishable values, so a regression back to :layer-id
  ;; lookup returns the wrong (bogus) entry and fails this assertion.
  (let [get-config {"static-config-id" {:type "geojson"
                                         :feature-properties {"COUNTRY" [:str "#c"]}}
                     "per-instance-id" {:type "geojson"
                                         :feature-properties {"WRONG" [:str "#wrong"]}}}
        layer-map {:layer-id "per-instance-id"
                   :feature-layer-id "static-config-id"
                   :data-set {}}]
    (is (= {:type "geojson" :feature-properties {"COUNTRY" [:str "#c"]}}
           (inst/resolve-area-config get-config layer-map)))))

(deftest resolve-area-config-missing-get-config
  (is (nil? (inst/resolve-area-config nil {:layer-id "x" :feature-layer-id "y"}))))
