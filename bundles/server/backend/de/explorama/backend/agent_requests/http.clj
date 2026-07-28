(ns de.explorama.backend.agent-requests.http
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [GET POST routes]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [malli.core :as m]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.timbre :refer [error]]))

(def ^:private error-status
  {:not-found 404
   :gone 410
   :conflict 409
   :not-claimed 409
   :invalid 422})

(defn- edn-response [status body]
  {:status status
   :headers {"Content-Type" "application/edn"}
   :body (pr-str body)})

(defn- outcome-response [outcome success-body]
  (if-let [request (:ok outcome)]
    (edn-response 200 (success-body request))
    (edn-response (get error-status (:error outcome) 400) outcome)))

(defn- public-type [{:keys [id description output-schema output-example]}]
  {:id id
   :description description
   :output-schema (m/form output-schema)
   :output-example output-example})

(defn- public-request [request]
  (select-keys request [:id :type :input :created-at]))

(defn- parse-type [type-param]
  (when (seq type-param)
    (edn/read-string type-param)))

(defn- types-handler [_]
  (edn-response 200 {:types (mapv public-type (registry/all-types))}))

(defn- parsed-type-filter [type-param]
  (try
    {:ok (parse-type type-param)}
    (catch Throwable e
      (error e "Unparseable type query param")
      {:invalid? true})))

(defn- list-handler [{{type-param "type"} :query-params}]
  (let [{:keys [ok invalid?]} (parsed-type-filter type-param)]
    (if invalid?
      (edn-response 400 {:error :malformed-type})
      (edn-response 200 {:requests (mapv public-request
                                         (store/open-requests ok))}))))

(defn- claim-handler [{{id :id} :params body :edn-body}]
  (outcome-response (store/claim! id (:agent body))
                    (fn [request] {:lease-expires-at (:lease-expires-at request)})))

(defn- result-handler [{{id :id} :params body :edn-body}]
  (outcome-response (store/submit! id body)
                    (constantly {:status :fulfilled})))

(defn- fail-handler [{{id :id} :params body :edn-body}]
  (outcome-response (store/fail! id (:reason body))
                    (constantly {:status :failed})))

(def ^:private api
  (routes
   (GET "/api/agent-requests/types" [] types-handler)
   (GET "/api/agent-requests" [] list-handler)
   (POST "/api/agent-requests/:id/claim" [] claim-handler)
   (POST "/api/agent-requests/:id/result" [] result-handler)
   (POST "/api/agent-requests/:id/fail" [] fail-handler)))

(defn- wrap-edn-body [handler]
  (fn [{:keys [body request-method] :as request}]
    (if (= :get request-method)
      (handler request)
      (try
        (let [raw (when body (slurp body))]
          (handler (assoc request :edn-body (when (seq raw) (edn/read-string raw)))))
        (catch Throwable e
          (error e "Unparseable EDN request body")
          (edn-response 400 {:error :malformed-body}))))))

(def ^:private api-path-prefix "/api/agent-requests")

(defn- wrap-auth [handler]
  (fn [request]
    (let [{:keys [principal error]} (auth/authenticate request)]
      (if principal
        (handler (assoc request :agent-principal principal))
        (edn-response (if (= :forbidden error) 403 401)
                      {:error (or error :unauthorized)})))))

(defn- wrap-api-path [handler]
  (fn [request]
    (when (str/starts-with? (str (:uri request)) api-path-prefix)
      (handler request))))

(def api-routes (-> api wrap-edn-body wrap-auth wrap-params wrap-api-path))

(def handler api-routes)
