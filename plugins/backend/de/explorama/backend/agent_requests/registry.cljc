(ns de.explorama.backend.agent-requests.registry
  (:require [de.explorama.shared.agent-requests.schema :as schema]))

(defonce ^:private types (atom {}))

(defn reset-types! []
  (reset! types {})
  nil)

(defn register-type! [{:keys [id] :as declaration}]
  (when-let [explanation (schema/explain-type-declaration declaration)]
    (throw (ex-info "Invalid request type declaration"
                    {:explanation explanation
                     :id id})))
  (swap! types assoc id declaration)
  declaration)

(defn type-declaration [type-id]
  (get @types type-id))

(defn all-types []
  (vec (sort-by :id (vals @types))))

(defn explain-result [type-id result]
  (if-let [{:keys [output-schema]} (type-declaration type-id)]
    (schema/explain-value output-schema result)
    {:type ["unknown request type"]}))
