---
name: explorama-plugin-work
description: Use when adding or changing Explorama plugins, frame behavior, re-frame events/subscriptions, Frontend Interface API usage, backend route registration, or shared plugin contracts.
---

# Explorama Plugin Work

## Overview

Use this skill for changes in Explorama's plugin architecture. Load `references/plugin-patterns.md` when changing plugin lifecycle, frame APIs, frontend/backend route flow, or shared route contracts.

## Workflow

1. Identify the vertical or plugin name and inspect all affected layers: `plugins/frontend`, `plugins/backend`, `plugins/shared`, and matching `plugins/*_test`.
2. Keep cross-platform contracts in shared namespaces. Keep bundle-specific runtime behavior in `bundles/`.
3. For frontend behavior, follow existing re-frame patterns and use `de.explorama.frontend.common.frontend-interface` instead of reaching into unrelated plugin state.
4. For backend communication, update the shared route key, frontend dispatch, backend handler, and backend `frontend-api/register-routes` together.
5. Add or update tests in the matching plugin test tree before relying on full bundle tests.

## Frontend Notes

- Frontend plugin code lives under `plugins/frontend/de/explorama/frontend/<plugin>/`.
- Use `re-frame/reg-event-fx`, `reg-event-db`, and `reg-sub` near the owning namespace.
- Plugin registration commonly goes through a `defn init` that waits for the Frontend Interface API, then dispatches registration events.
- Frame descriptors usually include `:id`, `:event`, `:module`, `:vertical`, `:type`, sizing, and optional classes.
- Frame event handlers commonly branch on actions such as `:frame/init`, `:frame/query`, `:frame/close`, and connection negotiation.

## Backend Notes

- Backend plugin code lives under `plugins/backend/de/explorama/backend/<plugin>/`.
- Route keys should be shared with the frontend, usually in `plugins/shared/de/explorama/shared/<plugin>/`.
- Register backend routes from the plugin backend namespace with `frontend-api/register-routes`.
- Handler signatures often receive a metadata map containing callbacks and a parameter vector. Preserve existing callback conventions such as `:client-callback`, `:failed-callback`, and broadcast callbacks.

## Tests And Verification

- Frontend tests: `plugins/frontend_test/de/explorama/frontend/<plugin>/`.
- Backend tests: `plugins/backend_test/de/explorama/backend/<plugin>/`.
- Shared tests: `plugins/shared_test/de/explorama/shared/<area>/`.
- Browser test runner imports many plugin tests: `bundles/browser/test/de/explorama/test_runner_ci.cljs`.
- Server backend tests run through `bundles/server/clj.deps.edn`.

Use `skills/explorama-bundle-development/SKILL.md` to select the exact bundle command.

## Common Mistakes

- Do not add a frontend route key without updating the shared route namespace and backend route map.
- Do not read or mutate another plugin's DB paths directly when the Frontend Interface API already exposes the needed value or event.
- Do not put server-only dependencies in shared `.cljc` namespaces without reader conditionals.
- Do not introduce a new frame action shape unless existing frame APIs cannot model the behavior.
