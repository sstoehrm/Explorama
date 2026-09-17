(ns de.explorama.backend.reporting.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.reporting.persistence.api :as api]))

(defn- user-info [user] {:username user})

(defn- check! [outcome]
  (when (and (vector? outcome) (= :error (first outcome)))
    (throw (ex-info (str "reporting refused: " (name (second outcome)))
                    {:gateway-error (if (= :no-rights (second outcome)) :unauthorized :invalid-params)
                     :reason (second outcome)})))
  outcome)

(defn register! []
  (dispatcher/register-op! :reporting/dashboards (fn [{:keys [user]}] (dispatcher/call-route api/all-dashboards [(user-info user)])))
  (dispatcher/register-op! :reporting/save-dashboard
                           (fn [{:keys [user params]}]
                             (check! (dispatcher/call-route api/save-dashboard [(user-info user) (:desc params)]))
                             (dispatcher/call-route api/all-dashboards [(user-info user)])))
  (dispatcher/register-op! :reporting/delete-dashboard
                           (fn [{:keys [user params]}]
                             (check! (dispatcher/call-route api/delete-dashboard [(user-info user) (:id params)]))
                             (dispatcher/call-route api/all-dashboards [(user-info user)])))
  (dispatcher/register-op! :reporting/reports (fn [{:keys [user]}] (dispatcher/call-route api/all-reports [(user-info user)])))
  (dispatcher/register-op! :reporting/save-report
                           (fn [{:keys [user params]}]
                             (check! (dispatcher/call-route api/save-report [(user-info user) (:desc params)]))
                             (dispatcher/call-route api/all-reports [(user-info user)])))
  (dispatcher/register-op! :reporting/delete-report
                           (fn [{:keys [user params]}]
                             (check! (dispatcher/call-route api/delete-report [(user-info user) (:id params)]))
                             (dispatcher/call-route api/all-reports [(user-info user)]))))
