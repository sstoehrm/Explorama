(ns de.explorama.shared.agent-requests.schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def type-declaration
  [:map {:closed true}
   [:id qualified-keyword?]
   [:description string?]
   [:output-schema :any]
   [:output-example :any]
   [:on-fulfilled fn?]])

(defn explain-type-declaration [declaration]
  (some-> (m/explain type-declaration declaration)
          me/humanize))

(defn explain-value [schema value]
  (some-> (m/explain schema value)
          me/humanize))
