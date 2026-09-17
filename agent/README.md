# Explorama CLI folder

Point Claude Code at this directory (`cd agent && claude`) so it picks up the
skills under `.claude/skills/`: `explorama` explains the gateway and the
workflow, `explorama-<plugin>` lists each plugin's operations.

The gateway is part of the server bundle. Enable it by naming your agent's
principal in `EXPLORAMA_AGENT_GATEWAY_PRINCIPALS` (see
`bundles/server/.env.example`), and export `EXPLORAMA_URL` and
`EXPLORAMA_AUTH_HEADER` in the shell that runs Claude Code.

The `explorama-<plugin>` skills are generated: run `bb agent-skills` from the
repository root after changing an op declaration. A server backend test fails
when they drift.
