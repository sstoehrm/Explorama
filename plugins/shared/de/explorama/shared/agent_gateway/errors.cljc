(ns de.explorama.shared.agent-gateway.errors)

(def http-status
  {:unauthorized 403
   :unknown-op 404
   :invalid-params 400
   :no-session 409
   :ambiguous-session 409
   :workspace-busy 409
   :timeout 504
   :op-failed 500})

(def types (set (keys http-status)))

(defn ok [result]
  {:status :ok :result result})

(defn error
  ([type message] (error type message nil))
  ([type message extra]
   {:status :error
    :error (merge {:type type :message message} extra)}))

(defn error? [response]
  (= :error (:status response)))
