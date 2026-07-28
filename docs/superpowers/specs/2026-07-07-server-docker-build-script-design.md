# Server Bundle Docker Build Script — Design

**Date:** 2026-07-07
**Status:** Approved
**Branch:** compose-only-docker-setup

## Goal

Let a user build the server bundle's container images with one command, without
going through docker compose — plain `docker build` with proper tags, suitable
for use outside the compose harness or for pushing to a registry.

## Context

`bundles/server/Dockerfile` is multi-stage: `builder` (JDK + Node + bb, runs
`build.sh`) feeding two final targets — `backend` (JRE + uberjar, serves `/ws`
on `:4001`) and `app-frontend` (nginx serving the static build). Builds require
the repo root as context (the Dockerfile copies `plugins/`, `styles/`,
`assets/`, `bundles/server/`). Today only `docker-compose.full.yml` wires these
builds up.

Known limitation: the in-container build currently fails at the advanced
ClojureScript compile (`prod-opts.edn` needs `:target :bundle` + a webpack
`:bundle-cmd`). The script is ready plumbing until that gap is fixed; compose
full mode has the same constraint.

## Interface

```bash
bundles/server/build-docker.sh [backend|frontend|all] [--push] [--help]
```

- Default target: `all` (both images).
- Images: `explorama/server-backend`, `explorama/server-frontend`.
- Tags: short git SHA (with `-dirty` suffix when the working tree has
  uncommitted changes) plus `latest`.
- Env overrides: `IMAGE_PREFIX` (default `explorama/server`), `TAG` (default
  the git SHA described above).
- `--push`: push both tags of each built image after a successful build.

## Behavior

- Location: `bundles/server/build-docker.sh`, next to `build.sh`, following its
  shell conventions (`set -e`-style strictness, section echoes).
- CWD-independent: resolves the repo root from the script's own location and
  runs `docker build -f bundles/server/Dockerfile <repo-root>` with
  `--target backend` / `--target app-frontend`.
- Header comment documents the known CLJS build gap.

## Error handling

- `set -euo pipefail`.
- Clear failure message when `docker` is not installed or the target argument
  is invalid; `--help` prints usage and exits 0.

## Documentation

- One-line mention in `bundles/server/README.md`.
- Mention in `docker/README.md`'s full-mode section as the non-compose
  alternative for building the images.

## Verification

- `shellcheck bundles/server/build-docker.sh`.
- `--help` and invalid-argument behavior.
- `docker build --check` (BuildKit dry validation) for both targets.
- A full end-to-end image build is blocked by the known CLJS gap and is out of
  scope, same as compose full mode.

## Out of scope

- Fixing the `prod-opts.edn` bundle-target gap.
- Registry configuration/login; `--push` assumes the user is authenticated.
- Building or tagging for the compose harness (compose full mode already
  handles that).
