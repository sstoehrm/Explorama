(ns de.explorama.backend.expdb.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.expdb.persistence.db-api :as db-api]
            [de.explorama.backend.expdb.temp-import.api :as import-api]))

(defn- meta-data [user file-name]
  {:name (str user "/" file-name)
   :extention "csv"})

(defn- analysis [[suggestion preview]]
  {:suggestion suggestion :preview preview})

(defn- checked [result]
  (if (false? (:success result))
    (throw (ex-info (str "import step failed: " (:error result))
                    {:gateway-error :invalid-params :error (:error result) :error-data (:error-data result)}))
    result))

(defn- upload [{:keys [user params]}]
  (let [{:keys [file-name content csv]} params
        md (meta-data user file-name)
        first-pass (analysis (dispatcher/call-route import-api/upload-file [md content]))]
    (if csv
      (analysis (dispatcher/call-route import-api/update-options [md :csv csv]))
      first-pass)))

(defn- buckets [metas _]
  (db-api/load-buckets metas))

(defn register! []
  (dispatcher/register-op! :expdb/upload upload)
  (dispatcher/register-op! :expdb/set-options
                           (fn [{:keys [user params]}]
                             (analysis (dispatcher/call-route import-api/update-options [(meta-data user (:file-name params)) :csv (:csv params)]))))
  (dispatcher/register-op! :expdb/set-mapping
                           (fn [{:keys [user params]}]
                             (checked (dispatcher/call-route import-api/import-file [(meta-data user (:file-name params)) (:mapping params)]))))
  (dispatcher/register-op! :expdb/commit (fn [_] (checked (dispatcher/call-route import-api/commit-import []))))
  (dispatcher/register-op! :expdb/cancel (fn [_] (checked (dispatcher/call-route import-api/cancel-import []))))
  (dispatcher/register-op! :expdb/delete
                           (fn [{:keys [user params]}]
                             (checked (dispatcher/call-route import-api/delete-file [(meta-data user (:file-name params))]))))
  (dispatcher/register-op! :expdb/buckets (fn [_] (dispatcher/call-route buckets []))))
