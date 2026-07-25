(ns de.explorama.frontend.map.map.impl.pixi.instance
  (:require [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.style :as style]))

(defonce ^:private registry (atom {}))

(defn new-instance-state [frame-id extra-fns]
  (atom {:frame-id frame-id
         :extra-fns extra-fns
         :engine nil
         :headless? true
         :marker-data {}
         :created-marker-ids #{}
         :event-cache {}
         :highlighted #{}
         :visible-ids nil
         :cluster? true
         :base-layers {}
         :current-base-layer nil
         :feature-data {}
         :filtered-feature-data {}
         :pending []
         :pending-render-listeners []}))

(defn register! [frame-id state-atom]
  (swap! registry assoc frame-id state-atom))

(defn lookup [frame-id]
  (get @registry frame-id))

(defn unregister! [frame-id]
  (swap! registry dissoc frame-id))

(defn enqueue-pending [state-map op]
  (update state-map :pending (fnil conj []) op))

(defn drain-pending [state-map]
  [(assoc state-map :pending [])
   (get state-map :pending [])])

(defn valid-move-to?
  "true when both zoom and position are present - move-to is a no-op
   (rather than resetting the viewport to zoom-nil garbage) otherwise.
   Replay/popup flows call move-to with nils for frames that never moved."
  [zoom position]
  (boolean (and zoom position)))

(defn push-markers!
  "Push the current marker-data (with highlight/visibility filters applied) to
   the booted engine. No-op while headless (:engine nil).

   The object-manager and state-handler share this instance state but own
   different keys: state-handler exclusively owns :marker-data (the full
   cache, written by set-marker-data), while the object-manager exclusively
   owns :created-marker-ids (the create/remove/clear bookkeeping set). What's
   actually rendered is the intersection of the two - entries of :marker-data
   whose id has been \"created\" via the object-manager - mirroring the
   render semantics the OpenLayers implementation had."
  [state-map]
  (when-let [engine (:engine state-map)]
    (let [renderable (select-keys (:marker-data state-map) (:created-marker-ids state-map))]
      (engine/set-markers! engine
                            (style/markers-map->engine-markers
                             renderable (:highlighted state-map) (:visible-ids state-map))))))
