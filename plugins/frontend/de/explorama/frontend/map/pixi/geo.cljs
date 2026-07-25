(ns de.explorama.frontend.map.pixi.geo
  "GeoJSON parsing and polygon hit-testing for the Pixi map engine's vector
   layers (overlayers + area feature layers).

   GeoJSON coordinates are already `[lon lat]` order per the spec - unlike
   the marker path elsewhere in this plugin (which is fed `[lat lon]`
   tuples and reverses them), nothing here reverses coordinate order.
   Every coordinate pair is projected exactly once via
   `de.explorama.frontend.map.pixi.projection/project` into normalized
   [0,1] world coordinates, and everything downstream (hit-testing,
   bbox, rendering) works in that world space."
  (:require [de.explorama.frontend.map.pixi.projection :as projection]))

(defn- ->clj
  "Accepts either a js object (as produced by `js/JSON.parse` or `fetch`
   `.json()`) or a clj map and normalizes to a clj map with string keys -
   GeoJSON's own keys (\"type\", \"features\", \"geometry\", \"coordinates\",
   \"properties\") are always strings."
  [geojson]
  (if (map? geojson)
    geojson
    (js->clj geojson :keywordize-keys false)))

(defn- project-coord [[lon lat]]
  (projection/project lon lat))

(defn- project-ring [ring]
  (mapv project-coord ring))

(defn- geometry->features [geometry properties]
  (let [{:strs [type coordinates]} geometry]
    (case type
      "Polygon"
      [{:properties properties
        :kind :polygon
        :rings (mapv project-ring coordinates)}]

      "MultiPolygon"
      (mapv (fn [polygon]
              {:properties properties
               :kind :polygon
               :rings (mapv project-ring polygon)})
            coordinates)

      "LineString"
      [{:properties properties
        :kind :line
        :rings [(project-ring coordinates)]}]

      "MultiLineString"
      [{:properties properties
        :kind :line
        :rings (mapv project-ring coordinates)}]

      "Point"
      [{:properties properties
        :kind :point
        :rings [[(project-coord coordinates)]]}]

      "MultiPoint"
      [{:properties properties
        :kind :point
        :rings (mapv (fn [coord] [(project-coord coord)]) coordinates)}]

      [])))

(defn- parse-feature [{:strs [geometry properties]}]
  (geometry->features geometry (dissoc properties "geometry")))

(defn parse-features
  "GeoJSON (js object or clj map, a FeatureCollection or a bare Feature) ->
   `[feature]` with feature = `{:properties {..} :kind :polygon|:line|:point
   :rings [[[wx wy] ...] ...]}`.

   Coordinates are projected to normalized [0,1] world space. For polygons
   ring 0 is the outer ring and any remaining rings are holes. A
   MultiPolygon fans out into one `:polygon` feature per polygon, all
   sharing the source feature's `:properties`. Lines carry one path per
   ring entry; points carry single-coordinate rings (one ring per point
   for MultiPoint)."
  [geojson]
  (let [{:strs [type features] :as gj} (->clj geojson)]
    (case type
      "FeatureCollection" (into [] (mapcat parse-feature) features)
      "Feature" (parse-feature gj)
      [])))

(defn- ray-cast
  "Standard even-odd ray-casting point-in-polygon test over a single ring
   of `[wx wy]` world points."
  [ring wx wy]
  (let [n (count ring)]
    (loop [i 0 j (dec n) inside? false]
      (if (= i n)
        inside?
        (let [[xi yi] (nth ring i)
              [xj yj] (nth ring j)
              crosses? (and (not= (> yi wy) (> yj wy))
                            (< wx (+ xi (* (/ (- xj xi) (- yj yi)) (- wy yi)))))]
          (recur (inc i) i (if crosses? (not inside?) inside?)))))))

(defn point-in-feature?
  "Ray-casting hit test in world coordinates. Only polygons participate
   (lines/points are picked by on-screen distance at render time, an
   engine concern, not this pure module's). Holes (rings 1+) are honored:
   a point inside the outer ring but inside any hole is not a hit."
  [{:keys [kind rings]} wx wy]
  (boolean
   (and (= kind :polygon)
        (seq rings)
        (ray-cast (first rings) wx wy)
        (not-any? #(ray-cast % wx wy) (rest rings)))))

(defn feature-bbox
  "`[minx miny maxx maxy]` in world coordinates over every ring of `feature`."
  [{:keys [rings]}]
  (reduce (fn [[minx miny maxx maxy] [x y]]
            [(min minx x) (min miny y) (max maxx x) (max maxy y)])
          [js/Infinity js/Infinity js/-Infinity js/-Infinity]
          (apply concat rings)))

(defn features-bbox
  "Union bbox (world coordinates) over `features`. `[]` in ->
   `[Infinity Infinity -Infinity -Infinity]` out - an inert sentinel rather
   than nil or a thrown error, chosen so a caller that folds this into a
   further min/max (e.g. combining with another bbox) doesn't need a nil
   check; callers doing a real zoom-to-layer are expected to only invoke
   this on a non-empty layer."
  [features]
  (reduce (fn [[minx miny maxx maxy] feature]
            (let [[fminx fminy fmaxx fmaxy] (feature-bbox feature)]
              [(min minx fminx) (min miny fminy) (max maxx fmaxx) (max maxy fmaxy)]))
          [js/Infinity js/Infinity js/-Infinity js/-Infinity]
          features))
