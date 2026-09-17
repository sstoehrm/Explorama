(ns de.explorama.backend.agent-gateway.relay
  (:require [de.explorama.backend.frontend-api :as frontend-api]
            [de.explorama.shared.agent-gateway.errors :as errors]
            [de.explorama.shared.agent-gateway.ws-api :as ws-api]
            [pneumatic-tubes.core :as tubes]
            [taoensso.timbre :refer [debug]]))

(defonce ^:private pending (atom {}))

(defn reset-pending! []
  (reset! pending {})
  nil)

(defn pending-request-ids []
  (set (keys @pending)))

(defn- public-session [tube]
  (select-keys tube [:client-id :role :connected-at]))

(defn- user-tubes [username]
  (tubes/find-tubes (fn [tube]
                      (and (= username (:username tube))
                           (some? (:client-id tube))))))

(defn sessions [username]
  (mapv public-session (user-tubes username)))

(defn- resolve-tube [username client-id]
  (let [candidates (user-tubes username)
        matching (if client-id
                   (filter #(= client-id (:client-id %)) candidates)
                   candidates)]
    (cond
      (empty? candidates)
      {:error (errors/error :no-session "the user has no connected session" {:user username})}

      (and client-id (empty? matching))
      {:error (errors/error :no-session "no session with that client-id"
                            {:user username :client-id client-id
                             :sessions (mapv public-session candidates)})}

      (> (count matching) 1)
      {:error (errors/error :ambiguous-session "the user has several sessions; pass :client-id"
                            {:user username :sessions (mapv public-session matching)})}

      :else {:ok (first matching)})))

(defn deliver-result! [request-id response]
  (when-let [{:keys [promise]} (get @pending request-id)]
    (swap! pending dissoc request-id)
    (deliver promise response)
    nil))

(defn on-tube-destroy! [{tube-id :tube/id}]
  (doseq [[request-id {:keys [promise] :as entry}] @pending
          :when (= tube-id (:tube-id entry))]
    (swap! pending dissoc request-id)
    (deliver promise (errors/error :no-session "the session disconnected mid-request"))))

(defn invoke! [username client-id op params timeout-ms]
  (let [{:keys [ok error]} (resolve-tube username client-id)]
    (if error
      error
      (let [request-id (str (java.util.UUID/randomUUID))
            p (promise)]
        (swap! pending assoc request-id {:promise p :tube-id (:tube/id ok)})
        (debug "agent command" {:request-id request-id :op op :client-id (:client-id ok)})
        (frontend-api/dispatch ok [ws-api/command request-id op params])
        (let [response (deref p timeout-ms ::timeout)]
          (if (= ::timeout response)
            (do (swap! pending dissoc request-id)
                (errors/error :timeout (str "no answer within " timeout-ms " ms") {:op op}))
            response))))))

;; Route handlers return nil: pneumatic-tubes stores a map return value as
;; the tube's data, which would clobber the identity set on connect.
(defn- result-route [_metas [request-id response]]
  (deliver-result! request-id response)
  nil)

(defn- tube-destroyed-route [{:keys [tube]} _]
  (on-tube-destroy! tube)
  nil)

(defn init []
  (frontend-api/register-routes {ws-api/result result-route
                                 :tube/on-destroy tube-destroyed-route})
  nil)
