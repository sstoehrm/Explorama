# Tooltip on Floating UI Design

Date: 2026-07-30

## Goal

Replace the `react-tooltip-lite` npm dependency with a Reagent tooltip built on
`@floating-ui/dom`, keeping the public `ui_base` tooltip API and its rendered
appearance, and remove the `legacy-peer-deps=true` workaround from all three
bundles.

## Why

`react-tooltip-lite@1.12.0` was published 2020-05-13 and has had no release
since. Its peer range is `react@^15.5.4 || ^16.0.0` (registry and tarball; the
browser lockfile records a divergent `|| ^17.0.0`, which is where npm's error
text comes from). Either way it excludes React 18.

It is the **only** package in the browser bundle whose peer range excludes
React 18. Every other React consumer already accepts 18:

| package | peer react range |
| --- | --- |
| react-beautiful-dnd 13.1.1 | `^16.8.5 \|\| ^17 \|\| ^18` |
| react-virtualized 9.22.6 | `^16.3 \|\| ^17 \|\| ^18 \|\| ^19` |
| re-resizable 6.10.1 | `^16.13.1 \|\| ^17 \|\| ^18` |
| react-window 1.8.10 | `^15 \|\| ^16 \|\| ^17 \|\| ^18` |
| rc-slider 9.7.5 | `>=16.9.0` |
| react-toastify 9.1.3 | `>=16` |
| react-d3-cloud 1.0.6 | `^16.8 \|\| ^17 \|\| ^18` |
| react-number-format 5.4.2 | `^0.14 \|\| ^15 \|\| ^16 \|\| ^17 \|\| ^18` |

Commit `e492d7b` ("deps(react18): browser bundle") states the coupling
directly: *".npmrc legacy-peer-deps covers react-tooltip-lite's stale peer
range."* The flag is set in `bundles/browser/.npmrc`,
`bundles/electron/frontend/.npmrc` and `bundles/server/.npmrc`, which suppresses
peer checking for every package in those trees, not just this one.

`npm install --dry-run --no-legacy-peer-deps` in `bundles/browser` currently
fails and names react-tooltip-lite as the sole conflict. That command is the
acceptance test for removing the flag.

The library is not broken at runtime on React 18: `Portal.js` gates on
`typeof ReactDOM.createPortal === 'function'`, so its
`unstable_renderSubtreeIntoContainer` / `unmountComponentAtNode` path is dead
code. Those are React-19-removed APIs, making this a React 19 liability rather
than a present-day failure.

## Scope

In scope: the component, its own popup CSS, the dependency in three bundles, and
the stale `cljsjs/react-tooltip-lite` entry in `tools/ui-base-overview`.

Out of scope: the `.tooltip-wrapper` structural selectors in
`styles/src/css/components/temp_domain.css` (7 tool variants),
`projects_domain.css`, `dashboards_domain.css` and the input-group rules at
`styles/src/tailwind.css:713-734`. The replacement keeps emitting
`.tooltip-wrapper` on the trigger, so those sheets are untouched.

Also out of scope: tooltip accessibility semantics. See "Accessibility" below.

## Module boundaries

### New: `plugins/frontend/de/explorama/frontend/ui_base/utils/floating.cljs`

Owns floating-ui interop and nothing else — no Reagent, no re-frame, no
knowledge of tooltips or CSS. Returns ClojureScript maps.

```clojure
(:require ["@floating-ui/dom" :refer [computePosition offset flip shift arrow autoUpdate]])

placement-map      ; {:up "top" :down "bottom" :left "left" :right "right"}
compute-position!  ; [reference floating opts] -> Promise<{:x :y :placement :arrow-x :arrow-y}>
auto-update!       ; [reference floating cb] -> teardown-fn
```

Middleware is fixed at the constants extracted from the outgoing library so the
rendered geometry matches:

| middleware | value | source |
| --- | --- | --- |
| `offset` | `:distance`, default 10 | `functions.js:35` — `arrow ? arrowSize : 3`, arrow is always on, `arrowSize` defaults to 10 |
| `flip` | `padding: 10` | `functions.js:17` — `bodyPadding = 10` |
| `shift` | `padding: 10` | same |
| `arrow` | `padding: 5` | `functions.js:15` — `minArrowPadding = 5` |

### Rewritten: `plugins/frontend/de/explorama/frontend/ui_base/components/common/tooltip.cljs`

Same namespace, same public `(tooltip params & childs)` signature, same
`error-boundary` + `validate` wrapper. Behaviour preserved verbatim:

- blank/nil `:text` with no children renders `[:<>]`
- blank/nil `:text` with children renders the children unwrapped
- string `:text` containing newlines is split and rejoined with `[:br]`

## Public API

The declared parameter map is trimmed from 18 keys to 10. The 8 removed keys
have zero call sites anywhere in `plugins/` or `bundles/`.

Kept:

| param | default | maps to |
| --- | --- | --- |
| `:text` | *required* | popup content |
| `:direction` | `:up` | floating-ui placement |
| `:distance` | `10` | `offset(n)` |
| `:hover-delay` | `500` | show timer |
| `:mouse-out-delay` | `200` | hide timer |
| `:use-hover?` | `true` | whether hover listeners are attached |
| `:tag-name` | `"div"` | trigger wrapper element tag |
| `:extra-class` | — | appended to trigger wrapper class |
| `:extra-style` | — | trigger wrapper inline style |
| `:color` | — | popup text colour |

Removed: `:alignment`, `:on-toggle`, `:event-on`, `:event-off`,
`:event-toggle`, `:arrow?`, `:arrow-size`, `:is-open?`.

Note the timing defaults. The wrapper's `default-parameters` override the
library's own (`hoverDelay` 200, `mouseOutDelay` undefined), so today's real
behaviour is 500ms show / 200ms hide. That is what the replacement implements.

`:arrow?` deserves a note: `tooltip.cljs:163` writes `(assoc :arror arrow?)`, a
typo for the library's `arrow` prop. The parameter has therefore never had any
effect and arrows always render. Dropping it and always rendering the arrow is
behavioural parity, not a regression.

### One spec correction

`:color` is declared `:type :string`, but every call site passes a keyword —
`search/views/main_search/core.cljs:152` and
`algorithms/components/goal.cljs:92,150` all pass `:color :gray`. Reagent's prop
conversion `name`s keywords, so it works while violating the declared spec. The
type widens to `[:string :keyword]` to match reality. No call site changes.

## DOM shape

### Trigger wrapper

Always rendered. This is the element external stylesheets select on.

```clojure
[tag-name {:class "tooltip-wrapper <extra-class>"
           :style extra-style
           :ref   #(reset! trigger-el %)
           :on-mouse-enter … :on-mouse-leave …}
 childs]
```

Keeps the literal `tooltip-wrapper` class and
`[&_button:disabled]:pointer-events-none`.

Drops the three `has-[.react-tooltip-lite]:*` utilities
(`absolute`, `top-0`, `z-[30000]`, `drop-shadow-…`). They existed only because
the library applied its `className` to both the trigger wrapper and its portal
div, so the `:has()` variant was the trick that let one class string style both.
With our own portal there is nothing for them to match; the `z-index` and
`drop-shadow` move onto the popup element, where they belong.

### Popup

Portaled to `js/document.body` via `react-dom/createPortal`, mounted only while
open. This follows the established convention in
`ui_base/components/misc/context_menu.cljs:147` and
`ui_base/components/formular/select.cljs:321`, both of which portal directly to
`document.body` with no container element.

```clojure
[:div {:class popup-class
       :style {:position "fixed" :left x :top y :color color
               :visibility (if pos "visible" "hidden")}
       :ref   #(reset! popup-el %)}
 content
 [:div {:class arrow-class :style {:left arrow-x :top arrow-y}}]]
```

The portal is mandatory, not a preference. `woco/frame/view/core.cljs:59-60`
sets `transform: translate3d(...)` on every frame, and a transformed ancestor
becomes the containing block for `position: fixed`, so a non-portaled popup
would be trapped inside the frame and clipped by its `overflow: hidden`.

`strategy: "fixed"` is used so coordinates are viewport-relative and independent
of `document.body`'s offset parent and margins.

## CSS ownership

`styles/src/tailwind.css:263-294` is deleted. Those five rules exist in the
"vendor/caller-DOM remnants" section precisely because the vendor hardcoded the
class names; owning the popup element removes that constraint, which is the
tailwind end state described in `CLAUDE.md`.

The values become a utility stack in `tooltip.cljs`: `text-xs`, `text-center`,
`rounded-xs`, `bg-gray-900`, `text-white`, `shadow-sm`, `min-w-[100px]`,
`max-w-[50em]`, `z-[30000]`, the `drop-shadow-[0_0_1px_var(--tooltip-shadow-color)]`
migrating off the wrapper, and em-based padding (`0.5em 1em`) which must stay in
`em` so it scales with the `text-xs` font size.

Every `!important` in that block disappears. They existed solely to out-specify
the vendor's inline styles, and there are none left to compete with.

The arrow keeps the border-triangle technique but collapses from four
directional classes to one class whose border-colour side is driven by
floating-ui's **resolved** placement, which may differ from the requested
`:direction` after `flip`.

`styles/src/css/base/themes.css:407` must be re-pointed at the new arrow class.
That rule (`.react-tooltip-lite-arrow, .ol-popup .anchor { opacity: 0 }`) sits
inside the forced-colors / high-contrast block; missing it silently regresses
high-contrast arrow hiding.

## Behaviour

State lives in a single `r/with-let`: an `open?` ratom, a `pos` ratom
(`{:x :y :placement :arrow-x :arrow-y}`), a timer-handle atom, an
autoUpdate-teardown atom, and a trigger-element atom captured by `:ref`. The
`finally` clause clears both timers and invokes the teardown; that is the entire
cleanup obligation.

- **mouse-enter** (listeners attached only when `:use-hover?`): cancel any
  pending hide, start the `:hover-delay` timer, then set `open?` true.
- **mouse-leave**: cancel any pending show, start the `:mouse-out-delay` timer,
  then set `open?` false, run the teardown and reset `pos` to nil.
- **popup mount** (`:ref` on the popup div): call `auto-update!` with the
  trigger as reference and the popup as floating element. Its callback
  recomputes position and resets `pos`. autoUpdate then handles scroll, resize
  and element-resize repositioning, replacing the vendor's manual scroll-parent
  listeners.

`computePosition` is asynchronous, so on the first frame the popup is mounted
but `pos` is still nil. Rendering at `0,0` would flash in the page corner. The
popup is therefore rendered with `visibility: hidden` until `pos` resolves —
which still measures correctly, unlike `display: none`, and needs no magic
offset. (The vendor parked the tip at `left: -10000000px`; `position.js:185`.)

## Accessibility

Deliberately unchanged: the popup carries no `role` and no ARIA wiring, exactly
as today.

`formular/button.cljs:237-238` already folds the tooltip text into the trigger's
accessible name (`:aria-label (or aria-label (str label " " title))`), so adding
`aria-describedby` would cause the text to be announced twice. There is no
`role="tooltip"` or `aria-describedby` anywhere in the frontend today.

Adding tooltip a11y (role, focus-triggered show, Escape-to-dismiss, and
unwinding the `title`→`aria-label` fold in `button.cljs` and `icon.cljs`) is
worthwhile but is a separate change with its own regression surface. Keeping it
out preserves a clean "no screen-reader behaviour changed" claim for this one.

## Dependency changes

In each of `bundles/browser`, `bundles/electron/frontend`, `bundles/server`:

- add `"@floating-ui/dom": "1.8.0"` (published 2026-07-11; depends only on
  `@floating-ui/core` and `@floating-ui/utils`, and declares **no** React peer
  dependency, so this class of conflict cannot recur)
- remove `"react-tooltip-lite": "1.12.0"`
- regenerate the lockfile
- delete `legacy-peer-deps=true` from `.npmrc`

`e2e/.npmrc` has no such flag and is untouched.

`tools/ui-base-overview/project.clj:16` drops its
`[cljsjs/react-tooltip-lite "1.11.2-0" :exclusions [cljsjs/react-dom]]` entry.

### On `tools/ui-base-overview`

Its overview page at
`src/cljs/overview/de/explorama/frontend/ui_base/overview/common/tooltip.cljs`
demonstrates four of the removed parameters (`:alignment`, `:event-toggle`,
`:on-toggle`, `:event-on`/`:event-off`). It does not constrain the API, because
the tool cannot currently build: `project.clj` lists `src/cljs/lib` as a source
path, but that directory does not exist and is not tracked in git — the ui_base
library was evidently supplied by the private `lein-explorama-sync` plugin,
annotated `;???` in the project file.

Its examples are updated anyway so the documentation is correct whenever the
tool is revived, but no build verification is claimed for it.

## Verification

1. **Harness page (manual).** The automated capture/diff gate no longer exists:
   commit `423db6b` ("styles: remove migration-era verification scripts")
   deleted `harness_capture.sh`, `harness_diff.py`, the `tailwind.harness.css`
   input and the `tailwind:harness` npm script as completed-migration tooling.

   What survives is the harness *build* — `bundles/server/harness.cljs.edn`, the
   `:harness` alias in `cljs.deps.edn`, `resources/public/harness.html`, and the
   three tooltip instances at
   `bundles/server/harness/de/explorama/frontend/ui_base_harness.cljs:307-323`.
   It is currently orphaned, because `harness.html` links
   `/css/5_utilities.harness.css`, which nothing produces any more.

   Re-pointing that link at the normal `5_utilities.css` (built by
   `npm run tailwind:dist`) makes the page render again. That works for the
   tooltip because its utility classes live in
   `plugins/frontend/.../tooltip.cljs`, which is inside the `@source` scan scope
   in `styles/src/tailwind.css:16`; the harness directory itself is not, so
   harness-only markup remains unstyled.

   This is a **manual visual check**, not an automated gate, and it covers the
   trigger wrapper only — the popup appears on hover and never entered the
   static capture even when the tooling existed.
2. **New e2e spec.** `e2e/src/e2e/specs/tooltip.cljs`, registered through
   `registry/defspec` and required from `e2e/src/e2e/main.cljs`. Really hovers a
   trigger and asserts the popup appears, its text matches, and its resolved
   side is correct. Requires `bundles/browser/build.sh` to have run first. This
   closes the popup coverage gap, which no suite covers today.
3. **Compile and test gates.** All three bundles compile shared `plugins/` code,
   so the browser, electron-frontend and server-frontend suites all run. Results
   are read per-suite from `report.xml` rather than the top-level summary, and
   its mtime is checked against the clock, per `CLAUDE.md`.
4. **Lint.** `clj-kondo --lint plugins/` compared against the existing baseline
   of 2 errors and ~1087 warnings; what matters is that the change adds none.
5. **Peer-dep gate.** `npm install --dry-run --no-legacy-peer-deps` run in each
   of the three bundle directories, which must succeed once the swap lands.

There are no ui_base unit tests and no Reagent DOM-render tests anywhere in the
repo. With the harness capture/diff pair retired, the new e2e spec is the only
*automated* behavioural gate over the tooltip; the harness page is a manual
visual aid alongside it.

## Risks

- **Visual drift in arrow geometry.** floating-ui's `arrow` middleware centres
  the arrow differently from the vendor's hand-rolled maths near the edges of a
  trigger. The parity constants above should keep this small, but it is the most
  likely place to need a visual adjustment.
- **`flip` behaviour differs.** The vendor recursed through `getDirection` with
  its own sufficiency checks; floating-ui flips on overflow detection. Tooltips
  near a viewport edge may choose a different side than before. This is
  generally an improvement but is a visible change.
- **`:extra-style` target.** The vendor's `styles` prop applied to the trigger
  wrapper, and that is preserved. Any call site that assumed it reached the
  popup was already wrong, but the two sites using it
  (`projects/views/tooltip.cljs:38`, `charts/charts/legend.cljs:291`) should be
  eyeballed.
- **`:extra-class` target.** Same hazard as `:extra-style`, but with broader
  reach: the vendor applied `className` to both the trigger wrapper and its
  portal div, so a rule targeting the popup through `:extra-class` would have
  worked by accident. The replacement applies it only to the trigger wrapper.
  The one call site (`projects/views/tooltip.cljs:37`, class
  `"projects__project__tool"`) has no rule anywhere under `styles/src/`, so
  there is no live regression.
- **Touch handling was dropped.** The vendor attached `onTouchStart`/
  `onTouchEnd` to the trigger plus body-level touch dismissal. The replacement
  attaches only `onMouseEnter`/`onMouseLeave` and relies on the browser's
  synthesised mouse events on tap, which show the tooltip but never dismiss it
  since no `mouseleave` follows a tap. Explorama is a desktop/Electron tool, so
  this is very likely irrelevant in practice, but it is a removed capability
  that was never a declared parameter, so it is recorded here rather than left
  implicit.
