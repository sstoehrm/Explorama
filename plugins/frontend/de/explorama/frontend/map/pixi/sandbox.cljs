(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.pixi.engine :as engine]))

(def wmts-template
  "https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_farbe/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png")

(defonce engine-ref (atom nil))

(defn- boot! []
  (let [canvas (.getElementById js/document "map-canvas")]
    (reset! engine-ref
            (engine/create!
             {:canvas canvas
              :tile-template wmts-template
              :viewport {:center [13.4 52.5] :zoom 6
                         :min-zoom 1 :max-zoom 19}}))))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot!))
    :reagent-render (fn [] [:canvas {:id "map-canvas"}])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
