(ns e2e.main
  (:require [e2e.registry :as registry]
            [e2e.specs.smoke]
            [e2e.specs.workspace-mechanics]))

(registry/export!)
