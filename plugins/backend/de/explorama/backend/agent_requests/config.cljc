(ns de.explorama.backend.agent-requests.config
  (:require [de.explorama.shared.common.configs.provider :refer [defconfig]]))

(def lease-ms
  (defconfig
    {:env :explorama-agent-requests-lease-ms
     :default 60000
     :type :integer
     :doc "How long an agent's claim on a request is held before it returns to the open queue."}))

(def ttl-ms
  (defconfig
    {:env :explorama-agent-requests-ttl-ms
     :default 900000
     :type :integer
     :doc "How long a request stays in the queue before it expires."}))

(def max-rejections
  (defconfig
    {:env :explorama-agent-requests-max-rejections
     :default 3
     :type :integer
     :doc "How often a result may fail schema validation before the request is failed."}))

(def raw-head-lines
  (defconfig
    {:env :explorama-agent-requests-raw-head-lines
     :default 20
     :type :integer
     :doc "How many raw lines of an uploaded file are sent as input for a mapping request."}))
