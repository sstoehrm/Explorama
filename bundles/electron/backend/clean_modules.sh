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
folders=("@cljs-oss" "@types" "@szmarczak" "accessibility-developer-tools" "acorn" "babel*" "babylon" "base64-js" "commander" "core-js" "devtron" "enhanced-resolve" "defer-to-connect" "defined" "define-properties" "detective" "detect-node" "highlight.js" "humanize-plus" "loose-envify" "lodash" "errno" "es6-error" "esutils" "function-bind" "get-intrinsic" "globalthis" "global-tunnel-ng" "globals" "ws" "npm-conf" "global-agent" "immediate" "ini" "is-core-module" "jsonparse" "JSONStream" "json-stringify-safe" "js-tokens" "konan" "lie" "lru-cache" "matcher" "memory-fs" "minimist" "pinkie" "roarr" "exceljs/dist")

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