(ns de.explorama.frontend.woco.markers
  (:require [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.frontend.ui-base.components.formular.core :refer [input-field]]
            [de.explorama.frontend.ui-base.components.misc.core :refer [icon]]
            [de.explorama.frontend.woco.path :as path]
            [re-frame.core :as re-frame]))

(def tool-id "tool-markers")

(defn toggle [db frame-id now]
  (if (get-in db (path/marker frame-id))
    (update-in db path/markers dissoc frame-id)
    (assoc-in db (path/marker frame-id) {:note nil :set-at now})))

(defn set-note [db frame-id note]
  (cond-> db
    (get-in db (path/marker frame-id))
    (assoc-in (conj (path/marker frame-id) :note) note)))

(defn remove-marker [db frame-id]
  (update-in db path/markers dissoc frame-id))

(defn clear [db]
  (update-in db path/root dissoc :markers :marker-mode?))

(defn all [db]
  (->> (get-in db path/markers)
       (sort-by (comp :set-at val))
       (mapv (fn [[frame-id {:keys [note set-at]}]]
               {:frame-id frame-id
                :note note
                :set-at set-at
                :title (get-in db (path/frame-title frame-id))
                :vertical (:vertical frame-id)}))))

(re-frame/reg-event-db ::toggle-mode (fn [db _] (update-in db path/marker-mode? not)))
(re-frame/reg-event-db ::toggle (fn [db [_ frame-id]] (toggle db frame-id (js/Date.now))))
(re-frame/reg-event-db ::set-note (fn [db [_ frame-id note]] (set-note db frame-id note)))
(re-frame/reg-event-db ::clear (fn [db _] (clear db)))

(re-frame/reg-sub ::mode? (fn [db _] (boolean (get-in db path/marker-mode?))))
(re-frame/reg-sub ::marked? (fn [db [_ frame-id]] (boolean (get-in db (path/marker frame-id)))))
(re-frame/reg-sub ::note (fn [db [_ frame-id]] (get-in db (conj (path/marker frame-id) :note))))

(defn badge [frame-id]
  (when @(re-frame/subscribe [::marked? frame-id])
    (let [placeholder @(re-frame/subscribe [::i18n/translate :markers-note-placeholder])]
      [:div.frame-marker.absolute.top-0.right-0.z-10.flex.items-center.gap-1.px-2.py-1.rounded-bl-sm.bg-orange-500.text-white
       {:data-marker "true"
        :on-mouse-down #(.stopPropagation %)}
       [icon {:icon :magic}]
       [input-field {:value (or @(re-frame/subscribe [::note frame-id]) "")
                     :placeholder placeholder
                     :extra-class "w-40"
                     :on-change #(re-frame/dispatch [::set-note frame-id %])}]])))
