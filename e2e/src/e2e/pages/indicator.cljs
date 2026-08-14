(ns e2e.pages.indicator
  (:require [e2e.pages.workspace :as ws]
            [promesa.core :as p]))

(defn frame [page]
  (ws/frame page :indicator))

;; A management-type tool still goes through the same window-placement-overlay
;; click ws/create-frame drives for the plain consumer-type tools; only a
;; second open (an existing frame of that vertical) would skip it by bringing
;; the frame to front instead, which this spec never triggers.
;;
;; Placing a frame with its top edge flush against the navbar (y around 60)
;; silently breaks dragging its header later - no error, the drag just never
;; starts - so y stays comfortably clear of it.
(defn open [page]
  (ws/create-frame page "#indicator" 60 150))

(defn create-graph [page]
  (.click (.getByText (frame page) "New aggregation (graph)" #js {:exact true})))

(defn graph-textarea [page]
  (.getByLabel (frame page) "Graph (EDN)"))

(defn set-graph-text [page text]
  (.fill (graph-textarea page) text))

;; The card on the overview shows this Name field, not the graph text's
;; :result node name - the two are independent, so the overview card and the
;; published datasource name only match here because the spec sets both.
(defn set-name [page name]
  (.fill (.getByLabel (frame page) "Name" #js {:exact true}) name))

;; (str ::drop-area) in de.explorama.frontend.indicator.views.graph-editor
;; compiles to ":de.explorama.frontend.indicator.views.graph-editor/drop-area"
;; (str on a keyword keeps the leading colon) - that full string, colon
;; included, is what ends up as the element's id.
(def ^:private drop-area-id
  ":de.explorama.frontend.indicator.views.graph-editor/drop-area")

(defn drop-area [page]
  (.locator (frame page) (str "[id=\"" drop-area-id "\"]")))

;; Unlike the other consumer frames ws/connect targets, the indicator frame
;; is created with :ignore-drop-on-frame? true, which disables the generic
;; whole-frame drop target entirely - a dataset only connects when the drag
;; ends inside the graph editor's own drop-area element.
(defn connect-dataset [page source-kw]
  (p/let [src (.boundingBox (ws/frame page source-kw))
          dst (.boundingBox (drop-area page))
          sx  (+ (.-x src) (/ (.-width src) 2))
          sy  (+ (.-y src) 8)
          dx  (+ (.-x dst) (/ (.-width dst) 2))
          dy  (+ (.-y dst) (/ (.-height dst) 2))]
    (p/do
      (.move (.-mouse page) sx sy)
      (.down (.-mouse page))
      (.move (.-mouse page) dx dy #js {:steps 25})
      (.up (.-mouse page)))))

(defn dataset-chip [page n]
  (.getByText (frame page) (str "Dataset " n) #js {:exact true}))

(defn save-button [page]
  (.getByRole (frame page) "button" #js {:name "Save"}))

(defn discard-button [page]
  (.getByRole (frame page) "button" #js {:name "Discard"}))

(defn back-button [page]
  (.getByRole (frame page) "button" #js {:name "Back to overview"}))

(defn preview-button [page]
  (.getByRole (frame page) "button" #js {:name "Run preview calculation"}))

;; The data-preview section is collapsed by default, and its content stays
;; hidden (not just visually empty) until its header is clicked open.
(defn open-preview-section [page]
  (.click (.getByText (frame page) "Data Preview" #js {:exact true})))

;; .prediction__data__list's first <ul> is the header row (one <li> per
;; column); every <ul> after it is a data row. Excluding the header keeps a
;; regression that returns zero data rows from passing on header cells alone.
(defn preview-rows [page]
  (.locator (frame page) ".prediction__data__list > ul:not(:first-child)"))

(defn preview-row [page label]
  (-> (preview-rows page) (.filter #js {:hasText label})))

(defn overview-card [page name]
  (-> (.locator (frame page) ".indicator__card")
      (.filter #js {:hasText name})))

(defn- open-card-menu [page name]
  (.click (.locator (overview-card page name) ".indicator__contextmenu")))

;; The context menu portals to document.body, so its entries are matched at
;; the page level rather than scoped to the indicator frame.
(defn- click-context-menu-item [page label]
  (.click (-> (.locator page ".context-menu-entry")
              (.filter #js {:hasText label}))))

(defn edit-card [page name]
  (p/do
    (open-card-menu page name)
    (click-context-menu-item page "Edit")))

(defn delete-card [page name]
  (p/do
    (open-card-menu page name)
    (click-context-menu-item page "Delete")))

;; Publishing goes through the same placement-overlay click as any other
;; frame creation, even though the card already carries a computed position.
(defn open-table-visualization [page name x y]
  (p/do
    (.click (.getByRole (overview-card page name)
                        "button" #js {:name "Visualize the data in the table"}))
    (.waitForSelector page ".window-placement-overlay" #js {:timeout 10000})
    (.click (.-mouse page) x y)
    (.waitForSelector page ".window-placement-overlay"
                      #js {:state "detached" :timeout 10000})))
