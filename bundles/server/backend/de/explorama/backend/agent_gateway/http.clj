(ns de.explorama.backend.agent-gateway.http
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [ANY GET POST routes]]
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.agent-gateway.relay :as relay]
            [de.explorama.shared.agent-gateway.catalog :as catalog]
            [de.explorama.shared.agent-gateway.errors :as errors]
            [de.explorama.shared.common.configs.provider :refer [defconfig]]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.timbre :refer [error]]))

(def principal-header
  (defconfig
    {:env :explorama-agent-gateway-principal-header
     :default "x-auth-request-user"
     :type :string
     :doc "Request header carrying the principal the fronting proxy authenticated."}))

(def allowed-principals
  (defconfig
    {:env :explorama-agent-gateway-principals
     :default #{}
     :type :edn-string
     :doc "Principals allowed on the agent gateway api. Empty denies everyone, so the api stays inert until a principal is named."}))

(def default-timeout-ms
  (defconfig
    {:env :explorama-agent-gateway-timeout-ms
     :default 30000
     :type :integer
     :doc "Default timeout for relayed operations that declare none."}))

(def ^:dynamic sessions-fn relay/sessions)

(defn- edn-response [status body]
  {:status status
   :headers {"Content-Type" "application/edn"}
   :body (pr-str body)})

(defn- respond [response]
  (edn-response (if (errors/error? response)
                  (get errors/http-status (get-in response [:error :type]) 500)
                  200)
                response))

(defn- catalog-handler [_]
  (respond (errors/ok {:ops (catalog/public-catalog)})))

(defn- sessions-handler [{{user "user"} :query-params}]
  (if (str/blank? user)
    (respond (errors/error :invalid-params "query parameter user is required"))
    (respond (errors/ok {:sessions (vec (sessions-fn user))}))))

(defn- invoke-handler [{{:keys [plugin op]} :params body :edn-body}]
  (respond (dispatcher/invoke {:op (keyword plugin op)
                               :params (:params body)
                               :user (:user body)
                               :client-id (:client-id body)})))

(def ^:private api
  (routes
   (GET "/api/agent/ops" [] catalog-handler)
   (GET "/api/agent/sessions" [] sessions-handler)
   (POST "/api/agent/ops/:plugin/:op" [] invoke-handler)
   (ANY "*" [] (fn [_] (respond (errors/error :unknown-op "no such route under /api/agent"))))))

(defn- wrap-edn-body [handler]
  (fn [{:keys [body request-method] :as request}]
    (if (= :get request-method)
      (handler request)
      (let [raw (when body (slurp body))
            parsed (try
                     {:ok (when (seq raw) (edn/read-string raw))}
                     (catch Throwable e
                       (error e "Unparseable EDN request body")
                       {:error e}))]
        (if (:error parsed)
          (respond (errors/error :invalid-params "request body is not readable EDN"))
          (handler (assoc request :edn-body (:ok parsed))))))))

(defn- header-principal [request]
  (let [value (get-in request [:headers (str/lower-case principal-header)])
        value (when value (str/trim value))]
    (when (seq value) value)))

(defn- wrap-auth [handler]
  (fn [request]
    (let [principal (header-principal request)]
      (if (and principal (contains? allowed-principals principal))
        (handler (assoc request :agent-principal principal))
        (respond (errors/error :unauthorized "no allow-listed principal on the request"))))))

(def ^:private api-path-prefix "/api/agent")

(defn- wrap-api-path [handler]
  (fn [request]
    (let [uri (str (:uri request))]
      (when (or (= uri api-path-prefix)
                (str/starts-with? uri (str api-path-prefix "/")))
        (handler request)))))

(def api-routes (-> api wrap-edn-body wrap-auth wrap-params wrap-api-path))

(def handler api-routes)

(defn init []
  (relay/init)
  (dispatcher/set-relay! (fn [user client-id op params timeout-ms]
                           (relay/invoke! user client-id op params
                                          (if (= catalog/default-timeout-ms timeout-ms)
                                            default-timeout-ms
                                            timeout-ms))))
  nil)
