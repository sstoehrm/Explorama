(ns de.explorama.frontend.map.pixi.style
  (:require [clojure.string :as str]))

(defn hex->int [hex]
  (let [h (some-> hex (str/replace #"^#" ""))
        n (when (and h (re-matches #"[0-9a-fA-F]{6}" h))
            (js/parseInt h 16))]
    (or n 0x000000)))

(defn marker-data->marker
  [[id [event-id location style]]]
  (when-let [[lat lon] (first location)]
    {:id id
     :event-id event-id
     :lon lon
     :lat lat
     :color (hex->int (:fillColor style))
     :color-hex (:fillColor style)
     :radius (or (:radius style) 6)
     :alpha (or (:fillOpacity style) 0.7)
     :highlighted? false}))

(defn markers-map->engine-markers [marker-data highlighted-ids visible-ids]
  (into []
        (comp (keep marker-data->marker)
              (filter #(or (nil? visible-ids) (contains? visible-ids (:id %))))
              ;; highlighted-ids holds marker-id strings (the map's key),
              ;; not the :event-id tuple - all plugin call-paths key
              ;; highlight lookups by marker-id.
              (map #(assoc % :highlighted?
                           (contains? (or highlighted-ids #{}) (:id %)))))
        marker-data))
