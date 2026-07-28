(ns de.explorama.backend.agent-requests.store
  (:require [de.explorama.backend.agent-requests.config :as config]
            [de.explorama.backend.agent-requests.queue :as queue]
            [de.explorama.backend.agent-requests.registry :as registry]
            [taoensso.timbre :refer [error]]))

(defonce ^:private state (atom {}))
(defonce ^:private watchers (atom {}))

(def known-user? queue/known-user?)

(def ^:dynamic *now-fn*
  (fn []
    #?(:clj (System/currentTimeMillis)
       :cljs (.getTime (js/Date.)))))

(defn reset-store! []
  (reset! state {})
  (reset! watchers {})
  nil)

(defn watch! [key callback]
  (swap! watchers assoc key callback)
  nil)

(defn unwatch! [key]
  (swap! watchers dissoc key)
  nil)

(defn- notify-watchers! []
  (doseq [[key callback] @watchers]
    (try
      (callback)
      (catch #?(:clj Throwable :cljs :default) e
        (error e "Agent request watcher failed" {:watcher key})))))

(def ^:private terminal-failure-statuses #{:failed :expired :cancelled})
(def ^:private terminal-statuses #{:fulfilled :failed :expired :cancelled})

(defn- notify-failure! [{{:keys [failed-callback]} :context :as request} reason]
  (when failed-callback
    (try
      (failed-callback {:error reason})
      (catch #?(:clj Throwable :cljs :default) e
        (error e "Agent request failure notification failed" {:id (:id request)
                                                               :status (:status request)})))))

(defn- terminal-failure-reason [{:keys [status reason]}]
  (if (= :failed status) reason status))

(defn- newly-terminal-failures [pre post]
  (keep (fn [[id request]]
          (let [prev-status (:status (get pre id))]
            (when (and (contains? terminal-failure-statuses (:status request))
                       (not (contains? terminal-statuses prev-status)))
              request)))
        post))

(defn- notify-terminal-failures! [pre post]
  (doseq [request (newly-terminal-failures pre post)]
    (notify-failure! request (terminal-failure-reason request))))

(defn- transact! [f]
  (let [outcome (volatile! nil)
        pre (volatile! nil)
        post (volatile! nil)]
    (swap! state (fn [current]
                   (let [[next result] (f current (*now-fn*))]
                     (vreset! pre current)
                     (vreset! post next)
                     (vreset! outcome result)
                     next)))
    (notify-watchers!)
    (notify-terminal-failures! @pre @post)
    @outcome))

(defn- new-id []
  (str #?(:clj (java.util.UUID/randomUUID)
          :cljs (random-uuid))))

(defn create! [{:keys [type input user context]}]
  (if-not (queue/known-user? user)
    (do (error "Agent request refused without a filing user" {:type type})
        nil)
    (transact! (fn [current now]
                 (queue/create current now {:id (new-id)
                                            :type type
                                            :input input
                                            :user user
                                            :context context
                                            :lease-ms config/lease-ms
                                            :ttl-ms config/ttl-ms
                                            :max-rejections config/max-rejections})))))

(defn claim! [id agent-id]
  (transact! (fn [current now]
               (queue/claim current now id agent-id))))

(defn- run-handler! [{:keys [type] :as request} result]
  (when-let [{:keys [on-fulfilled]} (registry/type-declaration type)]
    (try
      (on-fulfilled request result)
      (catch #?(:clj Throwable :cljs :default) e
        (error e "Agent request handler failed" {:id (:id request)
                                                 :type type})))))

(defn submit! [id result]
  (let [outcome (transact! (fn [current now]
                             (queue/submit current now id result
                                           (fn [value]
                                             (registry/explain-result
                                              (:type (get current id))
                                              value)))))]
    (when-let [request (:ok outcome)]
      (run-handler! request result))
    outcome))

(defn fail! [id reason]
  (transact! (fn [current now]
               (queue/fail current now id reason))))

(defn cancel! [id]
  (transact! (fn [current now]
               (queue/cancel current now id))))

(defn get-request [id]
  (get (queue/sweep @state (*now-fn*)) id))

(defn open-requests [type-filter]
  (queue/open-requests @state (*now-fn*) type-filter))

(defn user-requests [user]
  (queue/user-requests @state (*now-fn*) user))
