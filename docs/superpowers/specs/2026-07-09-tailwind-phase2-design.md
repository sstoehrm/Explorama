# Tailwind Phase 2 — Component Sheets to Utilities-in-Markup

**Date:** 2026-07-09
**Status:** Approved
**Issue:** #5 (builds on phase 1, PR #4; enabler overlaps issue #9)

## Goal

Migrate the component SCSS sheets (43 files, ~14.3k lines, BEM-style classes
like `chip`, `btn-*`, `dashboard__*`) to plain Tailwind utility stacks carried
in the owning CLJS components. End state per migrated sheet: the `.scss` file
is deleted, the visual result is unchanged, and the emitted classes are
standard Tailwind (arbitrary-value syntax like `bg-(--border)` allowed for
theme CSS-vars).

User decision: utilities-in-markup is the end state for ALL sheets (no
permanent CSS-in-Tailwind remnant layer); reached incrementally in batches.

## Why batching works (unlike phase 1)

Phase 1 required one atomic cutover because old and new names collided.
Phase 2 has no such collision: each sheet + its owning component(s) migrate
as a self-contained unit. Every batch is a separate plan → PR.

## Batches

- **Batch 0 (enabler):** a component render harness. (Original intent was
  fixing the `tools/ui-base-overview` gallery per issue #9, but investigation
  showed it is unbuildable: no lein toolchain and the private
  `lein-explorama-sync` plugin is unavailable. Instead: a purpose-built
  harness page — `bundles/server/harness/` — that mounts the batch's ui_base
  components in all catalog variants and self-serializes computed styles for
  headless capture. Issue #9 gets updated to reflect this.)
- **Batch 1 (primitives, ~2.5k lines):** `_buttons`, `_chips`, `_card`,
  `_checkbox`, `_input`, `_forms`, `_select`, `_tabs`, `_tooltip`, `_hints`,
  `_collapsible_list`. All are rendered through `ui_base` components, so
  callers don't change.
- **Batch 2–5 (domain sheets, grouped by feature area):** search;
  dashboards + reports; frames/woco + toolbar + sidebar + navbar;
  table + legend; remainder. Markup edits spread into plugin views here.
  Before migrating, `_explorama_backup.scss` (911 lines) and `_temp.scss`
  (647 lines) get a dead-rule audit — expectation is large deletions, not
  migrations.

Each batch gets its own implementation plan; this spec fixes the method and
specifies batches 0 and 1.

## Migration method (the repeatable recipe per sheet)

1. **Inventory the sheet:** list every selector; classify each as
   (a) simple rule on a class the CLJS emits, (b) state variant
   (`&:hover`, `&.active`, `&.light`), (c) descendant/compound rule,
   (d) dead (no matching markup anywhere — verify by grep, then delete).
2. **Translate to utility stacks** in the owning CLJS component:
   - Token mapping is phase-1's verified table: `size('8')` → spacing `2`
     (px/4), `radius('md')` → `rounded-md`, `color('gray-500')` →
     `text-gray-500`/`bg-gray-500`, `font-size('xs')` → `text-xs`,
     shadows/z-index via the phase-1 theme tokens. CSS-var references use
     arbitrary-value syntax (`bg-(--border)`).
   - State variants become Tailwind variant prefixes (`hover:bg-gray-100`)
     when the state is CSS-driven, or conditional class maps in CLJS when the
     state is app-driven (`:active?` prop).
   - Class knowledge stays PRIVATE to the owning component (defs at the top,
     like chip.cljs's existing pattern); callers keep using the component API.
3. **Descendant rules:** prefer threading explicit params to child components
   (e.g. chip passes icon color to the `icon` component). Where that is
   disproportionate, the rule moves to a clearly-marked "remnants" section in
   `styles/src/tailwind.css` with a comment naming the owning component.
   Remnants are counted per batch and expected to reach zero in phase 3.
4. **Delete** the sheet and its `@use` line in `style.scss` when empty.

## Verification per batch

Declaration-parity (phase 1's gate) cannot cover compound selectors, so
parity is proven at the rendered level:

1. **Gallery screenshots:** before/after screenshot of every gallery page
   showing the batch's components, pixel-compared (target: identical; every
   visible diff investigated and justified or fixed).
2. **Computed-style diff:** headless-Chromium dump of
   `getComputedStyle` for every DOM node of the affected gallery pages,
   before vs after, diffed. Catches non-default-state regressions that a
   single screenshot misses (hover styles excepted — noted as manual checks).
3. **Suites:** browser + server frontend test suites; clj-kondo clean.
4. **User interactive spot check** on the affected app screens before merge.

Baselines are captured BEFORE the batch's first change (same discipline as
phase 1).

## Out of scope

- Base SCSS (`_normalize`, `_themes`, `_animations`, `_scrollbars`, icons) —
  phase 3 (#10).
- Preflight adoption, dark mode.
- The email pipeline (already removed, PR #14).

## Risks

- Compound selectors with cascade interplay (e.g. `.frame .toolbar button`)
  are the main regression vector — the computed-style diff is the net.
- Dynamic class construction in components (like chip's color map) must emit
  statically scannable full class names, or be safelisted via
  `@source inline` as phase 1 did for icon colors.
- Gallery coverage is not 100% of component states; the per-batch plan lists
  which states get manual checks.
