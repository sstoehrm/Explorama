(ns de.explorama.frontend.map.map.config
  (:require [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.shared.common.configs.mouse :refer [mouse-default pref-key]]
            [re-frame.core :as re-frame]))

(defn- prepare-config [preference-value]
  (->> (filter #(= :woco (get % :context)) preference-value)
       (mapv #(select-keys % [:button :action]))))

; Set this just in case - the watcher should overwrite this value eventually
(def ^:private mouse-buttons-state (atom (prepare-config mouse-default)))

(defn mouse-buttons []
  @mouse-buttons-state)

(defn do-panning?
  "Checks if given mouse-button number equals to any configured panning action.

   1 = left button
   2 = middle button
   3 = right button"
  [mouse-button-number]
  (some (fn [{:keys [button action]}]
          (and (= button mouse-button-number)
               (= action :panning)))
        (mouse-buttons)))

(re-frame/reg-event-fx
 ::set-mouse-layout
 (fn [_ [_ preference-value]]
   (reset! mouse-buttons-state (prepare-config preference-value))
   {}))

(re-frame/reg-event-fx
 ::init
 (fn [_ _]
   {:dispatch (fi/call-api [:user-preferences :add-preference-watcher-event-vec]
                           pref-key
                           [::set-mouse-layout]
                           mouse-default)}))
