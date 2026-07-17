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
# Packaging (issue #28)
# ---------------------------------------------------------------------------
# Reassembled post-3ac8251: backend/prod-main-opts.edn and
# backend/prod-opts.edn now compile main.js and backend.js respectively
# (de.explorama.main.core / de.explorama.backend.woco.app.core, both
# :target :nodejs :simple), and frontend/prod-opts.edn webpack-bundles
# frontend.js (de.explorama.frontend.woco.app.core). All three write
# straight into dist_root/prepared per their :output-to /
# :bundle-cmd -o, so "compile" below just runs the three cljs.main
# invocations; layout assembly (package.json, html, preload JS, css/fonts/img,
# app/ icons, npm install) is backend/gather-assets.sh's prod branch.
dist_root := ../../dist/electron
prepared := $(dist_root)/prepared

prepare-prod:
	cd backend && bash gather-assets.sh prod
	bash prebuild-node-modules.sh linux prod
	cd backend && clj -M:prod-main
	cd backend && clj -M:prod
	cd frontend && clj -M:prod
	cp backend/clean_modules.sh $(prepared)/
	cd $(prepared) && bash clean_modules.sh
	rm -f $(prepared)/clean_modules.sh
	# cljs :output-dir trees are build scratch (goog/clojure/etc. sources +
	# unoptimized intermediate output) -- only the :output-to files
	# (main.js, public/js/{backend,frontend}.js) and frontend's async
	# webpack chunks (public/js/*.frontend.js) are needed at runtime.
	rm -rf $(prepared)/public/js/main
	rm -rf $(prepared)/public/js/backend

# electron-builder is a pinned backend devDependency (see backend/package.json
# "build" config + backend/package-lock.json) rather than a global install,
# so prepared/'s own `npm install` (in gather-assets.sh prod) already
# provisions it into prepared/node_modules/.bin -- npx just resolves that
# local bin instead of hitting the registry.
bundle-linux:
	cd $(prepared) && npx electron-builder --linux
	cd $(prepared) && mv dist/*.AppImage ../
	# NOTE: intentionally no `rm -rf $(prepared)` here (unlike the pre-3ac8251
	# Makefile) -- verify-boot needs prepared/ to still exist so it can run
	# the app before the tree is torn down; build-linux's own recipe (below)
	# does the teardown once bundle-linux has run after verify-boot.

# Untested on this machine (no Windows/wine toolchain available) -- mirrors
# bundle-linux's shape; verify before relying on it.
bundle-win:
	cd $(prepared) && npx electron-builder --win
	cd $(prepared) && mv dist/*.exe ../ 2>/dev/null || true
	cd $(prepared) && mv dist/*.AppImage ../ 2>/dev/null || true

# Launches $(prepared) under xvfb-run with a scratch HOME and polls for a
# clean main+worker boot (see verify-boot.sh for the exact pass/fail
# criteria). Also (idempotently) provisions prepared/node_modules/electron's
# postinstall binary, which prepared/'s --ignore-scripts npm install skips.
verify-boot:
	bash verify-boot.sh

# order: prepare -> verify boots -> bundle (verify-boot needs $(prepared) to
# still exist, so bundle-linux's teardown must not happen before it runs).
# Once bundle-linux has produced dist/electron/*.AppImage, $(prepared) (the
# unpacked node_modules/npm-installed tree, several hundred MB) is no longer
# needed -- the AppImage is self-contained -- so this recipe tears it down,
# matching the pre-3ac8251 chain's `rm -rf prepared` (issue #28 Task 4). To
# boot-test the AppImage itself afterwards (rather than $(prepared)), use
# verify-boot.sh's APP=<path> override, e.g.:
#   APP=../../dist/electron/Explorama-linux.AppImage bash verify-boot.sh
# the packaging chain is strictly sequential (gather -> prebuild -> compiles
# -> verify -> bundle); don't let -j interleave the phony prerequisites.
.NOTPARALLEL:

build-linux: prepare-prod verify-boot bundle-linux
	rm -rf $(prepared)

# Untested (see bundle-win); same verify-boot dependency as build-linux.
build-win: prepare-prod verify-boot bundle-win
	rm -rf $(prepared)

# ---------------------------------------------------------------------------
# dev-app (issue #28 Task 4): minimal restoration, main-process dev only.
# ---------------------------------------------------------------------------
# Compiles backend/main (de.explorama.main.core) unoptimized to
# backend/resources/main.js (backend/deps.edn's :dev-main alias, mirroring
# :prod-main's shape at :optimizations :none / RUNTIME_MODE "dev"), then
# launches it with backend's own devDependency electron.
#
# HONEST GAP: the main process will create its UI/worker windows, but those
# windows load backend/resources/public/{index,worker}.html, which expect
# js/frontend.js and js/backend.js -- names the per-half `clj -M:dev`
# figwheel builds do NOT emit (they write to resources/public/js/out/, see
# the `dev` target's comment above). That mismatch predates this task and
# isn't fixed here; wiring dev-app to the two figwheel dev builds is future
# work. In its current state dev-app is useful for iterating on the
# main-process source (window/lifecycle/IPC code in backend/main) -- the
# renderer windows will fail to load their script and show blank/errored
# content, which is expected, not a regression.
dev-app:
	cd backend && clj -M:dev-main
	cd backend && npx electron --no-sandbox ./resources/main.js

.PHONY: install assets test test-backend test-frontend clean-before dev \
	prepare-prod verify-boot bundle-linux bundle-win build-linux build-win \
	dev-app
