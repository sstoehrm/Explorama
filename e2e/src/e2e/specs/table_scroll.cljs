(ns e2e.specs.table-scroll
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

(def ^:private scroll-offset 360)

(defn- cell [frame testid column-index]
  (.first (.locator frame (str "[data-testid=\"" testid "\"]"
                               "[data-column-index=\"" column-index "\"]"))))

(defspec "the table header stays aligned with the body when scrolled sideways"
  (fn [page expect]
    (p/let [_ (search/run-with-datasource page expect dataset/netflix-name)
            _ (ws/create-frame page "#tool-table" 700 500)
            _ (ws/connect page :search :table)
            frame (ws/frame page :table)
            _ (-> (expect frame)
                  (.toContainText dataset/netflix-name #js {:timeout 60000}))
            body (.locator frame "[data-testid=\"table-body\"]")
            _ (-> (expect body) (.toBeVisible))
            _ (.evaluate body
                         "(el, offset) => { el.scrollLeft = offset; el.dispatchEvent(new Event('scroll')); }"
                         scroll-offset)
            _ (.waitForTimeout page 1000)
            header-cell (cell frame "table-header-cell" 2)
            body-cell (cell frame "table-body-cell" 2)
            _ (-> (expect header-cell) (.toBeVisible))
            _ (-> (expect body-cell) (.toBeVisible))
            header-box (.boundingBox header-cell)
            body-box (.boundingBox body-cell)
            drift (js/Math.abs (- (.-x header-box) (.-x body-box)))]
      (-> (expect drift) (.toBeLessThan 2)))))
