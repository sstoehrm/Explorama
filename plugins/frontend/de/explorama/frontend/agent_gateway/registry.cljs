(ns de.explorama.frontend.agent-gateway.registry
  (:require [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.shared.agent-gateway.errors :as errors]
            [de.explorama.shared.agent-gateway.ws-api :as ws-api]
            [re-frame.core :as re-frame]
            [taoensso.timbre :refer [warn]]))

(defonce ^:private handlers (atom {}))

(defn register-op! [op handler]
  (swap! handlers assoc op handler)
  nil)

(defn registered-ops []
  (set (keys @handlers)))

(defn reset-ops! []
  (reset! handlers {})
  nil)

(defn- reply [request-id response]
  (re-frame/dispatch [::send-result request-id response]))

(defn run-command [db request-id op params]
  (let [handler (get @handlers op)]
    (cond
      (not (fi/call-api [:interaction-mode :normal-db-get?] db))
      {:dispatch [::send-result request-id
                  (errors/error :workspace-busy "the workspace is not in normal mode" {:op op})]}

      (nil? handler)
      {:dispatch [::send-result request-id
                  (errors/error :unknown-op "no frontend handler registered" {:op op})]}

      :else
      (try
        (or (handler {:db db
                      :params params
                      :ok (fn [result] (reply request-id (errors/ok result)))
                      :fail (fn [type message] (reply request-id (errors/error type message {:op op})))})
            {})
        (catch :default e
          (warn e "agent op failed" {:op op})
          {:dispatch [::send-result request-id
                      (errors/error :op-failed (or (ex-message e) (str e)) {:op op})]})))))

(re-frame/reg-event-fx
 ws-api/command
 (fn [{db :db} [_ request-id op params]]
   (run-command db request-id op params)))

(defn send-result-fx [{db :db} [_ request-id response]]
  {:backend-tube [ws-api/result
                  {:client-id (fi/call-api :client-id-db-get db)}
                  request-id
                  response]})

(re-frame/reg-event-fx ::send-result send-result-fx)
