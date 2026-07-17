.DEFAULT_GOAL := test

# ---------------------------------------------------------------------------
# Post-3ac8251 layout: bundles/electron split into backend/ (Clojure backend
# + the Electron main/worker process source) and frontend/ (ClojureScript
# UI), each with its own deps.edn, figwheel config, gather-assets.sh and
# package.json. Targets below operate on both halves unless noted.
# ---------------------------------------------------------------------------

install:
	cd backend && npm install
	cd frontend && npm install

assets:
	cd backend && bash gather-assets.sh ${build_mode}
	cd frontend && bash gather-assets.sh ${build_mode}

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

# Backend suite compiles/runs on plain Node (no browser involved).
#
# NOTE: backend/package.json pins better-sqlite3@9.4.0 for the electron
# runtime (electron is pinned at 28.2.2; bumping the pin belongs to the
# packaging work in issue #28). That version has no prebuilt binary for
# modern Node (>=22, which CI's container also runs) and its native build
# fails against current V8 — so the TEST run installs a better-sqlite3@12
# prebuild via --no-save (node_modules only; manifest and lockfile keep the
# pin). The suite exercises the same better-sqlite3 API either way.
test-backend:
	cd backend && npm install better-sqlite3@12 --no-save --no-package-lock
	cd backend && bb run-tests-ci.bb.clj

# Frontend suite drives a headless chromium instance launched via
# frontend/figwheel-headless.sh, generated from the (gitignored,
# per-machine) frontend/figwheel-headless.sh.template.
test-frontend:
	if [ ! -f frontend/figwheel-headless.sh ]; then \
		cp frontend/figwheel-headless.sh.template frontend/figwheel-headless.sh; \
		chmod +x frontend/figwheel-headless.sh; \
	fi
	cd frontend && bb run-tests-ci.bb.clj

test: test-backend test-frontend

# ---------------------------------------------------------------------------
# Dev
# ---------------------------------------------------------------------------

# dev.cljs.edn in each half writes its build to resources/public/js/out
# (the old top-level resources/public/js and resources/main.js paths are
# artifacts of the pre-split single-directory layout and no longer apply).
clean-before:
	rm -rf backend/node_modules frontend/node_modules
	rm -rf backend/target frontend/target
	rm -rf backend/resources/public/js frontend/resources/public/js

# `make dev` used to drive one lein figwheel process building three builds
# together (main-dev dev-frontend dev-backend). Post-split, each half is an
# independent deps.edn project with its own single-build :dev alias
# (figwheel.main --build dev --repl), and there is no longer a "main" build
# to compile at all -- see the packaging note below. One Makefile recipe
# can't sensibly own two interactive REPLs in two terminals, so this prints
# instructions instead of faking a combined dev run.
dev: clean-before
	@echo "Electron dev builds run per-half now (post-3ac8251 split) -- use two terminals:"
	@echo ""
	@echo "  cd bundles/electron/backend  && clj -M:dev   # backend Figwheel REPL"
	@echo "  cd bundles/electron/frontend && clj -M:dev   # frontend Figwheel REPL"
	@echo ""
	@echo "Both figwheel-main.edn configs default to ring-server port 8020, so only"
	@echo "one half can bind that port at a time as currently configured."
	@echo "Run 'make assets build_mode=dev' first if resources/public/{css,fonts,img} are missing."

# ---------------------------------------------------------------------------
# Packaging (orphaned by the 3ac8251 restructure -- not implemented)
# ---------------------------------------------------------------------------
# dev-app, prepare-prod, bundle-win, bundle-linux, build-win, build-linux,
# prebuild-node-modules, compile, and install-builder all depended on the
# pre-split lein pipeline (`lein with-profile build cljsbuild once min-main
# min-frontend min-backend`, plus `lein with-profile dev figwheel main-dev
# dev-frontend dev-backend` for dev-app) and on a top-level resources/main.js
# produced by it. The 3ac8251 restructure removed the main-process build
# entirely: backend/main/de/explorama/main/*.cljs (the Electron main/worker
# process source) has no deps.edn alias or *-opts.edn compiling it anymore --
# backend/prod-opts.edn only builds de.explorama.frontend.woco.app.core.
# Packaging a working Electron app from this Makefile is not currently
# possible. Tracked in issue #28 (orphaned electron packaging
# pipeline); do not attempt to reimplement it here piecemeal.
unsupported:
	@echo "Electron packaging (dev-app/prepare-prod/bundle-*/build-*/compile) is"
	@echo "orphaned by the 3ac8251 backend+frontend split and not implemented here."
	@echo "See issue #28 (orphaned electron packaging pipeline)."
	@exit 0

.PHONY: install assets test test-backend test-frontend clean-before dev unsupported
