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

(defspec "ctrl-click toggles the marker highlight ring, never the popup"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        ;; taller viewport: the selection round-trip re-centers the map on
        ;; the highlighted marker, and with the default 1000px height that
        ;; position lands under the frame's floating bottom toolbar, which
        ;; would swallow the toggle-off click
        (.setViewportSize page #js {:width 1600 :height 1400})
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
                (p/let [red-after (gmap/highlight-ring-pixels (gmap/canvas page))
                        ring (gmap/highlight-ring-centroid (gmap/canvas page))
                        box2 (.boundingBox (gmap/canvas page))]
                  (-> (expect red-after) (.toBeGreaterThan (+ red-before 20)))
                  (-> (expect (.locator page ".map-popup"))
                      (.toHaveCount 0))
                  ;; second ctrl-click on the (re-centered) marker toggles off
                  (p/do
                    (.down (.-keyboard page) "Control")
                    (.click (.-mouse page)
                            (+ (.-x box2) (* (.-x ring) (/ (.-cssW ring) (.-w ring))))
                            (+ (.-y box2) (* (.-y ring) (/ (.-cssH ring) (.-h ring)))))
                    (.up (.-keyboard page) "Control")
                    (.waitForTimeout page 1500)
                    (p/let [red-toggled (gmap/highlight-ring-pixels (gmap/canvas page))]
                      (-> (expect red-toggled)
                          (.toBeLessThan (+ red-before 20))))))))))))))

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

(def ^:private arm-contextmenu-recorder
  (js/Function. "cv"
                "window.__ctxmenu = [];
                 cv.addEventListener('contextmenu', (e) => window.__ctxmenu.push(e.defaultPrevented));"))

(defspec "right-drag pans the map; the native context menu stays suppressed"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        ;; keep the canvas midpoint clear of the frame's floating bottom
        ;; toolbar (same geometry trap as the ctrl-click toggle spec)
        (.setViewportSize page #js {:width 1600 :height 1400})
        (gmap/setup-connected-map page expect)
        (.evaluate (gmap/canvas page) arm-contextmenu-recorder)
        (p/let [before (.evaluate (gmap/canvas page) gmap/scan-markers)
                box (.boundingBox (gmap/canvas page))]
          (let [cx (+ (.-x box) (/ (.-width box) 2))
                cy (+ (.-y box) (/ (.-height box) 2))]
            (p/do
              ;; right button is a default panning assignment (see
              ;; shared/common/configs/mouse.cljc); the engine must own the
              ;; gesture (not the woco workspace panning underneath)
              (.move (.-mouse page) cx cy)
              (.down (.-mouse page) #js {:button "right"})
              (.move (.-mouse page) (+ cx 80) (+ cy 60) #js {:steps 10})
              (.up (.-mouse page) #js {:button "right"})
              (.waitForTimeout page 1000)
              (p/let [after (.evaluate (gmap/canvas page) gmap/scan-markers)]
                (let [centroid (fn [meta]
                                 (let [blobs (vec (.-blobs meta))
                                       n (count blobs)]
                                   [(/ (reduce + (map #(.-cx %) blobs)) n)
                                    (/ (reduce + (map #(.-cy %) blobs)) n)]))
                      [bx by] (centroid before)
                      [ax ay] (centroid after)]
                  ;; blob population can change at the pan fringe, so allow a
                  ;; loose tolerance around the 80/60 drag delta
                  (-> (expect (js/Math.abs (- (- ax bx) 80))) (.toBeLessThan 30))
                  (-> (expect (js/Math.abs (- (- ay by) 60))) (.toBeLessThan 30))
                  (p/do
                    (.click (.-mouse page) (+ cx 80) (+ cy 60) #js {:button "right"})
                    (.waitForTimeout page 500)
                    (p/let [prevented (.evaluate page "window.__ctxmenu")]
                      (-> (expect (boolean (some true? prevented)))
                          (.toBe true)))))))))))))

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
