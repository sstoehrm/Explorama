(ns e2e.pages.workspace
  (:require [promesa.core :as p]))

(def ^:private frame-prefix
  {:search "woco_frame-search-"
   :table  "woco_frame-table-"
   :mosaic "woco_frame-mosaic-"
   :map    "woco_frame-map-"
   :charts "woco_frame-charts-"
   :note   "woco_frame-notes-"})

(defn frames [page]
  (.locator page ".frame"))

(defn frame [page plugin-kw]
  (.last (.locator page (str "[id^=\"" (get frame-prefix plugin-kw) "\"]"))))

;; Both overlays are optional and may mount asynchronously after
;; #workspace-root appears, so absence must not be treated as failure: a
;; bounded, auto-retrying click that swallows its own timeout is the only
;; way to wait for "either it appears and gets dismissed, or it never
;; shows up" without a fixed sleep.
(defn- dismiss-optional [locator]
  (-> (.click locator #js {:timeout 5000})
      (p/catch (fn [_] nil))))

(defn- dismiss-welcome [page]
  (dismiss-optional (.getByRole page "button" #js {:name "Close overview"})))

;; The hint carousel's other button is "next", which advances it rather than
;; closing it.
(defn- dismiss-tour [page]
  (dismiss-optional (.getByRole (.locator page ".window-handling-tour")
                                "button" #js {:name "Close"})))

(defn dismiss-overlays [page]
  (p/do
    (dismiss-welcome page)
    (dismiss-tour page)))

(defn open-workspace [page]
  (p/do
    (.goto page "/" #js {:waitUntil "load"})
    (.waitForSelector page "#workspace-root" #js {:timeout 30000})
    (dismiss-overlays page)))

(defn create-frame [page tool-id x y]
  (p/do
    (.click (.locator page tool-id))
    (.waitForSelector page ".window-placement-overlay" #js {:timeout 10000})
    (.click (.-mouse page) x y)
    (.waitForSelector page ".window-placement-overlay"
                      #js {:state "detached" :timeout 10000})))

(def ^:private blank-png
  (.from js/Buffer
         "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
         "base64"))

(defn stub-map-tiles [page]
  (.route page "**/*.png"
          (fn [route request]
            (if (re-find #"tile\.openstreetmap" (.url request))
              (.fulfill route #js {:status 200
                                   :contentType "image/png"
                                   :body blank-png})
              (.continue route)))))

(defn connect [page source-kw target-kw]
  (p/let [src (.boundingBox (frame page source-kw))
          dst (.boundingBox (frame page target-kw))
          sx  (+ (.-x src) (/ (.-width src) 2))
          sy  (+ (.-y src) 8)
          dx  (+ (.-x dst) (/ (.-width dst) 2))
          dy  (+ (.-y dst) (/ (.-height dst) 2))]
    (p/do
      (.move (.-mouse page) sx sy)
      (.down (.-mouse page))
      (.move (.-mouse page) dx dy #js {:steps 25})
      (.up (.-mouse page)))))
