(ns de.explorama.frontend.map.map.impl.pixi.stubs
  (:require [de.explorama.frontend.common.frontend-interface :as fi]))

(defonce ^:private seen (atom #{}))

(defn should-notify? [seen-set k]
  (not (contains? seen-set k)))

(defn- notify! [feature]
  (let [message (str (name feature) " is not yet available in the new map renderer")]
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
