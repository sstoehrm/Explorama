(ns de.explorama.frontend.map.pixi.projection)

(def ^:const max-lat 85.05112878)

(defn clamp-lat [lat]
  (-> lat (min max-lat) (max (- max-lat))))

(defn project
  "Web Mercator. Returns [x y] normalized to [0,1]. lon/lat in degrees."
  [lon lat]
  (let [lat (clamp-lat lat)
        x (/ (+ lon 180.0) 360.0)
        sin-lat (js/Math.sin (* lat (/ js/Math.PI 180.0)))
        y (- 0.5 (/ (js/Math.log (/ (+ 1 sin-lat) (- 1 sin-lat)))
                    (* 4 js/Math.PI)))]
    [x y]))

(defn unproject
  "Inverse of project. [x y] in [0,1] -> [lon lat] degrees."
  [x y]
  (let [lon (- (* x 360.0) 180.0)
        n (* js/Math.PI (- 1 (* 2 y)))
        lat (* (/ 180.0 js/Math.PI) (js/Math.atan (js/Math.sinh n)))]
    [lon lat]))

(defn world-px
  "World width in pixels at zoom: 256 * 2^zoom."
  [zoom]
  (* 256 (js/Math.pow 2 zoom)))
