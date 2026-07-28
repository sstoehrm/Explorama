# Tailwind Phase 3: Retire sass — migrate base SCSS + domain residuals to plain CSS

**Goal:** Remove sass from the Explorama styles build entirely — convert the base SCSS layer and the 29 `_*_domain.scss` residual sheets to plain (native-nested) CSS with `tailwind.css @theme` as the single token source of truth, compiled by lightningcss — so the exit criterion of issue #10 is met: no `.scss` left, `sass` removed, behavior unchanged.

**Architecture:** The migrated base + component CSS is authored as native-nested `.css` and compiled by **lightningcss** (already in the stack, the same engine Tailwind v4 uses) into the flattened, autoprefixed `3_style.css` — a drop-in replacement for the current `sass:dist` step. Design tokens move fully into `tailwind.css @theme`; the migrated CSS references them at runtime via the custom properties `@theme` emits (`var(--color-*)`, `var(--radius-*)`, …) instead of sass compile-time functions. The numbered-file pipeline and link order are unchanged.

**Tech Stack:** Tailwind v4 (pinned), lightningcss (already present), Babashka (build scripts), the existing phase-2 verification harnesses (`chrome_capture.sh`, `harness_capture.sh`, standalone compile-diff).

---

## Current state (post phase 2)

- **Base layer** — `styles/src/scss/base/` (12 files, ~1,976 lines): `_normalize` (normalize.css v8, pure), `_base`, `_colormap` (color token source: `$colors` map + `color()`/`contrastColor()` fns), `_colors` (stub), `_fonts` (@font-face), `helpers.scss` (`@forward` aggregator — compiles to a stray 494 KB `helpers.css`), `_iconmap` (generated `$imap` SVG data-URI map, 403 KB), `_icons` (`@mixin icon()` + `@each` → `.icon-*`), `_scrollbars` (`@mixin scrollbar()`), `_themes` (light/dark custom-property blocks — **dark mode is live**), `_variables` (radii/sizes/durations/… maps + fns), `_animations` (keyframes + `@each`).
- **Domain residuals** — `styles/src/scss/components/` (29 `_*_domain.scss`, 8,133 lines): all `@use`d by `style.scss`; **28** call `color()`/`map-get`; **7** use `@include` (mostly `@include icon()`, 11 call sites); **3** (`_dashboards`, `_temp`, `_frames`) use `@each`/`@mixin`/`@function`.
- **Token flow** — `_colormap.scss`/`_variables.scss` are the compile-time source of truth; the colormap is **already mirrored** into `tailwind.css @theme` as resolved literals (between the `BEGIN/END colormap` markers) by `styles/scripts/colormap_to_theme.bb.clj`; runtime theme custom props (`--text`, `--bg`, …) come from `_themes.scss`.
- **Build** — `sass:dist` (`src/scss` → `dist/css`, produces `style.css` + the stray `helpers.css`) then `tailwindcss` → `5_utilities.css`; `build.sh` renames `style.css` → `3_style.css`; link order `0_init → 1_vendor → 2_woco → 3_style → 4_temp → 5_utilities` (utilities last, unlayered, win ties). `sass` is invoked only by `sass:dist`/`watch:sass` (+ indirectly by `colormap_to_theme.bb.clj`).
- **Icons** — `styles/scripts/svg-to-css.js` reads 294 SVGs, emits the SCSS `$imap`; `_icons.scss` generates the static `.icon-*` classes; ~258 `.icon-*` class literals in markup are the real contract.
- **Preflight** — off by a deliberate custom import list (`tailwind.css` imports `theme.css` + `utilities.css`, omits `preflight.css`); `_normalize.scss` is authoritative.

## Locked decisions

1. **Full sass retirement** — base layer AND all 29 residuals convert; sass removed. Decomposed into batches like phase 2 (own spec + per-batch plans + SDD execution + one PR referencing #10 + a user spot-check).
2. **Preflight stays off** — `_normalize.scss` translates verbatim to plain `.css`. Adopting Preflight is a separate, deliberate visual-behavior change, explicitly **out of scope**.
3. **Dark mode preserved** — `_themes.scss`'s live `prefers-color-scheme` + `:has(.theme-light/.theme-dark)` implementation is kept; its light/dark custom-property blocks translate to plain-CSS rule bodies. No dark-mode redesign.
4. **`tailwind.css @theme` = single token source of truth** — migrated CSS references `var(--color-*)`/`var(--radius-*)`/… ; `_colormap.scss`/`_variables.scss` + the `colormap_to_theme.bb.clj` generator retire once nothing consumes them.
5. **Approach A: native-nested CSS compiled by lightningcss** — readable nested source, flattened + autoprefixed for all browsers, no new dependency. Retires *sass* (broader tool removal, incl. lightningcss's broken `cssmin`, is separately tracked in #11).

## End-state pipeline

No sass. `3_style.css` is produced by lightningcss from a native-nested CSS entry (the successor to `style.scss` — a CSS `@import` manifest). The numbered files (`0_init → 1_vendor → 2_woco → 3_style → 4_temp → 5_utilities`) and the "`5_utilities.css` links last" rule are unchanged; `build.sh`, `gather-assets`, and the bundle `index.html`s don't change. Removed: the `sass` dependency, `sass:dist`/`watch:sass`, the stray `helpers.css`, `_colormap.scss`, `_variables.scss`, `colormap_to_theme.bb.clj`. Custom-property resolution is source-order-independent, so component CSS in `3_style.css` referencing `var(--color-*)` defined in `5_utilities.css`'s `:root` resolves correctly.

## Migration mechanics (per-feature resolution — applied uniformly across base + residuals)

- `color('x-N')` → `var(--color-x-N)` (palette entries already mirrored in `@theme`; literals match by construction — verified).
- Computed color variants — `color('black', .5)`, `mix(black, color('gray'), 80%)` → `color-mix(in srgb, …)` or a resolved literal, chosen **per call for computed-value equivalence** (the one spot needing per-call verification).
- `radius()`/`size()`/`font-size()`/`duration()`/`layer()` → the corresponding `@theme` var or an arbitrary literal matching today's compiled value.
- `@include icon(name)` → `mask-image: var(--icon-name)` (the regenerated icon sheet emits `--icon-*` custom props alongside the `.icon-*` classes); the mixin body is otherwise fully static and inlines cleanly.
- `@each`/`@for`/`@mixin`/`@function` (~3 residuals + the icon/animation loops) → unrolled to the static rules they currently emit.
- Sass nesting → native CSS nesting (kept as-is); `@use`/`@forward` manifest → CSS `@import`s that lightningcss bundles.
- **Icons** — `svg-to-css.js` changes its output template from the SCSS `$imap` map to a `.css` file emitting `.icon-<name>` rules + `--icon-<name>` custom props. The `.icon-*` class contract in markup is unchanged.
- **Dark mode / normalize** — translated verbatim (resolve `color()`/`mix()` to literals/`color-mix()`; normalize becomes plain `.css` unchanged).

## Transition strategy (de-risks the incremental move)

`style.scss` compiles *everything* into one `3_style.css`, so sass and lightningcss can't each compile half. Two-phase move:

1. **Content conversion under sass (batches 0–3).** Convert each sheet's *content* to final CSS shape (`var(--*)`, inlined icon rules, unrolled loops, native-compatible nesting) but **keep it as `.scss` compiled by sass**. Sass passes `var()` through and flattens the same nesting, so each sheet compiles to a byte-identical `3_style.css` and is verified output-unchanged *under the existing compiler*. `_colormap`/`_variables` stay alive until their last consumer converts.
2. **Pipeline swap (final batch).** Rename all `.scss`→`.css`, replace the sass `@use` manifest with a lightningcss CSS-`@import` manifest, point `svg-to-css.js` at `.css`, delete sass + `_colormap`/`_variables` + the generator + the stray `helpers.css`. Because the content is already CSS-shaped, the swap is output-neutral.

## Batch decomposition (final grouping is a plan-phase detail)

- **Batch 0 — Enabler + base + icons + token authority.** Validate lightningcss produces a parity `3_style.css` (parallel-run vs. sass); regenerate the icon pipeline as CSS; convert the 12 base-layer files to CSS-shape; confirm `@theme` completeness. Sass still drives. Establishes the phase-3 calibration rulebook and reuses the phase-2 verification harnesses.
- **Batches 1–3 — Residual conversion.** The 29 residuals in ~3 cohesive groups: resolve `color()`→`var()`, inline `@include icon()`, unroll the ~3 loop/mixin sheets — kept as `.scss` under sass, each gated on a **sass compile-diff (output unchanged)** + AE=0 screenshots for reachable screens.
- **Final batch — Swap + sass removal + verification.** The pipeline swap above; whole-suite verification; #16 remnant-comment cleanup; docs + CLAUDE.md.

## Verification (reuses phase-2 machinery verbatim)

- **Per sheet:** standalone compile-diff for byte/computed-identity; AE=0 `chrome_capture.sh` screenshots on reachable screens; per-call computed-value checks for `color-mix()` resolutions.
- **Whole-suite (final):** server tests 71/0, browser tests 140/0, primitives harness floor 1028 (912 radius + 116 shadow), welcome MD5 stable, clj-kondo parity.
- **Two genuinely-new gates:** (a) Batch-0 lightningcss↔sass parity proof; (b) final-swap proof that `3_style.css` is computed-identical before vs. after dropping sass.

## Risks & edge cases

- **Computed color variants** (`mix()`, alpha) — sass `mix()` vs. CSS `color-mix(in srgb, …)` can differ in edge cases; each gets a computed-value check, falling back to a resolved literal where `color-mix` doesn't match.
- **lightningcss nesting/prefix parity** — proven up front in Batch 0 (parallel-run) before any conversion depends on it; the browser-target config must match today's autoprefix behavior.
- **`@theme` ↔ sass literal drift** — the `@theme` colormap was generated from `_colormap.scss`, so literals match; Batch 0 re-verifies completeness (every `color()`/`radius()`/… a sheet uses has a `@theme` var) before residual conversion relies on it.
- **Custom-prop availability** — `--color-*`/`--icon-*` must be defined at `:root` in a stylesheet that applies to every element; resolution is order-independent, but Batch 0 confirms the vars land in the shipped output.

## Out of scope

- Preflight adoption (separate visual-behavior change).
- Dark-mode redesign (the live implementation is preserved).
- Broader tooling/dependency removal — lightningcss `cssmin`, migration scripts, other deps (tracked in #11).
- The dormant auth login pages (#8), electron verification (#6), doc refresh (#13).
