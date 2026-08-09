(ns e2e.specs.graph-aggregation
  (:require [e2e.registry :refer [defspec]]
            [e2e.pages.workspace :as ws]
            [e2e.pages.indicator :as indicator]
            [e2e.pages.search :as search]
            [e2e.fixtures.dataset :as dataset]
            [promesa.core :as p]))

(def ^:private graph-name "e2e-graph")

;; release_year is the only genuinely numeric fact on the Netflix fixture;
;; "year" is the date-derived pseudo-attribute group-by resolves from each
;; event's occured-at date, not a literal column.
(def ^:private graph-text
  (str "{:nodes {:src {:type :datasource :dataset 1} "
       ":grouped {:type :operation :op :group-by :params {:attributes [\"year\"]}} "
       ":total {:type :operation :op :sum :params {:attribute \"release_year\"}} "
       ":out {:type :result :name \"" graph-name "\"}} "
       ":edges {[:src :grouped] {:direction :->} "
       "[:grouped :total] {:direction :->} "
       "[:total :out] {:direction :-> :as \"indicator\"}}}"))

;; indicator/open places its frame at (60, 150) sized 800x700, so the search
;; frame this spec drags from has to be placed clear of that box -
;; search/open-with-datasource's default (480, 340) sits inside it.
(defn- open-search [page expect]
  (p/do
    (ws/open-workspace page)
    (ws/create-frame page "#tool-search" 900 150)
    (.click (.first (.getByText (ws/frame page :search) "Topic/Datasource")))
    (search/select-datasource page dataset/netflix-name)
    (search/run page expect)))

(defn- assert-clean-validation [page expect]
  (p/do
    (-> (expect (.locator (ws/frame page :indicator) ".text-warning"))
        (.toHaveCount 0))
    (-> (expect (indicator/save-button page))
        (.toBeEnabled #js {:timeout 5000}))))

;; Project replay of a graph aggregation is out of scope for this spec.
;;
;; Step 6 is a within-session stand-in for a page reload: the browser
;; bundle's expdb persistence (bundles/browser/backend/.../expdb/persistence/
;; backend_simple.cljs) is a plain in-memory atom, so a real reload wipes the
;; just-saved graph along with everything else in the backend - it does not
;; come back reseeded, it comes back gone. e2e/src/e2e/specs/fact_units.cljs
;; documents the same limitation for freshly-imported data. What a reload
;; *would* additionally cover - the client-side graph-text/dataset-binding
;; caches actually being empty rather than merely unvisited - is out of
;; reach through the UI in this bundle, so this closes the live search frame
;; and reopens the card from the overview instead: it still proves the saved
;; text, the dataset connection and a clean validation all survive with no
;; live search frame left to drag from.
(defspec "building, saving, publishing and deleting a graph aggregation"
  (fn [page expect]
    (p/do
      (open-search page expect)
      (indicator/open page)
      (-> (expect (indicator/frame page)) (.toContainText "Your Indicators"))
      (indicator/create-graph page)
      (-> (expect (indicator/graph-textarea page))
          (.toHaveValue (js/RegExp. "my-aggregation")))

      (indicator/connect-dataset page :search)
      (-> (expect (indicator/dataset-chip page 1))
          (.toBeVisible #js {:timeout 30000}))

      ;; The overview card is titled from this Name field, independent of the
      ;; graph text's :result node name below - set both to graph-name so the
      ;; card and the published datasource end up with the same label.
      (indicator/set-name page graph-name)
      (indicator/set-graph-text page graph-text)
      (assert-clean-validation page expect)

      (indicator/open-preview-section page)
      (.click (indicator/preview-button page))
      (-> (expect (.locator (ws/frame page :indicator) ".icon-check"))
          (.toBeVisible #js {:timeout 30000}))
      (p/let [row-count (.count (.locator (ws/frame page :indicator) ".prediction__data__list li"))]
        (-> (expect row-count) (.toBeGreaterThan 0)))

      (.click (indicator/save-button page))
      (-> (expect (indicator/discard-button page)) (.toBeDisabled #js {:timeout 30000}))
      (.click (indicator/back-button page))
      (-> (expect (indicator/overview-card page graph-name)) (.toContainText "Graph"))

      (.click (.getByRole (ws/frame page :search) "button" #js {:name "Close"}))
      (indicator/edit-card page graph-name)
      (-> (expect (indicator/graph-textarea page)) (.toHaveValue graph-text))
      (-> (expect (indicator/dataset-chip page 1))
          (.toBeVisible #js {:timeout 30000}))
      (assert-clean-validation page expect)

      (.click (indicator/back-button page))
      (indicator/open-table-visualization page graph-name 950 150)
      (-> (expect (ws/frame page :table))
          (.toContainText graph-name #js {:timeout 60000}))

      (indicator/delete-card page graph-name)
      (-> (expect (.locator page ".dialog-header")) (.toContainText "Delete graph?"))
      (.click (.getByRole page "button" #js {:name "Delete" :exact true}))
      (-> (expect (indicator/overview-card page graph-name))
          (.toHaveCount 0 #js {:timeout 15000})))))
