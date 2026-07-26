(ns de.explorama.frontend.map.pixi.tile-source
  (:require [clojure.string :as str]))

(def half-extent
  "EPSG:3857 world half-extent in meters (half the projected world width,
   also used as its height since the projection is square)."
  20037508.342789244)

(defn normalize
  "Coerces a base-layer source into a canonical source desc
   {:type :xyz|:wms|:esri :url <str> :wms-layers <str>} used by source-url.
   A bare string is treated as an :xyz tile URL template (back-compat with
   the pre-stage-4 :tile-template string opt). A map has its :type
   keywordized (accepts either a string or an already-keyword :type from
   config) and every other key - notably :wms-layers, already a comma-joined
   string from config - passed through verbatim. nil passes through as nil -
   the engine's \"no tiles\" state."
  [source]
  (cond
    (nil? source) nil
    (string? source) {:type :xyz :url source}
    :else (update source :type keyword)))

(defn tile->bbox-3857
  "EPSG:3857 bounding box of tile {:z :x :y} in meters: [minx miny maxx maxy].
   Tile row 0 is the NORTH edge, i.e. maxy = half-extent at z0/row 0."
  [{:keys [z x y]}]
  (let [size (/ (* 2 half-extent) (js/Math.pow 2 z))
        minx (+ (- half-extent) (* x size))
        maxy (- half-extent (* y size))
        maxx (+ minx size)
        miny (- maxy size)]
    [minx miny maxx maxy]))

(defn- bbox-param [tile]
  (str/join "," (tile->bbox-3857 tile)))

(defn- append-params
  "OL appendParams parity: a base url that already carries a query string
   (contains ?) gets its own params joined with &, otherwise a fresh query
   is started with ?."
  [url]
  (if (str/includes? url "?") "&" "?"))

(defn- xyz-url
  "Reproduces tiles.cljs's tile-url template replacement ({z}/{x}/{y})."
  [{:keys [url]} {:keys [z x y]}]
  (-> url
      (str/replace "{z}" (str z))
      (str/replace "{x}" (str x))
      (str/replace "{y}" (str y))))

(defn- wms-url
  "WMS 1.3.0 GetMap URL, OL-parity. Param order is fixed (not alphabetical or
   spec-declaration order) so the resulting URL - used verbatim as a cache /
   settle-tracking key - is deterministic across calls:
   SERVICE, VERSION, REQUEST, LAYERS, STYLES, FORMAT, TILED, CRS, WIDTH,
   HEIGHT, BBOX. No TRANSPARENT param (OL's TileWMS default doesn't set one)."
  [{:keys [url wms-layers]} tile]
  (str url
       (append-params url) "SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
       "&LAYERS=" wms-layers
       "&STYLES=&FORMAT=image/png&TILED=true&CRS=EPSG:3857"
       "&WIDTH=256&HEIGHT=256"
       "&BBOX=" (bbox-param tile)))

(defn- split-query
  "[base-path existing-query-or-nil] - splits a url on its first ? so a path
   segment (e.g. /export) can be inserted before the query rather than
   after it."
  [url]
  (let [i (str/index-of url "?")]
    (if i
      [(subs url 0 i) (subs url (inc i))]
      [url nil])))

(defn- esri-url
  "ESRI export URL, OL-parity (TileArcGISRest defaults). Param order fixed
   for the same cache-key-determinism reason as wms-url:
   F, FORMAT, TRANSPARENT, SIZE, BBOXSR, IMAGESR, BBOX. No LAYERS (whole
   service) or DPI param. /export is a path segment, so any existing query
   on the base :url is split off first and re-appended after it rather than
   appendParams'd onto the base url directly (which would strand /export
   inside the old query string)."
  [{:keys [url]} tile]
  (let [[base existing-query] (split-query url)]
    (str base "/export"
         (if existing-query (str "?" existing-query "&") "?")
         "F=image&FORMAT=PNG32&TRANSPARENT=true&SIZE=256,256"
         "&BBOXSR=3857&IMAGESR=3857"
         "&BBOX=" (bbox-param tile))))

(defn source-url
  "Per-tile fetch URL for a normalized source desc (see normalize)."
  [{:keys [type] :as source} tile]
  (case type
    :xyz (xyz-url source tile)
    :wms (wms-url source tile)
    :esri (esri-url source tile)))
