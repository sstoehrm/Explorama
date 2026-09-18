(ns de.explorama.frontend.configuration.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.frontend-interface :as fi]))

(defn register! []
  (registry/register-op! :configuration/labels (fn [{:keys [db ok]}] (ok (fi/call-api [:i18n :get-labels-db-get] db)) {}))
  (registry/register-op! :configuration/languages (fn [{:keys [db ok]}] (ok (fi/call-api [:i18n :available-languages-db-get] db)) {}))
  (registry/register-op! :configuration/theme (fn [{:keys [db ok]}] (ok (fi/call-api :config-theme-db-get db)) {})))
