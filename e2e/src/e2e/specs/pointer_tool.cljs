(ns e2e.specs.pointer-tool
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

;; The header tool renders as navbar-item-<tool-id>-<icon>, so the tool id
;; alone is the stable part of it.
(def ^:private tool-sel "[id^=\"navbar-item-tool-markers\"]")
(def ^:private active-icon-sel (str tool-sel " span[class*='icon-magic']"))

;; A drag past the marker-toggle threshold (5px in frame/view/header.cljs)
;; must not also toggle the marker.
(defn- drag-header [page header]
  (p/let [hb (.boundingBox header)
          sx (+ (.-x hb) (/ (.-width hb) 2))
          sy (+ (.-y hb) (/ (.-height hb) 2))]
    (p/do
      (.move (.-mouse page) sx sy)
      (.down (.-mouse page))
      (.move (.-mouse page) sx (+ sy 30) #js {:steps 10})
      (.waitForTimeout page 200)
      (.up (.-mouse page)))))

(defspec "marker mode marks a frame from its header and the badge takes a note"
  (fn [page expect]
    (p/let [_ (search/run-with-datasource page expect dataset/netflix-name)
            header (.locator (ws/frame page :search) ".window__header")]
      (p/do
        (.click (.locator page tool-sel))
        (-> (expect (.locator page active-icon-sel)) (.toHaveClass #"active"))
        ;; ctrl+click stays the multiselect gesture, not a marker toggle.
        (.click header #js {:modifiers #js ["Control"]})
        (-> (expect (.locator (ws/frame page :search) "[data-marker]")) (.toHaveCount 0))
        ;; a plain drag of the header must not toggle it either.
        (drag-header page header)
        (-> (expect (.locator (ws/frame page :search) "[data-marker]")) (.toHaveCount 0))
        (.click header)
        (-> (expect (.locator (ws/frame page :search) "[data-marker]"))
            (.toBeVisible #js {:timeout 10000}))
        (.fill (.locator (ws/frame page :search) "[data-marker] input") "look here")
        (-> (expect (.locator (ws/frame page :search) "[data-marker] input"))
            (.toHaveValue "look here"))
        (.click header)
        (-> (expect (.locator (ws/frame page :search) "[data-marker]"))
            (.toHaveCount 0))
        (.click (.locator page tool-sel))
        (-> (expect (.locator page active-icon-sel)) .-not (.toHaveClass #"active"))))))
