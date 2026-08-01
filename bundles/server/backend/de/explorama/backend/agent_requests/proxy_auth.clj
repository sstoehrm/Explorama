(ns de.explorama.backend.agent-requests.proxy-auth
  (:require [clojure.string :as str]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.shared.common.configs.provider :refer [defconfig]]))

(def principal-header
  (defconfig
    {:env :explorama-agent-requests-principal-header
     :default "x-auth-request-user"
     :type :string
     :doc "Request header carrying the principal the fronting proxy authenticated."}))

(def allowed-principals
  (defconfig
    {:env :explorama-agent-requests-principals
     :default #{}
     :type :edn-string
     :doc "Principals allowed on the agent request api. Empty denies everyone, so the api stays inert until a principal is named."}))

(defn- header-principal [request]
  (let [value (get-in request [:headers (str/lower-case principal-header)])
        value (when value (str/trim value))]
    (when (seq value)
      value)))

(defn authenticate [request]
  (if-let [principal (header-principal request)]
    (if (contains? allowed-principals principal)
      {:principal principal}
      {:error :forbidden})
    {:error :unauthorized}))

(defn init []
  (auth/set-authenticator! authenticate)
  nil)
