(ns de.explorama.frontend.map.map.impl.pixi.stubs
  (:require [cuerdas.core :as cuerdas]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.common.i18n :as i18n]))

(defonce ^:private seen (atom #{}))

(defn should-notify? [seen-set k]
  (not (contains? seen-set k)))

(defn- notify! [feature]
  (let [message (cuerdas/format (i18n/translate-anywhere :map-feature-unavailable)
                                {:feature (name feature)})]
    (if (fi/api-definition :notify-event-dispatch)
      (fi/call-api :notify-event-dispatch {:type :warning
                                            :category {:misc :map}
                                            :message message})
      (js/alert message))))

(defn notify-unavailable! [frame-id feature]
  (let [k [frame-id feature]]
    (if (should-notify? @seen k)
      (do
        (swap! seen conj k)
        (notify! feature)
        true)
      false)))

(defn forget-frame!
  "Drop a destroyed frame's once-per-frame notice bookkeeping so a reopened
   frame with the same id notifies again."
  [frame-id]
  (swap! seen (fn [s] (into #{} (remove #(= frame-id (first %))) s))))
