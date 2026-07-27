(ns de.explorama.frontend.map.map.impl.pixi.state-handler-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [clojure.string :as str]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.map.impl.pixi.state-handler :as state-handler]))

(deftest popup-content-view-test
  (testing "popup html survives the reagent boundary (reagent strips
            dangerouslySetInnerHTML values that aren't unsafe-html-tagged)"
    (let [host (.createElement js/document "div")]
      (.appendChild (.-body js/document) host)
      (rdom/render [state-handler/popup-content-view
                    "<dl><dt>attr</dt><dd>value</dd></dl>"]
                   host)
      (let [inner (.-innerHTML host)]
        (.removeChild (.-body js/document) host)
        (is (str/includes? inner "<dl><dt>attr</dt><dd>value</dd></dl>"))))))
