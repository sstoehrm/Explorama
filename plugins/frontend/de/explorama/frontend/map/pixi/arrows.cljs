(ns de.explorama.frontend.map.pixi.arrows
  "Pure screen-space geometry for tapered movement-flow arrows: one filled
   polygon per arrow (a shaft that narrows into a head neck, flares into
   barbs, then comes to a point at the target) plus point-to-segment
   distance for hover hit-testing. Both are pure math over plain
   screen-pixel coordinates - callers own the world->screen projection.")

(def ^:private short-segment-px
  "Segments shorter than this have no room left for a legible head;
   arrow-polygon falls back to a plain tapered quad rather than draw a
   barb wider than the segment itself."
  8)

(defn arrow-polygon
  "Flat vertex list `[x0 y0 x1 y1 ...]` for ONE filled polygon depicting a
   movement arrow from source [sx sy] to target [tx ty], or nil when the
   two points coincide (zero-length segment - no direction to draw along).

   Classic 7-vertex barbed arrow: a shaft quad tapers from a
   `(max 1 (/ w-source 2))` half-width at the source to a
   `(max 1 (/ w-target 2))` half-width at the head-base neck (OL
   width=weight parity at the target end), then a head triangle flares out
   to `(max 3 w-target)` half-width barbs at that same head-base station
   before coming to a point exactly at the target. Head length - how far
   the head base sits back from the target along the source->target
   direction - is `(min (max 6 (* 1.8 w-target)) (* 0.6 len))`: the
   uncapped `(max 6 ...)` floor alone can place the head base behind the
   source on short/thick segments (a self-intersecting bowtie), so it is
   also capped at 60% of the segment's own length, which keeps the head
   base strictly between source and target for any positive `len`.
   Segments shorter than `short-segment-px` (8px) have no room left for a
   legible barb and skip the head entirely, returning a plain 4-vertex
   quad tapering from the source half-width to the target-shaft
   half-width.

   Vertex order walks the perimeter of the resulting polygon (simple,
   non-self-intersecting by construction: shaft, neck-flare and head
   triangle are each individually simple bands stacked along the
   source->target axis) starting at the source corner on the +n side of
   the source->target direction (n = that direction rotated 90 degrees):
   source-left -> head-base-neck-left -> barb-left -> tip (the target) ->
   barb-right -> head-base-neck-right -> source-right. The short-segment
   quad drops the three head-only vertices: source-left -> target-left ->
   target-right -> source-right. Ready for `.drawPolygon`."
  [[tx ty] [sx sy] w-target w-source]
  (let [dx (- tx sx) dy (- ty sy)
        len (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
    (when (pos? len)
      (let [ux (/ dx len) uy (/ dy len)
            nx (- uy) ny ux
            source-half-w (max 1 (/ w-source 2))
            target-half-w (max 1 (/ w-target 2))]
        (if (< len short-segment-px)
          [(+ sx (* nx source-half-w)) (+ sy (* ny source-half-w))
           (+ tx (* nx target-half-w)) (+ ty (* ny target-half-w))
           (- tx (* nx target-half-w)) (- ty (* ny target-half-w))
           (- sx (* nx source-half-w)) (- sy (* ny source-half-w))]
          (let [barb-half-w (max 3 w-target)
                head-len (min (max 6 (* 1.8 w-target)) (* 0.6 len))
                hbx (- tx (* ux head-len)) hby (- ty (* uy head-len))]
            [(+ sx (* nx source-half-w)) (+ sy (* ny source-half-w))
             (+ hbx (* nx target-half-w)) (+ hby (* ny target-half-w))
             (+ hbx (* nx barb-half-w)) (+ hby (* ny barb-half-w))
             tx ty
             (- hbx (* nx barb-half-w)) (- hby (* ny barb-half-w))
             (- hbx (* nx target-half-w)) (- hby (* ny target-half-w))
             (- sx (* nx source-half-w)) (- sy (* ny source-half-w))]))))))

(defn point-segment-dist-sq
  "Squared distance from point [px py] to the segment [ax ay]-[bx by].
   Projects the point onto the segment's line and clamps the projection
   parameter t to [0,1] - t<=0 collapses to the a endpoint, t>=1 to the b
   endpoint, otherwise t lands on an interior point - so endpoint and
   interior cases fall out of one formula. A zero-length segment (a = b)
   is handled as plain point-to-point distance, avoiding a divide by
   zero. Left squared since callers only ever compare against a squared
   hover tolerance."
  [[px py] [ax ay] [bx by]]
  (let [abx (- bx ax) aby (- by ay)
        ab-len-sq (+ (* abx abx) (* aby aby))]
    (if (zero? ab-len-sq)
      (let [dx (- px ax) dy (- py ay)]
        (+ (* dx dx) (* dy dy)))
      (let [apx (- px ax) apy (- py ay)
            t (-> (/ (+ (* apx abx) (* apy aby)) ab-len-sq)
                  (max 0)
                  (min 1))
            cx (+ ax (* t abx)) cy (+ ay (* t aby))
            dx (- px cx) dy (- py cy)]
        (+ (* dx dx) (* dy dy))))))
