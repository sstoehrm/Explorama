(ns de.explorama.frontend.map.map.impl.pixi.instance)

(defonce ^:private registry (atom {}))

(defn new-instance-state [frame-id extra-fns]
  (atom {:frame-id frame-id
         :extra-fns extra-fns
         :engine nil
         :headless? true
         :marker-data {}
         :event-cache {}
         :highlighted #{}
         :visible-ids nil
         :cluster? true
         :base-layers {}
         :current-base-layer nil
         :feature-data {}
         :filtered-feature-data {}
         :pending []}))

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
