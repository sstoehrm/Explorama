#!/bin/bash

npm install -g modclean

# NOTE (issue #28 Task 3): this list assumed -- in the old pre-split
# pipeline -- that electron's own postinstall (binary download) had already
# run by the time clean_modules.sh executed, so the packages it only needs
# for that one-time unzip/download were safe to prune afterward. The new
# prepare -> verify-boot -> bundle split defers that postinstall on purpose
# (prepared/'s npm install runs --ignore-scripts; verify-boot.sh runs
# `node node_modules/electron/install.js` itself, AFTER clean_modules.sh has
# already run as part of `make prepare-prod`). That means install.js's own
# require() graph has to survive clean_modules.sh now: extract-zip (and its
# deps debug + ms) unzips the downloaded archive, and @electron/get (and its
# deps fs-extra + universalify + jsonfile + progress) resolves/caches it.
# Confirmed empirically by running `node install.js` against a freshly
# clean_modules'd prepared/node_modules and iterating on each
# "Cannot find module" until it succeeded -- see task-3-e28-report.md.
# "electron/dist" is also excluded from this list now: it holds the actual
# downloaded electron binary that verify-boot.sh provisions (either just
# now, or already warm from a previous run) -- pruning it here would either
# no-op (nothing downloaded yet) or destroy real work (re-forcing a
# redundant download every single prepare-prod run).
#
# NOTE (issue #28 Task 4): 16 more entries came out of the prune list --
# "@szmarczak" plus base64-js, commander, defer-to-connect,
# define-properties, detect-node, es6-error, function-bind, get-intrinsic,
# global-agent, globalthis, json-stringify-safe, lodash, lru-cache, matcher,
# minimist, roarr. The pinned modern electron-builder (26.15.6) requires all
# of these unconditionally at load time -- they're transitive dependencies
# (direct or optional) of app-builder-lib's `got`/@electron/get download
# path, of its config/log helpers (roarr, lodash), or of global-agent's own
# proxy-support chain -- so electron-builder's own top-level require chain
# needs them to physically exist regardless of whether any of those code
# paths actually executes. None of the 16 are shipped in the packaged app
# (electron-builder is a devDependency; its own requirements never end up in
# the asar), so keeping them only costs prepared/node_modules disk space,
# not artifact size. The old pre-split pipeline's electron-builder version
# apparently didn't need them, so pruning was silently safe back then; the
# modern pin does. Found empirically: `make bundle-linux` failed with
# "Cannot find module" for @szmarczak/http-timer, then defer-to-connect, one
# at a time; rather than keep whacking moles, the full list was derived by
# walking prepared/package-lock.json's dependency graph from
# "node_modules/electron-builder" (direct + optional deps, transitively) and
# intersecting it with this folders array -- see task-4-e28-report.md for
# the script and full reasoning.
folders=("@cljs-oss" "@types" "accessibility-developer-tools" "acorn" "babel*" "babylon" "core-js" "devtron" "enhanced-resolve" "defined" "detective" "highlight.js" "humanize-plus" "loose-envify" "errno" "esutils" "global-tunnel-ng" "globals" "ws" "npm-conf" "immediate" "ini" "is-core-module" "jsonparse" "JSONStream" "js-tokens" "konan" "lie" "memory-fs" "pinkie" "exceljs/dist")

echo "------------------------------------"
echo "Reduce node modules"

modclean -n default:safe,default:caution -r

for folder in "${folders[@]}"
do	
  echo "  Remove ${folder}"
  rm -rf node_modules/$folder
done

echo "done."
echo "------------------------------------"