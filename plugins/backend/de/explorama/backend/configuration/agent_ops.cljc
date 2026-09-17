(ns de.explorama.backend.configuration.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.configuration.persistence.configs.api :as configs-api]))

(defn- user-info [user] {:username user})

(defn register! []
  (dispatcher/register-op! :configuration/entries
                           (fn [{:keys [user params]}]
                             (dispatcher/call-route configs-api/list-entries [(user-info user) (:config-types params)])))
  (dispatcher/register-op! :configuration/entry
                           (fn [{:keys [user params]}]
                             (dispatcher/call-route configs-api/get-entry [(user-info user) (:config-type params) (:config-id params)])))
  (dispatcher/register-op! :configuration/set-entry
                           (fn [{:keys [user params]}]
                             (dispatcher/call-route configs-api/update-entry
                                                    [(user-info user) (:config-type params) (:config-id params) (:entry params)]))))
