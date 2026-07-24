(ns de.explorama.frontend.map.pixi.popup
  (:require [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn popup-view
  "popup-state: reagent atom of nil | {:lon :lat :content}.
   tick: reagent atom bumped on every viewport change (forces reposition).
   engine-ref: atom holding the engine."
  [popup-state tick engine-ref]
  @tick ;; deref so we re-render on viewport changes
  (when-let [{:keys [lon lat content]} @popup-state]
    (when-let [e @engine-ref]
      (let [[sx sy] (vp/->screen (engine/get-viewport e) lon lat)]
        [:div.sandbox-popup {:style {:left (str sx "px") :top (str sy "px")}}
         content]))))
