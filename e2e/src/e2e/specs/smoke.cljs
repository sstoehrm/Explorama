(ns e2e.specs.smoke
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [promesa.core :as p]))

(defspec "the workspace boots and renders the tool palette"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (-> (expect page) (.toHaveTitle "Explorama"))
      (-> (expect (.locator page "[id^=tool-]")) (.toHaveCount 7)))))

(defspec "creating a search frame renders the search form"
  (fn [page expect]
    (p/do
      (ws/open-workspace page)
      (ws/create-frame page "#tool-search" 480 380)
      (-> (expect (ws/frame page :search)) (.toBeVisible))
      (-> (expect (ws/frame page :search)) (.toContainText "Topic/Datasource")))))
