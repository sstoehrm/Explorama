(ns e2e.specs.core-data-journey
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.search :as search]
            [promesa.core :as p]))

(defspec "a datasource search reports a result-set estimate"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (search/open page)
      (search/select-datasource page "Netflix")
      (-> (expect (search/traffic-light page))
          (.toContainText "323 Events" #js {:timeout 30000})))))

(defspec "running a search switches the action to apply-changes"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (search/open page)
      (search/select-datasource page "Netflix")
      (search/run page expect)
      (-> (expect (ws/frame page :search))
          (.toContainText "Apply changes" #js {:timeout 30000})))))

(defspec "connecting a search to a table renders the data"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (search/open page)
      (search/select-datasource page "Netflix")
      (search/run page expect)
      (ws/create-frame page "#tool-table" 1080 600)
      (ws/connect page :search :table)
      (-> (expect (ws/frame page :table))
          (.toContainText "323 Events" #js {:timeout 60000}))
      (-> (expect (ws/frame page :table))
          (.toContainText "Netflix" #js {:timeout 60000})))))
