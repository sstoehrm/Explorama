(ns de.explorama.backend.agent-requests.auth)

(defonce ^:private authenticator (atom nil))

(defn set-authenticator! [f]
  (reset! authenticator f)
  nil)

(defn reset-authenticator! []
  (reset! authenticator nil)
  nil)

(defn authenticate [request]
  (if-let [f @authenticator]
    (f request)
    {:error :unauthorized}))
