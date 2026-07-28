# Tailwind Phase 2 — Batch 5 (table / legend / remaining domain sheets + dead-audits) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each implementer sees ONLY their task brief + this file's **Global Constraints** section — nothing else. No prior batch context is assumed.

**Goal:** Drain the LAST batch of hand-authored SCSS — `_table.scss` (687),
`_legend.scss` (756), `_prediction.scss` (650), `_indicator.scss` (401),
`_importer.scss` (534), `_data_atlas.scss` (205), `_geomap.scss` (183),
`_notes.scss` (324), `_section.scss` (131), `_settings.scss` (45),
`_projects.scss` (93), `_datepicker.scss` (148), `_slider.scss` (172),
`_alerts.scss` (142), `_product_tour.scss` (13), the rest of `_forms_domain.scss`
(406) — plus the two DEAD-AUDIT legacy openers `_explorama_backup.scss` (911) and
`_temp.scss` (513), so every retained rule is either (a) a Tailwind utility stack
carried in the plugin view that renders the markup, (b) a byte-identical remnant
in a per-sheet `_<sheet>_domain.scss` residual (at that sheet's `@use` slot) when
the DOM is cross-plugin co-owned / vendor / attribute-prefix / deep-descendant /
dynamically-constructed / shared-marker / forced-colors-marked, or (c) deleted as
dead. Visual result unchanged. End state: NO hand-authored component/primitive
sheet remains in `styles/src/scss/components/` — every surviving file is a
`_*_domain.scss` residual re-justified for phase 3 (#10); the final #16 comment
sweep of `styles/src/tailwind.css` lands.

**Architecture:** Ownership grep-verified this planning session
(`docs/superpowers/artifacts/tailwind/batch5-inventory.md` has the full table):
- **Two whole-sheet DELETIONS:** `_importer.scss` (legacy importer UI replaced by
  `expdb/temp_import/core.cljs`; 0 emitters for every family) and `_explorama_backup.scss`
  (88% dead legacy top-nav; only `.explorama` base + `--window-maximized` live on
  `woco/page.cljs` → 2 tiny migrates, then the sheet deletes).
- **Owner-exclusive → migrate to markup:** the table plugin's div-table chrome
  (`.table--*__scrollable*`, `.table__*_color`, `.table__header__label`,
  `.page__size__selection`, `.table-header-scrollable-empty-cell`) → `table/table/view.cljs`;
  4 prediction leaf families → `algorithms/view.cljs`/`goal.cljs`/`subsection.cljs`/
  `prediction.cljs`; `.grid-cols-1-4`/`.color-circle` from `_temp` → configuration
  `topics.cljs`/ui_base `icon.cljs`.
- **Legacy / cross-plugin / vendor / dynamic → residual (verbatim), no markup:**
  `_legend.scss` (8-plugin co-ownership + `@include icon` + `.legend__color`
  forced-colors + `:has()` cascades) → whole-sheet verbatim; `_indicator.scss`
  (whole sheet under a live woco-constructed runtime frame id `[id^=woco_frame-indicator-]`);
  the vendor sheets `_geomap.scss` (OpenLayers/ol-ext), `_slider.scss` (rc-slider),
  `_datepicker.scss` (Blueprint), the Toastify part of `_alerts.scss` (react-toastify),
  the Quill part of `_notes.scss`; the cross-plugin shared markers `_section.scss`
  (`.section`, ~10 plugins), `_settings.scss`, `_projects.scss`, `_data_atlas.scss`,
  `_product_tour.scss` (deep `.dialog` override); the `_table.scss`/`_prediction.scss`/
  `_temp.scss` residual remainders.
- **Re-justified for phase 3 (#10):** `_forms_domain.scss` stays byte-identical
  (the `.input--w*`/`.explorama__form__*` shared-marker family; 0 deletable whole
  families); its header is refreshed.

**Tech Stack:** SCSS (dart-sass via `npm run sass:dist`), Tailwind v4 CSS-first
(`npm run tailwind:dist`), the batch-1 render harness
(`styles/scripts/harness_capture.sh` / `harness_diff.py`, regression floor 1028),
headless Chromium + Python3 for app-screen capture/diff
(`styles/scripts/chrome_capture.sh` / `chrome_diff.py` from batch 4), clj-kondo.

## Global Constraints

Batch-1 + batch-2 + batch-3 + batch-4 calibration rules — every one is binding here:

- **Utilities-in-markup is the end state.** Emitted classes are standard
  Tailwind; arbitrary-value syntax (`bg-(--border)`, `[justify-content:start]`,
  `[border:none]`, `[&_span[class^=icon-]]:bg-(--icon)`, `has-[…]:`,
  `grid-cols-[1fr_3fr_auto]`) is allowed for theme CSS-vars and DOM with no
  computed-identical Tailwind name.
- **Token table (phase-1 verified):** `size('N')` → spacing N/4 (`size('8')`→
  `p-2`/`gap-2`; `size('12')`→`3`; `size('16')`→`4`; `size('4')`→`1`;
  `size('6')`→`1.5`; `size('24')`→`6`; `size('2')`→`0.5`; `size('48')`→`12`;
  `size('64')`→`16`; `size('20')`→`5`; `size('36')`→`9`; `size('32')`→`8`;
  `size('28')`→`7`; `size('1')`→verify exact px in `_variables` (spacing `px`/`0.25`));
  `radius('md')`→`rounded-md`; the project `@theme` overrides the radius scale —
  use the EXACT `rounded-*` token (xxs/xs/sm/md/lg/xl/xxl); `radius('full')`→
  `rounded-full`; `color('gray-500')`→`text-gray-500`/`bg-gray-500`;
  `font-size('xs'|'sm')`→`text-xs|text-sm`; **`font-size('md')` maps to
  `text-base` (NOT `text-md`, which is not a real scale step in this repo) —
  verify the computed value**; `font-size('lg')`→`text-lg` (verify token);
  `line-height('slack')` → verify the token; shadows (`shadow('xs'|'sm'|'md'|'lg'|
  'xl'|'inner')`) and z via the phase-1 theme tokens. `em`/`rem` values stay as-is
  (arbitrary if no token). `width('1'|'2')` → the border-width token (verify px;
  else `[border-width:1px]`).
- **Alpha colours + raw hex need the EXACT literal (arbitrary value), NOT
  Tailwind's `/NN` (oklab color-mix):** every `color('white',.5)` style alpha and
  every raw hex/rgba (`#ff00dc24`, `#bc00ff2b`, `#182f3d`, `#d0e9e9`, `#e2f1f1`,
  `#f8f9fa`, `rgba(255,255,255,0.9)`, the legend/data-atlas literals, …) → EXACT
  literal (`bg-[#ff00dc24]`).
- **`calc(...)`/`min(...)`/`clamp(...)`/viewport-unit sizes stay arbitrary**
  (`w-[calc(100%-32px)]`, `h-[calc(100vh-240px)]`, `w-screen`/`h-screen` only if
  computed-identical, else arbitrary).
- **`border-none`/`outline-none` are NOT computed-identical** to `border:none`/
  `outline:none` — use arbitrary `[border:none]`/`[outline:none]`. Same for
  `border-bottom:none` on a `:last-child` → `last:[border-bottom:none]`.
- **Descendant/parent-context rules:** prefer explicit param threading; else keep
  as byte-identical remnant. **descendant-hover (`parent:hover child`) MUST
  translate as `group`/`group-hover:`**, never element `hover:` — trigger-region
  narrowing is gate-invisible (recurs in `_notes.scss` `.note-card:hover
  .note-remove`, `_prediction.scss` list hovers, `_indicator.scss` `.indicator__card:hover
  .indicator__actions`). Those are all RESIDUAL here, but if any leaf migrates,
  apply this rule.
- **App-driven state → conditional class map in CLJS**, not a CSS variant
  (`.table__normal_color.table__selected` → selected adds `bg-[#ff00dc24]`;
  `.explorama.explorama--window-maximized` → `hidden` when maximized).
- **Dynamic class names must stay statically scannable** (full class strings in
  code) or be safelisted via `@source inline(...)`. Attribute-prefix / `(str …)` /
  `@for` families (`.tool-#{$tool}`, `div[id^=woco_frame-…]`, `[class^='result__hint']`,
  `.checkbox-matrix .cols-N`) STAY RESIDUAL (relocated verbatim); their cljs
  constructors are UNTOUCHED.
- **Same-specificity tie-break is by generation/source order.** Migrating a
  family to a utility stack moves its declarations into `5_utilities.css`
  (linked LAST, unlayered) — utilities win equal-specificity ties against
  earlier component/residual CSS. Relocating into a residual sheet keeps the
  source `@use` slot (BEFORE `5_utilities.css`) — a residual loses an
  equal-specificity tie to a utility and must win by SPECIFICITY (raise the
  selector) or keep an existing `!important`. Element-selector→class migrations
  lift specificity (0,0,1)→(0,1,0) — re-check contextual sibling overrides still
  win. **CASCADE-TIE TRAP (batch-4 Task 7):** migrating a family whose OLD rule
  was a higher-specificity CONTEXTUAL selector (`.x > div` = 0,1,1, `.a .b` =
  0,2,0) to a FLAT class utility (0,1,0) can LOSE the tie to a retained residual
  rule → silent ~1px regression. Every migration task must (a) reason about each
  migrated declaration's specificity vs retained residuals, and (b) gate on an
  AE=0 SCREENSHOT (not compiled-CSS alone). **Never split one class's properties
  across markup + residual** (e.g. `.table--footer__parent` head-vs-tail) — keep
  the whole class in one place.
- **`::before`/`::after`/`::placeholder`/`::marker`/`[data-value]::before` content
  and non-default states (`:hover`/`:focus`/`:disabled`/`:has()`/`:nth-child`)
  are gate-invisible** to `getComputedStyle` — verify by compiled-CSS reading and
  list as manual checks.
- **Load order is load-bearing** (`5_utilities.css` after `3_style.css` after
  `4_temp.css`). Check it for every inline-vs-remnant tie.
- **Kept/dropped marker classes matching a Tailwind builtin may be LOAD-BEARING
  COMPOSITION** — check OLD declarations first via
  `docs/superpowers/artifacts/tailwind/old-utilities.edn` (2681-entry phase-1
  ground truth): identical old/new = intended composition → KEEP the marker;
  different/absent → translate explicitly or override. Batch-5 builtin-name
  risks: `.section`, `.content`, `.footer`, `.header`, `.title`, `.card`, `.active`,
  `.disabled`, `.hidden`, `.selected`, `.table`, `.condition`, `.row`, `.note`,
  `.matched`, `.info`, `.divider`, `.operator`, `.source`, `.open`.
- **MARKER-DROP grep MUST include BASE sheets (binding):** before dropping ANY
  class literal, grep consumers across `styles/src/scss/base/*` (esp.
  `_themes.scss` `@media (forced-colors: active)`) AND all
  `styles/src/scss/components/*` AND every plugin/bundle. **Batch-5 forced-colors
  markers that MUST stay in the DOM even after migrating a family:**
  `.explorama__input__mode` (**the only one on a MIGRATE family** — prediction's
  `goal.cljs` keeps the literal), `.input__mode`, `.legend__color`,
  `.indicator__create`, `.drag-drop-area--empty`, `.explorama__form__file-upload`,
  `.dialog`, universal `span[class^="icon-"]`.
- **Mandatory tie-flip sweep** whenever anything relocates into a residual sheet /
  a family migrates into `5_utilities.css`: after the batch's CSS changes land,
  grep every remaining sheet (`styles/src/scss/**` + `styles/src/tailwind.css`)
  for equal-specificity overrides of any relocated/migrated selector and confirm
  no tie flipped; the app + welcome screenshots are the net.
- **Ancestor-chain byte-identity (batch-4 lesson):** when relocating a NESTED scss
  rule into a residual sheet, preserve the FULL compiled ancestor chain
  byte-for-byte; verify residuals by standalone-compile-and-diff of the COMPILED
  SELECTOR, not just declarations.
- **Grep-verify ownership BEFORE migrate+delete (batch-4 lesson):** plan premises
  can be WRONG. Every task greps who emits each class across `plugins/` +
  `bundles/*/frontend`. Cross-plugin co-owned OR cross-plugin deep-descendant
  override → byte-identical RESIDUAL, NOT markup.
- **Verification is disposition-specific:** DEAD → grep-proof + build-clean +
  app-screenshot unchanged (or welcome-MD5/harness floor if unreachable);
  RESIDUAL relocation → standalone-compile-and-diff byte-identity +
  incremental screenshot/compiled-CSS; MARKUP migration → app-screenshot
  pixel-identity on the affected screen, or (screen not headlessly reachable)
  compiled-CSS reading of the new utility stack vs the old declarations +
  manual-check list.

Batch-5-specific constraints:

- **Branch:** `tailwind-phase2-batches2-5` (already checked out; batches 2, 3 & 4
  merged into it). Base after batch 4 = `7f90d90`. This is the LAST batch of a
  multi-batch LOOP — **NO PR task** (the loop controller opens the single PR after
  all batches). One commit per task.
- **Migrate tasks DO touch `5_utilities.css`** (Tasks 2, 4, 5, 8 — backup, temp,
  table-migrate, prediction). Every such task **MUST run the primitives harness**
  (`harness_capture.sh` + `harness_diff.py`) and confirm **0 NEW diffs / floor
  1028** (912 border-radius + 116 shadow vs the original pre-batch-1 baseline).
  Residual-only / deletion-only tasks (3, 6, 7, 9, 10, 11, 12, 13) do NOT touch
  `5_utilities.css`; run the harness once at Task 1 and once at Task 14.
- **App-level gates only** (no primitives-harness coverage of these plugin
  screens). Per-screen app baselines captured BEFORE the first change (Task 1),
  plus the always-reachable **welcome** page (MD5 floor
  `c10b9a777c2dd90663189f3905b9b9d9`) and the four batch-4 chrome baselines
  (frame-toolbar/sidebar-open/dialog/login) as a regression floor. Batch-2/3/4
  found many app screens unreachable headlessly → unreachable screens fall back to
  compiled-CSS reading + welcome-MD5 + harness floor.
- **Welcome MD5 flake:** the floor is `c10b9a777c2dd90663189f3905b9b9d9` (i18n
  cold-start carousel-vs-virtual-time race; the stable flake MD5 is `6b29a7ec`).
  Collateral proof (a `5_utilities.css`/`style.css` rebuild-diff disjoint from
  welcome CSS) is a valid substitute when a task does not touch welcome CSS.
- **Residual-sheet fallback:** the cross-plugin/vendor/deep-descendant/attribute-
  prefix/dynamic/forced-colors-marked remainder goes verbatim into a NEW per-sheet
  `_<sheet>_domain.scss`, added to `styles/src/scss/style.scss` `@use` in that
  sheet's CURRENT slot to keep the cascade identical. **New residual sheets +
  slots (current post-batch-4 `style.scss`):** `_section_domain.scss` (L29),
  `_projects_domain.scss` (L30), `_settings_domain.scss` (L55),
  `_legend_domain.scss` (L57), `_slider_domain.scss` (L58),
  `_datepicker_domain.scss` (L59), `_table_domain.scss` (L76),
  `_geomap_domain.scss` (L89), `_notes_domain.scss` (L90), `_alerts_domain.scss`
  (L100), `_data_atlas_domain.scss` (L101), `_prediction_domain.scss` (L102),
  `_indicator_domain.scss` (L103), `_product_tour_domain.scss` (L109, rename),
  `_temp_domain.scss` (L110). `_explorama_backup.scss` (L4) + `_importer.scss`
  (L91) are DELETED with **no** replacement. Each `_<sheet>_domain.scss` keeps the
  SAME `@use '../base/…' as *;` imports its source sheet had (verify per sheet:
  `variables`, `colormap`, `icons`, `scrollbars`, `sass:math` where used).
- **Comment cleanup (#16) rides along:** (a) each new `_*_domain.scss` gets a
  clear header naming why each family is deferred (phase 3, #10); (b) the inlined
  `.btn-secondary` block in `_table.scss` moves verbatim into `_table_domain.scss`
  (Task 6) with a header — resolving its #16 flag; (c) Task 14 does the FINAL #16
  sweep of `styles/src/tailwind.css`: update the button-remnant "unmigrated sibling
  sheets (`_navbar_domain`/`_temp`/`_table`)" reference (L283-284) now that `_temp`→
  `_temp_domain` and `_table`→`_table_domain`; re-justify the input/select/legend
  remnant blocks (tooltip 6 / button 39 / input 43 / select 89, + the legend-select
  block L783-855) for phase 3 (#10); fix any stale inline SCSS line-number cites as
  rules move. Delete only references this batch actually resolves.

## Reference facts (grep evidence, not assumption — re-verify per task)

- **`style.scss` `@use` order (load-bearing), current post-batch-4** (batch-5
  targets marked `*`; residuals to KEEP unmarked): `explorama_backup`(L4)* <
  `login_domain`(L12) < `navbar_domain`(L21) < `frames_domain`(L27) <
  `forms_domain`(L28)* < `section`(L29)* < `projects`(L30)* <
  `welcome_page_domain`(L38) < `toolbar_domain`(L44) < `sidebar_domain`(L54) <
  `settings`(L55)* < `snapshots_domain`(L56) < `legend`(L57)* < `slider`(L58)* <
  `datepicker`(L59)* < `loader_domain`(L67) < `table`(L76)* < `dialog_domain`(L85)
  < `search_domain`(L88) < `geomap`(L89)* < `notes`(L90)* < `importer`(L91)* <
  `dashboards_domain`(L94) < `reports_domain`(L99) < `alerts`(L100)* <
  `data_atlas`(L101)* < `prediction`(L102)* < `indicator`(L103)* <
  `presentation_domain`(L108) < `product_tour`(L109)* < `temp`(L110)* <
  `base/helpers`(L116). Each residual sheet occupies its source sheet's slot.
- **Owner map (grep-verified):** table div-table chrome → `table/table/view.cljs`
  (defs ~L31-45/L110/L306); `.explorama__table` → `expdb/temp_import/core.cljs:764`
  (cross-plugin, RESIDUAL); `.explorama__fulltext` → `woco/details_view.cljs`;
  legend → `reporting/views/legend.cljs` + `woco/frame/view/legend.cljs` (+8-plugin
  sub-parts); prediction root → `algorithms/view.cljs:229` (optional-class),
  `.explorama__input__mode` → `goal.cljs`, `.settings__section__subsection` →
  `subsection.cljs`, `.options__divider` → `prediction.cljs`; indicator →
  `[id^=woco_frame-indicator-]` runtime frame id (woco-constructed); `.grid-cols-1-4`
  → configuration `topics.cljs`; `.color-circle` → `ui_base/…/icon.cljs:283`;
  `.explorama` → `woco/page.cljs:359`.
- **Whole-sheet DEAD (re-grep before deleting):** `_importer.scss` (all families
  0-emitter — the live importer is `expdb/temp_import/core.cljs`); the 15
  `_explorama_backup.scss` legacy families; `_temp.scss` 7 families; `_table.scss`
  `checkbox-matrix` subtree + `--odd`/`--striped`/`--horizontal` +
  `fill--header--scrollbar` + stale `.explorama__form__select`/`.basic-multi-select`;
  `_prediction.scss` 9 families; `_indicator.scss` 5 families; `_alerts.scss`
  `.alert__*`/`.condition`/`.remove-condition`; `_notes.scss` `.note__box` tree.
- **Test suites:** `bundles/server` `clojure -Sdeps "$(cat cljs.deps.edn)"
  -M:test-ci` → expect **71/0**; `bundles/browser` `npm run test-ci`
  (run_in_background; result in `bundles/browser/report.xml`) → expect **140/0**.
- **Kondo parity:** 0 err on touched files; compare touched-file warn/err counts
  to a disposable `git worktree` at `7f90d90` (disable the stateful cache
  `--cache false`).
- **Existing residual sheets (do NOT touch except `_forms_domain` header +
  the #16 references):** `_login_domain`, `_navbar_domain`, `_frames_domain`,
  `_forms_domain`, `_welcome_page_domain`, `_toolbar_domain`, `_sidebar_domain`,
  `_snapshots_domain`, `_loader_domain`, `_dialog_domain`, `_search_domain`,
  `_dashboards_domain`, `_reports_domain`, `_presentation_domain`.

## Standard gate block (every task runs the applicable subset)

```bash
cd styles && npm run sass:dist && npm run tailwind:dist        # both clean, no NEW warnings
# app-screen parity (screens the task's family renders, if reachable — else compiled-CSS reading):
bash styles/scripts/chrome_capture.sh after-<task> <screen>    # per Task-1 reachability table
python3 styles/scripts/chrome_diff.py baseline after-<task>    # AE=0, or every diff investigated & justified
# batch-1 primitives regression floor (MANDATORY on any task that touches 5_utilities.css — Tasks 2,4,5,8):
bash styles/scripts/harness_capture.sh b5-after-<task>
python3 styles/scripts/harness_diff.py b5-baseline b5-after-<task>   # 0 NEW diffs (floor 1028)
# verbatim residual relocations only:
#   standalone-compile the moved block pre & post, diff the COMPILED SELECTOR → byte-identical
clj-kondo --lint <touched cljs/clj files> --cache false        # warn/err count == pre-batch baseline
```

**Disposition rule for every selector** (applied in each task's inventory):
element-local rule on a class the OWNER file emits and the owner file-set is
editable here → **migrate to markup** (keep forced-colors markers); no emitter
anywhere (grep-proven, incl. base sheets) → **delete**; cross-plugin co-owned /
vendor / attribute-prefix / deep-descendant cascade / dynamically-constructed
class / `@keyframes` / content-DOM element typography / shared marker /
forced-colors-marked → **byte-identical remnant** in `_<sheet>_domain.scss`.

---

### Task 1: Per-screen baselines + capture harness extension + inventory verify (BEFORE any change)

**Files:**
- Modify: `styles/scripts/chrome_capture.sh`, `styles/scripts/chrome_diff.py`
  (batch-4 scripts — extend the screen enumeration; keep the four batch-4 screens).
- Reference (read, do not edit): `styles/scripts/harness_capture.sh`,
  `styles/scripts/harness_diff.py`, `styles/scripts/dr_capture.sh`.
- Artifact (gitignored): `docs/superpowers/artifacts/tailwind/batch5-inventory.md`
  (drafted this planning session — VERIFY + extend, don't rewrite).

- [ ] **Step 1: Extend the capture script for batch-5 screens.** Add labels to
  `chrome_capture.sh <label> <screen>` for the screens the migrate/residual
  families render, reusing the batch-4 method (`bb gather-assets.bb.clj dev` in
  `bundles/server` or the server equivalent, prod-compile woco, serve
  `resources/public`, headless Chromium `--window-size=1400,900
  --virtual-time-budget=40000`, **double-capture + MD5-compare** each capture):
  `table` (open a table frame → `.explorama__table` / `.table--*__scrollable` /
  paging footer), `legend` (a viz frame with `.legend__panel`), `prediction`
  (`.explorama__prediction` algorithms frame), `indicator`
  (`[id^=woco_frame-indicator-]`), `data-atlas`, `notes` (`.note-card`),
  `settings` (settings sidebar), `projects` (projects dashboard / welcome grid),
  `slider`, `datepicker`, `geomap` (OL popup), `alerts` (Toastify), `product-tour`.
  Keep the four batch-4 screens (`frame-toolbar`/`sidebar-open`/`dialog`/`login`)
  and `welcome`.
- [ ] **Step 2: Capture baselines + record reachability.** `bash
  styles/scripts/chrome_capture.sh baseline <each-reachable-screen>`;
  double-capture each and confirm MD5-identical. Capture the welcome baseline and
  confirm MD5 `c10b9a777c2dd90663189f3905b9b9d9`. Capture the harness baseline
  `bash styles/scripts/harness_capture.sh b5-baseline` and confirm
  `harness_diff.py <original-baseline> b5-baseline` = floor **1028** (912 radius +
  116 shadow). For every screen that will NOT render headlessly (record which in
  the artifact), its gate falls back to compiled-CSS reading + manual checks +
  the welcome-MD5/harness floor (batch-2/3/4 precedent — many app screens were
  unreachable).
- [ ] **Step 3: Verify + extend the inventory artifact.** Re-run every DEAD
  verdict's grep (risk of deleting live code) and every owner-exclusivity claim
  (gates MIGRATE tasks) by reading the matched call sites — mark
  confirmed/corrected in `batch5-inventory.md`'s "Task 1 verification addendum".
  Highest-risk re-checks: `_importer.scss` whole-sheet 0-emitter (all roots incl.
  every bundle frontend); `_explorama_backup.scss` `.explorama`/`--window-maximized`
  live + 15 dead families; `_temp.scss` 7 dead + 2 co-orphans
  (`_search_domain.scss:478 .input__clearable`, `_importer` `.col-*`);
  `checkbox-matrix` 0-emitter; prediction/indicator dead families; the vendor
  in-use confirmations. Record per-screen reachability. This artifact drives Tasks
  2-13; a family's disposition may only change during its own task with recorded
  evidence.
- [ ] **Step 4: Commit** the two scripts only (artifacts are gitignored):
  `git commit -m "tailwind(batch5): baseline capture scripts (add batch-5 screens) + inventory verify"`.

---

### Task 2: DEAD-AUDIT `_explorama_backup.scss` → migrate `.explorama` to page.cljs + delete sheet

**Files:** `plugins/frontend/de/explorama/frontend/woco/page.cljs` (renders
`[:div.explorama …]` at L359-362); `styles/src/scss/components/_explorama_backup.scss`
(delete whole file); `styles/src/scss/style.scss` (remove `@use
'components/explorama_backup';`, slot L4). **Touches `5_utilities.css`** (migrated
utilities) → harness gate.

- [ ] **Step 1: Re-grep-prove the 15 dead families.** For each family
  (`.explorama__header`, `.explorama__logo`/`--small`, `.exploramamosaic__logo`,
  `.mosaic__logo`/`.mosaic__name`, `.explorama__name`, `.explorama__projecttitle`,
  `.explorama__project` + descendants, `.explorama__user` + descendants,
  `.explorama__tools ul li.tool__*`, `.explorama__menu`/`.explorama__menusection`,
  `.hidden-legal`, `.app-overlay` + descendants, the `.fadeIn`/`.fadeInUp` classes,
  the 5 local `@keyframes`, and the DEAD `.explorama--window-maximized .explorama__header`/
  `.explorama__toolbar` descendants) run `grep -rln --include='*.cljs'
  --include='*.cljc' -E -- "<token>" plugins bundles/browser/frontend
  bundles/electron/frontend bundles/server/frontend` (exclude `js/out`/`cljs-out`/
  `resources/public`) → expect **0** source emitters. Confirm the local
  `@keyframes fadeIn`/`fadeInUp` are redundant duplicates of `base/_animations.scss`
  (live code uses those + `fadeInLeft`). Confirm `.explorama__tools li.tool__X` is
  dead because the live DOM class is single-hyphen `tool-X`
  (`direct_visualization.cljs:99/118`), never `tool__X`.
- [ ] **Step 2: Migrate the 2 live families onto `page.cljs`.** The live rules are
  `.explorama {position:relative; z-index:1400}` and
  `.explorama.explorama--window-maximized {display:none}`. In `woco/page.cljs`
  L359-362, the div is:
  ```clojure
  [:div.explorama
   {:class (when (and maximized-frame render?) "explorama--window-maximized")}
   [explorama-header]]
  ```
  Rewrite as (base utilities on the element + the maximized state as a conditional
  `hidden`, since `explorama--window-maximized` is emitted ONLY here — grep-confirm):
  ```clojure
  [:div.explorama.relative
   {:class (str "z-[1400]" (when (and maximized-frame render?) " hidden"))}
   [explorama-header]]
  ```
  (Keep the `.explorama` marker literal harmlessly; the `z-[1400]`/`relative`/
  `hidden` are the migrated declarations. If the `.explorama--window-maximized`
  literal turns out to be consumed elsewhere — it is not, per grep — keep it and
  add `hidden` alongside instead of replacing.) Verify `z-1400` is not a project z
  token → use `z-[1400]`.
- [ ] **Step 3: Delete the sheet.** `git rm
  styles/src/scss/components/_explorama_backup.scss`; remove its `@use` line
  (slot L4) — replace with a 1-line comment noting the sheet was 88% dead legacy
  top-nav and its 2 live rules migrated to `woco/page.cljs` (batch-5, task-2).
- [ ] **Step 4: Gates.** `cd styles && npm run sass:dist && npm run tailwind:dist`
  clean; `chrome_capture.sh` + diff vs baseline for any reachable workspace screen
  = AE=0 (or compiled-CSS confirmation that `.explorama {relative z-[1400]}` and
  the maximized `hidden` resolve identically — the maximized state is likely
  unreachable → manual check); welcome MD5 unchanged; **harness floor 1028, 0 NEW
  diffs** (touches `5_utilities.css`); `clj-kondo --lint page.cljs --cache false`
  == baseline.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): migrate .explorama
  base + window-maximized to woco/page.cljs; delete dead _explorama_backup.scss (88% dead legacy nav)"`.

---

### Task 3: WHOLE-SHEET DEAD `_importer.scss` → delete (deletion only)

**Files:** `styles/src/scss/components/_importer.scss` (delete whole file);
`styles/src/scss/style.scss` (remove `@use 'components/importer';`, slot L91).
Does NOT touch `5_utilities.css`.

- [ ] **Step 1: Prove `.explorama__importer` (the sheet root) dead.** `grep -rln
  --include='*.cljs' --include='*.cljc' -E -- "explorama__importer" plugins
  bundles/browser/frontend bundles/electron/frontend bundles/server/frontend` →
  expect **0** source emitters. Confirm the live importer is
  `expdb/temp_import/core.cljs` and it renders `.welcome__page`/`.welcome__section.projects`/
  `.projects-container`/`.explorama__table`/`.footer` + ui_base — NONE of
  `_importer.scss`'s classes.
- [ ] **Step 2: Prove the unscoped roots dead.** `grep -rln … -E -- "<token>"`
  for `mosaic__features`, `feature__list`, `column__section`, `row__section`,
  `tables__container` → expect **0** each (across all bundle frontends). Confirm
  the `table__header` grep hits are the table plugin's own
  `table__header__actions`/`__label` (inside `table/`), never under `.tables__container`.
  Confirm the double-dead form refs `.explorama__form__actions`/
  `.explorama__form__checkbox-container` (0 emitters).
- [ ] **Step 3: Delete.** `git rm styles/src/scss/components/_importer.scss`;
  remove its `@use` line (slot L91) — replace with a 1-line comment noting the
  legacy importer UI was fully dead (replaced by `expdb/temp_import/core.cljs`),
  batch-5. (The `_temp.scss` `.explorama__importer .col-*` co-orphan is retired in
  Task 4.)
- [ ] **Step 4: Gates.** Build clean; `chrome_capture.sh` + diff vs baseline for
  any reachable screen = AE=0 (nothing rendered used these) / compiled-CSS
  confirmation the deleted selectors matched nothing; welcome MD5 unchanged;
  **harness NOT touched**; kondo n/a (no cljs). Report the compiled-line delta.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): delete wholly-dead
  _importer.scss (legacy importer UI, 0 emitters; live importer is temp_import/core.cljs)"`.

---

### Task 4: DEAD-AUDIT `_temp.scss` → delete 7 dead, migrate 2, relocate 12 → `_temp_domain.scss`

**Files:** create `styles/src/scss/components/_temp_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/temp_domain';` at slot L110,
replacing `@use 'components/temp';`); `_temp.scss` (delete → `git rm`);
`plugins/frontend/de/explorama/frontend/configuration/…/topics.cljs` (emits
`.grid-cols-1-4`); `plugins/frontend/de/explorama/frontend/ui_base/…/icon.cljs`
(icon-map `.color-circle` at ~L283); `styles/src/scss/components/_search_domain.scss`
(delete the co-orphan `.input__clearable` rule at ~L478). **Touches `5_utilities.css`**
(2 migrated families) → harness gate.

- [ ] **Step 1: Prove + delete the 7 dead families.** Grep 0 emitters for
  `window__tools`, `input__icon`, `input__clearable`, `a.tooltip` (the anchor
  class `tooltip`, distinct from `tooltip-wrapper`), `ul.user__list` (+ all child
  tokens `role__tag`/`remove-user`/`domain-expert`/`data-scientist`/`groups`),
  `avatar`, `.explorama__importer .col-*`. Delete those rules. Also delete the
  co-orphan `.input__clearable` rule in `_search_domain.scss` (~L478) in the same
  task (both twins retire together — `input__clearable` = 0 emitters).
- [ ] **Step 2: Migrate `.grid-cols-1-4`.** Owner-exclusive: configuration
  `topics.cljs`. `grid-template-columns: 1fr 3fr auto` → `grid-cols-[1fr_3fr_auto]`
  on the emitting div. Delete the `.grid-cols-1-4` rule from `_temp.scss`.
- [ ] **Step 3: Migrate `.color-circle`.** Owner-exclusive icon-map marker
  (`ui_base/…/icon.cljs:283` `:class "color-circle"`, rendered by
  `notes/toolbar.cljs:49`). Read the exact rule; it is roughly `display:block;
  width:size('20'); height:size('20'); border-radius:radius('full');
  border:width('2') solid color('black',.1)` → `block w-5 h-5 rounded-full
  border-2 border-[rgba(0,0,0,0.1)]` (verify each token/px against the source;
  use the exact alpha literal, NOT `/10`). Add the utilities to the icon-map
  `:class`. Delete the `.color-circle` rule from `_temp.scss`.
- [ ] **Step 4: Relocate the 12 residual families → `_temp_domain.scss`.** Create
  the sheet with the source imports (`@use 'sass:math'; @use '../base/variables'
  as *; @use '../base/colormap' as *; @use '../base/icons' as *;` — verify against
  `_temp.scss` head) and a header naming why each family is deferred (phase 3).
  Relocate VERBATIM (byte-for-byte, incl. `/* line N */` provenance comments and
  `!important`s): `.window__header span[class^=icon-]`, `.tool-#{$tool}`/
  `.tooltip-wrapper:has(> .tool-*)`/`.btn-group .tool-*`, `.explorama__window
  .window__body__wrapper .overlay`, `div[id^="woco_frame-visualization-charts"]
  .legend__panel .subsection__control` (+ nested), `.settings__section` block,
  `:where(.sidebar,.legend__panel) .input--w6:has(+ .input--w100) .select-input`
  (both variants), `.input__mode`(+`:hover`) (**forced-colors marker — keep**),
  `h3 {margin:0}`, `.viewport__controls__framelist` block, `.row`/`.col-*` legacy
  grid (+ media query + `math.div`), `.export-footer` (+ `th`),
  `.explorama__workspace:has(.sidebar.show) #viewport-toolbar`. Add `@use
  'components/temp_domain';` at slot L110. `git rm styles/src/scss/components/_temp.scss`;
  remove/replace its `@use` line.
- [ ] **Step 5: Gates.** Build clean; **standalone-compile-and-diff** the 12
  relocated blocks pre vs post (byte-identical compiled selectors); reachable
  screens AE=0 or compiled-CSS + manual checks (the migrated `grid-cols-1-4` topics
  view, the `color-circle` note toolbar icon) for unreachable; welcome MD5
  unchanged; **harness floor 1028, 0 NEW** (touches `5_utilities.css`);
  `clj-kondo --lint topics.cljs icon.cljs --cache false` == baseline.
- [ ] **Step 6: Commit** — `git commit -m "tailwind(batch5): _temp dead sweep (7
  families + _search_domain input__clearable co-orphan) + migrate grid-cols-1-4/color-circle
  + relocate remainder to _temp_domain"`.

---

### Task 5: `_table.scss` dead sweep + migrate 11 families → `table/table/view.cljs`

**Files:** `plugins/frontend/de/explorama/frontend/table/table/view.cljs`;
`styles/src/scss/components/_table.scss` (delete the dead + migrated rules; the
residual 8 families stay for Task 6). **Touches `5_utilities.css`** → harness gate.

- [ ] **Step 1: Delete the DEAD groups.** Grep 0 emitters, then delete:
  `.explorama__table--odd td`, `.explorama__table--striped tr:nth-child(2n-1) td`,
  `.explorama__table--horizontal …`, the ENTIRE `table.checkbox-matrix` +
  `div.checkbox-matrix` subtree (`.cols-2..12`, `>div`, `>div.indent::before`,
  `>div.header-col/-row`, `>div .explorama__form__checkbox-container` +label/
  ::before/::after — `checkbox-matrix` = 0 emitters, ~140 lines),
  `.fill--header--scrollbar`, and the stale `.table--footer__parent
  .explorama__form__select` (+ `.basic-multi-select`) `_forms` ref.
- [ ] **Step 2: Migrate the 11 owner-exclusive families onto `view.cljs`** (defs
  ~L31-45/L110/L306; each class emitted ONLY by `table/table/view.cljs` — grep-confirm):
  - `.table--header__scrollable__parent {overflow:hidden !important}` → `[overflow:hidden]!`
  - `.table--header__scrollable__cell` → `flex justify-between items-center
    py-[10px] px-2 box-border border-0 border-r border-b border-(--border)
    bg-(--bg-hover) font-bold text-left overflow-hidden`; `:hover` →
    `hover:opacity-80 hover:cursor-pointer`
  - `.table--body__scrollable__parent {overflow:auto}` → `overflow-auto`
  - `.table--body__scrollable__cell` → `inline-block p-1 box-border border-r
    border-(--border) align-middle whitespace-nowrap overflow-hidden text-ellipsis`
  - `.table__normal_color` → `bg-transparent cursor-pointer`;
    `.table__second_color` → `bg-(--bg-section) cursor-pointer`
  - `.table__normal_color.table__selected` / `.table__second_color.table__selected`
    → conditional class map: when selected, add `bg-[#ff00dc24]` / `bg-[#bc00ff2b]`
    (exact literals; keep the base `.table__normal_color`/`.table__second_color`
    utilities + swap the bg on the selected branch)
  - `.table__header__label` → `overflow-hidden text-ellipsis whitespace-nowrap`
  - `.page__size__selection {overflow:hidden}` → `overflow-hidden`; **KEEP the
    `page__size__selection` class literal** on the markup (the Task-6 residual
    footer tail uses it as an ancestor)
  - `.table-header-scrollable-empty-cell` → `inline-block py-[10px] px-2 box-border
    border-r border-(--border) bg-(--bg-hover) font-bold text-left align-bottom
    overflow-hidden`
  Do NOT migrate `.enable--linebreaks` (cross-plugin: charts+mosaic+table+algorithms
  → RESIDUAL, Task 6). Do NOT touch `.table--footer__parent` (RESIDUAL, Task 6).
  Border translation: `border:0px solid var(--border); border-width:0 1px 1px 0` =
  a 1px right+bottom border in `--border` → `border-0 border-r border-b
  border-(--border)`; verify computed identity.
- [ ] **Step 3: Cascade-tie check + gates.** Reason about each migrated flat
  utility (0,1,0, in `5_utilities.css` last) vs any retained `_table.scss`/
  `_table_domain` residual on the SAME element — the migrated cells/colors have no
  higher-specificity contextual residual competitor (footer/paging residuals are
  descendant selectors on DIFFERENT elements). Build clean; **`table` screenshot
  AE=0** if reachable (open a table frame) or compiled-CSS reading of the new
  stacks vs old declarations + manual checks (`:hover` cell opacity, `.table__selected`
  bg both colors, empty-cell) if unreachable; **harness floor 1028, 0 NEW**;
  `clj-kondo --lint view.cljs --cache false` == baseline.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(batch5): _table dead sweep
  (checkbox-matrix + variants) + migrate div-table chrome to table/view.cljs"`.

---

### Task 6: `_table.scss` residual → `_table_domain.scss` (verbatim, incl. inlined btn-secondary)

**Files:** create `styles/src/scss/components/_table_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/table_domain';` at slot L76,
replacing `@use 'components/table';`); `_table.scss` (delete relocated → file
empties → `git rm`). Does NOT touch `5_utilities.css`.

- [ ] **Step 1: Justify residual (record in inventory).** For each of the 8
  families note why it fails MIGRATE: `.explorama__table` base/`--bordered` =
  cross-plugin (`expdb/temp_import/core.cljs`, importer-consumed) + bare-element
  descendants; `.explorama__fulltext` = deep-descendant `woco/details_view.cljs`
  cascade (ul/li/p/table/th/td, `:hover`/`:nth-child`/`.active+.active`);
  `.table--footer__parent` (head+tail, incl. inlined `.btn-secondary`) = deep
  cascade into ui_base `.btn-group`/`.input`/`.text-input`/`.btn-clear`, `:has()`,
  `:disabled`, inlined `%btn` on raw `<button>`s (keep the WHOLE class here — do
  NOT split its properties); `.paging__selection__page__parent/__limit/__input__field`
  = attach to ui_base button-group/input-field internals (`>a>div` child-combinator);
  `.enable--linebreaks` = cross-plugin shared marker; `div[id^="woco_frame-table"]
  .window__body>div` = attribute-prefix runtime frame id + shared `.window__body`.
- [ ] **Step 2: Create `_table_domain.scss`** with the source imports (`@use
  '../base/variables' as *; @use '../base/colormap' as *;` — verify against
  `_table.scss` head; NO icons) and a header naming each deferred family + the #16
  note (the inlined `.btn-secondary` block moves here verbatim; its disabled
  `border-color` is inert because `border-width:0` is set unconditionally — dead
  weight kept for byte-identity). Relocate the 8 families **VERBATIM** (byte-for-byte,
  incl. `/* line N */` comments and `!important`s + the full inlined btn-secondary
  block). Add `@use 'components/table_domain';` at slot L76.
- [ ] **Step 3: Delete** the relocated blocks from `_table.scss`; the file empties
  → `git rm styles/src/scss/components/_table.scss`; remove/replace its `@use`
  line. Leave `expdb/temp_import/core.cljs`, `woco/details_view.cljs`, and ui_base
  components UNTOUCHED.
- [ ] **Step 4: Gates.** Build clean; **standalone-compile-and-diff** the relocated
  blocks pre vs post (byte-identical compiled selectors — the gold-standard check);
  `table` screenshot AE=0 or compiled-CSS confirmation for unreachable; tie-flip
  check: the inlined `.btn-secondary` (in `_table_domain`, slot L76) vs the batch-1
  button remnant in `tailwind.css` (loads last) — the inlined block on raw
  button-group `<button>`s is a descendant selector that must still win where it
  did (grep for equal-specificity competitors); **harness NOT touched**; kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): relocate _table.scss
  residual to _table_domain (cross-plugin explorama__table, fulltext, footer/paging
  ui_base cascade, inlined btn-secondary #16); delete _table.scss"`.

---

### Task 7: `_legend.scss` → `_legend_domain.scss` residual (verbatim, all-residual)

**Files:** create `styles/src/scss/components/_legend_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/legend_domain';` at slot L57,
replacing `@use 'components/legend';`); `_legend.scss` (delete → `git rm`). Does
NOT touch `5_utilities.css`. **No cljs edits** (all-residual).

- [ ] **Step 1: Justify residual.** All 6 families fail MIGRATE: `.legend__panel`
  (+ all sub-families) = cross-plugin (8 plugins) deep-descendant cascade +
  `:has()` + `[class^=icon-]` + `@include icon` mixin + bare-element +
  cross-component `.select-input`/`.explorama__form__select`; `ul.legend` =
  cross-plugin + **`.legend__color` forced-colors marker** (`_themes.scss:362` —
  keep) + data-URI; `.data__hint` = cross-plugin (indicator+reporting+woco);
  `.color__assignments`/`.color__assignments__row` = child-combinator into
  react-dnd DOM + `:has()` + ui_base `.input`/`.text-input`/`input[type=color]`;
  `dl.data-desc-list` = bare-element cascade + `&.source`. Note the legend-owned
  SELECT remnants already live in `tailwind.css` (~L783-855) — nothing to dissolve
  in `_legend.scss` (Task 14 re-justifies that tailwind.css block for phase 3).
- [ ] **Step 2: Create `_legend_domain.scss`** with the source imports (`@use
  '../base/variables' as *; @use '../base/colormap' as *; @use '../base/icons' as
  *;` — verify against `_legend.scss` head; the `@include icon(...)` ×3 dependency
  requires `base/icons`) and a header naming the cross-plugin co-ownership +
  `.legend__color` forced-colors marker + phase-3 deferral. Relocate the WHOLE
  sheet **VERBATIM**. Add `@use 'components/legend_domain';` at slot L57.
- [ ] **Step 3: Delete** `_legend.scss` (`git rm`); remove/replace its `@use` line.
- [ ] **Step 4: Gates.** Build clean; **standalone-compile-and-diff** the whole
  sheet pre vs post (byte-identical compiled output); `legend` screenshot AE=0 if
  reachable or compiled-CSS confirmation + manual checks (`.legend__color`
  circle/line swatches, `:has()` collapsibles, `@include icon` mixin output) if
  unreachable; welcome MD5 unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): relocate _legend.scss
  to _legend_domain residual (8-plugin co-owned panel, @include icon, .legend__color
  forced-colors, :has() cascades); delete _legend.scss"`.

---

### Task 8: `_prediction.scss` → migrate 4 to algorithms markup + delete 9 dead + `_prediction_domain.scss`

**Files:** `plugins/frontend/de/explorama/frontend/algorithms/view.cljs`
(optional-class root), `.../goal.cljs` (`.explorama__input__mode`),
`.../subsection.cljs` (`.settings__section__subsection`), `.../prediction.cljs`
(`.options__divider`); create `styles/src/scss/components/_prediction_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/prediction_domain';` at slot L102);
`_prediction.scss` (delete → `git rm`). **Touches `5_utilities.css`** → harness gate.

- [ ] **Step 1: Delete the 9 DEAD families.** Grep 0 emitters, then delete:
  `.explorama__prediction__info`; the react-select-v1 vendor block
  (`.Select-option.Select-value*`, `.select__checked`, `.select__single-value*` —
  ui_base select is custom, never emits these); `.prediction__actions`;
  `.prediction__load`; `.prediction__select`; the `.file-upload` block (incl.
  `div.importer__progress .bar`); `.react-select-container .icon-check`;
  `.settings__section_collapsible .icon-check`; `label.label__clickable`;
  `.input__data__section div` (underscore variant; live one is hyphenated
  `.input-data-section`).
- [ ] **Step 2: Migrate the 4 owner-exclusive leaf families.**
  - `.explorama__prediction` root flex → extend the `:optional-class` string at
    `algorithms/view.cljs:229` to `"explorama__prediction flex flex-col items-stretch"`
    (verify the exact root decls; **keep `explorama__prediction` as the residual
    scope marker**).
  - `.explorama__input__mode` (+ `:hover`) → utilities on `goal.cljs`'s emitting
    element (e.g. `ml-[155px]` + `:hover` variant — read exact values). **KEEP the
    `explorama__input__mode` literal** (forced-colors marker `_themes.scss:349`).
  - `.settings__section__subsection` → element-local margins on `subsection.cljs`'s
    div + `last:[border-bottom:none]` for `&:last-child`.
  - `.options__divider` → `mt-6 border-b` on `prediction.cljs`'s
    `:div.options__divider`.
- [ ] **Step 3: Relocate the ~18 residual families → `_prediction_domain.scss`.**
  Create with source imports (`@use '../base/variables' as *; @use '../base/colormap'
  as *;` — verify) + a header. Relocate VERBATIM: `.explorama__prediction .window__body`,
  `.settings__section` gap/h2, the whole `.settings__section--new` block (`:has()`/
  `::before`/attr-prefix/data-URI/shared markers) — **but KEEP `.options__divider`
  migrated out**, i.e. relocate the surrounding block minus the `.options__divider`
  rule; `.settings__section h3`/`.settings__section--new h3`; `.section .content
  div div:has(canvas)`; `.section .section .content`; the `.content` block; the
  `.hint__*` family; `[class^='result__hint']` + `[class$=…]`;
  `.explorama__form__select{height:auto}`; `&.hidden .form__info__block`;
  `.form__info__block` dl/dt/dd; `.explorama__form__static + .prediction__data__list`;
  `div:has(+ .prediction__data__list)`; `.prediction__data__list …` (cross-plugin
  algorithms+indicator); `.prediction__save__action button:has(+div>.loader-md)`;
  the `:not(:last-child)` + `.col-6` grid rules; `.explorama__prediction
  .input-data-section .input` (cross-plugin). Add `@use 'components/prediction_domain';`
  at slot L102. `git rm styles/src/scss/components/_prediction.scss`.
- [ ] **Step 4: Cascade-tie + gates.** Reason about the 4 migrated flat utilities
  vs retained residuals scoped under `.explorama__prediction` (the migrated root/
  divider/subsection are on different elements than the residual descendants — no
  tie). Build clean; **standalone-compile-and-diff** the relocated residual blocks
  (byte-identical); `prediction` screenshot AE=0 if reachable or compiled-CSS +
  manual checks (input-mode hover offset, subsection last-child border, divider,
  root flex) if unreachable; **harness floor 1028, 0 NEW**; `clj-kondo --lint
  view.cljs goal.cljs subsection.cljs prediction.cljs --cache false` == baseline.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): _prediction migrate 4
  leaves to algorithms markup (keep explorama__input__mode forced-colors marker) +
  delete 9 dead + relocate remainder to _prediction_domain"`.

---

### Task 9: `_indicator.scss` → `_indicator_domain.scss` residual (prune 5 dead + verbatim)

**Files:** create `styles/src/scss/components/_indicator_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/indicator_domain';` at slot L103);
`_indicator.scss` (delete → `git rm`). Does NOT touch `5_utilities.css`. **No cljs
edits** (whole-sheet residual — attribute-prefix scoped).

- [ ] **Step 1: Prune the 5 DEAD inner families.** Grep 0 emitters, then remove:
  `button.indicator__ovlink`, `.indicator__send`, `ul.indicator__direct-vis`/
  `.indicator__direct-vis` (+ `li.tool__mosaic/__table/__map/__charts/__anchor`),
  `.dataset__definition`, `.indicator__tool`.
- [ ] **Step 2: Justify residual (whole remainder).** The sheet is scoped under
  `[id^=woco_frame-indicator-]` — a LIVE woco-constructed runtime frame id
  (`woco/frame/core.cljs:348`), NOT a plugin class → attribute-prefix RESIDUAL by
  rule. All inner families carry `:is()`/grid/`nth-child`/`.react-select-container`
  vendor/`@include icon`/`span[class^=icon-]`/`-webkit-line-clamp`/`&:empty`/`::after`/
  shared-marker overrides — none owner-exclusive + element-expressible without the
  plugin first adopting an `:optional-class` marker (no emitter does).
  **`.indicator__create` is pinned by `_themes.scss:299/313/340` forced-colors —
  keep.**
- [ ] **Step 3: Create `_indicator_domain.scss`** with source imports (`@use
  '../base/variables' as *; @use '../base/colormap' as *; @use '../base/icons' as
  *;` — verify; `@include icon` dependency) + a header noting the runtime-frame-id
  attribute-prefix scope + `.indicator__create` forced-colors marker. Relocate the
  remaining sheet **VERBATIM** (minus the 5 pruned dead families). Add `@use
  'components/indicator_domain';` at slot L103. `git rm styles/src/scss/components/_indicator.scss`.
- [ ] **Step 4: Gates.** Build clean; **standalone-compile-and-diff** the retained
  blocks pre vs post (byte-identical, minus the pruned dead); `indicator`
  screenshot AE=0 if reachable or compiled-CSS + manual checks (`.indicator__create`
  icon, `.react-select-container` overrides, `@include icon`, `-webkit-line-clamp`)
  if unreachable; welcome MD5 unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): relocate _indicator.scss
  to _indicator_domain residual (runtime-frame-id [id^] scope; prune 5 dead
  families; keep .indicator__create forced-colors marker); delete _indicator.scss"`.

---

### Task 10: Vendor whole-sheet residuals — `_geomap` / `_slider` / `_datepicker`

**Files:** create `styles/src/scss/components/_geomap_domain.scss`,
`_slider_domain.scss`, `_datepicker_domain.scss`; `styles/src/scss/style.scss`
(replace `@use 'components/geomap';`→`geomap_domain` (L89), `slider`→`slider_domain`
(L58), `datepicker`→`datepicker_domain` (L59)); `git rm` the three source sheets.
Does NOT touch `5_utilities.css`. **No cljs edits** (100% vendor DOM).

- [ ] **Step 1: Confirm vendor in-use.** Grep the wrappers our code renders:
  OpenLayers/ol-ext Popup (`map/map/impl/openlayers/object_manager.cljs`),
  rc-slider (`ui_base/…/slider.cljs`), Blueprint date component (`ui_base/…/date_picker.cljs`).
  Confirm 0 hiccup emitters for `.ol-*`/`.rc-slider-*`/`.bp4-*`/`.DayPicker-*`
  (runtime-generated vendor DOM). Note `.bp4-input-group.text-input` uses the
  `.text-input` shared input marker (`css_classes.cljs input-text-class`) — the
  datepicker's share of the input remnant family (re-justified for phase 3, Task 14).
- [ ] **Step 2: Relocate each verbatim.** Create each `_<sheet>_domain.scss` with
  the source imports (geomap: `variables`/`colormap`/`icons`; slider:
  `variables`/`colormap`; datepicker: `variables`/`colormap`/`scrollbars` — verify
  each head) + a header naming the vendor library. Relocate the WHOLE sheet
  VERBATIM. Add each `@use` at its slot (L89/L58/L59). `git rm` the three source
  sheets; remove/replace their `@use` lines.
- [ ] **Step 3: Gates.** Build clean; **standalone-compile-and-diff** each sheet
  pre vs post (byte-identical); vendor screens (`geomap` popup / `slider` /
  `datepicker`) AE=0 if reachable, else compiled-CSS confirmation (vendor DOM is
  usually gate-invisible — the byte-identical relocation is the gate); welcome MD5
  unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(batch5): relocate vendor sheets
  _geomap/_slider/_datepicker to _*_domain residuals (OpenLayers/rc-slider/Blueprint,
  no markup)"`.

---

### Task 11: `_alerts` + `_notes` + `_data_atlas` — delete dead + residual relocations

**Files:** create `_alerts_domain.scss`, `_notes_domain.scss`, `_data_atlas_domain.scss`;
`styles/src/scss/style.scss` (replace `alerts`→`alerts_domain` (L100),
`notes`→`notes_domain` (L90), `data_atlas`→`data_atlas_domain` (L101)); `git rm`
the three source sheets. Does NOT touch `5_utilities.css`. **No cljs edits.**

- [ ] **Step 1: `_alerts.scss` — delete 3 dead + relocate Toastify.** Grep 0
  emitters, delete `.alert__title`/`.alert__time`/`.alert__expiry`/`.alert__desc-standard`,
  `.condition`, `.condition button.remove-condition`. Confirm react-toastify in use
  (`woco/api/notifications.cljs`). Relocate the `.Toastify*` block VERBATIM to
  `_alerts_domain.scss` (imports `variables`/`colormap`; header naming react-toastify
  vendor + `::before` data-URI icons).
- [ ] **Step 2: `_notes.scss` — delete `.note__box` + relocate note-card/Quill.**
  Grep 0 emitters for `.note__box` (+ `__header`/`__title`/`__body`/`__footer`/
  `__authors`), delete the tree. Confirm Quill in use (`woco/notes/states.cljs`).
  Relocate the `.note-card`/`.note-remove`/Quill (`.ql-*`) block VERBATIM to
  `_notes_domain.scss` (imports `variables`/`colormap`; header noting `.note-card`
  is woco-owned but `:has(.ql-editor:focus)`/Quill-entangled → residual). Keep the
  unused `show-toolbar`/`toolbar-bottom` modifiers byte-identical (embedded in the
  Quill block).
- [ ] **Step 3: `_data_atlas.scss` — whole-sheet residual.** Relocate the WHOLE
  sheet VERBATIM to `_data_atlas_domain.scss` (imports `variables`/`colormap`/`icons`;
  header noting the `.window__body` cross-plugin scope + `::before`/`::after` icon
  injection + dynamic `.active`/`.matched` + generic-element `:is()` descendants).
  Do NOT split the marginal `.explorama__datenatlas {align-items:stretch}` decl out
  (entangled with the `.window__body` descendant scope; keep whole-sheet residual).
- [ ] **Step 4: Wire + gates.** Add the three `@use` lines at slots L100/L90/L101;
  `git rm` the three source sheets. Build clean; **standalone-compile-and-diff**
  each relocated block (byte-identical, minus the deleted alerts/notes dead);
  reachable screens (`alerts`/`notes`/`data-atlas`) AE=0 or compiled-CSS + manual
  checks (Toastify variants, note-card focus/Quill toolbar, data-atlas list icons)
  if unreachable; welcome MD5 unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(batch5): _alerts (delete
  alert__*/condition + Toastify residual) + _notes (delete note__box + note-card/Quill
  residual) + _data_atlas residual; delete source sheets"`.

---

### Task 12: `_section` + `_settings` + `_projects` + `_product_tour` — residual relocations

**Files:** create `_section_domain.scss`, `_settings_domain.scss`,
`_projects_domain.scss`, `_product_tour_domain.scss`; `styles/src/scss/style.scss`
(replace `section`→`section_domain` (L29), `settings`→`settings_domain` (L55),
`projects`→`projects_domain` (L30), `product_tour`→`product_tour_domain` (L109));
`git rm` the four source sheets. Does NOT touch `5_utilities.css`. **No cljs edits.**

- [ ] **Step 1: `_section.scss` → `_section_domain.scss`.** All-residual (`.section`/
  `.subsection` shared marker, ~10 plugins, `:has()`/attr-prefix/child-combinators).
  Relocate VERBATIM (imports `variables`/`colormap`; header noting cross-plugin
  shared marker). Slot L29.
- [ ] **Step 2: `_settings.scss` → `_settings_domain.scss`.** All-residual
  (`.content.settings` co-owned configuration+expdb, deep under cross-plugin
  `.sidebar`, adjacent-sibling `input+label`, attr-prefix icon). Relocate VERBATIM
  (imports `variables`/`colormap`). Slot L55.
- [ ] **Step 3: `_projects.scss` → `_projects_domain.scss`.** All-residual
  (`.projects` co-owned projects+expdb; `.card` shared-component override;
  `.new-indicator` shared marker whose base rule the `_navbar_domain.scss:122`
  override depends on). Relocate VERBATIM (imports `variables`/`colormap`/`icons`
  — verify; the `icons` `@use` is present-but-unused, keep for byte-identity).
  Slot L30. Confirm the `_navbar_domain` `.navbar .menu .new-indicator` override
  still finds the base rule after the rename (both selectors unchanged).
- [ ] **Step 4: `_product_tour.scss` → `_product_tour_domain.scss` (RENAME).**
  All-residual (deep `.dialog.product-tour .dialog-body` override of the cross-plugin
  co-owned `.dialog` family; `.dialog` forced-colors marker `_themes.scss:370`;
  attr-prefix `[class^]`/attr-suffix `[src$]`). Relocate VERBATIM (imports
  `variables`/`colormap`) keeping the L109 slot (do NOT fold into `_dialog_domain`
  — that would move it to the earlier L85 cascade position, not byte-identical).
- [ ] **Step 5: Wire + gates.** Add the four `@use` lines at their slots; `git rm`
  the four source sheets. Build clean; **standalone-compile-and-diff** each
  relocated sheet (byte-identical); reachable screens (`settings`/`projects`/
  `product-tour`) AE=0 or compiled-CSS confirmation if unreachable; welcome MD5
  unchanged (section/projects touch the welcome grid — confirm the welcome MD5
  floor or the collateral rebuild-diff); **harness NOT touched**; kondo n/a.
- [ ] **Step 6: Commit** — `git commit -m "tailwind(batch5): relocate _section/
  _settings/_projects/_product_tour to _*_domain residuals (shared markers, deep
  co-owned overrides); delete source sheets"`.

---

### Task 13: `_forms_domain.scss` re-justify for phase 3 (#10) — header refresh, byte-identical

**Files:** `styles/src/scss/components/_forms_domain.scss` (header comment only).
Does NOT touch `5_utilities.css`. **No rule/declaration changes** (byte-identical CSS).

- [ ] **Step 1: Re-verify every family is still live.** Grep emitter counts for
  the 9 families (`form` global; the `:is(.explorama__form__input,__select,
  __checkbox-container,__radio-container)` group; `.form__message`/`.error__message`;
  `.explorama__form__flex`; `.drag-drop-area`/`.explorama__form__file-upload`/`--empty`
  + `.drop-target`; `.input--w2..w100`; `.explorama__form__row div[class^='col-']`;
  `ul.edc__layouts`; `.explorama__form__static`). Confirm 0 WHOLE families are
  deletable (the only dead tokens — `explorama__form__checkbox-container`,
  `explorama__form__radio-container`, `error__message` — are byte-welded into live
  grouped `:is()`/`.form__message,.error__message` selectors; deleting them would
  change shipped bytes for LIVE rules → OUT OF SCOPE). Confirm the base
  forced-colors deps hold (`.drag-drop-area--empty`, `.explorama__form__file-upload`
  at `_themes.scss:294/314/387`).
- [ ] **Step 2: Refresh the header comment ONLY.** Update the sheet's opening
  comment block to re-justify it as the phase-3 shared-marker family (#10),
  naming the anchors (`.input--w*` 115 sites, the `:is()` group 8+6 plugins,
  `.drag-drop-area`/`file-upload` forced-colors, `form` global) and stating that
  it stays byte-identical for phase 3. Do NOT change any rule/selector/declaration.
- [ ] **Step 3: Gates.** Build clean; **standalone-compile-and-diff** the whole
  sheet pre vs post → **byte-identical compiled output** (only the header comment
  changed, comments do not survive compilation → the compiled CSS is literally
  unchanged); welcome MD5 unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(batch5): re-justify
  _forms_domain header as the phase-3 (#10) shared-marker family (byte-identical CSS)"`.

---

### Task 14: #16 final tailwind.css comment sweep + tie-flip sweep + whole-batch verification (NO PR)

- [ ] **Step 1: Full tie-flip sweep** (batch-1 `1d983d1` methodology, mandatory).
  For every selector this batch (a) relocated into a residual sheet
  (`_temp_domain`/`_table_domain`/`_legend_domain`/`_prediction_domain`/
  `_indicator_domain`/`_geomap_domain`/`_slider_domain`/`_datepicker_domain`/
  `_alerts_domain`/`_notes_domain`/`_data_atlas_domain`/`_section_domain`/
  `_settings_domain`/`_projects_domain`/`_product_tour_domain`) OR (b) migrated
  into `5_utilities.css` (backup `.explorama`, temp `.grid-cols-1-4`/`.color-circle`,
  the 11 table families, the 4 prediction leaves), grep ALL remaining sheets
  (`styles/src/scss/**` + `styles/src/tailwind.css`) for equal-specificity
  competitors and confirm no tie flipped. Special-cases to record: the inlined
  `.table--footer__parent .btn-group` `.btn-secondary` (in `_table_domain`, slot
  L76) vs the batch-1 button remnant in `tailwind.css` (loads last); the migrated
  table flat utilities vs any retained `_table_domain` descendant on the same cell;
  `.new-indicator` base (`_projects_domain`, L30) vs the `_navbar_domain` (L21)
  position override; `.section`/`.content` shared markers now in `_section_domain`
  vs the many sheets that reference them. Record the sweep result.
- [ ] **Step 2: Suites.**
```bash
cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci 2>&1 | grep -E 'testsuites|success'   # expect 71/0
cd ../browser && npm run test-ci   # run_in_background; expect 140/0 — real result in bundles/browser/report.xml
```
- [ ] **Step 3: App-screen screenshot compares.** `chrome_capture.sh` for each
  reachable screen (the four batch-4 chrome + the batch-5 screens per Task-1
  reachability) → AE=0 or every diff justified with pixel counts; and the welcome
  page → MD5 `c10b9a777c2dd90663189f3905b9b9d9` (or the collateral rebuild-diff if
  the flake recurs). Screens not headlessly reachable: state that compiled-CSS
  reading + the per-task manual-check lists are the substitute.
- [ ] **Step 4: Harness floor check.** `bash styles/scripts/harness_capture.sh
  b5-final` + `python3 styles/scripts/harness_diff.py <original-baseline> b5-final`
  → floor still **1028** (912 radius + 116 shadow), 0 NEW diffs vs `b5-baseline`
  (the batch-1 primitives must survive the Tasks 2/4/5/8 `5_utilities.css` additions).
- [ ] **Step 5: Kondo parity.** `clj-kondo --lint --cache false` the touched cljs
  files (`woco/page.cljs`, configuration `topics.cljs`, `ui_base/…/icon.cljs`,
  `table/table/view.cljs`, `algorithms/view.cljs`/`goal.cljs`/`subsection.cljs`/
  `prediction.cljs`); compare warn/err counts to a disposable `git worktree` at
  `7f90d90` (strip `line:col`, sort, diff — expect zero textual diff).
- [ ] **Step 6: FINAL #16 comment sweep of `styles/src/tailwind.css`.** (a) Update
  the button-remnant "unmigrated sibling sheets (`_navbar_domain`/`_temp`/`_table`)"
  reference (≈ L283-284) — `_temp`→`_temp_domain`, `_table`→`_table_domain`; the
  `.btn-group` family is now also consumed by the inlined `_table_domain` block.
  (b) Re-justify the phase-2 remnant owner blocks (tooltip 6 / button 39 / input 43
  / select 89, + the legend-select block ≈ L783-855) for phase 3 (#10) — update any
  stale bare-sheet-name cites and confirm each block names its phase-3 rationale.
  (c) Fix any stale inline SCSS line-number cites in the new `_*_domain.scss`
  sheets. Delete only references this batch resolves.
- [ ] **Step 7: Audit + components/ end-state + report.** Totals: dead families
  deleted (whole `_explorama_backup.scss` + `_importer.scss` + the per-sheet dead
  groups) with line deltas; families migrated-to-markup per file; the 15 new
  `_*_domain.scss` residual sheets with line counts; `@source inline` safelists
  added (expected 0 — verify). **Confirm the end-state:** `ls
  styles/src/scss/components/` — enumerate every remaining file and confirm each is
  a `_*_domain.scss` residual (14 prior + 15 new + `_forms_domain.scss`) with a
  documented phase-3 justification; confirm ZERO hand-authored component/primitive
  sheets remain. **Record the components/-empty ambiguity resolution** (see Global
  Constraints — the literal-empty goal requires a controller decision: (A) accept
  residuals-remain re-justified for phase 3 [recommended, matches precedent], (B)
  relocate all residuals to a new dir, or (C) fold into tailwind.css). Update
  `CLAUDE.md` Styles section (one line) naming the batch-5 `_*_domain.scss`
  residual sheets + the two whole-sheet deletions. Commit any stragglers + the doc
  note.
- [ ] **Step 8: Commit** — `git commit -m "tailwind(batch5): batch-5 verification
  (tie-flip sweep, suites, screens, welcome MD5, floor 1028, final #16 sweep,
  components/ end-state audit)"`. **No PR** — the loop controller opens the single
  PR after all batches.

---

## Self-review against the batch-1/2/3/4 quality bar

- **Spec coverage:** all 18 issue-#20 sheets are covered — `_explorama_backup.scss`
  (Task 2 dead-audit + tiny migrate), `_temp.scss` (Task 4 dead-audit + migrate +
  residual), `_importer.scss` (Task 3 whole-sheet dead), `_table.scss` (Tasks 5
  migrate/dead + 6 residual), `_legend.scss` (Task 7 residual), `_prediction.scss`
  (Task 8 migrate + dead + residual), `_indicator.scss` (Task 9 residual + prune),
  `_geomap`/`_slider`/`_datepicker` (Task 10 vendor residual), `_alerts`/`_notes`/
  `_data_atlas` (Task 11 dead + residual), `_section`/`_settings`/`_projects`/
  `_product_tour` (Task 12 residual), `_forms_domain.scss` (Task 13 phase-3
  re-justification). Per-screen baselines are Task 1 with an explicit unreachable→
  compiled-CSS fallback. The welcome MD5 floor (`c10b9a77`) gates every task; the
  primitives harness floor (1028) gates the Tasks 2/4/5/8 `5_utilities.css`
  additions. The tie-flip sweep is a Global Constraint + Task 14 Step 1. The final
  #16 tailwind.css comment sweep is Task 14 Step 6. No PR task (loop controller).
- **Dead-audit-first:** Tasks 2 (backup 88% dead), 3 (importer whole-dead), 4
  (temp 33% dead) are the early deletion-heavy tasks, each with a mandatory
  re-grep-at-execution gate (grep-verdict table before deletion).
- **No placeholders:** every family names its exact emitting file(s) from grep,
  exact classes, exact token/arbitrary-value translations, and exact `style.scss`
  slot. Genuinely-uncertain dispositions (the `.explorama--window-maximized`
  conditional; screen reachability) get a deterministic procedure + evidence
  standard.
- **Batch-4 lessons baked in:** ownership grep before migrate+delete (every task);
  cascade-tie trap (Tasks 5/8 reason about flat-utility vs retained-residual
  specificity + gate on AE=0 screenshot; `.table--footer__parent` kept whole to
  avoid a split-property tie); ancestor-chain byte-identity (every residual task
  gates on standalone-compile-diff of the COMPILED SELECTOR); `5_utilities.css`
  tasks carry the harness floor gate; welcome MD5 floor + collateral-proof fallback.
- **Task right-sizing:** one coherent owner-family-set (or a mechanical group of
  same-disposition sheets) per task, each ending in an independently testable
  deliverable. `_table.scss` (largest live migrate) is split dead+migrate (Task 5)
  + verbatim residual (Task 6). The vendor sheets (Task 10), the dead+residual mid
  sheets (Task 11), and the shared-marker renames (Task 12) are grouped mechanical
  relocations (batch precedent). Migrate tasks (2/4/5/8) touch `5_utilities.css`
  and carry the harness gate; residual/deletion tasks do not.
- **Open risk flagged (the ONE ambiguity for the controller):** issue #20's
  "**end state = components/ empty**" is NOT literally achievable in batch 5 —
  after all migrations, `components/` holds ~29 `_*_domain.scss` residual sheets
  (14 prior + 15 new + `_forms_domain`), every one a genuine phase-3 residual
  (vendor DOM / cross-plugin / attribute-prefix runtime ids / shared markers /
  `:has()` cascades) that cannot dissolve without phase-3 (#10) markup work.
  Task 14 audits + documents the end-state and records the resolution;
  **recommended = interpretation (A)**: accept that `components/` contains ONLY
  re-justified `_*_domain.scss` residuals (zero hand-authored component sheets) —
  matching all prior-batch precedent (residuals kept at their `@use` slot). (B) a
  clean follow-up moving all residuals to a dedicated `residual/` dir, or (C)
  folding them into `tailwind.css`, are LARGER speculative reorgs the issue's
  "re-justified for phase 3" language does not require — do NOT bake either into
  implementer tasks without controller sign-off.
