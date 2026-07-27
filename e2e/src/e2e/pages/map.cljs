(ns e2e.pages.map
  (:require [e2e.pages.workspace :as ws]
            [promesa.core :as p]))

(defn canvas [page]
  (.locator (ws/frame page :map) "canvas"))

;; Markers are canvas-drawn sprites, so there is no DOM to locate them by.
;; The engine keeps preserveDrawingBuffer on (screenshot export relies on
;; it), which makes pixel readback reliable: blit the WebGL canvas onto a 2d
;; canvas, collect connected blobs of non-background pixels, and classify by
;; size - single markers are 12px circles, cluster bubbles are >=20px.
(def ^:private scan-body
  "const w = cv.width, h = cv.height;
     const c2 = document.createElement('canvas');
     c2.width = w; c2.height = h;
     const ctx = c2.getContext('2d');
     ctx.drawImage(cv, 0, 0);
     const d = ctx.getImageData(0, 0, w, h).data;
     const bg = (r, g, b) => Math.abs(r - 234) < 14 && Math.abs(g - 234) < 14 && Math.abs(b - 234) < 14;
     const marked = new Uint8Array(w * h);
     for (let y = 0; y < h; y++)
       for (let x = 0; x < w; x++) {
         const i = (y * w + x) * 4;
         if (d[i + 3] > 200 && !bg(d[i], d[i + 1], d[i + 2])) marked[y * w + x] = 1;
       }
     const seen = new Uint8Array(w * h);
     const blobs = [];
     for (let y = 0; y < h; y++)
       for (let x = 0; x < w; x++) {
         const idx = y * w + x;
         if (!marked[idx] || seen[idx]) continue;
         let minX = x, maxX = x, minY = y, maxY = y, n = 0;
         const stack = [idx];
         seen[idx] = 1;
         while (stack.length) {
           const cur = stack.pop();
           const cx = cur % w, cy = (cur / w) | 0;
           n++;
           if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
           if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
           for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
             const nx = cx + dx, ny = cy + dy;
             if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
             const ni = ny * w + nx;
             if (marked[ni] && !seen[ni]) { seen[ni] = 1; stack.push(ni); }
           }
         }
         if (n > 8) blobs.push({cx: (minX + maxX) / 2, cy: (minY + maxY) / 2,
                                wpx: maxX - minX + 1, hpx: maxY - minY + 1});
       }
     return {blobs: blobs, w: w, h: h, cssW: cv.clientWidth, cssH: cv.clientHeight};")

;; locator.evaluate treats a plain string as an expression, not a callable,
;; so the scan is built as a real function for Playwright to serialize.
(def ^:private scan-fn (js/Function. "cv" scan-body))

(defn- single? [blob]
  (and (<= (.-wpx blob) 16) (<= (.-hpx blob) 16)))

(defn click-blob
  "Click the page at a blob's center, translating canvas-buffer coordinates
   to page coordinates."
  [page canvas-loc blob meta]
  (p/let [box (.boundingBox canvas-loc)]
    (.click (.-mouse page)
            (+ (.-x box) (* (.-cx blob) (/ (.-cssW meta) (.-w meta))))
            (+ (.-y box) (* (.-cy blob) (/ (.-cssH meta) (.-h meta)))))))

(defn find-single-marker
  "Resolves to {:blob .. :meta ..} for a single (non-cluster) marker,
   clicking cluster bubbles to zoom into their members (bounded rounds)
   until one separates out; nil when none can be found."
  [page canvas-loc]
  (p/loop [round 0]
    (p/let [meta (.evaluate canvas-loc scan-fn)
            blobs (vec (.-blobs meta))
            singles (filterv single? blobs)
            clusters (filterv (complement single?) blobs)]
      (cond
        (seq singles) {:blob (first singles) :meta meta}
        (and (< round 8) (seq clusters))
        (p/do
          (click-blob page canvas-loc (first clusters) meta)
          (.waitForTimeout page 1200)
          (p/recur (inc round)))
        :else nil))))
