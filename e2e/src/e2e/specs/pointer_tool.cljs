(ns e2e.specs.pointer-tool
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

;; The header tool renders as navbar-item-<tool-id>-<icon>, so the tool id
;; alone is the stable part of it.
(def ^:private tool-sel "[id^=\"navbar-item-tool-markers\"]")

(defspec "marker mode marks a frame from its header and the badge takes a note"
  (fn [page expect]
    (p/do
      (search/run-with-datasource page expect dataset/netflix-name)
      (.click (.locator page tool-sel))
      (.click (.locator (ws/frame page :search) ".window__header"))
      (-> (expect (.locator (ws/frame page :search) "[data-marker]"))
          (.toBeVisible #js {:timeout 10000}))
      (.fill (.locator (ws/frame page :search) "[data-marker] input") "look here")
      (-> (expect (.locator (ws/frame page :search) "[data-marker] input"))
          (.toHaveValue "look here"))
      (.click (.locator (ws/frame page :search) ".window__header"))
      (-> (expect (.locator (ws/frame page :search) "[data-marker]"))
          (.toHaveCount 0)))))
