# Tailwind Phase 1 — Replace the Homegrown Utility Layer

**Date:** 2026-07-08
**Status:** Approved
**Decision:** Full rename to standard Tailwind (user-selected over px-named
compat config).

## Goal

Replace Explorama's homegrown utility CSS (7 SCSS partials, ~885 lines) with
standard Tailwind (v4), renaming all markup to canonical Tailwind class names.
Component sheets (`styles/src/scss/components/*.scss`) stay untouched — their
migration is a later phase.

## Context (verified)

- Utility partials: `_spacing.scss`, `_sizing.scss`, `_layouting.scss`,
  `_borders.scss`, `_shadows.scss`, `_colors.scss`, `_transformations.scss`
  under `styles/src/scss/base/`, generated from a px-named `$sizes` map.
- All 18 sizes map exactly to standard Tailwind spacing values:
  0→`0`, 1→`px`, 2→`0.5`, 4→`1`, 6→`1.5`, 8→`2`, 10→`2.5`, 12→`3`, 14→`3.5`,
  16→`4`, 20→`5`, 24→`6`, 28→`7`, 32→`8`, 36→`9`, 40→`10`, 48→`12`, 64→`16`.
- Color utilities (`text-<name>`, `bg-<name>`) are generated from
  `colormap.$colors`; declaring those token names as Tailwind theme colors
  preserves them without renaming. `icon-<name>`, `icon-<name>-important`,
  `text-decor-<name>` are custom (no Tailwind equivalent).
- Aliases with canonical equivalents: `flex-column`→`flex-col`,
  `align-center/between/around/evenly/start/end/stretch`→`content-*`
  (they set `align-content`), `align-items-*`→`items-*`,
  `align-self-*`→`self-*`.
- Custom helpers with no equivalent: `center-x`, `center-y` (absolute
  centering via translate), kept as `@utility` definitions.
- ~533 `:class` sites in `plugins/frontend` + `bundles/*/frontend`; ~54 build
  class strings dynamically (`str`/`cond`/`when`/`if`).
- CSS load order today: `0_init.css`, `1_vendor/*`, `2_woco.css`,
  `3_style.css` (sass output incl. utilities), `4_temp.css`.

## Design

### 1. Tailwind v4, utilities only

- New entry `styles/src/tailwind.css`:
  - imports Tailwind **without Preflight** (existing `_normalize.scss` stays
    authoritative),
  - `@theme` declaring the colormap tokens as colors (same names),
  - `@source` globs for `plugins/frontend/**/*.{cljs,cljc}`,
    `plugins/shared/**/*.{cljs,cljc}`, `bundles/*/frontend/**/*.{cljs,cljc}`,
  - `@utility` definitions for `center-x`, `center-y`, `icon-<color>`,
    `icon-<color>-important`, `text-decor-<color>` (color variants generated
    or enumerated from the colormap).
- Build: `@tailwindcss/cli` invoked from `styles/package.json`
  (`tailwind:dist` script, wired into the existing `build`/watch flows),
  output `dist/css/5_utilities.css`, loaded after all existing sheets in all
  three bundles' `index.html` (+ `gather-assets` copies it like the others).
- Remove the 7 utility partials from `style.scss` and delete them.

### 2. Rename codemod

- Babashka script (`styles/scripts/tailwind-rename.bb.clj`) with an explicit
  old→new token table: the 18-entry size map crossed with each family
  (`p*`, `m*`, `gap*`, `w`/`h`, `top/bottom/left/right`, `divide-x/y`,
  border widths), plus the alias table above.
- Single-pass, whole-token matching (old `p-4`→`p-1` and old `p-1`→`p-px`
  coexist; sequential replacement would corrupt).
- Only rewrites string literals whose tokens are all recognized utility
  classes; emits a report of near-`:class` strings it did not touch, for
  manual review.
- The ~54 dynamic class sites are grep-audited and hand-fixed to whole class
  names (no runtime name construction remains for utility classes).

### 3. Atomic cutover

Codemod result, SCSS deletions, Tailwind build wiring, and index.html changes
land in a single commit/PR — old and new names collide (`p-1` = 1px before,
4px after), so no intermediate state may ship.

### 4. Verification

1. **Mapping audit:** script asserts, per table entry, that the
   Tailwind-emitted declaration matches the old compiled declaration
   (both generated — scriptable).
2. **Codemod dry-run report** reviewed before applying.
3. **Suites:** frontend CI (browser + server bundles), clj-kondo on changed
   files.
4. **Visual parity:** before/after headless-Chromium screenshots of key
   screens (welcome page, a data frame) in the browser bundle and the server
   compose harness; manual eyeball diff.

## Out of scope

- Component sheets migration (`components/*.scss`).
- Preflight adoption; dark mode.
- Email templates pipeline (`styles/emails/`).
- CSS minification of the remaining sass output.

## Risks

- Missed dynamic class construction → missing utility at runtime. Mitigated
  by the dry-run report, the grep audit, and screenshot checks.
- Name collisions between old markup and new CSS during development —
  addressed by the atomic cutover.
- Tailwind content scanning missing a class used only in `.cljc` shared code —
  mitigated by including `plugins/shared` in `@source`.
