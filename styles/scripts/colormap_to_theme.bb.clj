(ns colormap-to-theme
  (:require [babashka.process :as p]
            [clojure.string :as str]))

;; Reads $colors / $frame / $charts from src/scss/base/_colormap.scss and
;; prints "  --color-<name>: <value>;" lines for pasting into tailwind.css.
;;
;; NOTE: many colormap entries are Sass expressions, not literal values
;; (e.g. 'gray-50': tint($gray, 95%), 'group-1': map-get($colors, 'teal-700')).
;; A plain-text/regex extraction can't resolve those, so this script
;; compiles a tiny probe stylesheet that @use's the real colormap module
;; through the project's pinned `sass` compiler and reads back the
;; resolved CSS values. That guarantees byte-identical output to what the
;; old (pre-Tailwind) build actually shipped -- see
;; docs/superpowers/artifacts/tailwind/old-utilities.edn, e.g.
;; "bg-gray-50" -> "background-color: rgb(248.8, 249.55, 249.9);".
;;
;; Run from styles/ (relative paths below assume that cwd). Rerun manually
;; whenever the colormap changes; paste output into tailwind.css between
;; the "BEGIN colormap" / "END colormap" markers.

(def probe-scss
  "@use 'src/scss/base/colormap' as cm;
:root {
  @each $name, $color in cm.$colors {
    --color-#{$name}: #{$color};
  }
  @each $name, $color in cm.$frame {
    --color-#{$name}: #{$color};
  }
  @each $name, $color in cm.$charts {
    --color-#{$name}: #{$color};
  }
}")

(let [{:keys [out err exit]} @(p/process ["npx" "sass" "--stdin" "--load-path=." "--quiet"]
                                          {:in probe-scss :out :string :err :string})]
  (when-not (zero? exit)
    (binding [*out* *err*] (println err))
    (System/exit exit))
  (doseq [line (str/split-lines out)
          :let [trimmed (str/trim line)]
          :when (str/starts-with? trimmed "--color-")]
    (println (str "  " trimmed))))
