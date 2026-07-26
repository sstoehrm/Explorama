(ns de.explorama.frontend.search.views.components.location-region
  "Pure geometry helpers for the Pixi-based location picker: no engine, DOM,
   or re-frame dependency, so these are unit-testable without a browser."
  (:require [de.explorama.frontend.map.pixi.projection :as projection]))

(defn corners->values
  "Two `[lat lng]` corners of an axis-aligned rectangle (either diagonal, in
   either order) -> `[min-lat min-lng max-lat max-lng]`."
  [[lat1 lng1] [lat2 lng2]]
  [(min lat1 lat2) (min lng1 lng2) (max lat1 lat2) (max lng1 lng2)])

(defn values->feature
  "`[min-lat min-lng max-lat max-lng]` -> a `map/pixi` vector-layer polygon
   feature: the 4 corners projected to world coords (`projection/project`
   takes lon first), ring closed by repeating the first point last."
  [[min-lat min-lng max-lat max-lng]]
  (let [corners [[min-lat min-lng]
                 [min-lat max-lng]
                 [max-lat max-lng]
                 [max-lat min-lng]]
        ring (mapv (fn [[lat lng]] (projection/project lng lat)) corners)]
    {:kind :polygon
     :properties {}
     :rings [(conj ring (first ring))]}))

(defn values->extent
  "`[min-lat min-lng max-lat max-lng]` -> `[min-lon min-lat max-lon max-lat]`
   for `viewport/fit-extent`, which expects lon-first pairs."
  [[min-lat min-lng max-lat max-lng]]
  [min-lng min-lat max-lng max-lat])
