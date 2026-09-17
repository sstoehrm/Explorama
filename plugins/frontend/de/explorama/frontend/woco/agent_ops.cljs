(ns de.explorama.frontend.woco.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.woco.api.couple :as couple]
            [de.explorama.frontend.woco.api.interaction-mode :as inter-mode]
            [de.explorama.frontend.woco.frame.api :as frame-api]
            [de.explorama.frontend.woco.frame.info :as frame-info]
            [de.explorama.frontend.woco.markers :as markers]
            [de.explorama.frontend.woco.path :as path]
            [re-frame.core :as re-frame]))

(defn frame-entry [db frame-id {[left top] :coords [width height] :size
                                :keys [title z-index is-minimized? is-maximized? published-by-frame type]}]
  {:id frame-id
   :vertical (:vertical frame-id)
   :type type
   :title title
   :left left :top top :width width :height height
   :z-index z-index
   :minimized? (boolean is-minimized?)
   :maximized? (boolean is-maximized?)
   :di (frame-info/api-value db frame-id :di)
   :published-by published-by-frame
   :color-group (or (get-in db (path/frame-header-color frame-id))
                    (when published-by-frame
                      (get-in db (path/frame-header-color published-by-frame))))})

(defn frames [db]
  (into []
        (keep (fn [[frame-id desc]]
                (when (and (map? frame-id) (map? desc))
                  (frame-entry db frame-id desc))))
        (get-in db path/frames)))

(defn connections [db]
  (let [all (get-in db path/frames)
        publishes (for [[id {:keys [published-by-frame]}] all
                        :when (and (map? id) published-by-frame)]
                    {:from published-by-frame :to id :kind :publishes})
        coupled (->> (keys all)
                     (filter map?)
                     (mapcat (fn [id] (map (fn [other] #{id other}) (couple/couple-with db id))))
                     distinct
                     (map (fn [pair]
                            (let [[a b] (sort-by :frame-id (vec pair))]
                              {:from a :to b :kind :coupled}))))]
    (vec (concat publishes coupled))))

(defn workspace [db]
  {:interaction-mode (inter-mode/current db)
   :workspace-id (get-in db (path/workspace-id))
   :viewport (get-in db path/navigation-position)
   :project (fi/call-api :loaded-project-db-get db)})

(defonce ^:private replies (atom {}))

(def ^:private reply-ttl-ms 600000)

(defn finish-reply! [reply-id f]
  (when-let [reply (get @replies reply-id)]
    (swap! replies dissoc reply-id)
    (f reply)))

(defn stash-reply! [ok fail]
  (let [id (str (random-uuid))]
    (swap! replies assoc id {:ok ok :fail fail})
    (js/setTimeout #(finish-reply! id (fn [{:keys [fail]}] (fail :timeout "the plugin did not answer"))) reply-ttl-ms)
    id))

(defn- frame? [db frame-id]
  (boolean (get-in db (path/frame-desc frame-id))))

(re-frame/reg-event-fx
 ::reply-frame
 (fn [{db :db} [_ reply-id frame-id]]
   (finish-reply! reply-id (fn [{:keys [ok]}]
                             (ok (frame-entry db frame-id (get-in db (path/frame-desc frame-id))))))
   {}))

(re-frame/reg-event-fx
 ::reply-frames
 (fn [{db :db} [_ reply-id]]
   (finish-reply! reply-id (fn [{:keys [ok]}] (ok (frames db))))
   {}))

(re-frame/reg-event-fx
 ::reply-connections
 (fn [{db :db} [_ reply-id]]
   (finish-reply! reply-id (fn [{:keys [ok]}] (ok (connections db))))
   {}))

(re-frame/reg-event-fx
 ::frame-state-reply
 (fn [_ [_ reply-id state]]
   (finish-reply! reply-id (fn [{:keys [ok]}] (ok (when state (dissoc state :preview :ratio)))))
   {}))

(re-frame/reg-event-fx
 ::reply-with
 (fn [{db :db} [_ reply-id f]]
   (finish-reply! reply-id (fn [{:keys [ok]}] (ok (f db))))
   {}))

(defn new-frame [db vertical before]
  (some (fn [[id _]]
          (when (and (map? id) (= vertical (:vertical id)) (not (contains? before id)))
            id))
        (get-in db path/frames)))

(def ^:private open-retry-ms 100)
(def ^:private open-max-tries 50)

(re-frame/reg-event-fx
 ::reply-new-frame
 (fn [{db :db} [_ reply-id vertical before tries]]
   (if-let [id (new-frame db vertical before)]
     (do (finish-reply! reply-id (fn [{:keys [ok]}] (ok (frame-entry db id (get-in db (path/frame-desc id))))))
         {})
     (if (pos? tries)
       {:dispatch-later [{:ms open-retry-ms :dispatch [::reply-new-frame reply-id vertical before (dec tries)]}]}
       (do (finish-reply! reply-id (fn [{:keys [fail]}] (fail :op-failed "the frame did not appear")))
           {})))))

(defn open-frame
  "Dispatches `open-event` and answers with the frame of `vertical` that appears afterwards."
  [{:keys [db ok fail]} vertical open-event]
  {:dispatch-n [open-event
                [::reply-new-frame (stash-reply! ok fail) vertical
                 (set (filter map? (keys (get-in db path/frames)))) open-max-tries]]})

(defn open-vertical [{:keys [db params ok fail]}]
  (let [{:keys [vertical source-frame-id position opts]} params
        event (get-in (fi/call-api :service-category-db-get db :visual-option) [(keyword vertical) :event])]
    (if-not event
      (do (fail :invalid-params (str "no visual vertical " vertical)) {})
      {:dispatch-n [[event source-frame-id (or position [100 200]) true
                     (merge opts {:overwrites {:behavior {:force :provided-position}}})]
                    [::reply-new-frame (stash-reply! ok fail) vertical
                     (set (filter map? (keys (get-in db path/frames)))) open-max-tries]]})))

(defn with-frame [{:keys [db params fail]} f]
  (let [{:keys [frame-id]} params]
    (if (frame? db frame-id)
      (f frame-id)
      (do (fail :invalid-params "unknown frame-id") {}))))

(defn open-op [vertical]
  (fn [{:keys [params] :as ctx}]
    (open-vertical (assoc ctx :params {:vertical vertical
                                       :source-frame-id (:source-frame-id params)
                                       :position (:position params)}))))

(defn state-op [state-fn]
  (fn [{:keys [db ok] :as ctx}]
    (with-frame ctx (fn [frame-id] (ok (state-fn db frame-id)) {}))))

(defn set-geometry [{:keys [ok fail params] :as ctx}]
  (with-frame ctx
    (fn [frame-id]
      (let [{:keys [left top width height]} params]
        {:dispatch-n (cond-> []
                       (and left top) (conj [::frame-api/set-frame-coords frame-id left top])
                       (and width height) (conj [::frame-api/set-frame-size frame-id width height width height])
                       :always (conj [::reply-frame (stash-reply! ok fail) frame-id]))}))))

(defn- frame-event-op [event-fn reply-event]
  (fn [{:keys [ok fail] :as ctx}]
    (with-frame ctx
      (fn [frame-id]
        {:dispatch-n [(event-fn frame-id)
                      (case reply-event
                        :frame [::reply-frame (stash-reply! ok fail) frame-id]
                        :frames [::reply-frames (stash-reply! ok fail)])]}))))

(defn frame-state [{:keys [ok fail] :as ctx}]
  (with-frame ctx
    (fn [frame-id]
      {:dispatch [::frame-api/query frame-id :vis-desc [::frame-state-reply (stash-reply! ok fail)]]})))

(defn- connect [{:keys [db params ok fail]}]
  (let [{:keys [source target]} params]
    (if (and (frame? db source) (frame? db target))
      {:dispatch-n [[::frame-api/connect-code source target]
                    [::reply-connections (stash-reply! ok fail)]]}
      (do (fail :invalid-params "unknown source or target frame") {}))))

(defn register! []
  (registry/register-op! :woco/frames (fn [{:keys [db ok]}] (ok (frames db)) {}))
  (registry/register-op! :woco/connections (fn [{:keys [db ok]}] (ok (connections db)) {}))
  (registry/register-op! :woco/workspace (fn [{:keys [db ok]}] (ok (workspace db)) {}))
  (registry/register-op! :woco/frame-state frame-state)
  (registry/register-op! :woco/open-vertical open-vertical)
  (registry/register-op! :woco/connect connect)
  (registry/register-op! :woco/set-geometry set-geometry)
  (registry/register-op! :woco/set-title
                         (fn [{:keys [params] :as ctx}]
                           ((frame-event-op (fn [frame-id] [::frame-api/set-title (:title params) frame-id]) :frame) ctx)))
  (registry/register-op! :woco/minimize (frame-event-op (fn [frame-id] [::frame-api/minimize frame-id]) :frame))
  (registry/register-op! :woco/maximize (frame-event-op (fn [frame-id] [::frame-api/maximize frame-id]) :frame))
  (registry/register-op! :woco/normalize (frame-event-op (fn [frame-id] [::frame-api/normalize frame-id]) :frame))
  (registry/register-op! :woco/bring-to-front (frame-event-op (fn [frame-id] [::frame-api/bring-to-front frame-id]) :frame))
  (registry/register-op! :woco/close (frame-event-op (fn [frame-id] [::frame-api/close frame-id]) :frames))
  (registry/register-op! :woco/markers (fn [{:keys [db ok]}] (ok (markers/all db)) {}))
  (registry/register-op! :woco/clear-markers (fn [{:keys [ok]}] (ok []) {:dispatch [::markers/clear]})))
