(ns e2e.main
  (:require [e2e.registry :as registry]
            [e2e.specs.smoke]
            [e2e.specs.workspace-mechanics]
            [e2e.specs.core-data-journey]))

(registry/export!)
