# react-virtualized to @tanstack/react-virtual Migration Design

Date: 2026-07-31

## Goal

Replace `react-virtualized@9.22.6` with `@tanstack/react-virtual@3` across the
three files that import it, leaving no `react-virtualized` reference in source,
styles, or the three bundle manifests.

## Why

`react-virtualized` last saw real work in 2023; 9.22.6 (Jan 2025) was a patch.
Its author directs new work to `react-window`. It is 2.24 MB unpacked and pulls
six runtime dependencies (`clsx`, `prop-types`, `dom-helpers`, `loose-envify`,
`@babel/runtime`, `react-lifecycles-compat`). `@tanstack/react-virtual` is
56 KB with a single dependency (`@tanstack/virtual-core`), actively released,
and its peer range covers React 16.8 through 19 — the repo is on React 18.3.1.

`react-window` v2 was the other serious candidate and is closer to a drop-in for
the table's 2D grid. It was not chosen: its scroll control is imperative-only,
which is a worse fit than owning the scroll container outright, and its row
renderers are components rather than callbacks, which would have forced
`r/reactify-component` at every call site.

Nothing is broken today. `react-virtualized` declares React 18/19 support and
works. This is dependency hygiene, not a bug fix.

## Current usage

Three files import the package directly:

| File | Imports | Features |
|---|---|---|
| `plugins/frontend/de/explorama/frontend/ui_base/components/common/virtualized_list.cljs` | `AutoSizer`, `List`, `CellMeasurer`, `CellMeasurerCache` | fixed + dynamic row heights, `scrollToIndex`, `noRowsRenderer` |
| `plugins/frontend/de/explorama/frontend/ui_base/components/formular/select.cljs:5` | `AutoSizer`, `List` | fixed rows, `scrollToIndex`, `disableHeight` |
| `plugins/frontend/de/explorama/frontend/table/table/view.cljs:2` | `Grid` | two 2D grids, controlled `scrollLeft`/`scrollTop`, `onScroll`, `scrollToRow` |

`virtualized-list` has two real consumers: `collapsible_list.cljs:172` and
`projects/protocol/core.cljs:478`. The third apparent consumer,
`configuration/project/post_processing_dialog.cljs:13`, requires the symbol but
never calls it — a dead require, removed as part of commit 1.

Non-obvious couplings:

- `select.cljs:244-246` whitelists three `ReactVirtualized__*` class names for
  `elementFromPoint` hit-testing.
- `styles/src/css/components/snapshots_domain.css:38` selects on
  `.ReactVirtualized__Grid__innerScrollContainer`.
- `table/view.cljs:43` uses `overflow-hidden!` specifically to beat
  react-virtualized's inline `overflow: auto`.

## Decisions

### `virtualized-list` keeps its public API byte-identical

`parameter-definition`, the derived malli spec, the `^:export`, and the
`tools/ui-base-overview` entry are unchanged. The `:row-renderer` signature
`(fn [key index style row])` stays.

This is affordable because every consumer applies the `style` argument verbatim
to its own outermost div (`collapsible_list.cljs:78,101`,
`protocol/core.cljs:489`, `select.cljs:687`). react-virtualized passes
`{position, top, left, width, height}`; the tanstack equivalent is the same
shape plus `transform: translateY(...)`. Fixed-height rows therefore need no
consumer changes at all, which is what makes the e2e suite a meaningful check —
a red run means the swap, not a moved API.

### Integration via `:f>` and `useVirtualizer`

Each component becomes an `:f>` function component internally, calling
tanstack's public `useVirtualizer` hook. The alternative — driving the
`@tanstack/virtual-core` `Virtualizer` class from `r/create-class` — fits the
codebase's dominant idiom better but depends on `_didMount`/`_willUpdate`, the
framework-adapter surface rather than the app-author API, and carries upgrade
risk tanstack does not promise against.

Reagent 1.3.0's `:f>` wraps render in `ratom/run-in-reaction`
(`reagent/impl/component.cljs:443-451`), so ratom and cursor derefs are tracked
inside function components. This matters: `select.cljs`'s `list-menu` derefs
`@(:select-idx acc-state)` and `@(:last-key acc-state)`, and the table view
derefs `frame-scroll-x`/`frame-scroll-y` cursors, all mid-render.
`woco/core.cljs:198` already renders the entire app through `:f>`.

### `:full-width?` / `:full-height?` via ResizeObserver

`AutoSizer` measured the parent and handed down pixel dimensions. The idiomatic
tanstack equivalent, `height: 100%` on the scroll div, only resolves when every
ancestor has a definite height. `protocol`'s parent does;
`collapsible_list`'s mount points were not all traced.

A small helper observes the parent element and reports pixel dimensions,
reproducing `AutoSizer` exactly. Parity is guaranteed regardless of ancestor
CSS, at the cost of roughly fifteen lines.

Both `virtualized-list` and `select` need it, so it lives in a new
`ui_base/utils/resize.cljs` alongside the existing `subs.cljs` / `timeout.cljs`
utilities rather than being private to either component. The table does not use
it — the table view already receives its dimensions from `infos-sub`
(`view.cljs:419`).

### Positioning maths is extracted and unit-tested

The `style` map handed to `:row-renderer` is the contract this migration
promises not to break, and it is pure arithmetic. It moves into
`ui_base/utils/virtual.cljs` (`row-style`, `cell-style`, `sizer-style`), shared
by all three components and tested in `plugins/frontend_test/`.

This matters because the 73 existing frontend test namespaces are all
pure-logic; nothing in the suites renders a component. Extracting the maths is
what makes any part of this migration unit-testable at all. Everything else is
covered by e2e or by manual verification.

Row offsets use `top`, not `transform`. tanstack's examples use
`transform: translateY(...)`, but react-virtualized emitted `top`, and consumer
CSS has been written against that for years.

### Table scroll keeps its re-frame round-trip

`scroll-x`/`scroll-y` are in `ws-api/logging-keys`
(`plugins/shared/de/explorama/shared/table/ws_api.cljc:42-44`), so they persist
into project vis-state. The body's `onScroll` continues to call `set-config-fn`
exactly as today; an effect pushes the re-frame values back onto the scroll
elements.

The DOM-direct alternative (sync `header.scrollLeft` from `body.scrollLeft`
without a render, persisting only on the existing `delayed-log-fn` timer) would
remove a re-frame round-trip per scroll frame and is measurably smoother. It is
deliberately out of scope: it changes when `logging-keys` are written, so it
needs its own verification of project save/restore, and mixing it into a library
swap would make any regression ambiguous. It remains available as a follow-up.

## Commit 1 — `virtualized-list` internals

Structure becomes a scroll container plus a sizer div:

```clojure
[:div {:ref el-ref :class extra-class :style {...overflow auto, w/h}}
 [:div {:style {:height (.getTotalSize v) :width "100%" :position "relative"}}
  ...rows]]
```

- Fixed rows: `row-renderer` receives
  `{:position "absolute" :top 0 :left 0 :width "100%" :height (.-size vi)
    :transform (str "translateY(" (.-start vi) "px)")}`.
- Dynamic rows: a wrapper div carries the transform, `data-index`, and
  `:ref (.-measureElement v)`; `row-renderer` receives `style` as `{}`. This
  replaces `CellMeasurer`, `CellMeasurerCache`, `autosize-row`, and
  `default-dynamic-row-renderer` (`virtualized_list.cljs:54-75`).
- `:scroll-to-index` becomes a `scrollToIndex` effect keyed on the index.
- `:overscan-row-count` maps to `:overscan`.
- `:no-rows-renderer` becomes a branch on `row-count` being zero.
- `:list-extra-style` applies to the scroll container; `:parent-extra-style`
  applies to the outer wrapper, preserving its current
  "only when `:full-width?` or `:full-height?`" condition.

Consumer changes in this commit:

- `protocol/core.cljs:489` — `(assoc style :overflow "auto")` becomes
  `{:overflow "auto"}`, since the wrapper now owns positioning. This is the
  only `virtualized-list` consumer line that changes in the whole migration;
  `collapsible_list.cljs` is untouched.
- `post_processing_dialog.cljs:13` — dead require removed.
- `snapshots_domain.css:38` — rewritten against the new DOM, with the
  explanatory comment at lines 14-15 updated to match.

## Commit 2 — `select.cljs`

- `AutoSizer {:disableHeight true}` (line 802) is replaced by the
  `ui_base/utils/resize.cljs` helper, width only.
- `list-menu` becomes an `:f>` component around `useVirtualizer`.
- `:scrollToIndex select-idx` becomes a `scrollToIndex` effect.
- `react-virt-grid-class`, `react-virt-list-class`, and
  `react-virt-innerscroll-class` (lines 244-246) are replaced by marker classes
  we emit ourselves on the scroll container and sizer div.
  `in-list-check-classes` drops from seven entries to six, because
  react-virtualized's `List` rendered three nested elements where the
  replacement renders two. The `elementFromPoint` mechanism at lines 840-858 is
  otherwise untouched.

Replacing that whitelist with `(.closest elem ".select-option-list")` would
express the intent better, but the menu renders through
`react-dom/createPortal` into `document.body`, so DOM ancestry differs from
React ancestry. That is a separate behavioural change and is out of scope.

## Commit 3 — `table/view.cljs`

- Header: a horizontal-only virtualizer. `rowCount` is 1, so nothing vertical
  is virtualized.
- Body: two virtualizers, vertical for rows and horizontal for columns. Cells
  are positioned by `translateX(col.start) translateY(row.start)`.
- Both use `config/column-width`. Today the header uses `config/column-width`
  (`view.cljs:150`) while the body hardcodes `120` (`view.cljs:229`). The values
  are currently identical, so there is no live bug, but the divergence is
  exactly what produces header/body misalignment. Both grids are being
  rewritten, so this is fixed here.
- `onScroll` calls `set-config-fn` for `scroll-x`/`scroll-y` as today. An effect
  writes those values back to the header and body elements, guarded on
  `(not= el.scrollLeft scroll-x)` so the write cannot re-trigger its own scroll
  event. The existing handlers at lines 235 and 238 already have that shape.
- `focus-row-idx` becomes a `scrollToIndex` effect, keeping the non-nil guard
  and the comment at line 256 that explains why it must be conditional.
- `table-header-scrollable-parent-class` drops from `overflow-hidden!` to
  `overflow-hidden`.
- The `fill-header?` extra-column logic (lines 133-147) is unchanged.

## Commit 4 — dependency removal

`react-virtualized` is dropped from `bundles/browser/package.json:38`,
`bundles/electron/frontend/package.json:38`, and
`bundles/server/package.json:36`, with the three lockfiles regenerated.
`@tanstack/react-virtual` is added to all three.

The `tools/ui-base-overview` docstring at
`common/virtualized_list.cljs:9`, which names react-virtualized, is updated.

## Testing

Per commit: the browser, electron-frontend, and server-frontend suites (all
three compile `plugins/`), plus `clj-kondo` compared against the baseline of
2 errors and ~1087 warnings. Actual numbers get reported, not asserted.

`report.xml` is read per-suite rather than from the top-level summary, and its
mtime checked against the clock, per the traps documented in CLAUDE.md.

e2e runs after commit 3, against a fresh `bundles/browser/build.sh`.

### New e2e coverage

Existing table specs assert only rendered content: `core_data_journey`
("connecting a search to a table renders the data") and `fact_units` ("a unit
assigned at import reaches the table header"). Nothing scrolls a table.

Header/body column alignment under horizontal scroll is the specific behaviour
commit 3 risks, and it is unobserved. A spec is added that scrolls a table
horizontally and asserts the header column stays aligned with its body column.
This follows `8e4406a`, which shipped an e2e spec for drag reordering alongside
the `react-beautiful-dnd` swap.

## Risks

- **Dynamic-height measurement in `protocol`.** The measured wrapper has no
  fixed height and the inner div keeps `overflow: auto`. If the inner div
  clips rather than sizing to content, rows collapse. Verified visually against
  a project with protocol steps.
- **Scroll feedback loop in the table.** Mitigated by the equality guard, but
  it is the failure mode to watch if scrolling stutters.
- **`select` hit-testing.** The `elementFromPoint` workaround is timing
  -dependent (a 100 ms `setTimeout`) and has no automated coverage. Verified
  manually by clicking the last option in a long dropdown, the case the
  workaround exists for.
