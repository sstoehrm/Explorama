(ns e2e.pages.search
  (:require [e2e.pages.workspace :as ws]
            [promesa.core :as p]))

;; Ids are frame-scoped, so both locators are prefix matches inside the frame.
(defn traffic-light [page]
  (.locator (ws/frame page :search) "[id^=search-traffic-light-]"))

(defn- run-button [page]
  (.locator (ws/frame page :search) "[id^=search-run-]"))

(defn open [page]
  (p/do
    (ws/create-frame page "#tool-search" 480 340)
    (.click (.first (.getByText (ws/frame page :search) "Topic/Datasource")))))

;; The picker is a custom combobox, not a <select>: open it, then choose the
;; option by its visible label.
(defn select-datasource [page label]
  (p/do
    (.click (.first (.getByText (ws/frame page :search) "Select...")))
    (.click (.first (.getByText page label #js {:exact true})))))

(defn run [page expect]
  (p/do
    (-> (expect (run-button page)) (.toBeEnabled #js {:timeout 30000}))
    (.click (run-button page))
    (-> (expect (run-button page)) (.toBeEnabled #js {:timeout 60000}))))
