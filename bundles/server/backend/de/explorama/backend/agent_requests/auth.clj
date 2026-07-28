(ns de.explorama.backend.agent-requests.auth
  (:require [clojure.string :as str]
            [de.explorama.backend.rights-roles.config :as rr-config]
            [de.explorama.backend.rights-roles.http-util :refer [safe-http-post]]
            [de.explorama.backend.rights-roles.oidc.util :as oidc]
            [de.explorama.shared.common.configs.provider :refer [defconfig]]))

(def allowed-principals
  (defconfig
    {:env :explorama-agent-requests-principals
     :default #{}
     :type :edn-string
     :doc "Subjects or client ids allowed on the agent request api. Empty means any principal with a valid token."}))

(def cache-ms
  (defconfig
    {:env :explorama-agent-requests-auth-cache-ms
     :default 60000
     :type :integer
     :doc "How long a validated agent api token stays cached."}))

(defonce ^:private cache (atom {}))

(defn reset-cache! []
  (reset! cache {})
  nil)

(defn- introspect [token]
  (let [{:keys [client-id client-secret] :as auth-config} rr-config/authorization-config]
    (when-let [endpoint (oidc/introspection-endpoint auth-config)]
      (safe-http-post endpoint
                      {:content-type "application/x-www-form-urlencoded"
                       :form-params {:token token
                                     :client_id client-id
                                     :client_secret client-secret}}))))

(def ^:dynamic *introspect-fn* introspect)

(defn- principal [{:keys [active sub client_id]}]
  (when active
    (or sub client_id)))

(defn- cached-principal [token now]
  (let [{:keys [value valid-until]} (get @cache token)]
    (when (and value (< now valid-until))
      value)))

(defn- resolve-principal [token]
  (let [now (System/currentTimeMillis)]
    (or (cached-principal token now)
        (when-let [value (principal (*introspect-fn* token))]
          (swap! cache assoc token {:value value
                                    :valid-until (+ now cache-ms)})
          value))))

(defn- bearer-token [request]
  (let [header (get-in request [:headers "authorization"])]
    (when (and header (str/starts-with? (str/lower-case header) "bearer "))
      (str/trim (subs header (count "Bearer "))))))

(defn- edn-response [status body]
  {:status status
   :headers {"Content-Type" "application/edn"}
   :body (pr-str body)})

(defn wrap-auth [handler]
  (fn [request]
    (if-let [token (bearer-token request)]
      (if-let [principal (resolve-principal token)]
        (if (or (empty? allowed-principals)
                (contains? allowed-principals principal))
          (handler (assoc request :agent-principal principal))
          (edn-response 403 {:error :forbidden}))
        (edn-response 401 {:error :unauthorized}))
      (edn-response 401 {:error :unauthorized}))))
