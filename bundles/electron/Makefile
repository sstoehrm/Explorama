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
	# Makefile) -- Task 3's verify-boot needs prepared/ to still exist so it
	# can run the app before the tree is torn down. Once verify-boot lands,
	# add the cleanup after it in build-linux, not here.

# Untested on this machine (no Windows/wine toolchain available) -- mirrors
# bundle-linux's shape; verify before relying on it.
bundle-win:
	cd $(prepared) && npx electron-builder --win
	cd $(prepared) && mv dist/*.exe ../ 2>/dev/null || true
	cd $(prepared) && mv dist/*.AppImage ../ 2>/dev/null || true

# verify-boot (Task 3) doesn't exist yet -- this target will fail until it
# lands. Referencing it now (rather than skipping straight to bundle-linux)
# keeps the intended order (prepare -> verify boots -> bundle) visible in the
# Makefile itself instead of only in the issue #28 plan.
build-linux: prepare-prod verify-boot bundle-linux

# Untested (see bundle-win); same verify-boot dependency as build-linux.
build-win: prepare-prod verify-boot bundle-win

.PHONY: install assets test test-backend test-frontend clean-before dev \
	prepare-prod bundle-linux bundle-win build-linux build-win
