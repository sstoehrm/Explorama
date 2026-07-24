(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.pixi.engine :as engine]))

(def wmts-template
  "https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_farbe/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png")

(defonce engine-ref (atom nil))

(defn- demo-markers [n]
  (mapv (fn [i]
          {:id i
           :lon (+ 6.0 (rand 9.0))     ; roughly across Germany
           :lat (+ 47.5 (rand 7.0))
           :color (rand-nth [0xd62728 0x1f77b4 0x2ca02c 0xff7f0e])})
        (range n)))

(defn- boot! []
  (let [canvas (.getElementById js/document "map-canvas")
        e (engine/create!
           {:canvas canvas
            :tile-template wmts-template
            :viewport {:center [10.5 51.0] :zoom 6 :min-zoom 1 :max-zoom 19}})]
    (reset! engine-ref e)
    (engine/set-markers! e (demo-markers 1000))))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot!))
    :reagent-render
    (fn []
      [:div
       [:div.sandbox-toolbar
        [:button {:on-click #(engine/set-markers! @engine-ref (demo-markers 1000))}
         "Regenerate 1k"]]
       [:canvas {:id "map-canvas"}]])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
