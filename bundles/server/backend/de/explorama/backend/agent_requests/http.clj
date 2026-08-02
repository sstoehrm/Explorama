(ns de.explorama.backend.agent-requests.http
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [GET POST routes]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [de.explorama.shared.common.configs.provider :refer [defconfig]]
            [malli.core :as m]
            [org.httpkit.server :as hk]
            [org.httpkit.timer :as timer]
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

(def max-wait-seconds
  (defconfig
    {:env :explorama-agent-requests-max-wait
     :default 30
     :type :integer
     :doc "Upper bound in seconds for long-polling the agent request api."}))

(defn clamp-wait [wait-param]
  (let [parsed (try
                 (Integer/parseInt (str wait-param))
                 (catch NumberFormatException _ 0))]
    (max 0 (min max-wait-seconds parsed))))

(defn- open-response [type-filter]
  (edn-response 200 {:requests (mapv public-request (store/open-requests type-filter))}))

(defn- default-async-respond
  "Registers the request's deferred cleanup (returned by `respond-fn`) as the
  channel's close handler, so a client that disconnects mid-wait is
  unregistered immediately instead of lingering until the wait elapses."
  [request respond-fn]
  (let [finish-box (atom nil)]
    (hk/as-channel
     request
     {:on-open (fn [channel]
                 (reset! finish-box
                         (respond-fn (fn [response] (hk/send! channel response)))))
      :on-close (fn [_channel _status]
                  (when-let [finish! @finish-box]
                    (finish!)))})))

(def ^:dynamic *async-respond-fn* default-async-respond)

(defn- deferred-list
  "Calls `respond-fn` with a `send-response` sink; `respond-fn` must return a
  no-arg cleanup/answer function so callers (production's channel close
  handler, or a test double) can trigger the same exactly-once completion
  path that the watcher and the timeout use."
  [request type-filter wait-seconds]
  (*async-respond-fn*
   request
   (fn [send-response]
     (let [watch-key (gensym "agent-requests-wait")
           done (atom false)
           timer-task (atom nil)
           finish! (fn []
                     (when (compare-and-set! done false true)
                       (store/unwatch! watch-key)
                       (when-let [task @timer-task]
                         (timer/cancel task))
                       (send-response (open-response type-filter))))]
       (store/watch! watch-key
                     (fn []
                       (when (seq (store/open-requests type-filter))
                         (finish!))))
       (reset! timer-task (timer/schedule-task (* 1000 wait-seconds) (finish!)))
       (when (seq (store/open-requests type-filter))
         (finish!))
       finish!))))

(defn- list-handler [{{type-param "type" wait-param "wait"} :query-params :as request}]
  (let [{:keys [ok invalid?]} (parsed-type-filter type-param)]
    (if invalid?
      (edn-response 400 {:error :malformed-type})
      (let [wait-seconds (clamp-wait wait-param)]
        (if (or (zero? wait-seconds)
                (seq (store/open-requests ok)))
          (open-response ok)
          (deferred-list request ok wait-seconds))))))

(defn- claim-handler [{{id :id} :params principal :agent-principal}]
  (outcome-response (store/claim! id principal)
                    (fn [request] {:lease-expires-at (:lease-expires-at request)})))

(defn- result-handler [{{id :id} :params body :edn-body principal :agent-principal}]
  (outcome-response (store/submit! id principal body)
                    (constantly {:status :fulfilled})))

(defn- fail-handler [{{id :id} :params body :edn-body principal :agent-principal}]
  (outcome-response (store/fail! id principal (:reason body))
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
    (let [uri (str (:uri request))]
      (when (or (= uri api-path-prefix)
                (str/starts-with? uri (str api-path-prefix "/")))
        (handler request)))))

(def api-routes (-> api wrap-edn-body wrap-auth wrap-params wrap-api-path))

(def handler api-routes)
