(ns de.explorama.shared.woco.agent-ops)

(def frame-id :any)

(def frame
  [:map
   [:id frame-id] [:vertical string?] [:type [:maybe keyword?]] [:title [:maybe string?]]
   [:left [:maybe number?]] [:top [:maybe number?]] [:width [:maybe number?]] [:height [:maybe number?]]
   [:z-index [:maybe number?]] [:minimized? boolean?] [:maximized? boolean?]
   [:di :any] [:published-by :any] [:color-group :any]])

(def frames [:vector frame])

(def connection
  [:map [:from frame-id] [:to frame-id] [:kind [:enum :publishes :coupled]]])

(def marker
  [:map [:frame-id frame-id] [:note [:maybe string?]] [:set-at number?] [:title [:maybe string?]] [:vertical string?]])

(def ^:private frame-only [:map {:closed true} [:frame-id frame-id]])

(def ops
  [{:op :woco/frames :side :frontend
    :doc "Every frame in the workspace with its vertical, title, geometry, z-order, window state, the data instance it shows and the frame that published that data instance. Frame ids are opaque; pass them back verbatim."
    :input [:map {:closed true}] :output frames}
   {:op :woco/connections :side :frontend
    :doc "Edges between frames: :publishes (the :from frame's data instance feeds the :to frame) and :coupled (the frames are coupled)."
    :input [:map {:closed true}] :output [:vector connection]}
   {:op :woco/frame-state :side :frontend
    :doc "The full serializable state of one frame as its plugin reports it (the vis-desc used by reporting), without the screenshot."
    :input frame-only :output [:maybe map?]}
   {:op :woco/workspace :side :frontend
    :doc "Interaction mode (:normal, :read-only, :pending-read-only, :no-render, :render), workspace id, viewport position and the loaded project. Check the mode before mutating."
    :input [:map {:closed true}]
    :output [:map [:interaction-mode keyword?] [:workspace-id :any] [:viewport :any] [:project :any]]}
   {:op :woco/markers :side :frontend
    :doc "Frames the user marked with the pointer tool, with their notes. A marked frame is the one the user wants you to work on."
    :input [:map {:closed true}] :output [:vector marker]}
   {:op :woco/clear-markers :side :frontend
    :doc "Remove every marker."
    :input [:map {:closed true}] :output [:vector marker]}
   {:op :woco/open-vertical :side :frontend
    :doc "Open a visualization frame (table, mosaic, map, charts or algorithms) at :position, fed by :source-frame-id's data instance when given. Returns the new frame."
    :input [:map {:closed true}
            [:vertical [:enum "table" "mosaic" "map" "charts" "algorithms"]]
            [:source-frame-id {:optional true} frame-id]
            [:position {:optional true} [:tuple number? number?]]
            [:opts {:optional true} map?]]
    :output frame :timeout-ms 10000}
   {:op :woco/connect :side :frontend
    :doc "Feed :source's data instance into :target, like dragging one frame onto another. Returns the connections."
    :input [:map {:closed true} [:source frame-id] [:target frame-id]] :output [:vector connection]}
   {:op :woco/set-geometry :side :frontend
    :doc "Move and/or resize a frame in workspace pixels. Returns the frame."
    :input [:map {:closed true} [:frame-id frame-id]
            [:left {:optional true} number?] [:top {:optional true} number?]
            [:width {:optional true} number?] [:height {:optional true} number?]]
    :output frame}
   {:op :woco/set-title :side :frontend :doc "Set a frame's user title. Returns the frame."
    :input [:map {:closed true} [:frame-id frame-id] [:title string?]] :output frame}
   {:op :woco/minimize :side :frontend :doc "Minimize a frame. Returns the frame." :input frame-only :output frame}
   {:op :woco/maximize :side :frontend :doc "Maximize a frame. Returns the frame." :input frame-only :output frame}
   {:op :woco/normalize :side :frontend :doc "Restore a minimized or maximized frame. Returns the frame." :input frame-only :output frame}
   {:op :woco/bring-to-front :side :frontend :doc "Raise a frame above the others. Returns the frame." :input frame-only :output frame}
   {:op :woco/close :side :frontend :doc "Close a frame. Returns the remaining frames." :input frame-only :output frames}])
