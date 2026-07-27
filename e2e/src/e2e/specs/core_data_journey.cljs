(ns e2e.specs.core-data-journey
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.map :as gmap]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

(defspec "a datasource search reports a result-set estimate"
  (fn [page expect]
    (p/do
      (search/open-with-datasource page dataset/netflix-name)
      (-> (expect (search/traffic-light page))
          (.toContainText dataset/netflix-event-count #js {:timeout 30000})))))

(defspec "running a search switches the action to apply-changes"
  (fn [page expect]
    (p/do
      (search/run-with-datasource page expect dataset/netflix-name)
      (-> (expect (ws/frame page :search))
          (.toContainText "Apply changes" #js {:timeout 30000})))))

(defspec "connecting a search to a table renders the data"
  (fn [page expect]
    (p/do
      (search/run-with-datasource page expect dataset/netflix-name)
      (ws/create-frame page "#tool-table" 1080 600)
      (ws/connect page :search :table)
      (-> (expect (ws/frame page :table))
          (.toContainText dataset/netflix-event-count #js {:timeout 60000}))
      (-> (expect (ws/frame page :table))
          (.toContainText dataset/netflix-name #js {:timeout 60000})))))

(defspec "connecting a search to a mosaic renders the canvas"
  (fn [page expect]
    (p/do
      (search/run-with-datasource page expect dataset/netflix-name)
      (ws/create-frame page "#tool-mosaic" 1080 350)
      (ws/connect page :search :mosaic)
      (-> (expect (ws/frame page :mosaic))
          (.toContainText dataset/netflix-event-count #js {:timeout 60000}))
      (-> (expect (.locator (ws/frame page :mosaic) "canvas"))
          (.toBeVisible))
      (-> (expect (.locator (ws/frame page :mosaic) "canvas"))
          (.toHaveCount 1)))))

(defspec "connecting a search to a map renders without contacting tile servers"
  (fn [page expect]
    (p/let [tile-requests (ws/stub-map-tiles page)]
      (p/do
        (search/run-with-datasource page expect dataset/netflix-name)
        (ws/create-frame page "#tool-map" 1080 650)
        (ws/connect page :search :map)
        (-> (expect (ws/frame page :map))
            (.toContainText dataset/netflix-event-count #js {:timeout 60000}))
        (-> (expect (.locator (ws/frame page :map) "canvas"))
            (.toBeVisible))
        (-> (expect (.locator (ws/frame page :map) "canvas"))
            (.toHaveCount 1))
        (ws/assert-no-live-tile-requests expect tile-requests)))))

(defspec "clicking a single map marker opens the event popup"
  (fn [page expect]
    (p/let [_ (ws/stub-map-tiles page)]
      (p/do
        (search/run-with-datasource page expect dataset/netflix-name)
        (ws/create-frame page "#tool-map" 1080 650)
        (ws/connect page :search :map)
        (-> (expect (ws/frame page :map))
            (.toContainText dataset/netflix-event-count #js {:timeout 60000}))
        ;; let zoom-to-data and the first full render settle before scanning
        (.waitForTimeout page 2500)
        (p/let [found (gmap/find-single-marker page (gmap/canvas page))]
          (when-not found
            (throw (js/Error. "no single (non-cluster) marker found on the map canvas")))
          (p/do
            (gmap/click-blob page (gmap/canvas page) (:blob found) (:meta found))
            ;; popup content arrives after the dbl-click debounce plus an
            ;; event-data roundtrip; toContainText polls through both
            (-> (expect (.locator page ".map-popup"))
                (.toContainText dataset/netflix-name #js {:timeout 15000}))))))))
