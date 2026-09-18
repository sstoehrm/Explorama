(ns de.explorama.shared.expdb.agent-ops)

(def csv [:map {:closed true} [:separator string?] [:quote string?]])

(def ops
  [{:op :expdb/upload :side :backend
    :doc "Stage a csv file's raw :content under :file-name and analyze it. Returns the heuristic mapping :suggestion (a data-transformer descriptor with :meta-data and :mapping) and a :preview of the first parsed rows. Pass :csv to override the separator and quote."
    :input [:map {:closed true} [:file-name [:string {:min 1}]] [:content string?] [:csv {:optional true} csv]]
    :output [:map [:suggestion :any] [:preview :any]] :timeout-ms 120000}
   {:op :expdb/set-options :side :backend
    :doc "Re-analyze a staged file with new csv options. Returns :suggestion and :preview."
    :input [:map {:closed true} [:file-name [:string {:min 1}]] [:csv csv]] :output [:map [:suggestion :any] [:preview :any]] :timeout-ms 120000}
   {:op :expdb/set-mapping :side :backend
    :doc "Validate :mapping (a full descriptor, usually the suggestion with edits) against the data-transformer schema and stage the import in a transaction. Returns the staging result including :mapping-errors. Nothing is persisted until :expdb/commit."
    :input [:map {:closed true} [:file-name [:string {:min 1}]] [:mapping map?]] :output :any :timeout-ms 600000}
   {:op :expdb/commit :side :backend
    :doc "Commit the staged import into the shared database. Only acts on an import this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged or another user (including the import dialog, which shares the same staging slot) staged it. Ask the user before calling this. Returns the import summary."
    :input [:map {:closed true}] :output :any :timeout-ms 600000}
   {:op :expdb/cancel :side :backend
    :doc "Discard the staged import transaction. Only acts on an import this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged or another user (including the import dialog, which shares the same staging slot) staged it."
    :input [:map {:closed true}] :output :any}
   {:op :expdb/delete :side :backend
    :doc "Discard a staged file and its transaction. Only acts on the file this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged, a different file is staged, or another user (including the import dialog, which shares the same staging slot) staged it."
    :input [:map {:closed true} [:file-name [:string {:min 1}]]] :output :any}
   {:op :expdb/buckets :side :backend :doc "The database's buckets with their datasources, to verify an import." :input [:map {:closed true}] :output :any}])
