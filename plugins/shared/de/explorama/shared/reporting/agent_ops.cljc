(ns de.explorama.shared.reporting.agent-ops)

(def ^:private desc [:map [:id string?] [:name string?]])

(def ops
  [{:op :reporting/dashboards :side :backend :doc "All dashboards the user may read, with their tiles." :input [:map {:closed true}] :output :any}
   {:op :reporting/save-dashboard :side :backend
    :doc "Create or replace a dashboard description (:id, :name, tiles). Read an existing one from :reporting/dashboards and modify it. Returns :reporting/dashboards."
    :input [:map {:closed true} [:desc desc]] :output :any}
   {:op :reporting/delete-dashboard :side :backend :doc "Delete a dashboard. Returns :reporting/dashboards." :input [:map {:closed true} [:id string?]] :output :any}
   {:op :reporting/reports :side :backend :doc "All reports the user may read." :input [:map {:closed true}] :output :any}
   {:op :reporting/save-report :side :backend :doc "Create or replace a report description. Returns :reporting/reports." :input [:map {:closed true} [:desc desc]] :output :any}
   {:op :reporting/delete-report :side :backend :doc "Delete a report. Returns :reporting/reports." :input [:map {:closed true} [:id string?]] :output :any}])
