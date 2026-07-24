(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            ["pixi.js-legacy" :refer [Application Graphics]]))

(defn- boot-pixi! []
  (let [canvas (.getElementById js/document "map-canvas")
        app (Application. (clj->js {:autoStart true
                                    :width (.-clientWidth canvas)
                                    :height (.-clientHeight canvas)
                                    :backgroundColor 0xEAEAEA
                                    :antialias true
                                    :resolution (or js/window.devicePixelRatio 1)
                                    :autoDensity true
                                    :view canvas}))
        g (Graphics.)]
    (.beginFill g 0x3366cc)
    (.drawRect g 40 40 160 100)
    (.endFill g)
    (.addChild (.-stage app) g)
    app))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot-pixi!))
    :reagent-render (fn [] [:canvas {:id "map-canvas"}])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
