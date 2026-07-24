(ns de.explorama.frontend.map.pixi.markers
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Graphics Sprite Text Container]]))

(def ^:const base-radius 6)

(defn circle-texture [^js app radius]
  (let [g (Graphics.)]
    (.beginFill g 0xffffff)
    (.drawCircle g radius radius radius)
    (.endFill g)
    (.generateTexture (.-renderer app) g)))

(defn- cluster-radius [count]
  (+ 10 (min 24 (* 4 (js/Math.log count)))))

(defn- node-key [node]
  (if (:cluster? node)
    (let [[cx cy] (:cell node)]
      (str "cluster:" cx ":" cy))
    (:id node)))

(defn render-nodes!
  "Render single markers as tinted sprites and clusters as bubble+count.
   `index` maps node-key -> {:kind :marker|:cluster :obj <displayobject> :node <node>}."
  [^js _app ^js container ^js texture index nodes vpt]
  (let [wanted (set (map node-key nodes))]
    (doseq [[k entry] @index
            :when (not (contains? wanted k))]
      (.removeChild container (:obj entry))
      (.destroy (:obj entry) #js {:children true})
      (swap! index dissoc k))
    (doseq [node nodes
            :let [k (node-key node)
                  [sx sy] (vp/->screen vpt (:lon node) (:lat node))]]
      (if (:cluster? node)
        (let [entry (or (get @index k)
                        (let [c (Container.)
                              g (Graphics.)
                              t (Text. "" (clj->js {:fontSize 11 :fill 0xffffff :fontFamily "sans-serif"}))]
                          (set! (.-anchor.x t) 0.5) (set! (.-anchor.y t) 0.5)
                          (.addChild c g) (.addChild c t)
                          (.addChild container c)
                          (let [e {:kind :cluster :obj c :g g :t t :node node}]
                            (swap! index assoc k e) e)))
              ^js g (:g entry)
              ^js t (:t entry)
              r (cluster-radius (:count node))]
          (.clear g)
          (.beginFill g 0x1f77b4 0.85) (.drawCircle g 0 0 r) (.endFill g)
          (set! (.-text t) (str (:count node)))
          (set! (.-x (:obj entry)) sx) (set! (.-y (:obj entry)) sy)
          (swap! index assoc-in [k :node] node))
        (let [entry (or (get @index k)
                        (let [s (Sprite. texture)]
                          (set! (.-anchor.x s) 0.5) (set! (.-anchor.y s) 0.5)
                          (.addChild container s)
                          (let [e {:kind :marker :obj s :node node}]
                            (swap! index assoc k e) e)))
              ^js s (:obj entry)]
          (set! (.-x s) sx) (set! (.-y s) sy)
          (set! (.-tint s) (or (:color node) 0x000000))
          (set! (.-alpha s) 0.7)
          (set! (.-scale.x s) (if (:highlighted? node) 1.6 1.0))
          (set! (.-scale.y s) (if (:highlighted? node) 1.6 1.0))
          (swap! index assoc-in [k :node] node))))))
