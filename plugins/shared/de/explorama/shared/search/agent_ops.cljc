(ns de.explorama.shared.search.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def ^:private frame-only [:map {:closed true} [:frame-id woco/frame-id]])

(def ops
  [{:op :search/open :side :frontend
    :doc "Open an empty search frame at the default position. Returns the frame. Fill it with :search/set-form or :search/add-rows, then :search/run."
    :input [:map {:closed true}] :output woco/frame :timeout-ms 10000}
   {:op :search/attributes :side :frontend
    :doc "The attribute descriptors ([name type] tuples) known to the search and their types, plus the attributes already in the frame's form."
    :input frame-only :output [:map [:attribute-types :any] [:frame-attributes :any]]}
   {:op :search/form-state :side :frontend
    :doc "The frame's form data: attribute descriptor -> selected values and options, as the search stores it."
    :input frame-only :output map?}
   {:op :search/data-instance :side :frontend
    :doc "The data instance the frame currently publishes, or nil before :search/run."
    :input frame-only :output :any}
   {:op :search/set-form :side :frontend
    :doc "Replace the frame's whole form data with :formdata (the shape :search/form-state returns). Returns the form state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:formdata map?]] :output map?}
   {:op :search/add-rows :side :frontend
    :doc "Add attribute rows by name with selected values, e.g. [[\"country\" [\"Germany\"]] [\"year\" [2020]]]. Returns the form state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:rows [:vector [:tuple string? :any]]]] :output map?}
   {:op :search/run :side :frontend
    :doc "Run the search: creates the data instance from the form and answers with it once the backend delivered it."
    :input frame-only :output :any :timeout-ms 120000}
   {:op :search/open-visualization :side :frontend
    :doc "Open a visualization fed by this search frame. Same as :woco/open-vertical with :source-frame-id set."
    :input [:map {:closed true} [:frame-id woco/frame-id]
            [:vertical [:enum "table" "mosaic" "map" "charts" "algorithms"]]
            [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}])
