(ns de.explorama.backend.agent-gateway.dispatcher
  (:require [de.explorama.shared.agent-gateway.catalog :as catalog]
            [de.explorama.shared.agent-gateway.errors :as errors]
            [taoensso.timbre :refer [warn]]))

(defonce ^:private backend-handlers (atom {}))
(defonce ^:private relay (atom nil))

(defn register-op! [op handler]
  (swap! backend-handlers assoc op handler)
  nil)

(defn registered-ops []
  (set (keys @backend-handlers)))

(defn set-relay! [f]
  (reset! relay f)
  nil)

(defn reset-registry! []
  (reset! backend-handlers {})
  (reset! relay nil)
  nil)

(defn- run-backend-op [op handler user params]
  (try
    (errors/ok (handler {:user user :params params}))
    (catch #?(:clj Throwable :cljs :default) e
      (let [{:keys [gateway-error] :as data} (ex-data e)]
        (warn e "agent op failed" {:op op})
        (errors/error (or gateway-error :op-failed)
                      (or (ex-message e) (str e))
                      (assoc (dissoc data :gateway-error) :op op))))))

(defn call-route
  "Runs a websocket route fn with synthetic metas and returns what it handed to client-callback."
  [route-fn params]
  (let [result (atom ::none)
        failure (atom ::none)]
    (route-fn {:client-callback (fn [& args] (reset! result (vec args)))
               :failed-callback (fn [& args] (reset! failure (vec args)))
               :broadcast-callback (fn [& _] nil)
               :user-validation (constantly true)
               :client-id nil}
              params)
    (cond
      (not= ::none @failure)
      (throw (ex-info "the route reported a failure" {:gateway-error :op-failed :failure @failure}))

      (= ::none @result)
      (throw (ex-info "the route did not answer" {:gateway-error :op-failed}))

      :else
      (let [r @result] (if (= 1 (count r)) (first r) r)))))

(defn invoke [{:keys [op params user client-id]}]
  (let [{:keys [side] :as declaration} (catalog/declaration op)
        params (or params {})
        explain (when declaration (catalog/explain-input op params))]
    (cond
      (nil? declaration)
      (errors/error :unknown-op "no such operation" {:op op})

      explain
      (errors/error :invalid-params "params do not match the op's input schema" {:op op :explain explain})

      (= :backend side)
      (if-let [handler (get @backend-handlers op)]
        (run-backend-op op handler user params)
        (errors/error :unknown-op "operation declared but no backend handler registered" {:op op}))

      (nil? user)
      (errors/error :invalid-params "frontend ops need :user" {:op op})

      (nil? @relay)
      (errors/error :no-session "no session relay configured" {:op op})

      :else
      (@relay user client-id op params (catalog/timeout-ms op)))))
