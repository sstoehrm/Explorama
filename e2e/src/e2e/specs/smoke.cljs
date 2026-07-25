(ns e2e.specs.smoke
  (:require [e2e.registry :refer [defspec]]
            [promesa.core :as p]))

(defspec "the workspace boots and renders the tool palette"
  (fn [page expect]
    (p/do
      (.goto page "/" #js {:waitUntil "load"})
      (-> (expect page) (.toHaveTitle "Explorama"))
      (-> (expect (.locator page "#workspace-root")) (.toBeVisible))
      (-> (expect (.locator page "[id^=tool-]")) (.toHaveCount 7)))))
