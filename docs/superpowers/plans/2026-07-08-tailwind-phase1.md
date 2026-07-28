# Tailwind Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Explorama's homegrown utility CSS with standard Tailwind v4, renaming all markup to canonical Tailwind class names in one atomic cutover.

**Architecture:** A shared mapping file (`tailwind-mapping.edn`) drives three scripts: an audit that classifies every compiled old utility selector and declaration-compares mapped pairs against Tailwind output (the completeness gate), a codemod that rewrites class strings in CLJS/CLJC, and the Tailwind entry CSS (`styles/src/tailwind.css`) that regenerates the utilities. Old CSS and new markup collide (`p-1` = 1px old, 4px new), so the markup rewrite + SCSS deletion + build wiring land in a single commit.

**Tech Stack:** Tailwind v4 (`tailwindcss` + `@tailwindcss/cli`, CSS-first config), Babashka scripts, existing sass pipeline for component sheets.

## Global Constraints

- All npm dependencies pinned exactly (no `^`/`~`) — user rule.
- Preflight stays OFF: import `tailwindcss/theme.css` + `tailwindcss/utilities.css` only, never plain `@import "tailwindcss"`.
- Component sheets (`styles/src/scss/components/*.scss`), `_variables.scss`, `_colormap.scss`, `_fonts.scss` icons/scrollbars/animations partials stay (only the class-generating utility partials go).
- Utilities CSS must load AFTER component CSS in every bundle (today guaranteed by `helpers` being the last import in `style.scss`; afterwards by `5_utilities.css` being the last stylesheet link).
- The cutover commit must be atomic: codemod result + partial deletion + `5_utilities.css` wiring together.
- Work on branch `tailwind-phase1` off `main`.
- Verification baseline: screenshots + old-CSS inventory captured BEFORE any change (Task 1).

## Reference: verified facts from the spec investigation

- Utility partials, all under `styles/src/scss/base/`, forwarded by `base/helpers.scss`, imported last in `style.scss`: `_spacing`, `_sizing`, `_layouting`, `_borders`, `_shadows`, `_colors`, `_transformations`. Typography utilities (`text-xs`…`text-5xl`, `text-bold`, `line-height-*`) live in `_fonts.scss` — that file ALSO contains font-face/base rules, so it is trimmed, not deleted.
- `$sizes` (spacing): px-named, every value = name/4 in Tailwind terms: 0→`0`, 1→`px`, 2→`0.5`, 4→`1`, 6→`1.5`, 8→`2`, 10→`2.5`, 12→`3`, 14→`3.5`, 16→`4`, 20→`5`, 24→`6`, 28→`7`, 32→`8`, 36→`9`, 40→`10`, 48→`12`, 64→`16`.
- `$sizes-ext` (w/h families) adds larger px values (…240→`60`, 256→`64`, 288→`72`, 320→`80`, 352→`88`, 384→`96`) plus `full`, `auto`, and the broken `menu`/`sidebar`/`modal`/`error` (unitless z-index values used as widths — classify `:drop` unless markup uses them). Tailwind v4's spacing scale is dynamic (any numeric multiple of `--spacing: 0.25rem` works), so N px → N/4 holds for every entry.
- `$widths` (border/outline): 0,1,2,4,8 px — matches Tailwind's px-named border widths; only `*-1` maps to the bare form (`border-1`→`border`, `border-t-1`→`border-t`, `outline-1`→`outline`).
- `$radii`: semantic names (`none,xxs,xs,sm,md,lg,xl,xxl,full`) with custom values (md=8px ≠ Tailwind md=6px) → keep names+values via `--radius-*` theme tokens (incl. custom `--radius-xxs`, `--radius-xxl`).
- `$shadows`: `xs,sm,md,lg,xl,xxl,inner,none`, values built on `var(--box-shadow-color, …)` → keep via `--shadow-*` theme tokens (incl. `--shadow-xxl`, `--shadow-inner`).
- Colormap: Tailwind-style palettes `gray/purple/teal/orange/red/blue/green/yellow` ×(`50`…`900` + bare) + `white`, `black`, `white-alpha-50`, `black-alpha-50` → theme colors, names preserved ⇒ `bg-*`/`text-*`/`border-*`/`outline-*`/`shadow-<color>` keep working without rename.
- `icon-<color>`/`icon-<color>-important` (88 usages) and `icon-normal/hover/disabled/secondary/inverted`: custom sprite-tinting classes → stay sass-generated in a trimmed `_colors.scss`.
- `text-decor-*`: 0 usages in markup → drop.
- ~533 static `:class` sites; ~54 dynamic construction sites (`grep -rE ':class \(str|:class \(cond|:class \(when|:class \(if'`).
- Compiled-CSS selector inventory (built via `npm run sass:dist`, extract from `dist/css/style.css`) is the ground truth for classification.

## File Structure

- Create `styles/scripts/tailwind-mapping.edn` — single source of truth: rename table, same-name list, custom list, drop list.
- Create `styles/scripts/tailwind_audit.bb.clj` — completeness + declaration-parity gate.
- Create `styles/scripts/tailwind_rename.bb.clj` — markup codemod (dry-run + apply).
- Create `styles/src/tailwind.css` — Tailwind entry (no preflight, @source, @theme, @utility).
- Create `styles/scripts/colormap_to_theme.bb.clj` — generates the `@theme` color block from `_colormap.scss` (checked-in output inside `tailwind.css`, regenerate on colormap change).
- Modify `styles/package.json` (pinned deps + `tailwind:dist`/`tailwind:watch` scripts wired into `build`, `build:prod`, `watch:all`), `styles/src/scss/style.scss`, `styles/src/scss/base/helpers.scss`, `styles/src/scss/base/_colors.scss` (trim), `styles/src/scss/base/_fonts.scss` (trim typography utilities).
- Delete `_spacing.scss`, `_sizing.scss`, `_layouting.scss`, `_borders.scss`, `_shadows.scss`, `_transformations.scss`.
- Modify `bundles/browser/resources/public/index.html`, `bundles/electron/resources/public/index.html`, `bundles/server/resources/public/index.html` (exact paths verified in Task 6, step 1): add `<link rel="stylesheet" href="/css/5_utilities.css">` after the last CSS link.
- Markup rewrite across `plugins/frontend/**/*.{cljs,cljc}`, `plugins/shared/**/*.{cljs,cljc}`, `bundles/*/frontend/**/*.{cljs,cljc}`.

---

### Task 1: Branch, baseline inventory, baseline screenshots

**Files:**
- Create: `styles/scripts/extract_old_css.bb.clj`
- Output artifacts (gitignored `docs/superpowers/artifacts/tailwind/`): `old-utilities.edn`, `baseline-*.png`

**Interfaces:**
- Produces: `old-utilities.edn` — map of `{"class-name" "declaration-block-string"}` for every top-level single-class selector in the utility zone; consumed by Tasks 3 and 4.

- [ ] **Step 1: Create branch**

```bash
cd /home/soeren/repos/private/Explorama && git checkout main && git pull && git checkout -b tailwind-phase1
mkdir -p docs/superpowers/artifacts/tailwind
```

- [ ] **Step 2: Build current CSS**

```bash
cd styles && npm run sass:dist
```
Expected: `dist/css/style.css` exists.

- [ ] **Step 3: Write the extractor**

`styles/scripts/extract_old_css.bb.clj`:
```clojure
(ns extract-old-css
  (:require [clojure.string :as str]))

;; Extracts {class-name declarations} for simple single-class selectors from
;; the compiled stylesheet. Utility classes are all simple selectors; complex
;; selectors (descendants, pseudo, attribute) belong to component css and are
;; ignored.
(defn parse [css]
  (into (sorted-map)
        (for [[_ sel body] (re-seq #"(?m)^(\.[A-Za-z0-9\\_.-]+)\s*\{([^}]*)\}" css)
              :let [sel (str/replace sel #"\\" "")]
              ;; single class only: exactly one leading dot, no combinators
              :when (and (str/starts-with? sel ".")
                         (not (re-find #"[ >~+:\[]" (subs sel 1)))
                         ;; ".foo.bar" chained selectors excluded
                         (not (str/includes? (subs sel 1) ".")))]
          [(subs sel 1)
           (-> body str/trim (str/replace #"\s+" " "))])))

(let [css (slurp "dist/css/style.css")
      m (parse css)]
  (spit "../docs/superpowers/artifacts/tailwind/old-utilities.edn" (pr-str m))
  (println (count m) "simple class selectors extracted"))
```

- [ ] **Step 4: Run it**

```bash
bb scripts/extract_old_css.bb.clj
```
Expected: prints a count (≳2000) and writes `old-utilities.edn`.

- [ ] **Step 5: Baseline screenshots (server bundle static build is already verified working)**

```bash
cd ../bundles/server && bb gather-assets.bb.clj dev
cd resources/public && python3 -m http.server 8899 & sleep 1
chromium --headless --disable-gpu --no-sandbox --virtual-time-budget=20000 \
  --window-size=1400,900 --screenshot=../../../../docs/superpowers/artifacts/tailwind/baseline-welcome.png \
  http://localhost:8899/
kill %1
```
Expected: `baseline-welcome.png` written (frontend JS bundle must exist from the earlier prod build; if not, run `clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core` first).

- [ ] **Step 6: Commit scaffolding**

```bash
git add styles/scripts/extract_old_css.bb.clj
git commit -m "tailwind: add old-css extractor for migration audit"
```

---

### Task 2: Tailwind v4 build scaffolding

**Files:**
- Create: `styles/src/tailwind.css`
- Create: `styles/scripts/colormap_to_theme.bb.clj`
- Modify: `styles/package.json`

**Interfaces:**
- Produces: `npm run tailwind:dist` → `dist/css/5_utilities.css`; `styles/src/tailwind.css` with `@source` covering all CLJS/CLJC markup. Consumed by Tasks 3, 5, 6.

- [ ] **Step 1: Install pinned deps**

```bash
cd styles && npm install --save-dev --save-exact tailwindcss @tailwindcss/cli
grep -E 'tailwind' package.json   # verify exact versions, no ^
```

- [ ] **Step 2: Generate the color theme block**

`styles/scripts/colormap_to_theme.bb.clj`:
```clojure
(ns colormap-to-theme
  (:require [clojure.string :as str]))

;; Reads $colors from _colormap.scss and prints --color-<name>: <value>; lines.
;; Rerun manually whenever the colormap changes; paste output into tailwind.css.
(let [scss (slurp "src/scss/base/_colormap.scss")
      ;; entries look like  'gray-500': #6b7280,  or  'white-alpha-50': rgba(...),
      entries (re-seq #"'([a-z0-9-]+)':\s*([^,\n]+)" scss)]
  (doseq [[_ name value] entries]
    (println (format "  --color-%s: %s;" name (str/trim value)))))
```

```bash
bb scripts/colormap_to_theme.bb.clj > /tmp/theme-colors.txt && head -5 /tmp/theme-colors.txt
```
Expected: lines like `--color-gray-50: #f9fafb;`.

- [ ] **Step 3: Write the Tailwind entry**

`styles/src/tailwind.css` (paste the generated color lines into the marked block; radius/shadow/font-size values copied verbatim from `_variables.scss` `$radii`/`$shadows` and `_fonts.scss` sizes):
```css
/* Explorama utilities — Tailwind v4, CSS-first config.
   Preflight is intentionally NOT imported: base/_normalize.scss remains
   authoritative. This file replaces the deleted base/_spacing|_sizing|
   _layouting|_borders|_shadows|_transformations partials and the color/
   typography utility loops. */
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/utilities.css" layer(utilities);

@source "../../plugins/frontend";
@source "../../plugins/shared";
@source "../../bundles/browser/frontend";
@source "../../bundles/electron/frontend";
@source "../../bundles/server/frontend";

@theme {
  /* ---- colors: generated by scripts/colormap_to_theme.bb.clj ---- */
  /* BEGIN colormap */
  /* (paste generated --color-* lines here) */
  /* END colormap */

  /* ---- radii: values from $radii in base/_variables.scss ---- */
  --radius-xxs: 0.125rem;
  --radius-xs: 0.25rem;
  --radius-sm: 0.375rem;
  --radius-md: 0.5rem;
  --radius-lg: 0.75rem;
  --radius-xl: 1rem;
  --radius-xxl: 1.5rem;

  /* ---- shadows: values from $shadows in base/_variables.scss ---- */
  --shadow-xs: 0 1px 2px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.05));
  --shadow-sm: 0 2px 3px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.25)), 0 0 2px -1px var(--box-shadow-color, rgba(0, 0, 0, 0.25));
  --shadow-md: 0 2px 6px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.25)), 0 0 4px -2px var(--box-shadow-color, rgba(0, 0, 0, 0.25));
  --shadow-lg: 0 2px 10px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.25)), 0 0 6px -4px var(--box-shadow-color, rgba(0, 0, 0, 0.25));
  --shadow-xl: 0 4px 25px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.25)), 0 0 10px -6px var(--box-shadow-color, rgba(0, 0, 0, 0.25));
  --shadow-xxl: 0 8px 50px 0 var(--box-shadow-color, rgba(0, 0, 0, 0.25));
  --shadow-inner: inset 0 2px 4px 2px var(--box-shadow-color, rgba(0, 0, 0, 0.05));
}

/* ---- custom utilities with no Tailwind equivalent ---- */
@utility center-x {
  left: 50%;
  transform: translateX(-50%);
}
@utility center-y {
  top: 50%;
  transform: translateY(-50%);
}
@utility center {
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
}
/* named z-index helpers (values from the old _layouting.scss) */
@utility z-menu { z-index: 100; }
@utility z-sidebar { z-index: 100; }
@utility z-modal { z-index: 200; }
@utility z-error { z-index: 300; }
```
Note: exact declaration bodies for `center-*`/`z-*` MUST be copied from the old compiled css (`old-utilities.edn`) — the values above are from the investigation; Task 3's audit verifies them.

- [ ] **Step 4: Wire npm scripts**

In `styles/package.json` add to `"scripts"`:
```json
"tailwind:dist": "tailwindcss -i src/tailwind.css -o dist/css/5_utilities.css",
"tailwind:watch": "tailwindcss -i src/tailwind.css -o dist/css/5_utilities.css --watch",
```
and append `&& npm run tailwind:dist` to the existing `build` and `build:prod` scripts (inspect them first: `grep -A2 '"build'` — they are npm-run-all chains; add `tailwind:dist` as a final member instead if that fits the pattern). Add `watch:tailwind": "npm run tailwind:watch"` so `watch:all`'s `watch:*` glob picks it up.

- [ ] **Step 5: Probe build**

```bash
npx tailwindcss -i src/tailwind.css -o /tmp/probe.css
grep -c "bg-gray-500\|items-center" /tmp/probe.css || true
```
Expected: builds without error. (Class presence depends on markup already using canonical names — `items-center` appears only after Task 5; `bg-gray-500` should appear immediately since color classes are unchanged.)

- [ ] **Step 6: Commit**

```bash
git add package.json package-lock.json src/tailwind.css scripts/colormap_to_theme.bb.clj
git commit -m "tailwind: v4 build scaffolding (no preflight, colormap theme)"
```

---

### Task 3: Mapping file + audit gate

**Files:**
- Create: `styles/scripts/tailwind-mapping.edn`
- Create: `styles/scripts/tailwind_audit.bb.clj`

**Interfaces:**
- Consumes: `docs/superpowers/artifacts/tailwind/old-utilities.edn` (Task 1), `npx tailwindcss` (Task 2).
- Produces: `tailwind-mapping.edn` with keys `:rename` (map old→new), `:same` (set), `:custom` (set), `:drop` (set); `bb scripts/tailwind_audit.bb.clj` exits 0 only when every old utility selector is classified and every `:rename`/`:same` entry's declarations match Tailwind's output. Consumed by Task 4 (codemod reads `:rename`).

- [ ] **Step 1: Write the mapping file**

`styles/scripts/tailwind-mapping.edn` — numeric families are generated (see `:families` + `:size-map`), aliases are explicit. Start from this content; the audit's "unclassified" report drives completion:
```edn
{;; px-name -> tailwind spacing name (both $sizes and $sizes-ext follow n/4)
 :size-map {"0" "0" "1" "px" "2" "0.5" "4" "1" "6" "1.5" "8" "2" "10" "2.5"
            "12" "3" "14" "3.5" "16" "4" "20" "5" "24" "6" "28" "7" "32" "8"
            "36" "9" "40" "10" "48" "12" "64" "16" "80" "20" "96" "24"
            "112" "28" "128" "32" "144" "36" "160" "40" "176" "44" "192" "48"
            "208" "52" "224" "56" "240" "60" "256" "64" "288" "72" "320" "80"
            "352" "88" "384" "96"}
 ;; families whose numeric suffix is renamed via :size-map (incl. negative -m*)
 :families ["p" "px" "py" "pt" "pr" "pb" "pl"
            "m" "mx" "my" "mt" "mr" "mb" "ml"
            "-m" "-mx" "-my" "-mt" "-mr" "-mb" "-ml"
            "gap" "gap-x" "gap-y"
            "w" "min-w" "max-w" "h" "min-h" "max-h"
            "top" "right" "bottom" "left"
            "divide-x" "divide-y"]
 ;; explicit old -> new renames
 :rename {"flex-column" "flex-col"
          "flex-grow" "grow" "flex-grow-0" "grow-0"
          "flex-shrink" "shrink" "flex-shrink-0" "shrink-0"
          "flex-basis-0" "basis-0" "flex-basis-auto" "basis-auto"
          "flex-basis-full" "basis-full"
          "align-start" "content-start" "align-end" "content-end"
          "align-center" "content-center" "align-between" "content-between"
          "align-around" "content-around" "align-evenly" "content-evenly"
          "align-stretch" "content-stretch"
          "align-items-start" "items-start" "align-items-end" "items-end"
          "align-items-center" "items-center"
          "align-items-baseline" "items-baseline"
          "align-items-stretch" "items-stretch"
          "align-self-start" "self-start" "align-self-end" "self-end"
          "align-self-center" "self-center"
          "align-self-baseline" "self-baseline"
          "align-self-stretch" "self-stretch"
          "border-1" "border" "border-t-1" "border-t" "border-r-1" "border-r"
          "border-b-1" "border-b" "border-l-1" "border-l"
          "border-x-1" "border-x" "border-y-1" "border-y"
          "outline-1" "outline"
          "text-bold" "font-bold" "text-semibold" "font-semibold"
          "text-medium" "font-medium" "text-light" "font-light"
          "text-extrabold" "font-extrabold" "text-regular" "font-normal"
          "text-normal" "font-normal"
          "text-italic" "italic" "text-non-italic" "not-italic"
          "text-underline" "underline" "text-no-underline" "no-underline"
          "text-line-through" "line-through" "text-overline" "overline"
          "text-uppercase" "uppercase" "text-lowercase" "lowercase"
          "text-capitalize" "capitalize" "text-normal-case" "normal-case"
          "text-truncate" "truncate"
          "text-md" "text-base"
          "text-underline-offset-0" "underline-offset-0"
          "text-underline-offset-1" "underline-offset-1"
          "text-underline-offset-2" "underline-offset-2"
          "text-underline-offset-3" "underline-offset-[3px]"
          "text-underline-offset-4" "underline-offset-4"
          "text-underline-offset-5" "underline-offset-[5px]"
          "line-height-none" "leading-none" "line-height-tight" "leading-tight"
          "line-height-snug" "leading-snug" "line-height-normal" "leading-normal"
          "line-height-relaxed" "leading-relaxed" "line-height-loose" "leading-loose"
          "line-height-slack" "leading-[2.5]"
          "order--1" "-order-1" "order--2" "-order-2" "order--3" "-order-3"
          "order--4" "-order-4" "order--5" "-order-5" "order--6" "-order-6"
          "order--7" "-order-7" "order--8" "-order-8" "order--9" "-order-9"
          "order--10" "-order-10"}
 ;; same name in Tailwind — audit still declaration-compares these
 :same #{"flex" "grid" "block" "inline" "hidden" "static" "fixed" "absolute"
         "relative" "sticky" "flex-row" "flex-row-reverse" "flex-col"
         "flex-col-reverse" "flex-wrap" "flex-wrap-reverse" "flex-nowrap"
         "flex-1" "flex-auto" "flex-initial" "flex-none"
         "rounded-full" "rounded-none" "shadow-none"
         "order-first" "order-last" "order-none"
         "border-solid" "border-dashed" "border-dotted" "border-double"
         "border-none" "outline-none" "outline-solid" "outline-dashed"
         "outline-dotted" "outline-double"
         "text-left" "text-center" "text-right" "text-justify"
         "text-xs" "text-sm" "text-lg" "text-xl"}
 ;; kept as custom css/@utility or trimmed sass (not renamed)
 :custom #{"center" "center-x" "center-y"
           "z-menu" "z-sidebar" "z-modal" "z-error" "z-0" "z-auto"
           "icon-normal" "icon-hover" "icon-disabled" "icon-secondary"
           "icon-inverted" "text-secondary" "text-disabled" "text-inverted"
           "text-warning" "text-xxs" "text-xxl"
           "flex-row--align-right" "flex-row--align-distributed"
           "flex-row--grid" "row" "dib"
           "animation-fade-in" "animation-fade-in-up" "animation-fade-out"
           "animation-gradient" "animation-pulse" "animation-pulse-weak"}
 ;; defined by old css but unused in markup (audit verifies zero usages)
 :drop #{"text-3xl" "text-4xl" "text-5xl" "text-justify-all"}
 ;; prefix families classified wholesale
 :prefix-same ["justify-" "justify-items-" "justify-self-" "place-"
               "place-items-" "place-self-" "items-" "self-" "content-"
               "col-span-" "col-start-" "col-end-" "row-span-" "row-start-"
               "row-end-" "grid-cols-" "grid-rows-" "opacity-" "rotate-"
               "rounded-" "shadow-" "border-" "outline-" "outline-offset-"
               "divide-"]
 :prefix-custom ["icon-" "bg-" "text-decor-"]
 ;; color-suffixed families keep names because theme colors keep names
 :prefix-color-same ["text-" "border-" "outline-" "shadow-" "bg-"]}
```
This starting table is NOT assumed complete or correct — the audit (next steps) reports every unclassified selector and every declaration mismatch; iterate on the EDN until the audit passes. Selectors like `grid-cols-2-fr`, `text-decor-*`, `menu/sidebar/modal/error` sizes will surface there and get classified (`:custom`/`:drop`) with usage checked by the codemod's unknown-token report.

- [ ] **Step 2: Write the audit script**

`styles/scripts/tailwind_audit.bb.clj`:
```clojure
(ns tailwind-audit
  (:require [babashka.process :refer [shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def mapping (edn/read-string (slurp "scripts/tailwind-mapping.edn")))
(def old (edn/read-string (slurp "../docs/superpowers/artifacts/tailwind/old-utilities.edn")))

(defn numeric-rename
  "p-8 -> p-2 via :families x :size-map; nil when not a numeric-family class."
  [cls]
  (some (fn [fam]
          (when (str/starts-with? cls (str fam "-"))
            (let [suffix (subs cls (inc (count fam)))]
              (when-let [new-suffix (get (:size-map mapping) suffix)]
                (str fam "-" new-suffix)))))
        (sort-by (comp - count) (:families mapping))))

(defn classify [cls]
  (cond
    (contains? (:same mapping) cls) [:same cls]
    (contains? (:custom mapping) cls) [:custom nil]
    (contains? (:drop mapping) cls) [:drop nil]
    (get (:rename mapping) cls) [:rename (get (:rename mapping) cls)]
    (numeric-rename cls) [:rename (numeric-rename cls)]
    (some #(str/starts-with? cls %) (:prefix-custom mapping)) [:custom nil]
    (some #(str/starts-with? cls %) (concat (:prefix-same mapping)
                                            (:prefix-color-same mapping))) [:same cls]
    :else [:unclassified nil]))

;; 1) completeness
(def classified (into {} (map (juxt identity classify)) (keys old)))
(def unclassified (sort (keep (fn [[c [k _]]] (when (= k :unclassified) c)) classified)))

;; 2) declaration parity: build a probe file containing every target class,
;;    run tailwind, compare declarations.
(def targets (sort (distinct (keep (fn [[_ [k new]]] (when (#{:same :rename} k) new)) classified))))
(spit "/tmp/tw-probe.html" (str/join " " (map #(format "class=\"%s\"" %) targets)))
(spit "/tmp/tw-probe.css" (str "@import \"tailwindcss/theme.css\" layer(theme);\n"
                               "@import \"tailwindcss/utilities.css\" layer(utilities);\n"
                               (slurp "src/tailwind.css") ; reuse theme+@utility (source dirs are additive; probe adds all targets)
                               "\n@source \"/tmp/tw-probe.html\";\n"))
(shell "npx tailwindcss -i /tmp/tw-probe.css -o /tmp/tw-out.css")
;; NOTE: exact reuse mechanics may need adjusting when implementing: the goal
;; is one tailwind build whose content includes every target class name, with
;; the same @theme/@utility config as src/tailwind.css.

(def new-css (slurp "/tmp/tw-out.css"))
(defn decls [css cls]
  (let [esc (-> cls (str/replace "." "\\.") (str/replace "[" "\\[") (str/replace "]" "\\]"))]
    (when-let [[_ body] (re-find (re-pattern (str "(?s)\\." esc "\\s*\\{([^}]*)\\}")) css)]
      (-> body str/trim (str/replace #"\s+" " ")))))

(def mismatches
  (for [[old-cls [kind new-cls]] classified
        :when (#{:same :rename} kind)
        :let [o (get old old-cls) n (decls new-css new-cls)]
        :when (not= o n)]
    {:old old-cls :new new-cls :old-decl o :new-decl n}))

(println "unclassified:" (count unclassified))
(run! println unclassified)
(println "declaration mismatches:" (count mismatches))
(run! prn (take 50 mismatches))
(System/exit (if (and (empty? unclassified) (empty? mismatches)) 0 1))
```

- [ ] **Step 3: Run audit, iterate mapping until clean**

```bash
cd styles && bb scripts/tailwind_audit.bb.clj; echo "exit: $?"
```
Expected first run: FAILS with a list of unclassified selectors (e.g. `grid-cols-2-fr`, `text-decor-*`, `w-menu`) and mismatches. Iterate: classify stragglers in the EDN; for mismatches decide per case — value-identical is required for `:same`/`:rename`; where the old declaration differs semantically (e.g. old `grid-cols-2` vs Tailwind's), move the old name to `:custom` (keep old css) or fix the theme token. Rerun until `exit: 0`.
IMPORTANT: normalization differences (whitespace, `0px` vs `0`, color formats, CSS-var fallbacks) will produce false mismatches — extend the `decls` normalizer as needed (e.g. lowercase hex, strip trailing `;`), but never loosen it past value equivalence.

- [ ] **Step 4: Commit**

```bash
git add scripts/tailwind-mapping.edn scripts/tailwind_audit.bb.clj
git commit -m "tailwind: mapping table + declaration-parity audit gate"
```

---

### Task 4: Rename codemod

**Files:**
- Create: `styles/scripts/tailwind_rename.bb.clj`

**Interfaces:**
- Consumes: `tailwind-mapping.edn` (`:rename`, `:families`, `:size-map`, `:same`, `:custom`, prefix lists) and `old-utilities.edn` (the known-old-class set).
- Produces: `bb scripts/tailwind_rename.bb.clj --dry-run|--apply` over the markup trees; unknown-token report on stdout.

- [ ] **Step 1: Write the codemod**

`styles/scripts/tailwind_rename.bb.clj`:
```clojure
(ns tailwind-rename
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def mapping (edn/read-string (slurp "scripts/tailwind-mapping.edn")))
(def old-classes (set (keys (edn/read-string
                             (slurp "../docs/superpowers/artifacts/tailwind/old-utilities.edn")))))

(defn numeric-rename [cls]
  (some (fn [fam]
          (when (str/starts-with? cls (str fam "-"))
            (when-let [n (get (:size-map mapping) (subs cls (inc (count fam))))]
              (str fam "-" n))))
        (sort-by (comp - count) (:families mapping))))

(defn rename-token
  "New name for a single class token, or nil if unchanged/unknown."
  [t]
  (or (get (:rename mapping) t)
      (numeric-rename t)))

(def known?
  "Token is a known old utility (renamed or kept)."
  (fn [t] (or (rename-token t) (old-classes t))))

(def dirs ["../plugins/frontend" "../plugins/shared"
           "../bundles/browser/frontend" "../bundles/electron/frontend"
           "../bundles/server/frontend"])

;; Rewrites tokens inside every double-quoted string literal. A string is
;; rewritten only when at least one token renames; tokens are whole-word.
;; Unknown tokens inside strings that ALSO contain a renamed token are
;; reported (likely component classes mixed with utilities — fine — but
;; reviewed).
(def report (atom []))
(defn rewrite-string [s]
  (let [tokens (str/split s #" ")
        new-tokens (map (fn [t] (or (rename-token t) t)) tokens)]
    (when (not= tokens new-tokens)
      (doseq [t tokens :when (and (seq t) (not (known? t)))]
        (swap! report conj t)))
    (str/join " " new-tokens)))

(defn rewrite-file [f apply?]
  (let [src (slurp (str f))
        ;; every "..." string literal (no escaped quotes inside class strings)
        out (str/replace src #"\"([A-Za-z0-9 _.:\\/-]*)\""
                         (fn [[whole inner]]
                           (let [r (rewrite-string inner)]
                             (if (= r inner) whole (str "\"" r "\"")))))]
    (when (not= src out)
      (println (str f))
      (when apply? (spit (str f) out)))
    (not= src out)))

(let [apply? (= "--apply" (first *command-line-args*))
      files (mapcat #(fs/glob % "**.{cljs,cljc}") dirs)
      changed (count (filter #(rewrite-file % apply?) files))]
  (println changed "files" (if apply? "rewritten" "would change"))
  (println "unknown tokens co-occurring with renames:"
           (sort (distinct @report))))
```

- [ ] **Step 2: Self-test on a fixture**

```bash
cd styles && cat > /tmp/fixture.cljs <<'EOF'
(def x [:div {:class "flex gap-8 p-4 align-items-center welcome__page"}])
(def y [:div {:class "p-1 border-1 text-bold"}])
EOF
mkdir -p /tmp/fixdir && cp /tmp/fixture.cljs /tmp/fixdir/
bb -e "$(sed 's#(def dirs .*#(def dirs [\"/tmp/fixdir\"])#' scripts/tailwind_rename.bb.clj)" --apply
cat /tmp/fixdir/fixture.cljs
```
Expected output file contains: `"flex gap-2 p-1 items-center welcome__page"` and `"p-px border font-bold"` — this exercises the collision case (`p-4`→`p-1` in the same run as `p-1`→`p-px`), whole-token safety, and pass-through of component classes.

- [ ] **Step 3: Dry run on the real tree**

```bash
bb scripts/tailwind_rename.bb.clj --dry-run | tee /tmp/rename-report.txt | tail -20
```
Expected: file list + count (order of 100–300 files) + unknown-token list. Review the unknown-token list: component classes (`__`-style) are fine; anything utility-shaped (e.g. `w-menu`, `grid-cols-2-fr`, dynamic fragments) gets a decision — classify in the EDN or note for the manual pass in Task 5.

- [ ] **Step 4: Commit**

```bash
git add scripts/tailwind_rename.bb.clj
git commit -m "tailwind: markup rename codemod (dry-run verified)"
```

---

### Task 5: The atomic cutover

**Files:**
- Modify: all markup files (codemod), ~54 dynamic `:class` sites (manual), `styles/src/scss/style.scss`, `styles/src/scss/base/helpers.scss`, `styles/src/scss/base/_colors.scss`, `styles/src/scss/base/_fonts.scss`
- Delete: `styles/src/scss/base/_spacing.scss`, `_sizing.scss`, `_layouting.scss`, `_borders.scss`, `_shadows.scss`, `_transformations.scss`
- Modify: the three bundle `index.html` files

**Interfaces:**
- Consumes: everything above.
- Produces: a single commit where markup, SCSS, and bundle wiring flip together.

- [ ] **Step 1: Manual audit of dynamic class sites**

```bash
cd /home/soeren/repos/private/Explorama
grep -rn -E ':class \(str|:class \(cond|:class \(when|:class \(if' plugins/frontend plugins/shared bundles/*/frontend --include=*.cljs --include=*.cljc > /tmp/dynamic-sites.txt
wc -l /tmp/dynamic-sites.txt
```
Open each site; rewrite any that CONSTRUCT utility names from fragments (e.g. `(str "p-" size)`) into whole-name selection (e.g. `(if big? "p-4" "p-2")`). Sites that merely concatenate whole class strings need only their literals renamed (the codemod already handles those literals). Record each decision as a checklist in the commit message body.

- [ ] **Step 2: Apply codemod**

```bash
cd styles && bb scripts/tailwind_rename.bb.clj --apply
cd .. && git diff --stat | tail -3
```

- [ ] **Step 3: Trim and delete SCSS**

- `style.scss`: delete the final `@use 'base/helpers';` line (and its comment).
- `base/helpers.scss`: remove forwards of the six deleted partials and of the color/typography loops; keep forwards for anything remaining (icons, scrollbars, animations — check `@forward` list against surviving files).
- `_colors.scss`: keep ONLY the `icon-#{$name}`, `icon-#{$name}-important` loops and the named `icon-normal/hover/disabled/secondary/inverted`, `text-secondary/disabled/inverted/warning` rules (they are `:custom`); delete `text-*`, `bg-*`, `border-*`, `outline-*`, `shadow-<color>`, `text-decor-*` loops.
- `_fonts.scss`: delete the `text-xs..5xl`, `text-bold`-style weight/style/decoration/transform, and `line-height-*` utility rules; keep font-face and any base element rules. Keep `text-xxs`/`text-xxl` (they are `:custom`).
- Delete the six partials:
```bash
git rm styles/src/scss/base/_spacing.scss styles/src/scss/base/_sizing.scss \
  styles/src/scss/base/_layouting.scss styles/src/scss/base/_borders.scss \
  styles/src/scss/base/_shadows.scss styles/src/scss/base/_transformations.scss
```
- Rebuild sass to confirm no dangling references:
```bash
cd styles && npm run sass:dist
```
Expected: compiles clean.

- [ ] **Step 4: Wire `5_utilities.css` into the bundles**

Find the exact index.html files and current link blocks:
```bash
grep -rn "4_temp.css" bundles/*/resources/public/index.html bundles/*/*/resources/public/index.html 2>/dev/null
```
In each, after the last stylesheet link add:
```html
    <link rel="stylesheet" href="/css/5_utilities.css" type="text/css">
```
Confirm each bundle's `gather-assets` copies `dist/css` wholesale (it copies the directory; `5_utilities.css` rides along):
```bash
grep -n "css" bundles/server/gather-assets.bb.clj | head
```

- [ ] **Step 5: Build utilities and re-run audit gate**

```bash
cd styles && npm run tailwind:dist && ls -la dist/css/5_utilities.css
bb scripts/tailwind_audit.bb.clj; echo "exit: $?"
```
Expected: exit 0.

- [ ] **Step 6: No-old-names gate**

```bash
cd /home/soeren/repos/private/Explorama
grep -rn -E '"(flex-column|align-items-[a-z]+|align-self-[a-z]+|text-bold|text-semibold|line-height-[a-z]+|flex-grow|flex-shrink|border-1|text-truncate)[" ]' plugins bundles --include=*.cljs --include=*.cljc | grep -v node_modules; echo "hits above must be 0"
```
Expected: no output.

- [ ] **Step 7: The atomic commit**

```bash
git add -A
git commit -m "tailwind: cutover — standard Tailwind utilities, full markup rename"
```
(Commit body: summarize codemod stats, dynamic-site decisions, deleted files.)

---

### Task 6: Verification

**Files:** none new (artifacts only)

- [ ] **Step 1: Lint**

```bash
clj-kondo --lint plugins/frontend plugins/shared bundles/server/frontend bundles/browser/frontend bundles/electron/frontend 2>&1 | tail -3
```
Expected: error count identical to a `main` baseline run (`git stash`-free comparison: run the same command on main first if unsure).

- [ ] **Step 2: Frontend test suites**

```bash
cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci 2>&1 | grep -E 'testsuites|success'
cd ../browser && npm run test-ci 2>&1 | tail -5
```
Expected: server 71 tests 0 failures; browser suite passes (same counts as on main).

- [ ] **Step 3: Rebuild server frontend and screenshot**

```bash
cd ../server && bb gather-assets.bb.clj dev
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core
cd resources/public && python3 -m http.server 8899 & sleep 1
chromium --headless --disable-gpu --no-sandbox --virtual-time-budget=20000 \
  --window-size=1400,900 --screenshot=/home/soeren/repos/private/Explorama/docs/superpowers/artifacts/tailwind/after-welcome.png \
  http://localhost:8899/
kill %1
```

- [ ] **Step 4: Compare screenshots**

Read both PNGs (baseline-welcome.png, after-welcome.png) side by side and compare visually; spacing, borders, colors, and typography must match. Investigate ANY visible difference via the audit report before accepting.

- [ ] **Step 5: Interactive spot check (user)**

Ask the user to run their normal dev workflow and click through 2–3 screens (welcome, a data frame, a dialog) before merge.

---

### Task 7: PR

- [ ] **Step 1: Push and open PR**

```bash
git push -u origin tailwind-phase1
gh pr create --base main --title "Tailwind phase 1: replace homegrown utility CSS, full rename to standard names" --body-file <(cat <<'EOF'
[summarize: what moved to Tailwind, mapping/audit/codemod architecture, verification evidence, out of scope]

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```
