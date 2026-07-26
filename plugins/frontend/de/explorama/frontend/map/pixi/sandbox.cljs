(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.geo :as geo]
            [de.explorama.frontend.map.pixi.popup :as popup]
            [de.explorama.frontend.map.pixi.projection :as projection]))

(def wmts-template
  "https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_farbe/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png")

(defonce engine-ref (atom nil))
(defonce popup-state (r/atom nil))
(defonce vp-tick (r/atom 0))
(defonce demo-overlay-state (r/atom {:added? false :visible? false}))
(defonce demo-arrows-state (r/atom {:added? false :visible? false}))
(defonce demo-heatmap-state (r/atom {:added? false :visible? false}))
(defonce hovered-arrow (r/atom nil))

(def demo-overlay-id ::demo-overlay)
(def demo-arrows-id ::demo-arrows)
(def demo-heatmap-id ::demo-heatmap)

(def demo-overlay-geojson
  "A small hand-rolled polygon roughly covering Germany with a rectangular
   hole in the middle, so the sandbox visually exercises beginHole/endHole."
  {"type" "FeatureCollection"
   "features"
   [{"type" "Feature"
     "properties" {"name" "Demo overlay"}
     "geometry"
     {"type" "Polygon"
      "coordinates" [[[6.0 47.5] [15.0 47.5] [15.0 55.0] [6.0 55.0] [6.0 47.5]]
                     [[9.5 50.0] [11.5 50.0] [11.5 52.5] [9.5 52.5] [9.5 50.0]]]}}]})

(defn- toggle-demo-overlay! []
  (let [e @engine-ref
        {:keys [added? visible?]} @demo-overlay-state]
    (if added?
      (let [next-visible? (not visible?)]
        (engine/set-vector-layer-visible! e demo-overlay-id next-visible?)
        (swap! demo-overlay-state assoc :visible? next-visible?))
      (do
        (engine/add-vector-layer! e demo-overlay-id
          {:features (geo/parse-features demo-overlay-geojson)
           :style {:stroke-width 2 :stroke-color 0x4b4f53 :stroke-alpha 0.9
                   :fill-color 0x364655 :fill-alpha 0.35}
           :visible? true})
        (reset! demo-overlay-state {:added? true :visible? true})))))

(defn- demo-markers [n]
  (mapv (fn [i]
          {:id i
           :lon (+ 6.0 (rand 9.0))     ; roughly across Germany
           :lat (+ 47.5 (rand 7.0))
           :color (rand-nth [0xd62728 0x1f77b4 0x2ca02c 0xff7f0e])})
        (range n)))

(def demo-arrows-data
  "A handful of hand-picked city-to-city arrows across Germany at the three
   weights called out in the stage-3 brief, to visually exercise the taper
   (thin/medium/thick shafts) and hover highlight."
  (let [ll->world (fn [lon lat] (projection/project lon lat))]
    [{:id 0 :fw (ll->world 13.405 52.52) :tw (ll->world 11.582 48.135)
      :weight 2 :original 2 :attribute "Berlin -> Munich"}
     {:id 1 :fw (ll->world 9.993 53.551) :tw (ll->world 8.6821 50.1109)
      :weight 8 :original 8 :attribute "Hamburg -> Frankfurt"}
     {:id 2 :fw (ll->world 6.9603 50.9375) :tw (ll->world 13.405 52.52)
      :weight 16 :original 16 :attribute "Cologne -> Berlin"}]))

(defn- demo-heatmap-points [n]
  (mapv (fn [_]
          (let [lon (+ 6.0 (rand 9.0))
                lat (+ 47.5 (rand 7.0))
                [wx wy] (projection/project lon lat)]
            {:wx wx :wy wy :weight (inc (rand-int 10))}))
        (range n)))

(defn- toggle-demo-arrows! []
  (let [e @engine-ref
        {:keys [added? visible?]} @demo-arrows-state]
    (if added?
      (let [next-visible? (not visible?)]
        (engine/set-arrow-layer-visible! e demo-arrows-id next-visible?)
        (swap! demo-arrows-state assoc :visible? next-visible?))
      (do
        (engine/add-arrow-layer! e demo-arrows-id {:arrows demo-arrows-data :visible? true})
        (reset! demo-arrows-state {:added? true :visible? true})))))

(defn- toggle-demo-heatmap! []
  (let [e @engine-ref
        {:keys [added? visible?]} @demo-heatmap-state]
    (if added?
      (let [next-visible? (not visible?)]
        (engine/set-heatmap-layer-visible! e demo-heatmap-id next-visible?)
        (swap! demo-heatmap-state assoc :visible? next-visible?))
      (do
        (engine/add-heatmap-layer! e demo-heatmap-id {:points (demo-heatmap-points 200) :visible? true})
        (reset! demo-heatmap-state {:added? true :visible? true})))))

(defn- boot! []
  (let [canvas (.getElementById js/document "map-canvas")
        e (engine/create!
           {:canvas canvas
            :tile-template wmts-template
            :viewport {:center [10.5 51.0] :zoom 6 :min-zoom 1 :max-zoom 19}})]
    (reset! engine-ref e)
    (engine/set-markers! e (demo-markers 1000))
    (engine/on-change! e (fn [_] (swap! vp-tick inc)))
    (engine/on-pick e
      (fn [node _evt]
        (reset! popup-state
                (when (and node (not (:cluster? node)))
                  {:lon (:lon node) :lat (:lat node)
                   :content [:div
                             [:strong "Event " (str (:id node))]
                             [:div (str "lon " (.toFixed (:lon node) 3)
                                        ", lat " (.toFixed (:lat node) 3))]]}))))
    (engine/on-hover-arrow! e (fn [hit _evt] (reset! hovered-arrow hit)))))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot!))
    :reagent-render
    (fn []
      [:div
       [:div.sandbox-toolbar
        [:button {:on-click #(engine/set-markers! @engine-ref (demo-markers 1000))}
         "Regenerate 1k"]
        [:button {:on-click #(engine/fit-markers! @engine-ref)} "Zoom to data"]
        [:button {:on-click #(engine/set-cluster! @engine-ref (not (:cluster? @(:state @engine-ref))))}
         "Toggle clustering"]
        [:button {:on-click #(engine/set-highlighted! @engine-ref (set (range 10)))}
         "Highlight 10"]
        [:button {:on-click toggle-demo-overlay!} "Demo overlay"]
        [:button {:on-click toggle-demo-arrows!} "Demo arrows"]
        [:button {:on-click toggle-demo-heatmap!} "Demo heatmap"]
        (when-let [hit @hovered-arrow]
          [:span.sandbox-hover-arrow (str "hover: " (:attribute (:arrow hit)))])]
       [:canvas {:id "map-canvas"}]
       [popup/popup-view popup-state vp-tick engine-ref]])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
