(ns e2e.specs.map-interactions
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.map :as gmap]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

(defn- click-single-marker
  "Find a single (non-cluster) marker and click it (double-click when dbl?);
   resolves to the page [x y] that was clicked."
  ([page] (click-single-marker page false))
  ([page dbl?]
   (p/let [found (gmap/find-single-marker page (gmap/canvas page))]
     (when-not found
       (throw (js/Error. "no single (non-cluster) marker found on the map canvas")))
     (p/let [box (.boundingBox (gmap/canvas page))]
       (let [{:keys [blob meta]} found
             x (+ (.-x box) (* (.-cx blob) (/ (.-cssW meta) (.-w meta))))
             y (+ (.-y box) (* (.-cy blob) (/ (.-cssH meta) (.-h meta))))]
         (p/do
           (if dbl?
             (.dblclick (.-mouse page) x y)
             (.click (.-mouse page) x y))
           [x y]))))))

(defspec "the map popup's close control hides it"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        (gmap/setup-connected-map page expect)
        (click-single-marker page)
        (-> (expect (.locator page ".map-popup"))
            (.toContainText dataset/netflix-name #js {:timeout 15000}))
        (.click (.getByText (.locator page ".map-popup") "×"))
        (-> (expect (.locator page ".map-popup"))
            (.toHaveCount 0 #js {:timeout 15000}))))))

(defspec "double-clicking a marker opens the details view"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        (gmap/setup-connected-map page expect)
        (click-single-marker page true)
        (-> (expect (.locator page "#explorama-sidebar"))
            (.toContainText "Details view" #js {:timeout 20000}))
        (-> (expect (.locator page "#explorama-sidebar"))
            (.toContainText dataset/netflix-name #js {:timeout 20000}))))))

(defspec "minimizing and restoring a map frame keeps it interactive"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        (gmap/setup-connected-map page expect)
        (.click (.getByRole (ws/frame page :map) "button" #js {:name "Minimize"}))
        (-> (expect (.locator (ws/frame page :map) ".window__body"))
            (.toBeHidden #js {:timeout 10000}))
        (.click (.getByRole (ws/frame page :map) "button" #js {:name "Normalize"}))
        (-> (expect (.locator (ws/frame page :map) "canvas"))
            (.toBeVisible #js {:timeout 10000}))
        (.waitForTimeout page 1500)
        ;; the restored engine must still render markers and answer clicks
        (click-single-marker page)
        (-> (expect (.locator page ".map-popup"))
            (.toContainText dataset/netflix-name #js {:timeout 15000}))))))

(defspec "ctrl-clicking a marker draws the highlight ring, not the popup"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        (gmap/setup-connected-map page expect)
        (p/let [red-before (gmap/highlight-ring-pixels (gmap/canvas page))
                found (gmap/find-single-marker page (gmap/canvas page))]
          (when-not found
            (throw (js/Error. "no single (non-cluster) marker found on the map canvas")))
          (p/let [box (.boundingBox (gmap/canvas page))]
            (let [{:keys [blob meta]} found
                  x (+ (.-x box) (* (.-cx blob) (/ (.-cssW meta) (.-w meta))))
                  y (+ (.-y box) (* (.-cy blob) (/ (.-cssH meta) (.-h meta))))]
              (p/do
                (.down (.-keyboard page) "Control")
                (.click (.-mouse page) x y)
                (.up (.-keyboard page) "Control")
                (.waitForTimeout page 1500)
                (p/let [red-after (gmap/highlight-ring-pixels (gmap/canvas page))]
                  (-> (expect red-after) (.toBeGreaterThan (+ red-before 20)))
                  (-> (expect (.locator page ".map-popup"))
                      (.toHaveCount 0)))))))))))

(defspec "switching the base layer fetches tiles from the new source"
  (fn [page expect]
    (p/let [tile-requests (ws/stub-map-tiles page)]
      (p/do
        (ws/open-workspace page)
        (ws/create-frame page "#tool-map" 800 400)
        (-> (expect (.locator (ws/frame page :map) "canvas"))
            (.toBeVisible #js {:timeout 30000}))
        (.waitForTimeout page 1500)
        (.click (.first (.getByText (ws/frame page :map) "Edit" #js {:exact true})))
        (.click (.first (.getByText (ws/frame page :map) "Base german" #js {:exact true})))
        (.click (.first (.getByText page "Swiss Standard" #js {:exact true})))
        (-> (expect (ws/frame page :map))
            (.toContainText "Swiss Standard" #js {:timeout 15000}))
        (.waitForTimeout page 2000)
        (p/let [requests (tile-requests)]
          (-> (expect (boolean (some #(= "tile.osm.ch"
                                         (.-hostname (js/URL. (.url %))))
                                     requests)))
              (.toBe true)))))))

(defspec "the geographic location picker renders its pixi mini-map"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (search/open page)
      (.click (.first (.getByText (ws/frame page :search) "Geographic")))
      (.click (.first (.getByText (ws/frame page :search) "location" #js {:exact true})))
      (-> (expect (.locator (ws/frame page :search) ".map-input"))
          (.toBeVisible #js {:timeout 15000}))
      (-> (expect (.locator (ws/frame page :search) ".map-input canvas"))
          (.toHaveCount 1 #js {:timeout 15000})))))
