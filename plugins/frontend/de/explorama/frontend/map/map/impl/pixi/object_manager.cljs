(ns de.explorama.frontend.map.map.impl.pixi.object-manager
  (:require [de.explorama.frontend.map.map.protocol.object-manager :as proto]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.map.impl.pixi.stubs :as stubs]
            [de.explorama.frontend.map.pixi.engine :as engine]))

(deftype PixiObjectManager [frame-id state]
  proto/mapObjectManager
  (create-map-instance [_ headless?]
    (swap! state assoc :headless? headless?)
    nil)
  (map-instance [_]
    (:engine @state))

  (create-marker-layer [_]
    (swap! state assoc :marker-layer? true)
    nil)
  (remove-marker-layer [_]
    (swap! state assoc :marker-layer? false)
    nil)
  (marker-layer-created? [_]
    (boolean (:marker-layer? @state)))
  (get-cluster-layer [_]
    (:engine @state))
  (get-marker-layer [_]
    (:engine @state))

  (create-markers [_ markers-data]
    (swap! state update :marker-data merge markers-data)
    (inst/push-markers! @state)
    nil)
  (remove-markers [_ marker-ids]
    (swap! state update :marker-data #(apply dissoc % marker-ids))
    (inst/push-markers! @state)
    nil)
  (clear-markers [_]
    (swap! state assoc :marker-data {})
    (inst/push-markers! @state)
    nil)
  (marker-created? [_ marker-id]
    (contains? (:marker-data @state) marker-id))
  (get-marker-objs [_ marker-ids]
    (map #(get (:marker-data @state) %) marker-ids))
  (marker-ids [_]
    (keys (:marker-data @state)))

  (create-feature-layer [_ feature-layer]
    (stubs/notify-unavailable! frame-id :feature-layer)
    (swap! state update :stub-feature-layers (fnil conj #{}) (:layer-id feature-layer))
    nil)
  (remove-feature-layer [_ _feature-layer-id]
    nil)
  (feature-layer-created? [_ feature-layer-id]
    (contains? (:stub-feature-layers @state) feature-layer-id))
  (get-feature-layer-obj [_ _feature-layer-id]
    nil)
  (all-feature-layers [_]
    (:stub-feature-layers @state))

  ;;Feature Layer Movement specific fns:
  (create-arrow-features [_ feature-layer-id _arrow-descs]
    (stubs/notify-unavailable! frame-id :movement)
    (swap! state update :stub-feature-layers (fnil conj #{}) feature-layer-id)
    nil)
  (clear-arrow-features [_ _feature-layer-id]
    nil)
  (get-arrow-features [_ _feature-layer-id _arrow-ids]
    nil)
  (arrow-feature-ids [_ _feature-layer-id]
    nil)

  ;;Feature Layer Heatmap specific fns:
  (clear-heatmap-features [_ _feature-layer-id]
    nil)
  (create-heatmap-features [_ feature-layer-id _heatmap-data]
    (stubs/notify-unavailable! frame-id :heatmap)
    (swap! state update :stub-feature-layers (fnil conj #{}) feature-layer-id)
    nil)

  (create-area-features [_ feature-layer-id _descs]
    (stubs/notify-unavailable! frame-id :area)
    (swap! state update :stub-feature-layers (fnil conj #{}) feature-layer-id)
    nil)
  (remove-area-features [_ _feature-layer-id _descs]
    nil)

  (create-overlayer [_ overlayer]
    (swap! state assoc-in [:stub-overlayers (:name overlayer)] overlayer)
    nil)
  (remove-overlayer [_ overlayer-id]
    (swap! state update :stub-overlayers dissoc overlayer-id)
    nil)
  (overlayer-created? [_ overlayer-id]
    (contains? (:stub-overlayers @state) overlayer-id))
  (get-overlayer-obj [_ overlayer-id]
    (get (:stub-overlayers @state) overlayer-id))

  (create-base-layer [_ base-layer]
    (swap! state (fn [s]
                   (cond-> (assoc-in s [:base-layers (:name base-layer)] base-layer)
                     (and (:default base-layer) (nil? (:current-base-layer s)))
                     (assoc :current-base-layer (:name base-layer)))))
    nil)
  (remove-base-layer [_ base-layer-id]
    (swap! state update :base-layers dissoc base-layer-id)
    nil)
  (base-layer-created? [_ base-layer-id]
    (contains? (:base-layers @state) base-layer-id))
  (get-base-layer-obj [_ base-layer-id]
    (get (:base-layers @state) base-layer-id))

  (get-popup [_]
    (:engine @state))

  (destroy-instance [_]
    (when-let [engine-ref (:engine @state)]
      (engine/destroy! engine-ref))
    (inst/unregister! frame-id)
    nil))

(defn create-instance [frame-id extra-fns]
  (let [state (inst/new-instance-state frame-id extra-fns)]
    (inst/register! frame-id state)
    (->PixiObjectManager frame-id state)))
