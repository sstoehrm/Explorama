# Tailwind Phase 2 — Batch 2 (search) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the search feature area off SCSS: `_search.scss` (1136 lines), the search-owned parts of `_forms_domain.scss` (`.explorama__form__row`, `.form__message`), and the search-related rules in `_temp.scss` (the `.search__block--error` error family + the search icon/toggle/filter-overlay rules). End state: every retained search rule is either (a) a Tailwind utility stack carried in the plugin view / ui_base component that renders the markup, (b) a byte-identical remnant in a marked owner section (`styles/src/tailwind.css` or a new `_search_domain.scss` residual) when the DOM is caller-supplied / vendor / deep-descendant-cascade and not worth threading, or (c) deleted as dead. Visual result unchanged.

**Architecture:** Unlike batch 1, these are DOMAIN sheets with no single ui_base owner. Ownership is split five ways — verified by grep during this batch: (1) the search plugin views under `plugins/frontend/de/explorama/frontend/search/views/`; (2) `plugins/frontend/de/explorama/frontend/woco/frame/view/overlay/filter.cljs`, a heavy CO-OWNER that renders the search-block / sidebar / constrain-overlay markup for the filter feature; (3) the `ui_base` `misc/traffic_light.cljs` component (owns `.explorama__lights`/`.lights__status`); (4) a large DEAD fraction (blueprint `.bp4-*` — blueprint is not a dependency — plus `.search__methods`/`.search__cards`/`.search__footer`/`.search__summary`/`.dialog-table`/`.search__help` and other zero-emitter families); (5) shared markers already owned by the batch-1 remnant blocks (`.input`, `.select-input`, `.input--w*`). Because the batch-1 primitives harness does NOT render any search screen, parity for markup migrations is proven at the app level (search view screenshots) plus compiled-CSS reading; verbatim remnant relocations are proven by standalone-compile-and-diff.

**Tech Stack:** SCSS (dart-sass via `npm run sass:dist`), Tailwind v4 CSS-first (`npm run tailwind:dist`), the batch-1 render harness (`styles/scripts/harness_capture.sh` / `harness_diff.py`) for the batch-1 regression floor, headless Chromium + Python3 for app-screen capture/diff, clj-kondo.

## Global Constraints

Batch-1 calibration rules (from `.superpowers/sdd/progress.md`, section `tailwind-phase2-batch01`) — every one is binding here:

- **Utilities-in-markup is the end state.** Emitted classes are standard Tailwind; arbitrary-value syntax (`bg-(--border)`, `[justify-content:start]`, `[border:none]`) is allowed for theme CSS-vars and forms with no computed-identical Tailwind name.
- **Token table (phase-1 verified):** `size('N')` → spacing N/4 (`size('8')`→`p-2`/`gap-2`); `radius('md')`→`rounded-md` (project `@theme` overrides the radius scale — use the exact `rounded-*` token: xxs/xs/sm/md/lg/xl/xxl); `color('gray-500')`→`text-gray-500`/`bg-gray-500`; `font-size('xs')`→`text-xs`; shadows/z via the phase-1 theme tokens. `radius('full')`→literal `9999px`/`rounded-full`. `em`-based values stay `em`. Alpha colours need the exact `rgba()` literal, NOT Tailwind's `/NN` (which is an oklab color-mix). `justify/align: start/end` need the arbitrary `[justify-content:start]` form.
- **`border-none`/`outline-none` are NOT computed-identical** to `border:none`/`outline:none` (they leave border-color/custom-prop state) — use arbitrary `[border:none]`/`[outline:none]` when translating those.
- **Class knowledge stays PRIVATE** to the owning component/view (def'd stacks at the top of the ns, chip.cljs pattern) for ui_base migrations; for plugin-view markup migrations the stack goes directly on the emitting hiccup element.
- **Dynamic class names must stay statically scannable** (full class strings in code) or be safelisted via `@source inline(...)`. The search error class is built by `str` (`main_search/core.cljs` `error-classes`, `filter.cljs`) — any migrated variant must keep the full literal.
- **Descendant/parent-context rules:** prefer explicit param threading to the child component; else keep the rule as a byte-identical remnant naming its owner. **descendant-hover (`parent:hover child`) MUST translate as `group`/`group-hover:`**, never element `hover:` (trigger-region narrowing is gate-invisible).
- **Same-specificity tie-break is by generation/source order.** Relocating a rule into `5_utilities.css` (loaded last) or moving a `@use` line can flip an equal-specificity tie. Element-selector→class migrations lift specificity (0,0,1)→(0,1,0) — re-check contextual sibling overrides still win. `::before`/`::after`/`::placeholder` content and non-default states (`:hover`/`:focus`/`:disabled`/`:invalid`) are gate-invisible to `getComputedStyle` — verify by compiled-CSS reading and list as manual checks.
- **Load order (5_utilities after 3_style after 4_temp) is load-bearing.** Check it explicitly for every inline-vs-remnant tie.
- **Kept/dropped marker classes matching a Tailwind builtin** (`top`/`right`/`hidden`/`static`/`fixed`/`grid`/`table`/`block`/`inline`/`row`/`value`) may be LOAD-BEARING COMPOSITION — check the old declarations first (compiled output / `old-utilities.edn`); identical old/new = intended composition, KEEP; different/absent = translate explicitly or override with `!`. `.dialog-table .row`, `.map-input`, and `.select-input` markers are the recurrence risks here.
- **Mandatory tie-flip sweep** (batch-1 commit `1d983d1` methodology) whenever anything relocates into `5_utilities.css`: after the batch's CSS changes land, grep every remaining sheet for equal-specificity overrides of any relocated selector and confirm no tie flipped; the welcome/app screenshots are the net.

Batch-2-specific constraints:

- **Branch:** `tailwind-phase2-batches2-5` (already checked out, off `main` post-PR-#15). This is one batch of a multi-batch LOOP — **NO PR task** (the loop controller opens the single PR after all batches). One commit per family-group task.
- **Per-screen app baselines captured BEFORE the first change** (Task 1): search view normal state + error state at minimum; filter overlay best-effort. Same discipline as batch-1's welcome baseline.
- **The primitives harness still gates batch-1 components** (floor **1028** = 912 radius + 116 shadow accepted noise). Every task that relocates into `5_utilities.css` or edits a batch-1 remnant re-runs the harness and confirms 0 NEW diffs vs the batch-2 harness baseline (floor vs the original pre-batch-1 baseline stays 1028).
- **Verification is disposition-specific** (no single gate fits all): DEAD deletion → grep-proof + build-clean + app-screenshot unchanged; REMNANT relocation → standalone-compile-and-diff byte-identity + incremental screenshot; MARKUP migration → app-screenshot pixel-identity on the affected screen, or (screen not headlessly reachable) compiled-CSS reading of the new utility stack vs the old declarations + manual-check list.
- **Comment cleanup (#16) rides along:** as search markers migrate or dissolve, update the tailwind.css remnant-header comments that cite `_search`/`.search__block--error` (input remnant lists `_search` among 11 sibling sheets; select remnant documents the `.search__block--error` co-occurrence). Delete only references this batch actually resolves.
- **Residual-sheet fallback:** the entangled/deep-descendant remainder that cannot become markup goes verbatim into a new `styles/src/scss/components/_search_domain.scss`, added to `styles/src/scss/style.scss` `@use`, with a header naming why each family is deferred — exactly the `_forms_domain.scss` precedent (batch-1 Task 13). It compiles in search's current source-order slot to keep the cascade identical.

## Reference facts (from this batch's investigation — grep evidence, not assumption)

Owner map (grep over `plugins/frontend plugins/shared bundles/*/frontend`):

| Family (in `_search.scss` unless noted) | Emitting file(s) | Disposition signal |
|---|---|---|
| `.explorama__window--search` + `window__body`/`window__wrapper`/`window__wrapper__searchlist`/`list__section` chrome | `search/core.cljs`, `search/views/search_bar.cljs`, woco `filter.cljs` | search-view + woco co-owner; deep descendant → some markup, some residual |
| `.search__direct*` (two blocks: contextual + standalone L773) | `search/views/main_search/core.cljs` (free-view), `search/views/components/header_bar.cljs` | markup migration |
| `.search__resultinfo` container | `main_search/core.cljs` | markup migration (light spans → traffic_light) |
| `.explorama__lights`/`.lights__status`/`.lights--*`/`.lights__message`/`.lights__info` | ui_base `misc/traffic_light.cljs` (`parent-class`,`light-class`,`*-class` defs) | ui_base component migration |
| `.search__actions`/`.search__modules`/`.search__ready`/`.search__section` | `main_search/core.cljs`, woco `filter.cljs` | markup migration + co-owner |
| `.search__sidebar` (+ `search__sidebar__list`) | `search/views/attribute_bar.cljs`, woco `filter.cljs` | markup migration + co-owner |
| `.search__block`/`__input`/`__label`/`__actions` + `.multiple__inputs` | `main_search/core.cljs`, `search/views/components/advanced_mode_components.cljs`, woco `filter.cljs` (note woco typo `search__block_actions`) | markup migration + co-owner |
| `.explorama__search__block` (+ `.explorama__form__row` child in `_temp.scss` L479) | woco `filter.cljs` only | woco-owned markup migration |
| `.map-container`/`.map-actions`/`.map-hint`/`.map-input`/`.hint-text` | `search/views/components/location.cljs` (single owner) | clean self-contained markup migration |
| `.explorama__form__row` (`_forms_domain.scss`) | `main_search/core.cljs`, woco `filter.cljs` | markup migration + co-owner |
| `.form__message` (`_forms_domain.scss`) | `search/views/components/row_message.cljs`, `projects/views/project_card.cljs`, `ui_base/utils/css_classes.cljs` (constant) | shared marker → stays remnant/residual |
| `.search__block--error*` family (`_temp.scss` L191-230) | built by `str` in `main_search/core.cljs` `error-classes`, woco `filter.cljs` | error-state; carry co-occurrence reasoning (below) |
| `.search__block__label .attribute__toggle` (`_temp.scss` L94) | `search/views/components/...` (topic/datasource switch) | markup migration |

DEAD (zero emitters across all frontend src — verify each with the Task-2 grep before deleting):
`.bp4-input`, `.bp4-input-group`, `.bp4-datepicker`, `.explorama__form__block__container`(+`> .bp4-popover-wrapper`/`> .explorama__input__mode`) — **blueprint is not a dependency**; `.search__methods`, `.search__cards`, `.search__card`, `.search__footer`, `.search__summary`, `.search__summary__element` (the whole `.window__wrapper__search .search__main` nested block, `_search.scss` L816-949); `.dialog-table` family (L953-1034); `.search__help`(+p); `.list__categories`; `.search__resulticon` (the `span.search__resulticon` half of the grouped light selectors); `.explorama__filter--fixed`/`.explorama__filter--active`; `.search__message`(+`.icon__check`, L654-666); `.dib`; `.input__separator`; `.input__range`.

Co-occurrence reasoning to CARRY (from the select remnant header, `styles/src/tailwind.css` L774-786) — the corrected model, NOT "different blocks": `.search__block--error .input .select-input` (0,3,0) TIES select's `.input.invalid .select-input` (0,3,0) and DOES co-occur (every invalid search row has `.search__block--error` and contains a select). The tie is inert because search selects mark invalidity PER-VALUE via `:mark-invalid? true` (`search/views/components/elements.cljs:49,67`) → `.value.invalid` chips → matched by `.input:has(.multi-select-values > .value.invalid) .select-input` at (0,5,0), which beats (0,3,0) regardless of load order. The literal `.input.invalid` (0,3,0) form needs the wrapper `:invalid?` param, which NO search-block select is ever passed (the only search `:invalid?`, `search/views/toolbar.cljs:89`, is the save-query-popup input-field). Any relocation of the `.search__block--error` rules must preserve this — the error family stays a byte-identical remnant unless its markup is migrated onto the row element itself.

Infra facts:
- `styles/src/scss/style.scss` `@use` order (load-bearing): `forms_domain`(L8) < `search`(L24) < `temp`(L36, last). A new `_search_domain.scss` must occupy search's slot to preserve cascade.
- Batch-1 harness + artifacts are present in the working tree (`styles/scripts/harness_capture.sh`, `docs/superpowers/artifacts/tailwind/` — gitignored); the `.styles.json` baselines exist locally. Re-capture a batch-2 harness baseline at Task 1.
- Kondo parity reference (batch 1): 1 pre-existing error, ~922-925 warnings, byte-identical pre/post once the stateful cache is disabled. Compare touched-file counts, do not chase env drift.

## Standard gate block (every task runs the applicable subset)

```bash
cd styles && npm run sass:dist && npm run tailwind:dist        # both clean, no new warnings
# app-screen parity (screens the task's family renders):
bash styles/scripts/search_capture.sh after-<task>            # captures search-normal (+ -error if reachable)
python3 styles/scripts/search_diff.py baseline after-<task>   # AE=0, or every diff investigated & justified
# batch-1 regression floor (ANY task that relocates into 5_utilities.css or edits a batch-1 remnant):
bash styles/scripts/harness_capture.sh b2-after-<task>
python3 styles/scripts/harness_diff.py b2-baseline b2-after-<task>   # 0 NEW diffs (floor vs original still 1028)
# verbatim remnant relocations only:
#   standalone-compile the moved block pre & post, diff → byte-identical
clj-kondo --lint <touched cljs/clj files>                     # warn/err count == pre-batch baseline for those files
```
Disposition rule for every selector (applied in each task's inventory): **element-local rule on a class the owner file-set emits, and the full owner file-set is editable here → migrate to markup**; **no emitter anywhere (grep-proven) → delete**; **caller-supplied/vendor/portaled DOM, deep-descendant cascade not worth threading, or a shared marker owned elsewhere → byte-identical remnant** (tailwind.css owner section if it joins an existing family; else `_search_domain.scss`).

---

### Task 1: Per-screen baselines + inventory artifact (BEFORE any change)

**Files:** create `styles/scripts/search_capture.sh`, `styles/scripts/search_diff.py`; write inventory to `docs/superpowers/artifacts/tailwind/batch2-inventory.md` (gitignored artifact).

- [ ] **Step 1: Build the search-screen capture script.** Reuse batch-1's app build+serve (Task-14 welcome method): `bb gather-assets.bb.clj dev` in `bundles/server`, `clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core`, serve `resources/public` on a port, headless Chromium `--window-size=1400,900 --virtual-time-budget=40000`. Drive the app to the search view: try, in order, (a) a URL hash / query that auto-opens a search frame if one exists; (b) an injected `<script>` that dispatches the woco "create search frame" event after load; (c) capture the welcome landing with the search launcher visible. Produce `docs/superpowers/artifacts/tailwind/search-normal.png`. For the ERROR state, inject an invalid attribute row (so `.search__block--error` renders) or dispatch the validation event. **If neither the search view nor the error state is reachable headlessly, record that in the artifact and the plan's gate falls back to compiled-CSS reading + manual checks for those families** (per the batch-2 verification constraint). Freeze whatever capture method works; it is the reference for all batch-2 screenshot gates.

- [ ] **Step 2: Capture baselines.** `bash styles/scripts/search_capture.sh baseline` → `search-normal-baseline.png` (+ `-error-baseline.png` if reachable). Read each PNG; confirm it shows the intended screen (not blank / not an i18n-placeholder race — batch-1 Task-6 flakiness lesson: double-capture and confirm MD5-identical before trusting). Also capture the batch-2 harness baseline: `bash styles/scripts/harness_capture.sh b2-baseline` and confirm `harness_diff.py <original-baseline> b2-baseline` reports the known 1028 floor (batch-1 components unchanged on this branch).

- [ ] **Step 3: Inventory artifact.** List EVERY selector in `_search.scss`, the search-owned rules in `_forms_domain.scss` (`.explorama__form__row*`, `.form__message`), and the search rules in `_temp.scss` (L26,36,40,80,94,191-230,479-504, the constrain-overlay L506-569 that `filter.cljs` renders). Classify each per the disposition rule, recording for each: emitting file(s) (from `grep -rn <class> plugins/frontend plugins/shared bundles/*/frontend`), specificity, state-only? (gate-invisible), and target disposition (migrate→file / delete / remnant→section). This artifact drives Tasks 2-8; a family's disposition may only change during its task with recorded evidence.

- [ ] **Step 4: Commit** the two scripts only (artifacts are gitignored): `git commit -m "tailwind(search): baseline capture scripts + inventory"`.

---

### Task 2: Dead-rule sweep (`_search.scss`, deletion only)

**Files:** `styles/src/scss/components/_search.scss`.

- [ ] **Step 1: Prove dead.** For each candidate family in the Reference-facts DEAD list, run `grep -rn -- "<class>" plugins/frontend plugins/shared bundles/browser/frontend bundles/electron/frontend bundles/server/frontend` AND `grep -rn "<class>" styles/src/scss` (catch `@extend`/contextual references in sibling sheets). Zero markup emitters AND no live cross-sheet dependency = delete. Record each family's grep verdict in the inventory. Blueprint families are dead because blueprint (`bp4-*`) is not in any `package.json`.
- [ ] **Step 2: Split grouped selectors before deleting.** For grouped rules where only one selector is dead (e.g. `.search__resultinfo span.search__resulticon.lights--green, ... span.lights__status.lights--green`), delete ONLY the dead `search__resulticon` half; the `span.lights__status` half is live (Task 3). Do not delete a whole group when a co-selector is live.
- [ ] **Step 3: Delete** the proven-dead families/selectors from `_search.scss`.
- [ ] **Step 4: Gates.** sass+tailwind build clean; `search_capture.sh` + diff vs baseline = AE=0 (nothing rendered used these); harness untouched (no `5_utilities.css` change — skip harness or confirm 0). kondo n/a (no cljs). Report deleted-family count + compiled-line delta.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(search): delete dead _search.scss families (blueprint, search__methods/cards/footer, dialog-table, ...)"` with the grep-verdict table in the body.

---

### Task 3: Traffic-light family → `ui_base/components/misc/traffic_light.cljs`

**Files:** `plugins/frontend/de/explorama/frontend/ui_base/components/misc/traffic_light.cljs`; `_search.scss` (delete migrated rules).

**Scope:** `.explorama__lights` container (L717-721) + `.lights__status` base + `.lights--red/yellow/green/grey` bg-image SVGs (L724-753) + `.lights__message` (L756) + `.lights__info` icon (L763-768), which the component renders via its `parent-class`/`light-class`/`*-class` defs. Plus the live `.search__resultinfo span.lights__status.lights--*` variants (L266-297) — SAME visual, different parent-context size (20×20 vs 30×25).

- [ ] **Step 1: Inventory** the two contexts. The bg-image + `.lights__status` display/size(default) go onto the component's `light-class`/`color-class` stacks (private defs at top, keep the literal `lights__status`/`lights--*` markers emitted for sibling-sheet selectors per calibration rule 6). The SVG data-URIs become arbitrary `bg-[url(...)]` utilities (or stay as a small remnant if the URL breaks Tailwind's parser — decide by build).
- [ ] **Step 2: Parent-context size divergence.** `.search__resultinfo` sizes the light 20×20 but `.explorama__lights` sizes it 30×25. The component cannot know its parent-class size — thread it (a `:size`/`:light-class` param, or size on the wrapper the search view controls) OR keep the `.search__resultinfo span.lights__status` size override as a search-view remnant (Task 5) while the color bg moves to the component. Pick the smaller-diff option; record which.
- [ ] **Step 3: Translate** into `traffic_light.cljs` stacks; delete the migrated base rules from `_search.scss`.
- [ ] **Step 4: Gates.** Build clean; app screenshot (search result-info shows a light) AE=0 or justified; **traffic_light is NOT in the frozen harness catalog** → state variants + the `.lights__info` tooltip icon verified by compiled-CSS reading (list as manual checks); harness diff 0 if anything touched `5_utilities.css`; kondo clean on `traffic_light.cljs`.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(search): migrate traffic-light family to ui_base misc/traffic_light"`.

---

### Task 4: Map integration family → `search/views/components/location.cljs`

**Files:** `plugins/frontend/de/explorama/frontend/search/views/components/location.cljs`; `_search.scss` (delete L1039-1137).

**Scope:** `.map-container`/`.map-actions`/`.map-hint` (L1039-1070) and `.map-input`/`.hint-text`/`.ol-zoom` (L1072-1137). Single clean owner, self-contained, no cross-plugin sharing.

- [ ] **Step 1: Inventory** `location.cljs`'s hiccup; map each rule to its element. Note the risks: `.map-input` is a bare marker (not a Tailwind builtin, safe); the `&::before` overlay and `&:hover .hint-text` (parent-hover→child) rules are gate-invisible/hover — the `:hover .hint-text` MUST become `group`/`group-hover:` (calibration). `.map-input.unselected` state is app-driven → conditional class map.
- [ ] **Step 2: Translate** to stacks on `location.cljs` elements; delete the block from `_search.scss`. `::before` pseudo + `.ol-zoom{display:none}` (OpenLayers vendor DOM) that cannot be expressed as element utilities → small remnant in `_search_domain.scss` or a `[&::before]:`/`[&_.ol-zoom]:` arbitrary variant if the build accepts it.
- [ ] **Step 3: Gates.** Build clean; app screenshot of the map picker (location step) AE=0 or justified — if the map step is not headlessly reachable, compiled-CSS reading substitutes (record); hover/`::before`/`.unselected` states as manual checks; harness 0 if `5_utilities.css` touched; kondo clean.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(search): migrate map-integration family to search/views/components/location"`.

---

### Task 5: Search-view chrome & layout families → search plugin views

**Files:** `search/views/main_search/core.cljs`, `search/views/search_bar.cljs`, `search/views/attribute_bar.cljs`, `search/views/components/header_bar.cljs`; `_search.scss` (delete migrated), `_search_domain.scss` (create; deep-descendant residual).

**Scope (search-plugin-owned, NOT the woco-shared block families — those are Task 6):** window chrome `.explorama__window--search`/`.explorama__window__wrapper`/`.explorama__window__search-main` (L7-40); the `.window__wrapper__searchlist .list__section` list tree (L43-143, `search_bar.cljs`); `.search__direct*` both blocks (L159-211 + standalone L773-806, `main_search/core.cljs` free-view + `header_bar.cljs`); `.search__resultinfo` container + `span.info`/`span.icon__info-circle` (L236-297, minus dead resulticon); `.search__actions`/`.actions-left`/`.actions-right`/tool-order `li` (L311-395); `.search__ready` icons (L389-418); `.search__modules` (L426-450); the `.search__section`/`.search__sidebar`/`.search__main` heading rules (L456-484); `.search__sidebar` sidebar tree (L488-550, `attribute_bar.cljs`).

- [ ] **Step 1: Inventory & split.** For each rule decide migrate-vs-residual by the disposition rule. Element-local rules on divs these files render → markup stacks. DEEP descendant rules that reach list/`ul`/`li`/`h1`/`h2` DOM or caller-supplied children where threading is disproportionate (e.g. `.window__wrapper__searchlist .list__section ul li:hover`, the tool-order `li.tool__*{order}` set) → verbatim into `_search_domain.scss`. `li:hover` background is a real hover → `hover:` variant on the `li` if the `li` is emitted here, else residual. Watch `.search__resultinfo span.lights__status` size (coordinate with Task 3's decision).
- [ ] **Step 2: Translate** the migratable rules onto the emitting hiccup in the four view files; relocate the residual set verbatim into `_search_domain.scss` (create it, add `@use 'components/search_domain';` to `style.scss` in search's slot, header explaining each deferred family). Delete migrated + relocated rules from `_search.scss`.
- [ ] **Step 3: Gates.** Build clean; **standalone-compile-diff** the residual block (byte-identical to its old `_search.scss` form); app screenshot search-normal AE=0 or justified; hover/`:first-of-type` states as compiled-CSS manual checks; harness diff 0 NEW vs b2-baseline (relocations into `_search_domain.scss` compile in-order, no `5_utilities.css` change unless a stack introduces one); kondo clean on the four files.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(search): migrate search-view chrome to search plugin views (+ _search_domain residual)"`.

---

### Task 6: Search-block families shared with the woco filter overlay

**Files:** `search/views/main_search/core.cljs`, `search/views/components/advanced_mode_components.cljs`, `plugins/frontend/de/explorama/frontend/woco/frame/view/overlay/filter.cljs`; `_search.scss` / `_temp.scss` (delete migrated); `_search_domain.scss` (residual).

**Scope:** `.explorama__search__block` + `.explorama__search__block__actions` + `.explorama__filter--*` (L553-580; woco-owned) and the `.search__block`/`.search__block__input`/`.search__block__label`/`.search__block__actions` + `.multiple__inputs`/`.input--w100` family (L588-651), rendered by BOTH `main_search/core.cljs` (`search-attribute-row`) and `filter.cljs` (note woco's typo class `search__block_actions`). Plus `_temp.scss` L479-504 `.explorama__search__block .explorama__form__row` (filter overlay) and L94-101 `.search__block__label .attribute__toggle`.

- [ ] **Step 1: Inventory both emitters.** Confirm the div structure matches between `main_search/core.cljs` and `filter.cljs`; migrate the class stack onto BOTH (disjoint files, same visual). Flag the woco `search__block_actions` single-underscore typo — decide keep-as-is (match current) vs normalise (only if a rule targets it). `.input--w100` is a `_forms_domain` anchor (115 sites) — the `.multiple__inputs .input--w100` override (L634) is a search-context override of a shared marker → residual, not markup. The `> div:has(.input + span[class^="icon-"] + .input)` rule (L629) reaches ui_base input DOM → residual.
- [ ] **Step 2: Translate** migratable stacks onto both files' block markup; relocate the shared-marker/`:has()` overrides verbatim to `_search_domain.scss`. `.attribute__toggle` (link-styled hover) → stack on the `topic-datasource-switch` element (`search-selection-component`) with `hover:underline`. Delete migrated rules from `_search.scss`/`_temp.scss`.
- [ ] **Step 3: Gates.** Build clean; standalone-compile-diff residuals; app screenshots search-normal AND search-error (the block renders in both; error adds `search__block--error` — but that family is Task 7) AE=0 or justified; harness 0 NEW; kondo clean on all three files.
- [ ] **Step 4: Commit** — `git commit -m "tailwind(search): migrate search-block families (search view + woco filter overlay)"`.

---

### Task 7: `_temp.scss` error-state family + remaining search rules

**Files:** `styles/src/scss/components/_temp.scss`; possibly `styles/src/tailwind.css` (select owner section) / `_search_domain.scss`.

**Scope:** the `.search__block--error .input {.text-input,.select-input,.input-hint}` family + `.search__block--error{color}` (L191-230); the search icon-tint rules (`.search__direct button span[class^="icon-"]` L26; `.explorama__search__bar .input__clearable ...` L36-42); `.search__modules/.settings__actions/.indicator__actions .btn-group .btn-toggled` radius (L80-84); the constrain-overlay family `.constrainview__overlay` (L506-569, rendered by `filter.cljs`); the filter-overlay `.explorama__search__block` block (L479-504 — coordinate with Task 6).

- [ ] **Step 1: Classify.** The `.search__block--error .input .select-input` / `.text-input` rules select DOM rendered by ui_base input/select components (shared `.input` marker) under a search-row wrapper — they are the SAME shared-marker family as the batch-1 input/select remnants and are NOT expressible as component markup. Keep them as byte-identical remnants; the target section is the **select owner block in `styles/src/tailwind.css`** (they belong to the select-context error family already documented there) OR `_search_domain.scss` — choose by which keeps the co-occurrence tie provably inert. **Carry the corrected co-occurrence reasoning** (Reference facts) into the relocation comment; do NOT re-introduce the "different blocks" model.
- [ ] **Step 2: Tie audit (mandatory).** Because relocating the error rules changes their source order relative to `_select`'s remnant and `_temp`, re-derive the `.search__block--error .input .select-input` (0,3,0) vs `.input.invalid .select-input` (0,3,0) tie: confirm the (0,5,0) `:has(.value.invalid)` rule still wins and that no search-block select is passed `:invalid?`. Record the derivation. The icon-tint (`span[class^="icon-"]`) and `.btn-group .btn-toggled` radius rules target icon/button-group DOM → remnant (they join the existing button/icon remnant family reasoning). The `.constrainview__overlay` family is `filter.cljs`-owned overlay chrome → migrate to markup in `filter.cljs` if element-local, else residual.
- [ ] **Step 3: Relocate/migrate**; delete from `_temp.scss`.
- [ ] **Step 4: Gates.** Build clean; standalone-compile-diff every relocated block; app screenshot search-ERROR AE=0 or justified (this is the error-state screen — if unreachable headlessly, compiled-CSS reading of the error family is the substitute, and the tie derivation is the primary evidence); **tie-flip sweep**: grep all sheets for equal-specificity overrides of every relocated selector; harness 0 NEW; report the tie derivation.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(search): relocate _temp search error-state family + search rules (co-occurrence tie preserved)"`.

---

### Task 8: `_forms_domain.scss` search parts + remnant-dissolution check

**Files:** `styles/src/scss/components/_forms_domain.scss`; `styles/src/tailwind.css` (comment cleanup); `main_search/core.cljs` / `filter.cljs` if `.explorama__form__row` migrates.

**Scope:** `.explorama__form__row` + its `label`/`div[class^='col-']`/`p` rules (`_forms_domain.scss` L207-303), rendered by `main_search/core.cljs` (L108) and `filter.cljs`; `.form__message` (L125-131), rendered by `row_message.cljs` via the `ui_base/utils/css_classes.cljs` constant + `projects/project_card.cljs`.

- [ ] **Step 1: `.explorama__form__row`.** It is a `_forms_domain` ANCHOR (12 sites per that sheet's header) but the search/woco uses are the two live emitters found. If ALL live emitters are search/woco (grep to confirm no other plugin renders `.explorama__form__row` outside the `.settings__section`/`.explorama__search__block` contexts) → migrate the base + `label`/`col`/`p` rules onto the two view elements. If other-plugin emitters remain → leave in `_forms_domain.scss` and record why (it is not a search-exclusive family; out of this batch). The `div[class^='col-']` descendant → residual/leave (col-* is the legacy grid, `_temp.scss`-owned).
- [ ] **Step 2: `.form__message`.** SHARED marker (projects + search + a ui_base css_classes constant) with no single owner → stays in `_forms_domain.scss`. Do NOT migrate; record as deferred-shared.
- [ ] **Step 3: Remnant-dissolution + comment cleanup (#16).** Re-read the tailwind.css input remnant header (lists `_search` among 11 sibling sheets, L508) and select remnant header (the `.search__block--error` co-occurrence, L774-786). For each search reference: if this batch's migrations removed the dependency (e.g. `_search` no longer contextually selects `.input`/`.select-input`), delete/trim that reference; if it still holds (shared markers still emitted by ui_base components used inside search), keep it and update the count. Evidence: grep the migrated sheets for the marker; the header text must match the post-batch reality. `.select-value-tagged` is legend-owned (not search) — confirm it is untouched.
- [ ] **Step 4: Gates.** Build clean; standalone-compile-diff any relocation; app screenshots normal+error AE=0 or justified; harness 0 NEW; kondo clean on any touched cljs.
- [ ] **Step 5: Commit** — `git commit -m "tailwind(search): migrate _forms_domain search parts + dissolve resolved remnant comments"`.

---

### Task 9: Tie-flip sweep + batch-2 verification

- [ ] **Step 1: Full tie-flip sweep** (batch-1 `1d983d1` methodology, mandatory). For every selector this batch relocated into `5_utilities.css`, `_search_domain.scss`, or a moved `@use` slot, grep ALL remaining sheets (`styles/src/scss/**` + `styles/src/tailwind.css`) for equal-specificity overrides; confirm no tie flipped winner. Special-case the `.search__block--error`/`.input.invalid` (0,3,0) tie from Task 7. Record the sweep result.
- [ ] **Step 2: Suites.**
```bash
cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci 2>&1 | grep -E 'testsuites|success'   # expect 71/0
cd ../browser && npm run test-ci   # run_in_background; expect 140/0 — real result in bundles/browser/report.xml
```
- [ ] **Step 3: App-screen screenshot compares.** `search_capture.sh` for search-normal (+ error if reachable) and the welcome page (batch-1 baseline `baseline-welcome-reliable.png` must still be MD5-identical — batch 2 must not regress the tie that batch-1 Task-14 fixed). Every diff AE=0 or justified with pixel counts. Screens not headlessly reachable: state that compiled-CSS reading + the per-task manual-check lists are the substitute.
- [ ] **Step 4: Harness floor check.** `harness_capture.sh b2-final` + `harness_diff.py <original-baseline> b2-final` → floor still **1028**, 0 NEW diffs (batch-1 primitives untouched by batch 2).
- [ ] **Step 5: Kondo parity.** `clj-kondo --lint` the touched cljs/clj files; compare warn/err counts to the pre-batch baseline for those files (disable the stateful cache to avoid env drift, per batch-1 Task-6).
- [ ] **Step 6: Audit + report.** Totals: sheets/families deleted (dead), lines removed, families migrated-to-markup (per file), remnants added (`_search_domain.scss` line count + tailwind.css owner-section deltas), `@source inline` safelists added, comment references dissolved. Confirm `_search.scss` is empty→deleted (remove its `@use` line in `style.scss`) OR document exactly what remains and why. Update `CLAUDE.md` Styles section if `_search_domain.scss` or new safelists were added (one line). Commit any stragglers + the doc note.
- [ ] **Step 7: Commit** — `git commit -m "tailwind(search): batch-2 verification (tie-flip sweep, suites, screenshots, floor 1028)"`. **No PR** — the loop controller opens the single PR after all batches.

---

## Self-review against the batch-1 quality bar

- **Spec coverage:** all three issue-#17 sheets are covered — `_search.scss` (Tasks 2-6), `_forms_domain.scss` search parts (Task 8), `_temp.scss` `.search__block--error` + search rules (Task 7). The corrected co-occurrence reasoning is carried verbatim (Reference facts + Task 7). Per-screen baselines (normal + error) are Task 1; the harness floor (1028) gates batch-1 regressions (every task + Task 9); the tie-flip sweep is a Global Constraint + Task 9 Step 1; comment cleanup #16 is Task 8 Step 3; remnant dissolution is Task 8 Step 3. No PR task (loop controller). Commit convention `tailwind(search): ...` mirrors batch-1's `tailwind: migrate <family> to <owner>`.
- **No placeholders:** every family names its exact emitting file(s) from grep, exact classes, exact SCSS line ranges, and exact `style.scss` slot. The DEAD list is grep-verified (blueprint proven not-a-dependency). Genuinely-uncertain dispositions (migrate vs residual, parent-context size, screen reachability) get a deterministic procedure + evidence standard, not a guess — matching batch-1's "per-task specifics" style.
- **Task right-sizing:** one owner file-set per middle task, independently reviewable: dead-sweep (deletion), traffic_light (ui_base), location (single plugin file), search-view chrome (4 search views), search-block (search+woco co-owners), `_temp` error family (relocations), `_forms_domain` (shared markers). `main_search/core.cljs`/`filter.cljs` recur across tasks but in disjoint div regions (flagged). Honest about the batch-1 lesson: entangled/shared/deep-descendant families become `_search_domain.scss` residual (the `_forms_domain` precedent), not forced markup.
- **Open risks flagged in the plan:** (1) headless reachability of the search view / error state is unproven — Task 1 builds the capture and falls back to compiled-CSS if unreachable; (2) `woco/filter.cljs` is a heavy co-owner, so "search" migrations edit woco too, with a class-name typo (`search__block_actions`) to preserve; (3) `traffic_light.cljs` is a ui_base component outside the frozen harness catalog → verified by app-screenshot + compiled-CSS, not the harness; (4) the `.search__block--error` (0,3,0) tie is inert only via the (0,5,0) `:has(.value.invalid)` rule — relocation must preserve source order and the derivation; (5) shared markers (`.input`/`.input--w*`/`.form__message`) are owned by batch-1 remnants / other plugins and are deferred, so `_search.scss` may not reach fully-empty — the plan requires documenting exactly what residual remains.
