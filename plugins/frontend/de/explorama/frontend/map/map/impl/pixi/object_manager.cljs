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

  ;; NOTE: :marker-data (the actual marker payload) is owned exclusively by
  ;; the state-handler's set-marker-data - the object-manager only tracks
  ;; which of those ids have been "created" (:created-marker-ids), so that a
  ;; clear-markers/remove-markers here can never wipe the cache set-marker-data
  ;; just wrote (see instance.cljs push-markers! for the render-side join).
  (create-markers [_ markers-data]
    (swap! state update :created-marker-ids (fnil into #{}) (keys markers-data))
    (inst/push-markers! @state)
    nil)
  (remove-markers [_ marker-ids]
    (swap! state update :created-marker-ids #(apply disj % marker-ids))
    (inst/push-markers! @state)
    nil)
  (clear-markers [_]
    (swap! state assoc :created-marker-ids #{})
    (inst/push-markers! @state)
    nil)
  (marker-created? [_ marker-id]
    (contains? (:created-marker-ids @state) marker-id))
  (get-marker-objs [_ marker-ids]
    (map #(get (:marker-data @state) %) marker-ids))
  (marker-ids [_]
    (seq (:created-marker-ids @state)))

  (create-feature-layer [_ feature-layer]
    (case (:type feature-layer)
      :feature (inst/create-area-feature-layer! state feature-layer)
      :movement (inst/stage-arrow-layer! state (:layer-id feature-layer))
      :heatmap (inst/stage-heatmap-layer! state (:layer-id feature-layer))
      (do (stubs/notify-unavailable! frame-id :feature-layer)
          (swap! state update :stub-feature-layers (fnil conj #{}) (:layer-id feature-layer))))
    nil)
  (remove-feature-layer [_ _feature-layer-id]
    nil)
  (feature-layer-created? [_ feature-layer-id]
    (boolean (or (contains? (:stub-feature-layers @state) feature-layer-id)
                 (contains? #{:area :movement :heatmap}
                            (get-in @state [:vector-layers feature-layer-id :kind])))))
  (get-feature-layer-obj [_ feature-layer-id]
    (let [entry (get-in @state [:vector-layers feature-layer-id])]
      (when (contains? #{:area :movement :heatmap} (:kind entry)) entry)))
  (all-feature-layers [_]
    (:stub-feature-layers @state))

  ;;Feature Layer Movement specific fns:
  (create-arrow-features [_ feature-layer-id arrow-descs]
    (inst/create-arrow-features! state feature-layer-id arrow-descs)
    nil)
  (clear-arrow-features [_ feature-layer-id]
    (inst/clear-arrow-features! state feature-layer-id)
    nil)
  (get-arrow-features [_ _feature-layer-id _arrow-ids]
    nil)
  (arrow-feature-ids [_ _feature-layer-id]
    nil)

  ;;Feature Layer Heatmap specific fns:
  (clear-heatmap-features [_ feature-layer-id]
    (inst/clear-heatmap-features! state feature-layer-id)
    nil)
  (create-heatmap-features [_ feature-layer-id heatmap-data]
    (inst/create-heatmap-features! state feature-layer-id heatmap-data)
    nil)

  (create-area-features [_ _feature-layer-id _descs]
    ;; Dead in practice: area/:feature layers are populated entirely by
    ;; create-feature-layer's :feature branch (inst/create-area-feature-layer!),
    ;; which resolves geometry inline - nothing calls this method.
    nil)
  (remove-area-features [_ _feature-layer-id _descs]
    nil)

  (create-overlayer [_ overlayer]
    (inst/create-overlayer! state overlayer)
    nil)
  (remove-overlayer [_ overlayer-id]
    (inst/remove-vector-layer! state overlayer-id)
    nil)
  (overlayer-created? [_ overlayer-id]
    (= :overlayer (get-in @state [:vector-layers overlayer-id :kind])))
  (get-overlayer-obj [_ overlayer-id]
    (let [entry (get-in @state [:vector-layers overlayer-id])]
      (when (= :overlayer (:kind entry)) entry)))

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
