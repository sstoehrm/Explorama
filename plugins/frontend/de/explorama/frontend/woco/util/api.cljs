(ns de.explorama.frontend.woco.util.api
  (:require [de.explorama.frontend.woco.api.registry :as registry]
            [re-frame.core :as re-frame]
            [re-frame.db :as rf-db]
            [taoensso.timbre :refer [warn]]))

(defonce ^:private errors (atom #{}))

(defn event-error-boundary [event-vec & vec-params]
  (if (vector? event-vec)
    (apply conj event-vec vec-params)
    (when-not (@errors [:event-error event-vec])
      (warn "Event-vec not available" event-vec)
      (swap! errors conj [:event-error event-vec])
      [::no-op])))

(defn sub-error-boundary [category service & vec-params]
  (if-let [sub-vector (registry/lookup-target @rf-db/app-db category service)]
    (re-frame/subscribe (apply conj sub-vector vec-params))
    (when-not (@errors [:sub-error category service])
      (warn "subscription not available" category service)
      (swap! errors conj [:sub-error category service])
      (atom nil))))

(defn db-get-error-boundary [db db-get-fn warn-identifier & fn-params]
  (if (fn? db-get-fn)
    (apply db-get-fn db fn-params)
    (when-not (@errors [:warn warn-identifier])
      (warn "db-get not available" warn-identifier)
      (swap! errors conj [:warn warn-identifier])
      nil)))