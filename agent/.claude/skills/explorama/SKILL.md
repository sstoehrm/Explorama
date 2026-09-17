---
name: explorama
description: Use when asked to inspect or operate a running Explorama workspace, import data into Explorama, or author indicator aggregation graphs. Explains the agent gateway API, how to pick the user's session, and the read-before-write workflow; the explorama-<plugin> skills list the operations.
---

# Explorama agent gateway

Explorama's server bundle exposes `/api/agent`. You act on a named user's live
browser session through it; the user sees every change immediately.

## Connect

Set `EXPLORAMA_URL` (the proxy's base URL) and `EXPLORAMA_AUTH_HEADER`, the
header the proxy accepts for your principal. The deployment allow-lists the
principal in `EXPLORAMA_AGENT_GATEWAY_PRINCIPALS`; a 403 means it is not listed.
All bodies and responses are EDN (`Content-Type: application/edn`).

1. `GET /api/agent/ops` lists every operation with its schemas. The
   `explorama-<plugin>` skills are rendered from it.
2. `GET /api/agent/sessions?user=<name>` lists the user's open sessions. With
   more than one, pass the chosen `:client-id` in every request; otherwise a
   request answers `:ambiguous-session` with the candidates.
3. `POST /api/agent/ops/<plugin>/<op>` with `{:user "<name>" :client-id "<id>" :params {...}}`.

## Workflow

- Start with `:woco/workspace`. Do nothing else while the interaction mode is
  not `:normal`; the workspace is replaying or read-only and every write
  answers `:workspace-busy`.
- Then `:woco/markers`. A marked frame is the one the user wants you to work
  on; its note says what. Clear markers with `:woco/clear-markers` when done.
- Read before you write: `:woco/frames`, `:woco/connections`, and the plugin's
  `state` op. Write ops return the same shape as the matching read op, so
  check the result instead of reading again.
- Frame ids are opaque EDN values. Pass them back exactly as received.
- `:expdb/commit` writes to the shared database. Confirm with the user before
  calling it; everything before it is staged and can be cancelled.
- There is no project save op. `:projects/create` is the only way to persist
  a workspace, and only when the user asks for it.

## Errors

`:unauthorized` (fix the principal), `:unknown-op` (re-read the catalog),
`:invalid-params` (`:explain` holds the malli explanation), `:no-session`
(the user is not logged in, or the session went away), `:ambiguous-session`
(pass `:client-id`), `:workspace-busy` (wait and poll `:woco/workspace`),
`:timeout` (the session did not answer; read state before retrying),
`:op-failed` (`:message` is the plugin's error).
