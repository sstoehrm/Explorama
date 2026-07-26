(ns de.explorama.frontend.map.pixi.tile-source-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.tile-source :as tile-source]))

(def he tile-source/half-extent)

(deftest bbox-z0-full-world
  (is (= [(- he) (- he) he he]
         (tile-source/tile->bbox-3857 {:z 0 :x 0 :y 0}))))

(deftest bbox-z1-nw-quadrant
  (testing "row 0 is the north edge, so x0/y0 is the NW quadrant"
    (is (= [(- he) 0 0 he]
           (tile-source/tile->bbox-3857 {:z 1 :x 0 :y 0})))))

(deftest bbox-z1-se-quadrant
  (is (= [0 (- he) he 0]
         (tile-source/tile->bbox-3857 {:z 1 :x 1 :y 1}))))

(deftest bbox-z2-spot
  (is (= [(- (/ he 2)) 0 0 (/ he 2)]
         (tile-source/tile->bbox-3857 {:z 2 :x 1 :y 1}))))

(deftest normalize-string-source
  (is (= {:type :xyz :url "https://s/{z}/{x}/{y}.png"}
         (tile-source/normalize "https://s/{z}/{x}/{y}.png"))))

(deftest normalize-wms-config-map
  (is (= {:type :wms :url "https://ows.terrestris.de/osm/service" :wms-layers "OSM-WMS"}
         (tile-source/normalize {:type "wms"
                                  :url "https://ows.terrestris.de/osm/service"
                                  :wms-layers "OSM-WMS"}))))

(deftest normalize-keyword-type-passthrough
  (is (= {:type :esri :url "https://server/arcgis/rest/services/Layer/MapServer"}
         (tile-source/normalize {:type :esri
                                  :url "https://server/arcgis/rest/services/Layer/MapServer"}))))

(deftest xyz-source-url-templating
  (is (= "https://s/5/17/10.png"
         (tile-source/source-url {:type :xyz :url "https://s/{z}/{x}/{y}.png"}
                                  {:z 5 :x 17 :y 10}))))

(deftest wms-source-url-exact
  (is (= (str "https://ows.terrestris.de/osm/service"
              "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
              "&LAYERS=OSM-WMS"
              "&STYLES=&FORMAT=image/png&TILED=true&CRS=EPSG:3857"
              "&WIDTH=256&HEIGHT=256"
              "&BBOX=" (- he) ",0,0," he)
         (tile-source/source-url {:type :wms
                                   :url "https://ows.terrestris.de/osm/service"
                                   :wms-layers "OSM-WMS"}
                                  {:z 1 :x 0 :y 0}))))

(deftest esri-source-url-exact
  (is (= (str "https://server/arcgis/rest/services/Layer/MapServer"
              "/export?F=image&FORMAT=PNG32&TRANSPARENT=true&SIZE=256,256"
              "&BBOXSR=3857&IMAGESR=3857"
              "&BBOX=" (- he) ",0,0," he)
         (tile-source/source-url {:type :esri
                                   :url "https://server/arcgis/rest/services/Layer/MapServer"}
                                  {:z 1 :x 0 :y 0}))))
