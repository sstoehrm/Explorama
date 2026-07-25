(ns de.explorama.frontend.map.pixi.geo-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.geo :as geo]
            [de.explorama.frontend.map.pixi.projection :as projection]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-9))

(defn- close-pt? [[ax ay] [bx by]] (and (close? ax bx) (close? ay by)))

(def triangle-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "triangle" "id" 1}
     "geometry" {"type" "Polygon"
                 "coordinates" [[[13 52] [14 52] [13.5 53] [13 52]]]}}]})

(def square-with-hole-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "square"}
     "geometry" {"type" "Polygon"
                 "coordinates" [[[0 0] [10 0] [10 10] [0 10] [0 0]]
                                [[4 4] [6 4] [6 6] [4 6] [4 4]]]}}]})

(def multi-polygon-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "multi"}
     "geometry" {"type" "MultiPolygon"
                 "coordinates" [[[[0 0] [1 0] [1 1] [0 1] [0 0]]]
                                [[[10 10] [11 10] [11 11] [10 11] [10 10]]]]}}]})

(def line-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "line"}
     "geometry" {"type" "LineString"
                 "coordinates" [[0 0] [1 1]]}}]})

(def multi-point-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "multi-point"}
     "geometry" {"type" "MultiPoint"
                 "coordinates" [[0 0] [1 1] [2 2]]}}]})

(def multi-line-string-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "multi-line"}
     "geometry" {"type" "MultiLineString"
                 "coordinates" [[[0 0] [1 1]]
                                [[2 2] [3 3] [4 4]]]}}]})

(def properties-with-geometry-fc
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "x" "id" 5 "geometry" "bogus"}
     "geometry" {"type" "Point" "coordinates" [1 1]}}]})

(deftest triangle-containment
  (testing "a point inside the projected triangle is a hit, outside is not"
    (let [[feature] (geo/parse-features triangle-fc)
          [ix iy] (projection/project 13.5 52.3)
          [ox1 oy1] (projection/project 13.5 51)
          [ox2 oy2] (projection/project 20 52)]
      (is (= :polygon (:kind feature)))
      (is (true? (geo/point-in-feature? feature ix iy)))
      (is (false? (geo/point-in-feature? feature ox1 oy1)))
      (is (false? (geo/point-in-feature? feature ox2 oy2))))))

(deftest polygon-hole-exclusion
  (testing "a point inside the hole is not a hit, elsewhere inside the outer ring is"
    (let [[feature] (geo/parse-features square-with-hole-fc)
          [hx hy] (projection/project 5 5)
          [ix iy] (projection/project 1 1)
          [ox oy] (projection/project 20 20)]
      (is (= 2 (count (:rings feature))))
      (is (false? (geo/point-in-feature? feature hx hy)))
      (is (true? (geo/point-in-feature? feature ix iy)))
      (is (false? (geo/point-in-feature? feature ox oy))))))

(deftest multi-polygon-fan-out
  (testing "MultiPolygon fans out to one :polygon feature per polygon, sharing properties"
    (let [features (geo/parse-features multi-polygon-fc)
          [f1 f2] features
          [x1 y1] (projection/project 0.5 0.5)
          [x2 y2] (projection/project 10.5 10.5)]
      (is (= 2 (count features)))
      (is (every? #(= :polygon (:kind %)) features))
      (is (every? #(= {"name" "multi"} (:properties %)) features))
      (is (true? (geo/point-in-feature? f1 x1 y1)))
      (is (false? (geo/point-in-feature? f1 x2 y2)))
      (is (true? (geo/point-in-feature? f2 x2 y2)))
      (is (false? (geo/point-in-feature? f2 x1 y1))))))

(deftest line-kind-and-no-polygon-hit-testing
  (testing "LineString parses to :line and never hit-tests true"
    (let [[feature] (geo/parse-features line-fc)
          [x y] (projection/project 0 0)]
      (is (= :line (:kind feature)))
      (is (= [[(projection/project 0 0) (projection/project 1 1)]] (:rings feature)))
      (is (false? (geo/point-in-feature? feature x y))))))

(deftest multi-point-single-feature-many-single-coord-rings
  (testing "MultiPoint stays one :point feature with one single-coordinate ring per point"
    (let [[feature] (geo/parse-features multi-point-fc)]
      (is (= 1 (count (geo/parse-features multi-point-fc))))
      (is (= :point (:kind feature)))
      (is (= 3 (count (:rings feature))))
      (is (every? #(= 1 (count %)) (:rings feature)))
      (is (= [[(projection/project 0 0)]
              [(projection/project 1 1)]
              [(projection/project 2 2)]]
             (:rings feature))))))

(deftest multi-line-string-single-feature-one-ring-per-subpath
  (testing "MultiLineString stays one :line feature with one ring per sub-path, point counts preserved"
    (let [[feature] (geo/parse-features multi-line-string-fc)]
      (is (= 1 (count (geo/parse-features multi-line-string-fc))))
      (is (= :line (:kind feature)))
      (is (= 2 (count (:rings feature))))
      (is (= [2 3] (mapv count (:rings feature))))
      (is (= [[(projection/project 0 0) (projection/project 1 1)]
              [(projection/project 2 2) (projection/project 3 3) (projection/project 4 4)]]
             (:rings feature))))))

(deftest properties-preserved-sans-geometry
  (testing "properties come through as a string-keyed map with \"geometry\" stripped"
    (let [[feature] (geo/parse-features properties-with-geometry-fc)]
      (is (= {"name" "x" "id" 5} (:properties feature)))
      (is (= :point (:kind feature))))))

(deftest js-object-input-accepted
  (testing "a plain js object (as fetch .json() or JSON.parse would return) works the same as a clj map"
    (let [[feature] (geo/parse-features (clj->js triangle-fc))]
      (is (= :polygon (:kind feature)))
      (is (= {"name" "triangle" "id" 1} (:properties feature))))))

(deftest bbox-correctness
  (testing "feature-bbox matches the projected extremes of the source ring"
    (let [[feature] (geo/parse-features triangle-fc)
          corners (map #(apply projection/project %) [[13 52] [14 52] [13.5 53]])
          xs (map first corners)
          ys (map second corners)
          expected [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]
          [minx miny maxx maxy] (geo/feature-bbox feature)]
      (is (close-pt? [minx miny] [(nth expected 0) (nth expected 1)]))
      (is (close-pt? [maxx maxy] [(nth expected 2) (nth expected 3)]))))
  (testing "features-bbox unions across features"
    (let [features (geo/parse-features multi-polygon-fc)
          [minx miny maxx maxy] (geo/features-bbox features)
          [ex0 ey0] (projection/project 0 0)
          [ex1 ey1] (projection/project 11 11)]
      (is (close-pt? [minx miny] [(min ex0 ex1) (min ey0 ey1)]))
      (is (close-pt? [maxx maxy] [(max ex0 ex1) (max ey0 ey1)]))))
  (testing "features-bbox of an empty collection returns the documented Infinity sentinel, not a crash"
    (is (= [js/Infinity js/Infinity js/-Infinity js/-Infinity]
           (geo/features-bbox [])))))
