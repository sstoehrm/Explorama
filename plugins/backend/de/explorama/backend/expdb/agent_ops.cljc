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

(defonce ^:private staged (atom nil))

(defn reset-staged! []
  (reset! staged nil))

(defn- own-staged! [user]
  (when-not (= user (:user @staged))
    (throw (ex-info "no import staged by this user" {:gateway-error :invalid-params}))))

(defn- own-staged-file! [user file-name]
  (when-not (and (= user (:user @staged)) (= file-name (:file-name @staged)))
    (throw (ex-info "no import staged by this user" {:gateway-error :invalid-params}))))

(defn- claim-staged! [user file-name]
  (let [current @staged]
    (if (or (nil? current) (= user (:user current)))
      (reset! staged {:user user :file-name file-name})
      (throw (ex-info "another user's import is staged" {:gateway-error :invalid-params})))))

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
                             (claim-staged! user (:file-name params))
                             (checked (dispatcher/call-route import-api/import-file [(meta-data user (:file-name params)) (:mapping params)]))))
  (dispatcher/register-op! :expdb/commit
                           (fn [{:keys [user]}]
                             (own-staged! user)
                             (let [result (checked (dispatcher/call-route import-api/commit-import []))]
                               (reset! staged nil)
                               result)))
  (dispatcher/register-op! :expdb/cancel
                           (fn [{:keys [user]}]
                             (own-staged! user)
                             (let [result (checked (dispatcher/call-route import-api/cancel-import []))]
                               (reset! staged nil)
                               result)))
  (dispatcher/register-op! :expdb/delete
                           (fn [{:keys [user params]}]
                             (own-staged-file! user (:file-name params))
                             (let [result (checked (dispatcher/call-route import-api/delete-file [(meta-data user (:file-name params))]))]
                               (reset! staged nil)
                               result)))
  (dispatcher/register-op! :expdb/buckets (fn [_] (dispatcher/call-route buckets []))))
