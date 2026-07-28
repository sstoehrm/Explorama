(ns de.explorama.backend.agent-requests.backend
  (:require [de.explorama.backend.agent-requests.api :as api]
            [de.explorama.backend.frontend-api :as frontend-api]
            [de.explorama.shared.agent-requests.ws-api :as ws-api]
            [taoensso.timbre :refer [debug]]))

(defn init []
  (frontend-api/register-routes {ws-api/list-requests api/list-requests
                                 ws-api/cancel-request api/cancel-request})
  (debug "agent-requests backend started"))
