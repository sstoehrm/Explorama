# Tailwind Phase 2 — Batch 4 (woco chrome) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each implementer sees ONLY their task brief + this file's **Global Constraints** section — nothing else. No prior batch context is assumed.

**Goal:** Migrate the workspace-chrome feature area off hand-authored SCSS —
`_frames.scss` (810), `_toolbar.scss` (177), `_sidebar.scss` (188),
`_navbar.scss` (155), `_welcome_page.scss` (131), `_login.scss` (111),
`_loader.scss` (133), `_context_menu.scss` (63), `_dialog.scss` (145),
`_flyout.scss` (195) — so every retained rule is either (a) a Tailwind utility
stack carried in the plugin view that renders the markup, (b) a byte-identical
remnant in a per-sheet `_<sheet>_domain.scss` residual (in that sheet's `@use`
slot) when the DOM is cross-plugin co-owned / vendor / deep-descendant /
dynamically-constructed / forced-colors-marked, or (c) deleted as dead. Visual
result unchanged; the four batch-1 `!important` markers in `_welcome_page.scss`
are REMOVED (issue #16) while keeping the welcome page byte-identical.

**Architecture:** Ownership grep-verified this planning session
(`docs/superpowers/artifacts/tailwind/batch4-inventory.md` has the full table):
- **Owner-exclusive components → migrate to markup:** `.toolbar*` →
  `ui_base/components/misc/toolbar.cljs`; `.context-menu*` →
  `ui_base/components/misc/context_menu.cljs`; `.dialog*`/`.overlay` →
  `ui_base/components/frames/dialog.cljs`; `.navbar*` → `woco/page.cljs` +
  `woco/tools.cljs`; `.sidebar` shell → `woco/sidebar.cljs`.
- **Legacy / cross-plugin / dynamic → residual, no markup:** `_frames.scss`
  (legacy compiled `.explorama__window`/`.frame` chrome, `window__body`
  rendered by ~15 viz plugins, `no-data-placeholder` by 11, two dynamic
  `@for 1..15` `map.get($frame,…)` group loops, `::after` clip-paths,
  data-URI notification, forced-colors `.frame>.header` marker); `_loader.scss`
  (whole-sheet `:nth-child` animation matrices + `:has()`); `_login.scss`
  (`body.login` element selector + shared form markers + `.explorama-overlay`
  content typography, cross-context with woco/copyright + woco/tools);
  `_welcome_page.scss` (`.welcome__page`/`panel`/`section` co-emitted by
  woco/welcome + projects/overview + expdb/temp_import).
- **Dead:** `_flyout.scss` (whole sheet — 0 source emitters) + ~260 lines of
  `_frames.scss` legacy families + `_login.scss` `.login-logo`.

**Tech Stack:** SCSS (dart-sass via `npm run sass:dist`), Tailwind v4 CSS-first
(`npm run tailwind:dist`), the batch-1 render harness
(`styles/scripts/harness_capture.sh` / `harness_diff.py`, regression floor 1028),
headless Chromium + Python3 for app-screen capture/diff, clj-kondo.

## Global Constraints

Batch-1 + batch-2 + batch-3 calibration rules — every one is binding here:

- **Utilities-in-markup is the end state.** Emitted classes are standard
  Tailwind; arbitrary-value syntax (`bg-(--border)`, `[justify-content:start]`,
  `[border:none]`, `[&_span[class^=icon-]]:bg-(--icon)`, `has-[…]:`) is allowed
  for theme CSS-vars and forms with no computed-identical Tailwind name.
- **Token table (phase-1 verified):** `size('N')` → spacing N/4 (`size('8')`→
  `p-2`/`gap-2`; `size('12')`→`3`; `size('16')`→`4`; `size('4')`→`1`;
  `size('6')`→`1.5`; `size('24')`→`6`; `size('2')`→`0.5`; `size('48')`→`12`;
  `size('64')`→`16`; `size('20')`→`5`; `size('36')`→`9`; `size('32')`→`8`;
  `size('1')`→verify exact px in `_variables` (spacing `px`/`0.25`));
  `radius('md')`→`rounded-md`; the project `@theme` overrides the radius scale —
  use the EXACT `rounded-*` token (xxs/xs/sm/md/lg/xl/xxl); `radius('full')`→
  `rounded-full`; `color('gray-500')`→`text-gray-500`/`bg-gray-500`;
  `font-size('xs'|'sm'|'md'|'lg')`→`text-xs|text-sm|text-md|text-lg` (verify
  `md`/`lg` map to project tokens); `layer('menu')`→the z token; shadows
  (`shadow('xs'|'sm'|'md'|'lg'|'xl'|'inner')`) and z via the phase-1 theme
  tokens. `em`-based values (`.9em`, `1.25em`, `1.125rem`) and `rem` stay as-is
  (arbitrary if no token). `width('1'|'2')` → the border-width token (verify px).
- **Alpha colours + raw hex need the EXACT literal (arbitrary value), NOT
  Tailwind's `/NN` (oklab color-mix):** `color('white',.5)`, `color('black',.5)`,
  `color('white',.1)`/`.2`, `color('purple',0.5)`, `color('blue',0.1|0.25)`,
  `color('gray-100',0.8)`, and every raw `#fff`/`#182f3d`/`#dee2e6`/`#ccc`/
  `#adb5bd`/`#868e96`/`#f8f9fa`/`#f9f9fa`/`#dee2e6`/`#343a40`/`#ced4da`/`#222f40`/
  the 15 `.explorama__reference` hexes/`rgba(0,0,0,0.1|0.25)`/`rgba(255,255,255,0.2)`/
  `rgba(27,28,30,0.8)` → EXACT literal.
- **`calc(...)`/`min(...)`/`clamp(...)`/viewport-unit sizes stay arbitrary**
  (`w-[calc(100%-32px)]`, `h-[calc(100vh-240px)]`, `min-w-[min(300px,100%)]`,
  `max-h-[min(600px,100%)]`, `w-100vw`/`h-100vh` → `w-screen`/`h-screen` only if
  computed-identical, else arbitrary).
- **`border-none`/`outline-none` are NOT computed-identical** to `border:none`/
  `outline:none` — use arbitrary `[border:none]`/`[outline:none]` (recurs:
  toolbar `button{border:none}`, navbar `.actions.btn-group{border:none}`,
  sidebar `iframe{border:none}`, `> .section:has(.details-view){border:none}`).
- **Descendant/parent-context rules:** prefer explicit param threading; else
  keep as byte-identical remnant. **descendant-hover (`parent:hover child`)
  MUST translate as `group`/`group-hover:`**, never element `hover:` (recurs:
  navbar `.menu a:hover span[class^=icon-]`, toolbar `button:hover span`,
  context-menu `:hover`, welcome `.app-footer a:hover`). Trigger-region
  narrowing is gate-invisible.
- **App-driven state → conditional class map in CLJS**, not a CSS variant
  (`.toolbar button.active`, `.dialog-message/.dialog-prompt/.dialog-warning`,
  `.sidebar.show`, `.context-menu-entry.disabled`).
- **Dynamic class names must stay statically scannable** (full class strings in
  code) or be safelisted via `@source inline(...)`. The `_frames.scss`
  `@for $i 1..15` group loops build `.group-#{$i}`/`.explorama__window__group-#{$i}`
  with `map.get($frame,'group-#{$i}')` runtime values — this is the split-token
  + runtime-value case → the group loops STAY RESIDUAL (relocated verbatim), the
  cljs constructors (`woco/frame/color.cljs`) are UNTOUCHED.
- **Same-specificity tie-break is by generation/source order.** Migrating a
  family to a utility stack moves its declarations into `5_utilities.css`
  (linked LAST, unlayered) — utilities win equal-specificity ties against
  earlier component/residual CSS. Relocating into a residual sheet keeps the
  source `@use` slot (BEFORE `5_utilities.css`) — a residual loses an
  equal-specificity tie to a utility and must win by SPECIFICITY instead
  (raise the selector) or keep an existing `!important`. Element-selector→class
  migrations lift specificity (0,0,1)→(0,1,0) — re-check contextual sibling
  overrides still win. `::before`/`::after`/`::placeholder`/`::marker` content
  and non-default states (`:hover`/`:focus`/`:disabled`/`:has()`/`:nth-child`)
  are gate-invisible to `getComputedStyle` — verify by compiled-CSS reading and
  list as manual checks.
- **Load order is load-bearing** (`5_utilities.css` after `3_style.css` after
  `4_temp.css`). Check it explicitly for every inline-vs-remnant tie.
- **Kept/dropped marker classes matching a Tailwind builtin may be LOAD-BEARING
  COMPOSITION** — check OLD declarations first via
  `docs/superpowers/artifacts/tailwind/old-utilities.edn` (2681-entry phase-1
  ground truth): identical old/new = intended composition → KEEP the marker;
  different/absent → translate explicitly or override. Batch-4 builtin-name
  risks: `.header`, `.footer`, `.content`, `.title`, `.actions`, `.menu`,
  `.divider`, `.overlay`, `.active`, `.disabled`, `.show`, `.hidden`, `.card`,
  `.project`, `.body`, `.tools`, `.label`, `.expand`, `.loader`, `.frame`,
  `.selected`, `.selection`, `.dialog`.
- **MARKER-DROP grep MUST include BASE sheets (binding):** before dropping ANY
  class literal, grep consumers across `styles/src/scss/base/*` (esp.
  `_themes.scss` `@media (forced-colors: active)` a11y overrides, L290-390) AND
  all `styles/src/scss/components/*` AND every plugin/bundle. **Batch-4
  forced-colors markers that MUST stay in the DOM even after migration:**
  `span[class^="icon-"]`, `.context-menu-entry` (+`.disabled`),
  `button .loader span`, `:where(.loader-sm,.loader-md,.loader-lg) span`,
  `.toolbar button.active` (+ its `span[class^="icon-"]`), `.progress-bar`
  (+`>span`), `.welcome__page`, `.dialog`, `.frame>.header`.
- **Mandatory tie-flip sweep** whenever anything relocates into a residual
  sheet / moved `@use` slot OR a family migrates into `5_utilities.css`: after
  the batch's CSS changes land, grep every remaining sheet for equal-specificity
  overrides of any relocated/migrated selector and confirm no tie flipped; the
  app + welcome screenshots are the net.
- **Verification is disposition-specific:** DEAD → grep-proof + build-clean +
  app-screenshot unchanged (or welcome-MD5/harness floor if unreachable);
  RESIDUAL relocation → standalone-compile-and-diff byte-identity +
  incremental screenshot/compiled-CSS; MARKUP migration → app-screenshot
  pixel-identity on the affected screen, or (screen not headlessly reachable)
  compiled-CSS reading of the new utility stack vs the old declarations +
  manual-check list. **The welcome__close `!important` removal is
  semantic-equivalence, NOT byte-identical** — gated by compiled-CSS
  specificity math + welcome MD5 `c10b9a777c2dd90663189f3905b9b9d9`.

Batch-4-specific constraints:

- **Branch:** `tailwind-phase2-batches2-5` (already checked out; batches 2 & 3
  merged into it — `_search.scss`/`_dashboards.scss`/`_reports.scss`/
  `_presentation.scss`/`_snapshots.scss` gone; `_search_domain`/`_forms_domain`/
  four batch-3 `_*_domain` sheets present). Base after batch 3 = `b6f17ab`.
  This is one batch of a multi-batch LOOP — **NO PR task** (the loop controller
  opens the single PR after all batches). One commit per task.
- **Migrate tasks DO touch `5_utilities.css` this batch** (unlike batch 3):
  Tasks 4/5/6/7/8 (toolbar, context_menu, dialog, navbar, sidebar) add utility
  stacks that Tailwind compiles into `dist/css/5_utilities.css`. Every such
  task **MUST run the primitives harness** (`harness_capture.sh` +
  `harness_diff.py`) and confirm **0 NEW diffs / floor 1028** — the batch-1
  primitives (buttons/inputs/etc.) share `5_utilities.css` and a stray utility
  or marker drop can regress them. Residual-only tasks (2/3/9/10/11) do NOT
  touch `5_utilities.css`; run the harness once at Task 1 and once at Task 12.
- **App-level gates only** (no primitives-harness coverage of these chrome
  screens). Per-screen app baselines captured BEFORE the first change (Task 1):
  **frame-with-toolbar**, **sidebar-open**, **a dialog**, **the login page**,
  plus the always-reachable **welcome** page (MD5 floor). Investigate headless
  reachability (batch-2/3 found several screens unreachable). Login is the
  DEFAULT initial state (`index.html` ships `<body class="initial login">`,
  `woco/page.cljs` L327-328 sets it when logged-out) → at least the background/
  overlay is trivially reachable; the workspace navbar + toolbar + a frame are
  reachable in a fresh workspace; sidebar + dialog need DOM-click triggers.
  Record reachability in the Task-1 artifact; unreachable → compiled-CSS reading
  + the welcome-MD5 floor, exactly as batch-2/3 handled unreachable screens.
- **Residual-sheet fallback:** the cross-plugin/vendor/deep-descendant/dynamic/
  forced-colors-marked remainder goes verbatim into a NEW per-sheet residual,
  added to `styles/src/scss/style.scss` `@use` in that sheet's CURRENT slot to
  keep the cascade identical (the `_search_domain.scss`/`_forms_domain.scss`
  precedent). **Anticipated new residual sheets + slots** (current post-batch-3
  `style.scss` order): `_login_domain.scss` (L5), `_navbar_domain.scss` (L6,
  only if residual survives variants), `_frames_domain.scss` (L7),
  `_welcome_page_domain.scss` (L11), `_toolbar_domain.scss` (L12, only if
  residual survives variants), `_sidebar_domain.scss` (L13),
  `_loader_domain.scss` (L19), `_dialog_domain.scss` (L23). `_flyout.scss`
  (L20) is DELETED with **no** replacement. Each `_*_domain.scss` keeps the same
  `@use '../base/…' as *;` imports its source sheet had (verify per sheet:
  `variables`, `colormap`, and `icons`/`sass:map` where used).
- **Comment cleanup (#16) rides along:** (a) the four `_welcome_page.scss`
  `!important` markers are REMOVED (Task 9, the headline); (b) write clear
  headers on each new `_*_domain.scss` naming why each family is deferred;
  (c) `_frames.scss`/`_sidebar.scss` reference `.card`/`.title__bar` contextual
  selectors (`.window-placement-overlay .card`, `.window-handling-tour .card`) —
  Task 12 checks the batch-1 `.card`/button remnant blocks in
  `styles/src/tailwind.css` for now-dissolvable references; (d) fix any stale
  inline SCSS line-number cites as rules move. Delete only references this batch
  actually resolves.

## Reference facts (grep evidence, not assumption — re-verify per task)

- **`style.scss` `@use` order (load-bearing), current post-batch-3:** `login`(L5)
  < `navbar`(L6) < `frames`(L7) < `forms_domain`(L8) < `section`(L9) <
  `projects`(L10) < `welcome_page`(L11) < `toolbar`(L12) < `sidebar`(L13) <
  `settings`(L14) < `snapshots_domain`(L15) < `legend`(L16) < `slider`(L17) <
  `datepicker`(L18) < `loader`(L19) < `flyout`(L20) < `context_menu`(L21) <
  `table`(L22) < `dialog`(L23) < `search_domain`(L26) < … < `temp`(L48, last)
  < `base/helpers`(L54). Each residual sheet occupies its source sheet's slot.
- **Owner map (grep-verified):** toolbar → `ui_base/components/misc/toolbar.cljs`;
  context-menu → `ui_base/components/misc/context_menu.cljs`; dialog →
  `ui_base/components/frames/dialog.cljs`; navbar → `woco/page.cljs` (L158) +
  `woco/tools.cljs`; sidebar → `woco/sidebar.cljs`; welcome → `woco/welcome.cljs`
  (+ cross-plugin projects/overview.cljs + expdb/temp_import/core.cljs); loader →
  button.cljs + loading_message.cljs + progress_bar.cljs; frames → cross-plugin
  (ui_base frames/* + woco/frame/view/* + every viz plugin).
- **DEAD (0 emitters, re-grep before deleting):** whole `_flyout.scss`
  (`.flyout__container`/`.flyout__inner`/`.action__save`/`.input__group`/
  `.saved__title`/`.saved__lastused`/`.explorama__flyout--close`); `_frames.scss`
  `.explorama__reference-*`/`.data__reference` (15 rules), `.button__close`,
  `.explorama__window--flex`(+`.ql-*`), `.window__body--ql`, `.window__body__frame`,
  `.explorama__window__footer`, `.window__body--scroll`(verify), the whole
  `.explorama__window--sidebar`/`.window__sidebar`/`.settings__action`/
  `.settings__subtitle`/`.settings__two__actions`/`.wrapper__main` legacy block;
  `_login.scss` `.login-logo`.
- **Test suites (same as batch-3):** `bundles/server`
  `clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci` → expect **71/0**;
  `bundles/browser` `npm run test-ci` (run_in_background; result in
  `bundles/browser/report.xml`) → expect **140/0**.
- **Kondo parity:** 0 err on touched files; compare touched-file warn/err counts
  to the pre-batch baseline (disposable `git worktree` at `b6f17ab`), disable the
  stateful cache (`--cache false`) to avoid env drift.
- **Existing residual sheets:** `_forms_domain.scss`, `_search_domain.scss`,
  `_dashboards_domain.scss`, `_reports_domain.scss`, `_presentation_domain.scss`,
  `_snapshots_domain.scss`. No batch-4 residual sheets exist yet.

## Standard gate block (every task runs the applicable subset)

```bash
cd styles && npm run sass:dist && npm run tailwind:dist        # both clean, no NEW warnings
# app-screen parity (screens the task's family renders, if reachable — else compiled-CSS reading):
bash styles/scripts/chrome_capture.sh after-<task> <screen>    # frame-toolbar / sidebar / dialog / login as applicable
python3 styles/scripts/chrome_diff.py baseline after-<task>    # AE=0, or every diff investigated & justified
# batch-1 primitives regression floor (MANDATORY on any task that touches 5_utilities.css — Tasks 4-8):
bash styles/scripts/harness_capture.sh b4-after-<task>
python3 styles/scripts/harness_diff.py b4-baseline b4-after-<task>   # 0 NEW diffs (floor 1028)
# verbatim residual relocations only:
#   standalone-compile the moved block pre & post, diff → byte-identical
clj-kondo --lint <touched cljs/clj files> --cache false        # warn/err count == pre-batch baseline for those files
```

**Disposition rule for every selector** (applied in each task's inventory):
element-local rule on a class the OWNER file emits and the owner file-set is
editable here → **migrate to markup** (keep forced-colors markers); no emitter
anywhere (grep-proven, incl. base sheets) → **delete**; cross-plugin co-owned /
vendor / portaled DOM / deep-descendant cascade / attribute-prefix selector /
dynamically-constructed class / `@keyframes` / content-DOM element typography /
forced-colors-marked → **byte-identical remnant** in `_<sheet>_domain.scss`
(welcome__close is the one deliberate-rewrite exception, Task 9).

---

### Task 1: Per-screen baselines + inventory artifact (BEFORE any change)

**Files:**
- Create: `styles/scripts/chrome_capture.sh`, `styles/scripts/chrome_diff.py`
- Reference (read, do not edit): `styles/scripts/dr_capture.sh`,
  `styles/scripts/dr_diff.py` (batch-3), `styles/scripts/harness_capture.sh`,
  `styles/scripts/harness_diff.py`
- Artifact (gitignored): `docs/superpowers/artifacts/tailwind/batch4-inventory.md`
  (already drafted this planning session — VERIFY + extend, don't rewrite)

- [ ] **Step 1: Build the capture script.** Clone the batch-3 method
  (`styles/scripts/dr_capture.sh` / `dr_diff.py`): `bb gather-assets.bb.clj dev`
  (or the server equivalent) in `bundles/server`, prod-compile woco, serve
  `resources/public`, headless Chromium `--window-size=1400,900
  --virtual-time-budget=40000`, double-capture + MD5-compare each capture before
  trusting (batch-1 flakiness lesson). Parameterize
  `chrome_capture.sh <label> <screen>` for FOUR screens plus welcome:
  - `frame-toolbar` — a fresh workspace with ≥1 frame open showing its
    `.frame>.header` + the `.toolbar` (drive via URL hash / injected event that
    creates a frame, or the closest reachable landing).
  - `sidebar-open` — the workspace with a sidebar shown (`.sidebar.show`;
    inject the woco event that opens a sidebar panel).
  - `dialog` — a dialog rendered (`.overlay .dialog`; inject a confirm/message
    dialog, e.g. projects confirm-dialog or a share dialog).
  - `login` — the logged-out landing (`body.login` + `.explorama-overlay` if the
    About overlay is reachable). This is the DEFAULT initial state, so at least
    the login background is reachable without navigation.
  - `welcome` — always captured (batch-1/2/3 regression floor).
  `chrome_diff.py` mirrors `dr_diff.py` (per-screen PNG MD5 + AE pixel diff,
  `--*` enumeration-churn suppression carried from batch-1).
- [ ] **Step 2: Capture baselines.** `bash styles/scripts/chrome_capture.sh
  baseline <each-reachable-screen>`; double-capture each and confirm MD5-identical.
  Capture the welcome baseline and confirm MD5
  `c10b9a777c2dd90663189f3905b9b9d9` (batch-1/2/3 floor — batch 4 must not
  regress it). Capture the harness baseline `bash styles/scripts/harness_capture.sh
  b4-baseline` and confirm `harness_diff.py <original-baseline> b4-baseline` =
  floor **1028** (912 radius + 116 shadow, batch-1 primitives unchanged). For any
  screen that will NOT render headlessly (record which), its gate falls back to
  compiled-CSS reading + manual checks.
- [ ] **Step 3: Verify + extend the inventory artifact.** Confirm every family in
  `batch4-inventory.md` against a fresh grep (the file was drafted at plan time;
  re-run each `grep -rln --include='*.cljs' --include='*.cljc'` and mark
  confirmed / corrected). Record per-screen reachability. This artifact drives
  Tasks 2-11; a family's disposition may only change during its own task with
  recorded evidence.
- [ ] **Step 4: Commit** the two scripts only (artifacts are gitignored):
  `git commit -m "tailwind(woco): batch-4 baseline capture scripts + inventory verify"`.

---

### Task 2: Whole-batch dead-rule sweep (deletion only)

**Files:** `styles/src/scss/components/_flyout.scss` (delete whole file),
`_frames.scss`, `_login.scss`; `styles/src/scss/style.scss` (remove the
`@use 'components/flyout';` line, slot L20).

- [ ] **Step 1: Prove `_flyout.scss` dead.** `grep -rln --include='*.cljs'
  --include='*.cljc' -E "flyout__container|flyout__inner|action__save|input__group|
  saved__title|saved__lastused|explorama__flyout" plugins bundles/browser/frontend
  bundles/electron/frontend bundles/server/frontend` (exclude `js/out`,
  `cljs-out`, `resources/public`). Expect **0** source emitters (hits only in
  compiled `3_style.css`). Confirm the `flyout` hit in `algorithms/path/core.cljs`
  is `flyout-open?` (a fn, unrelated). Also grep `styles/src/scss` (component +
  **base** sheets) for any `.flyout__container`/`.extra-column .flyout__container`
  cross-reference — expect none.
- [ ] **Step 2: Prove the `_frames.scss` dead families.** For EACH, run the same
  cljs/cljc grep + `grep -rn -- "<class>" styles/src/scss` (incl. base), locate
  the block **by selector** (line numbers are stale): `.explorama__reference-*
  .data__reference input` (15 rules, `data__reference` 0), `.button__close`
  (`!important`, 0), `.explorama__window__footer` (+`a:first-child`, 0),
  `.explorama__window--flex` (+`.window__body`/`.ql-container`/`.ql-editor`, 0),
  `.explorama__window .window__body.window__body--ql` (0),
  `.explorama__window .window__body__frame` (0),
  `.explorama__window .window__body--scroll` (verify 0), the entire
  `.explorama__window.explorama__window--sidebar …` block through `.wrapper__main`
  (`explorama__window--sidebar`/`window__sidebar`/`settings__subtitle`/
  `settings__two__actions` all 0; **re-grep `settings__action` and `wrapper__main`
  scoped** — they exist only under `indicator`'s own DOM, never under
  `.window__sidebar`, so the `.explorama__window--sidebar … settings__action`/
  `wrapper__main` rules are dead). Do NOT delete `.explorama__window .window__body`
  (base, live cross-plugin) — only the dead `--ql`/`--scroll`/`__frame` modifiers.
- [ ] **Step 3: Prove `_login.scss` `.login-logo` dead.** `grep -rn
  "login-logo"` → 0 source emitters. Split the `body.login form { … }` /
  `body.login { … }` blocks so ONLY `body.login .login-logo` is removed; leave
  `body.login`, `body.login form label/.input .text-input/.checkbox input`
  (→ Task 10 residual).
- [ ] **Step 4: Delete** the proven-dead: `git rm
  styles/src/scss/components/_flyout.scss` + remove its `@use` line (leave a
  1-line comment noting flyout was fully dead, batch-4); delete the flagged
  `_frames.scss` families and `_login.scss` `.login-logo`.
- [ ] **Step 5: Gates.** `cd styles && npm run sass:dist && npm run tailwind:dist`
  clean; `chrome_capture.sh` + diff vs baseline = AE=0 for reachable screens
  (nothing rendered used these) / compiled-CSS confirmation for unreachable;
  welcome MD5 `c10b9a77…` unchanged; **harness NOT touched** (residual/deletion
  only — no `5_utilities.css` change). kondo n/a (no cljs). Report deleted-family
  count + compiled-line delta per sheet.
- [ ] **Step 6: Commit** — `git commit -m "tailwind(woco): delete dead batch-4
  families (whole _flyout.scss, _frames legacy window/reference/sidebar blocks,
  .login-logo)"` with the grep-verdict table in the body.

---

### Task 3: `_frames.scss` remainder → `_frames_domain.scss` residual (verbatim)

**Files:** create `styles/src/scss/components/_frames_domain.scss`;
`styles/src/scss/style.scss` (`@use 'components/frames_domain';` at slot L7,
replacing `@use 'components/frames';`); `_frames.scss` (delete relocated → file
empties → `git rm`).

**Scope (all RESIDUAL — cross-plugin / dynamic / vendor / forced-colors-marked;
nothing migrates to markup — locate each block by SELECTOR):** the live
`.explorama__window .window__body*` cascade (base `.window__body`, `.flex`,
`.no-data-placeholder`+`>span`+icon, `canvas`, `.window__body__wrapper`+
`:has(.no-data-placeholder)`+`.extra-column`+`.no-data-placeholder`,
`.explorama__window--maximized`+`.window__header`); the
`div[id^='woco_frame-visualization-charts']`/`div[id^='woco_frame-data-provisioning']`/
`div[id^='woco_frame-ki']` attribute-prefix rules; the whole `.window__notification`
family (data-URI `.notification__close`, `filter:`, hardcoded rgba,
`.notification__message div button`+`:hover`); the `.frame` chrome (`.frame`+
`:not(.note-card)`, `.frame>.header`+`span.frame-icon`+`.title`/`.custom_title`+
`input::placeholder`+`.tools`+`@for group-#{$i}` loop, `.body-wrapper`+`.body`/
`.scroll`/`.extra-column`/`.no-data-placeholder`, `.footer`, `.drop-target`+
`.window__body:after` icon-mixin); `.frame.selected`/`.selection`/`.sticking`;
`#local-selection-bounding-box`/`#selection-bounding-box`; `.window-placement-overlay`/
`.window-drag-overlay`+`.card`; `.window-handling-tour`+`.card`/`img`/`.image-hint`/`h3`;
the legacy `@for 1..15` `.explorama__window__group-#{$i}` +
`.frame:has(.explorama__window__group-#{$i}) .window__body__wrapper` +
`.frame .explorama__window__group-#{$i}.header` gradient + `::after` clip-path.

- [ ] **Step 1: Justify residual (record in inventory).** For each family, note
  why it fails the migrate criterion: `window__body`/`no-data-placeholder` =
  cross-plugin (~15 / 11 emitters); `div[id^='woco_frame-…']` = attribute-prefix;
  `.window__notification` = data-URI + `filter:` + deep descendant (not
  element-expressible); `.frame>.header` = forced-colors marker + cross-plugin
  chrome + `::placeholder`; the two `@for` group loops = dynamic class +
  `map.get` runtime value (constructors in `woco/frame/color.cljs` UNTOUCHED);
  `.drop-target`/group `::after` = pseudo-element; `.card` = shared marker;
  ID selectors. Confirm none has a live cross-sheet equal-specificity competitor
  that a slot-preserving move would flip.
- [ ] **Step 2: Create `_frames_domain.scss`** with a header explaining each
  deferred family, keeping the source imports (`@use "sass:map";
  @use '../base/variables' as *; @use '../base/colormap' as *;
  @use '../base/icons' as *;` — verify against `_frames.scss` head). Relocate all
  in-scope blocks **VERBATIM** (byte-for-byte, including the `/* line N */`
  comments and `!important`s — this is a byte-identical move, NOT a rewrite).
  Add `@use 'components/frames_domain';` to `style.scss` at slot L7 (immediately
  where `@use 'components/frames';` was) so it compiles in frames' cascade
  position.
- [ ] **Step 3: Delete** the relocated blocks from `_frames.scss`. If it empties,
  `git rm styles/src/scss/components/_frames.scss` and remove any stale `@use`
  (the L7 slot now holds `frames_domain`). Leave `woco/frame/color.cljs` and all
  viz-plugin views UNTOUCHED.
- [ ] **Step 4: Gates.** Build clean; **standalone-compile-and-diff** the relocated
  blocks pre vs post (byte-identical compiled output — the gold-standard verbatim
  check); `frame-toolbar` screenshot AE=0 or compiled-CSS confirmation if
  unreachable; **harness NOT touched** (no `5_utilities.css` change); kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(woco): relocate _frames.scss
  remainder to _frames_domain residual (cross-plugin window/frame chrome, dynamic
  group loops, data-URI notification); delete _frames.scss"`.

---

### Task 4: `_toolbar.scss` → ui_base toolbar.cljs markup (+ variants/residual)

**Files:** `plugins/frontend/de/explorama/frontend/ui_base/components/misc/toolbar.cljs`;
`_toolbar.scss` (delete migrated → `git rm` if empty); create
`styles/src/scss/components/_toolbar_domain.scss` (slot L12) ONLY if residual
survives arbitrary variants.

**Scope (owner-exclusive → migrate; locate by SELECTOR):** `.toolbar` base,
`.toolbar-section` (+ `.toolbar-horizontal` variant), `.toolbar-divider`
(horizontal + vertical), `.toolbar-options`, `.toolbar button` chrome (padding,
`[border:none]`, `rounded-lg`, color/bg/transition, `span[class^=icon-]` sizing +
tint, `:hover`, `:active:enabled`, `:focus-visible`, `:disabled`, `.active` +
its `:hover`), `.toolbar-wrapper`, `.toolbar-popout`. **Keep `.toolbar button.active`
literal marker** (forced-colors). Residual/variant candidates: `.toolbar:has(>
.toolbar-options) .toolbar-section:last-child`, `button:has(> .label)`,
`.label:not(:only-child)`, `.toolbar-wrapper:has(.toolbar-horizontal)`,
`.toolbar-popout canvas` (Pixi vendor).

- [ ] **Step 1: Inventory & split.** Map each rule to a private def'd stack at
  the ns top (reuse across `toolbar`/`toolbar-horizontal`). `size('8')`→`p-2`,
  `size('4')`→`gap-1`, `size('2')`→`gap-0.5`, `size('20')`→`w-5 h-5`,
  `size('12')`→`w-3 h-3`, `radius('xl')`→`rounded-xl`, `radius('lg')`→`rounded-lg`,
  `radius('md')`→`rounded-md`, `radius('full')`→`rounded-full`, `shadow('lg')`→
  `shadow-lg`, `shadow('md')`→`shadow-md`, `font-size('xs')`→`text-xs`. Colors
  are theme vars → `text-(--text)`, `bg-(--bg)`, `bg-(--icon)`, `text-(--link)`,
  `bg-(--icon-hover)`, `bg-(--divider)`, `bg-(--icon-secondary)`,
  `border-(--border-focus)`, `text-(--text-disabled)`, `bg-(--bg-hover)`,
  `text-(--link-hover)`. `border:none`→`[border:none]`. `transition: color 120ms,
  background-color 120ms`→`[transition:color_120ms,background-color_120ms]`.
  `transform: scale(0.95)`→`active:scale-95` (verify enabled-guard →
  `active:enabled:scale-95`). `transform: rotate(-45deg)`→`[transform:rotate(-45deg)]`.
  `outline: size('2') solid var(--border-focus)`→
  `focus-visible:[outline:…]` arbitrary. The `span[class^=icon-]` descendants →
  `[&_span[class^=icon-]]:w-5` style arbitrary variants (verify the scanner emits
  them). `:has()`/`:not(:only-child)` → `has-[…]:`/`[&:not(:only-child)]:`
  variants IF compilable; else route to `_toolbar_domain.scss`. `.toolbar-popout
  canvas` (Pixi) → **residual** (vendor DOM).
- [ ] **Step 2: Translate** onto toolbar.cljs hiccup; keep the `.toolbar`/
  `.toolbar-section`/`.toolbar button.active` markers emitted; relocate any
  non-variant-expressible residual verbatim into `_toolbar_domain.scss` (create
  + `@use` at slot L12) with a header. Delete migrated rules from `_toolbar.scss`;
  if it empties, `git rm` + remove its `@use` line.
- [ ] **Step 3: Gates.** Build clean; `frame-toolbar` screenshot AE=0 (toolbar
  renders in the workspace) or compiled-CSS reading of the new stacks vs old
  declarations + manual-check list (hover/active/disabled/focus-visible,
  horizontal variant, popout canvas) if unreachable; **harness floor 1028, 0 NEW
  diffs** (touches `5_utilities.css`); standalone-compile-diff any residual;
  kondo clean on toolbar.cljs.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): migrate toolbar to
  ui_base toolbar.cljs (keep forced-colors .toolbar button.active marker; Pixi
  canvas residual)"`.

---

### Task 5: `_context_menu.scss` → ui_base context_menu.cljs markup (+ variants)

**Files:** `plugins/frontend/de/explorama/frontend/ui_base/components/misc/context_menu.cljs`;
`_context_menu.scss` (delete migrated → `git rm` if empty); create
`_context_menu_domain.scss` (slot L21) ONLY if residual survives variants.

**Scope (owner-exclusive → migrate):** `.context-menu` base (border, radius, bg,
shadow, overflow, `animation: 90ms fadeIn`), `.context-menu:not(:has(.context-menu-entry))`
padding, `.context-menu-group` (+ `:not(:last-child)` border-bottom),
`.context-menu-entry` (flex/gap/padding/color/transition, `:has(> span[class^=icon-]:not(.expand))`
padding-left, `span[class^=icon-]` tint, `.expand` margin, `:hover:not(.disabled)`,
`.disabled` + icon). **Keep `.context-menu-entry`/`.context-menu-entry.disabled`/
`.expand` literal markers** (forced-colors + logic).

- [ ] **Step 1: Inventory & split.** `size('8')`→`p-2`/`gap-2`, `size('16')`→`4`,
  `size('12')`→`pl-3`, the literal `34px` padding-left → `pl-[34px]`,
  `width('1')`→the border-width token (verify px; else `[border-width:1px]`),
  `radius('xl')`→`rounded-xl`, `shadow('md')`→`shadow-md`. Colors → `bg-(--bg)`,
  `border-(--border)`, `text-(--text)`, `bg-(--icon)`, `bg-(--bg-hover)`,
  `text-(--text-disabled)`, `bg-(--icon-disabled)`. `animation: 90ms fadeIn`→
  `[animation:90ms_fadeIn]` (the `fadeIn` keyframe already ships from batch-1 —
  verify it's still defined in a base/animations sheet; if not, keep the
  animation as residual). `user-select:none`→`select-none`. `:not(:has(…))`,
  `:has(> span…)`, `:not(:last-child)`, `:hover:not(.disabled)` → `not-has-[…]:`/
  `has-[…]:`/`[&:not(:last-child)]:`/`hover:[&:not(.disabled)]:` variants IF
  compilable; else `_context_menu_domain.scss`. The `.disabled span[class^=icon-]`
  descendant tint → conditional class map (the `.disabled` state) or
  `[&.disabled_span[class^=icon-]]:` variant.
- [ ] **Step 2: Translate** onto context_menu.cljs; keep the markers emitted;
  residual (if any) → `_context_menu_domain.scss` (slot L21). Delete migrated
  from `_context_menu.scss`; `git rm` if empty + remove `@use`.
- [ ] **Step 3: Gates.** Build clean; the context menu is likely NOT headlessly
  reachable (needs a right-click/open trigger) → compiled-CSS reading of the new
  stacks vs old declarations + manual checks (hover/disabled entries, icon
  padding-left `:has`, group border, fadeIn) is the primary gate; welcome MD5
  unchanged; **harness floor 1028, 0 NEW** (touches `5_utilities.css`); kondo
  clean.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): migrate context menu
  to ui_base context_menu.cljs (keep forced-colors .context-menu-entry markers)"`.

---

### Task 6: `_dialog.scss` → ui_base dialog.cljs markup (+ `_dialog_domain.scss`)

**Files:** `plugins/frontend/de/explorama/frontend/ui_base/components/frames/dialog.cljs`;
`_dialog.scss` (delete migrated → `git rm` if empty); create
`styles/src/scss/components/_dialog_domain.scss` (slot L23).

**Scope (owner-exclusive → migrate):** `.overlay` base (position inset, z, flex
center, padding, `color('black',.5)` bg), `.dialog` base (+ `.dialog-full-width`/
`-full-height`/`-auto-size`, `min(...)` sizes), `.dialog-header`, `.dialog-body`
(+ `:only-child` variant), `.dialog-footer`. **Keep `.dialog` literal marker**
(forced-colors). **Residual → `_dialog_domain.scss`:** `.explorama__window .overlay`
+ `.sidebar .overlay .dialog` (cross-context frame/sidebar overrides),
`.dialog-message/.dialog-prompt/.dialog-warning .dialog-header` colour variants
(descendant context — OR migrate as a conditional class map if dialog.cljs sets
the variant class; default residual), `.dialog-compact :where(…)` (`:where()`),
`.dialog .share-section` family (cross-plugin: projects/share_project.cljs +
reporting/share_dr.cljs), `.dialog {animation: scaleIn}` + `@keyframes scaleIn`.

- [ ] **Step 1: Inventory & split.** `size('24')`→`p-6`, `size('12')`→`p-3`,
  `size('16')`→`p-4`, `size('8')`→`gap-2`, `size('48') size('64')`→`py-12 px-16`,
  `size('1')`→the border-width token. `radius('xl')`→`rounded-xl`, `radius('xs')`→
  `rounded-xs`, `radius('md')`→`rounded-md`, `shadow('xl')`→`shadow-xl`,
  `shadow('sm')`→`shadow-sm`. `min(300px,100%)`/`min(600px,100%)`→
  `min-w-[min(300px,100%)]` / `max-w-[min(600px,100%)]` / `max-h-[min(600px,100%)]`.
  `color('black',.5)`→exact `bg-[rgba(…exact…)]` (NOT `/50`). Colors →
  `bg-(--bg)`, `border-(--border)`, `text-(--text)`, `bg-(--bg-section)`,
  `text-(--text-disabled)`. `font-size('md')`/`font-size('sm')`→`text-md`/`text-sm`.
  `:only-child`→`[&:only-child]:` variant. Keep the `.dialog` marker.
- [ ] **Step 2: Create `_dialog_domain.scss`** (imports `@use '../base/variables'
  as *; @use '../base/colormap' as *;` — verify against source head; header
  naming each deferred family), relocate the residual set VERBATIM (incl. the
  `@keyframes scaleIn` block and the `.dialog {animation:scaleIn}` rule — a
  keyframe must live in a sheet), add `@use 'components/dialog_domain';` after the
  L23 slot. Translate migratable rules onto dialog.cljs; delete migrated +
  relocated from `_dialog.scss`; `git rm` if empty + remove `@use`.
- [ ] **Step 3: Gates.** Build clean; `dialog` screenshot AE=0 if reachable
  (inject a confirm/message dialog) or compiled-CSS reading + manual checks
  (message/prompt/warning header colours, compact padding, share-section,
  scaleIn animation, only-child body centering) if unreachable; standalone-compile-diff
  the residual block; **harness floor 1028, 0 NEW** (touches `5_utilities.css`);
  kondo clean on dialog.cljs.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): migrate dialog to
  ui_base dialog.cljs (+ _dialog_domain residual: context overrides, share-section,
  @keyframes scaleIn)"`.

---

### Task 7: `_navbar.scss` → woco navbar markup (+ small residual)

**Files:** `plugins/frontend/de/explorama/frontend/woco/page.cljs` (renders
`.navbar` L158, `.logo__link`), `plugins/frontend/de/explorama/frontend/woco/tools.cljs`
(renders `.menu`); `_navbar.scss` (delete migrated → `git rm` if empty); create
`_navbar_domain.scss` (slot L6) ONLY if residual survives variants.

**Scope (owner-exclusive woco → migrate):** `.navbar` base (position/z
`layer('menu')`/flex/`100vw`/padding/gap/`pointer-events:none`, `>div` +
`:has(.logo__link)`, `a`), `.navbar .logo__link` (`content: var(--logo)`,
`:hover`/`:active` scale), `.navbar .project` (+ `.title`), `.navbar .menu`
(+ `a` + `span[class^=icon-]` + `:focus-visible` + `:has(.disabled)` +
`:hover:has(.disabled)` + `:hover/:active span[class^=icon-]:not(.disabled)`),
`.navbar .menu .divider`. **Residual → `_navbar_domain.scss` (or arbitrary
variants):** `.navbar .project .actions.btn-group` + `.btn-icon` chrome (overrides
ui_base btn-group — shared marker context), `.navbar .menu .new-indicator`
(cross-plugin shared marker: indicator/projects/woco), the `:has()`/`:not()`
descendant-state selectors on `.menu a` if not variant-expressible.

- [ ] **Step 1: Inventory & split.** `layer('menu')`→the z token, `size('8')`→
  `p-2`/`gap-2`/`pl-2`, `size('36')`→`h-9`, `size('20')`→`w-5 h-5`, `size('2')`→
  `w-0.5`/`gap-0.5`, `size('4')`→`mx-1`, literal `8px`/`7px`/`52px`/`3rem`/`1rem`
  → arbitrary. `radius('xl')`→`rounded-xl`, `radius('sm')`→`rounded-sm`,
  `radius('full')`→`rounded-full`, `shadow('lg')`→`shadow-lg`, `font-size('md')`→
  `text-md`. Colors → `bg-(--bg)`, `text-(--text)`, `bg-(--icon)`,
  `bg-(--icon-hover)`, `bg-(--icon-disabled)`, `bg-(--divider)`,
  `color('purple-700')`→`[outline-color:…]` for focus, `color('gray-700')` dotted.
  `content: var(--logo)`→`[content:var(--logo)]`. `transform: scale(1.03)`/`0.97`/
  `0.95`→`hover:scale-[1.03]`/`active:scale-[0.97]`. **descendant-hover**
  `.menu a:hover span[class^=icon-]:not(.disabled)`→`group`/`group-hover:` on the
  `<a>`/icon span (NOT element hover). `:focus-visible`+`:has(.disabled)` →
  variants. Keep `.new-indicator` shared marker → residual/keep.
- [ ] **Step 2: Translate** onto page.cljs (navbar shell + logo) + tools.cljs
  (menu). Relocate `.actions.btn-group`/`.btn-icon` override + `.new-indicator`
  position + non-expressible `:has()` into `_navbar_domain.scss` (slot L6, header)
  OR arbitrary variants. Delete migrated from `_navbar.scss`; `git rm` if empty +
  remove `@use`.
- [ ] **Step 3: Gates.** Build clean; `frame-toolbar`/workspace screenshot AE=0
  (navbar renders on the workspace) or compiled-CSS + manual checks (logo hover
  scale, menu icon hover/active, focus-visible outline, disabled cursor,
  new-indicator) if unreachable; standalone-compile-diff residual; **harness floor
  1028, 0 NEW** (touches `5_utilities.css`); kondo clean on page.cljs + tools.cljs.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): migrate navbar to
  woco page.cljs/tools.cljs (+ _navbar_domain: btn-group/new-indicator shared-marker
  overrides)"`.

---

### Task 8: `_sidebar.scss` → woco sidebar.cljs markup (+ `_sidebar_domain.scss`)

**Files:** `plugins/frontend/de/explorama/frontend/woco/sidebar.cljs`;
`_sidebar.scss` (delete migrated → `git rm` if empty); create
`styles/src/scss/components/_sidebar_domain.scss` (slot L13).

**Scope (woco shell → migrate):** `.sidebar` base (+ `.show`), `.sidebar .header`
(+ `h2`, `.actions` + `span`), `.sidebar .footer` (+ `h3`), `.sidebar hr`,
`.sidebar iframe` (`[border:none]`). **Residual → `_sidebar_domain.scss`:**
`.sidebar > .section:has(.details-view)` + `> .content`, `.sidebar > .overlay`,
`.sidebar > .tabs__navigation` (cross-plugin `.section`/`.overlay`/
`.tabs__navigation` markers, `:has()`), the whole `.sidebar .content > div:not(…)`
deep-child block (selects arbitrary plugin-injected panels — cross-plugin
children, `:not()`/`:has(+ h3)`/`:where(h1,h2)`+icon/`h2:has(span)`/
`input:disabled + label img`/`:has(.details-view)`), the whole `.sidebar
.details-view` table block (`.details-view` cross-plugin map/mosaic/woco,
`table thead th`/`tbody td`/`tr:nth-child(odd)`/`:has(.fulltext__container)`).

- [ ] **Step 1: Inventory & split.** `.sidebar` shell: `radius('xl')`→`rounded-xl`,
  `shadow('lg')`→`shadow-lg`, `bg-(--bg)`, literal `68px`/`8px`/`500px`/`120ms
  fadeInLeft` animation → arbitrary; `.header`/`.footer`: `size('8')`/`size('12')`/
  `size('4')` → padding/margin tokens, `font-size('sm')`→`text-sm`,
  `text-(--text-secondary)`, `bg-(--icon)`, `bg-(--border)`. `border:none`/
  `iframe{border:none}`→`[border:none]`. Everything under `.content > div:not(…)`
  and `.details-view` is cross-plugin/deep/`:nth-child`/`:has()`/`:where()` →
  **residual** (the `> div:not(.tabs__navigation, .overlay, .flex-initial,
  .details-view, .fulltext__container)` selector matches plugin-injected panels
  the woco sidebar does NOT own).
- [ ] **Step 2: Create `_sidebar_domain.scss`** (imports + header), relocate the
  residual set VERBATIM, add `@use 'components/sidebar_domain';` at slot L13.
  Translate the shell chrome onto sidebar.cljs (keep `.header`/`.footer`/`.content`
  markers emitted where the residual selectors still reference them). Delete
  migrated + relocated from `_sidebar.scss`; `git rm` if empty + remove `@use`.
- [ ] **Step 3: Gates.** Build clean; `sidebar-open` screenshot AE=0 if reachable
  (inject the sidebar-open event) or compiled-CSS + manual checks (details-view
  table zebra `:nth-child(odd)`, content deep-child borders, `:where(h1,h2)`
  headings, `:has(.details-view)` max-height) if unreachable; standalone-compile-diff
  residual; **harness floor 1028, 0 NEW** (touches `5_utilities.css`); kondo clean
  on sidebar.cljs.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): migrate sidebar shell to
  woco sidebar.cljs (+ _sidebar_domain: content deep-children + details-view table
  cross-plugin cascade)"`.

---

### Task 9: `_welcome_page.scss` → `_welcome_page_domain.scss` + `!important` REMOVAL (HEADLINE)

**Files:** create `styles/src/scss/components/_welcome_page_domain.scss` (slot
L11, replacing `@use 'components/welcome_page';`); `_welcome_page.scss` (delete
relocated → `git rm`). **No cljs edits** (welcome__close stays a residual
selector, not markup — see rationale). Read-only reference:
`plugins/frontend/de/explorama/frontend/ui_base/components/formular/button.cljs`
(btn-tertiary util stack), `styles/src/tailwind.css` (btn-tertiary icon-tint
remnant L299/307/328/332-336), `styles/src/scss/base/_themes.scss` (forced-colors).

**Scope:** `.welcome__page` (+ `:has(.projects-grid):not(:has(.welcome__header))`,
`.app-footer` + `--center`/`--right`/`--left`/`a`+`:hover`, `&~.toolbar-wrapper`),
`.welcome__panel` (+ `:has(…):not(…)`, `.welcome__header`+`.welcome__text`,
`.welcome__section`+`>h2`/`.actions`/`.project__list`/`.project__list--small`/
`.help__section`) → **byte-identical RESIDUAL** (cross-plugin: co-emitted by
woco/welcome + projects/overview + expdb/temp_import; `.welcome__page` is a
forced-colors marker — keep). `.welcome__close` (4 `!important`) → **deliberate
rewrite** (see Step 2).

- [ ] **Step 1: Enumerate exactly what the 4 `!important`s beat.** Read
  `button.cljs` `btn-tertiary-util-class` (rest `text-(--primary)`; hover
  `hover:enabled:text-(--text)`) and `tailwind.css` `.btn-tertiary span[class^=icon-]`
  rest (L328) + `.btn-tertiary:hover:enabled span[class^=icon-]` hover (L332-333)
  — record each value + specificity. Grep `_themes.scss` for `.welcome__close`
  (expect NONE — `.btn-tertiary` gets ButtonFace bg at L336, unaffected).
  Read `git show 1d983d1` (the batch-1 tie fix that ADDED these `!important`s) to
  confirm the intent: welcome__close is a "quieter" tertiary (rest text
  `var(--text-secondary)`, rest icon `var(--icon-secondary)`) that brightens to
  the normal tertiary hover (`var(--text)`/`var(--icon)`).
- [ ] **Step 2: Rewrite welcome__close `!important`-free via higher specificity.**
  In `_welcome_page_domain.scss`, express the two REST overrides at
  higher-than-utility specificity (order-robust — a residual sheet loads BEFORE
  `5_utilities.css`, so it must win by SPECIFICITY):
  ```scss
  .welcome__page .welcome__close {          // (0,2,0) > (0,1,0) text-(--primary)
      color: var(--text-secondary);
  }
  .welcome__page .welcome__close span[class^="icon-"] {   // (0,3,1) > (0,2,1) remnant
      order: 2;
      background-color: var(--icon-secondary);
  }
  ```
  **DROP the two `:hover` rules entirely:** button's own `hover:enabled:text-(--text)`
  ((0,3,0): 1 class + `:hover` + `:enabled`) beats the (0,2,0) rest residual on
  hover (identical intended value `var(--text)`); button's
  `.btn-tertiary:hover:enabled span[class^=icon-]` ((0,4,1): `.btn-tertiary` +
  `:hover` + `:enabled` + `[class^=icon-]` + `span`) beats the (0,3,1) rest-icon
  residual by specificity on hover (identical intended value `var(--icon)`; even
  if you count it as a tie, button loads last and still wins). **Verify BOTH
  assumptions in
  compiled CSS** (the hover values match AND the specificity/order math holds);
  if either fails for a declaration, keep a minimal higher-specificity
  non-`!important` override for that one declaration only. NO `!important`
  anywhere in the rewritten block.
- [ ] **Step 3: Relocate the rest byte-identical + wire the slot.** Create
  `_welcome_page_domain.scss` (imports `@use '../base/variables' as *;
  @use '../base/colormap' as *; @use '../base/icons' as *;` — verify against
  source head; header noting cross-plugin co-ownership + the welcome__close
  deliberate rewrite). Move `.welcome__page`/`.welcome__panel`/`.welcome__section`
  etc. VERBATIM. Add `@use 'components/welcome_page_domain';` at slot L11. `git rm
  styles/src/scss/components/_welcome_page.scss`; remove/replace its `@use` line.
- [ ] **Step 4: Gates (the headline verification).** Build clean; **welcome
  screenshot MUST be byte-identical: MD5 `c10b9a777c2dd90663189f3905b9b9d9`**
  (first capture; double-capture to rule out flake). **grep-prove `!important` is
  gone** from the welcome block (`grep -n '!important'
  styles/src/scss/components/_welcome_page_domain.scss` → the welcome__close
  block has none). Standalone-compile-diff the byte-identical `.welcome__page`/
  `.welcome__panel` families. Compiled-CSS reading: confirm `.welcome__page
  .welcome__close` (0,2,0) and its icon (0,3,1) out-specify the button utilities/
  remnant, and the hover resolves to button's own utilities. **Harness NOT
  touched** (no `5_utilities.css` change — welcome__close is residual, not
  markup); confirm harness floor 1028 unchanged as a sanity check. kondo n/a.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(woco): relocate _welcome_page
  to _welcome_page_domain residual; REMOVE 4 batch-1 !important markers via
  specificity (welcome MD5 c10b9a77 byte-identical)"`.

---

### Task 10: `_login.scss` → `_login_domain.scss` residual (verbatim)

**Files:** create `styles/src/scss/components/_login_domain.scss` (slot L5,
replacing `@use 'components/login';`); `_login.scss` (delete relocated → `git rm`).

**Scope (all RESIDUAL — `.login-logo` already deleted in Task 2):** `body.login`
(background url + `#182f3d` hardcoded, body element selector), `body.login form
label`/`.input .text-input`/`.checkbox input` (shared form markers —
`.input`/`.text-input`/`.checkbox` owned by forms_domain/ui_base, context
override), `.explorama-overlay` + `.explorama-overlay-content`/
`.explorama-overlay-close`/`h1`/`h2`/`h3`/`p`/`center`/`ul`/`ol`(+`&>li`+
`::marker` counter)/nested `ul` (cross-context content typography: `.explorama-overlay`
rendered by login + woco/copyright.cljs + woco/tools.cljs; hardcoded rem,
`color('purple-600')`, `counter()`, `::marker`).

- [ ] **Step 1: Justify residual.** Record: `body.login` = body element selector
  (not a component); `body.login form …` = shared form-marker context override;
  `.explorama-overlay` = cross-context (3 render sites) + content-DOM element
  typography + `::marker`/`counter()` — none is owner-exclusive + element-expressible.
- [ ] **Step 2: Create `_login_domain.scss`** (imports `@use '../base/variables'
  as *; @use '../base/colormap' as *;` — verify against source head; header naming
  the cross-context overlay + body/form residual), relocate the whole remaining
  `_login.scss` VERBATIM. Add `@use 'components/login_domain';` at slot L5.
  `git rm styles/src/scss/components/_login.scss`; remove/replace its `@use` line.
- [ ] **Step 3: Gates.** Build clean; `login` screenshot AE=0 (login background is
  the default initial state; the `.explorama-overlay` About page if reachable) or
  compiled-CSS confirmation for the form/overlay if the form doesn't render
  headlessly; welcome MD5 unchanged; standalone-compile-diff the relocated block;
  **harness NOT touched**; kondo n/a.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): relocate _login.scss to
  _login_domain residual (body.login, shared form markers, .explorama-overlay
  content typography); delete _login.scss"`.

---

### Task 11: `_loader.scss` → `_loader_domain.scss` residual (verbatim)

**Files:** create `styles/src/scss/components/_loader_domain.scss` (slot L19,
replacing `@use 'components/loader';`); `_loader.scss` (delete relocated → `git rm`).

**Scope (all RESIDUAL — `:nth-child` animation matrices / keyframe / descendant /
`:has()`, none static-utility-expressible):** `button .loader` (+ `span` +
`:nth-child(1..3)` animation-delay), `.loader-sm` (+ span), `.loader-md` (grid +
`:nth-child(1..4)`), `.loader-lg` (grid + `:nth-child(1..9)` +
`:nth-child(5){visibility:hidden}`), `.progress-bar` (+ `> span`),
`div:has(> [class^='loader-'])`. **Keep the forced-colors markers** (they stay
verbatim in the residual): `button .loader span`, `:where(.loader-sm,.loader-md,
.loader-lg) span`, `.progress-bar`/`>span`.

- [ ] **Step 1: Justify residual.** Record: every family is `:nth-child`
  per-child `animation-delay` (spinner-dot matrices — not expressible as static
  utility stacks), a descendant (`button .loader`), a child (`.progress-bar >
  span`), or `:has()` + attribute-prefix (`div:has(> [class^='loader-'])`).
  Confirm the `pulse`/animation keyframes referenced are defined in a base sheet
  (they stay; only `_loader.scss`'s own rules move).
- [ ] **Step 2: Create `_loader_domain.scss`** (imports `@use '../base/variables'
  as *; @use '../base/colormap' as *;` — verify against source head; header noting
  the nth-child/`:has()`/descendant rationale), relocate the whole `_loader.scss`
  VERBATIM. Add `@use 'components/loader_domain';` at slot L19. `git rm
  styles/src/scss/components/_loader.scss`; remove/replace its `@use` line.
- [ ] **Step 3: Gates.** Build clean; loaders are transient (only render while
  loading) → likely gate-invisible → compiled-CSS byte-identity + standalone-compile-diff
  is the primary gate; any reachable screen with a `.progress-bar`/loader AE=0;
  welcome MD5 unchanged; **harness NOT touched**; kondo n/a.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(woco): relocate _loader.scss to
  _loader_domain residual (nth-child spinner matrices, progress-bar, :has); delete
  _loader.scss"`.

---

### Task 12: Tie-flip sweep + batch-4 verification (NO PR)

- [ ] **Step 1: Full tie-flip sweep** (batch-1 `1d983d1` methodology, mandatory).
  For every selector this batch (a) relocated into a residual sheet
  (`_frames_domain`/`_login_domain`/`_welcome_page_domain`/`_loader_domain`/
  `_sidebar_domain`/`_dialog_domain` + any `_toolbar_domain`/`_navbar_domain`/
  `_context_menu_domain`) OR (b) migrated into `5_utilities.css` (toolbar/
  context_menu/dialog/navbar/sidebar stacks), grep ALL remaining sheets
  (`styles/src/scss/**` + `styles/src/tailwind.css`) for equal-specificity
  competitors and confirm no tie flipped. Special-cases to record: the migrated
  `.dialog`/`.dialog-header` base (now in `5_utilities.css`, last) vs the
  `_dialog_domain` message/prompt/warning `.dialog-header` variant (must still win
  by higher specificity); `.explorama__window .overlay` (`_dialog_domain`, L23)
  vs the migrated `.overlay` base utility; `_frames_domain` (L7) `.card` context
  vs the batch-1 `.card` remnant in `tailwind.css` (`.window-placement-overlay
  .card` (0,2,0) must beat the (0,1,0) card utility by specificity); the
  `.welcome__page .welcome__close` (0,2,0) vs button `text-(--primary)` (0,1,0)
  (Task 9). Record the sweep result.
- [ ] **Step 2: Suites.**
```bash
cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci 2>&1 | grep -E 'testsuites|success'   # expect 71/0
cd ../browser && npm run test-ci   # run_in_background; expect 140/0 — real result in bundles/browser/report.xml
```
- [ ] **Step 3: App-screen screenshot compares.** `chrome_capture.sh` for each
  reachable screen (`frame-toolbar`, `sidebar-open`, `dialog`, `login`) → AE=0 or
  every diff justified with pixel counts; and the welcome page → MD5
  `c10b9a777c2dd90663189f3905b9b9d9` byte-identical (the headline #16 removal must
  not regress it). Screens not headlessly reachable: state that compiled-CSS
  reading + the per-task manual-check lists are the substitute.
- [ ] **Step 4: Harness floor check.** `bash styles/scripts/harness_capture.sh
  b4-final` + `python3 styles/scripts/harness_diff.py <original-baseline> b4-final`
  → floor still **1028** (912 radius + 116 shadow), 0 NEW diffs vs `b4-baseline`
  (the batch-1 primitives must survive the Tasks 4-8 `5_utilities.css` additions).
- [ ] **Step 5: Kondo parity.** `clj-kondo --lint --cache false` the touched cljs
  files (`ui_base/components/misc/toolbar.cljs`,
  `ui_base/components/misc/context_menu.cljs`,
  `ui_base/components/frames/dialog.cljs`, `woco/page.cljs`, `woco/tools.cljs`,
  `woco/sidebar.cljs`); compare warn/err counts to a disposable `git worktree` at
  `b6f17ab` (strip `line:col`, sort, diff messages — expect zero textual diff).
- [ ] **Step 6: Audit + report.** Totals: dead families deleted (whole `_flyout.scss`
  + `_frames.scss` legacy + `.login-logo`) with line deltas; families migrated-to-markup
  per file (toolbar/context_menu/dialog/navbar/sidebar); residual sheets added
  with line counts (`_frames_domain`/`_login_domain`/`_welcome_page_domain`/
  `_loader_domain`/`_sidebar_domain`/`_dialog_domain` + any `_toolbar_domain`/
  `_navbar_domain`/`_context_menu_domain`); `@source inline` safelists added
  (expected 0 — verify); **the welcome `!important` removal confirmed** (4 removed,
  MD5 identical). #16 comment cleanup: check the batch-1 `.card`/button remnant
  blocks in `styles/src/tailwind.css` for now-dissolvable references to
  frames/sidebar markers (`.window-placement-overlay`/`.window-handling-tour`/
  `.card` contexts) and dissolve any this batch resolved; confirm each new
  `_*_domain.scss` carries a self-documenting header. Confirm which of the ten
  source sheets emptied→deleted (remove their `@use` lines) and document exactly
  what residual remains + why for any that did not. Update `CLAUDE.md` Styles
  section (one line) noting the new batch-4 `_*_domain.scss` residual sheets + the
  welcome `!important` removal. Commit any stragglers + the doc note.
- [ ] **Step 7: Commit** — `git commit -m "tailwind(woco): batch-4 verification
  (tie-flip sweep, suites, 4 chrome screens, welcome MD5, floor 1028, #16 cleanup)"`.
  **No PR** — the loop controller opens the single PR after all batches.

---

## Self-review against the batch-1/2/3 quality bar

- **Spec coverage:** all ten issue-#19 sheets are covered — `_frames.scss`
  (Tasks 2 dead, 3 residual), `_toolbar.scss` (Task 4 migrate), `_sidebar.scss`
  (Task 8 migrate+residual), `_navbar.scss` (Task 7 migrate+residual),
  `_welcome_page.scss` (Task 9 residual + `!important` removal — the headline),
  `_login.scss` (Tasks 2 dead, 10 residual), `_loader.scss` (Task 11 residual),
  `_context_menu.scss` (Task 5 migrate), `_dialog.scss` (Task 6 migrate+residual),
  `_flyout.scss` (Task 2 whole-sheet dead). Per-screen baselines (frame-toolbar,
  sidebar-open, dialog, login) are Task 1 with an explicit unreachable→compiled-CSS
  fallback (batch-2/3 precedent). The welcome MD5 floor (`c10b9a77`) gates the
  headline in Tasks 9 + 12. The primitives harness floor (1028) gates the Tasks
  4-8 `5_utilities.css` additions (Task 1 + each migrate task + Task 12). The
  tie-flip sweep is a Global Constraint + Task 12 Step 1. #16 comment cleanup
  rides along (welcome `!important` removal + new-residual-sheet headers + the
  `.card`/button remnant-block dissolution check). No PR task (loop controller).
- **Headline handled explicitly:** Task 9 dedicates Steps 1-4 to the welcome
  `!important` removal — enumerate what each `!important` beats (button tertiary
  util + icon-tint remnant, read from source + `tailwind.css` + `git show 1d983d1`),
  rewrite the two REST overrides as higher-specificity non-`!important` residuals
  (order-robust), DROP the two hover rules (button's own hover produces identical
  values), and prove byte-identity via compiled-CSS specificity math + welcome MD5.
  A per-declaration fallback is specified if any assumption fails; MD5 is the floor.
- **No placeholders:** every family names its exact emitting file(s) from grep,
  exact classes, and exact `style.scss` slot (login L5, navbar L6, frames L7,
  welcome L11, toolbar L12, sidebar L13, loader L19, flyout L20 deleted,
  context_menu L21, dialog L23). The DEAD list is grep-verified with re-verify-at-execution
  gates (whole `_flyout.scss`; the `_frames.scss` `settings__action`/`wrapper__main`
  scoped re-grep; `.login-logo`; `.window__body--scroll`). Token/arbitrary-value
  translations are concrete per task. Genuinely-uncertain dispositions
  (variant-vs-residual-sheet for `:has()`/`[class^]`, welcome hover-drop,
  screen reachability) get a deterministic procedure + evidence standard.
- **Task right-sizing:** one coherent owner-family-set per middle task,
  independently reviewable — dead sweep (deletion), frames (verbatim residual),
  toolbar / context_menu / dialog (ui_base component migrates), navbar / sidebar
  (woco migrates), welcome (headline residual + `!important` removal), login /
  loader (whole-sheet residual), verification. `_frames.scss` (largest) is split
  dead-sweep (Task 2) + verbatim relocation (Task 3). Migrate tasks (4-8) each
  touch `5_utilities.css` and carry the harness floor gate; residual tasks
  (2/3/9/10/11) do not.
- **Open risks flagged:** (1) four chrome screens' headless reachability is
  unproven — Task 1 builds `chrome_capture.sh` and falls back to compiled-CSS +
  welcome-MD5 floor (batch-2/3 precedent; login is reachable by default, navbar/
  toolbar/frame in a fresh workspace, sidebar/dialog need injected events);
  (2) the welcome `!important` removal is the headline — designed around
  specificity + MD5, with a per-declaration fallback; (3) `_frames.scss` is a
  legacy compiled sheet, ~half dead + otherwise wholly cross-plugin/dynamic →
  verbatim residual, no markup (matches batch-3's treatment of deep cross-plugin
  cascades); (4) Tasks 4-8 add to `5_utilities.css` (unlike batch 3's residual-only
  tasks) → the primitives-harness floor is a mandatory gate on each; (5) whether
  toolbar/context_menu/navbar's `:has()`/`[class^]` remainders become arbitrary
  variants or spill to a `_*_domain.scss` is decided per task (prefer variants;
  vendor `canvas` + cross-owned always residual), so `_toolbar_domain`/
  `_navbar_domain`/`_context_menu_domain` are created only if needed.
