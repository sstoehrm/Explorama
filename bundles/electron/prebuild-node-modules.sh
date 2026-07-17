#!/bin/bash
set -eu

# Downloads the electron-ABI prebuilt binding for better-sqlite3 and drops it
# into node_modules, replacing whatever `npm install` produced (or failed to
# produce -- see gather-assets.sh's prod branch, which runs `npm install
# --ignore-scripts` for exactly this reason: better-sqlite3@9.4.0's from-source
# build fails against modern Node/V8, and the electron runtime needs the
# electron ABI, not the host Node ABI, anyway).
#
# Recreated (post-3ac8251 split) from the pre-split
# bundles/electron/prebuild-node-modules.sh (see `git show
# 3ac8251^:bundles/electron/prebuild-node-modules.sh`); this script lives
# back at bundles/electron/ since packaging is a whole-app concern spanning
# backend/ and frontend/. cwd is expected to be bundles/electron/, same as
# the pre-split script, so the prod TARGET_PATH below is unchanged relative
# to the repo root.
#
# Usage: bash prebuild-node-modules.sh <os> <mode>
#   <os>   linux | win
#   <mode> prod | dev

pwd=`pwd`
os=$1
mode=$2
arch=""
bettersqlite3_version="9.4.0"
bettersqlite3_v="v119"

if [ $mode == "prod" ]
then
  TARGET_PATH="$pwd/../../dist/electron/prepared/node_modules/better-sqlite3/"
else
  TARGET_PATH="../node_modules/better-sqlite3/"
fi

if [ $os == "win" ]
then
    arch="win32-x64"
else
    arch="linux-x64"
fi

prebuild="prebuild"

if [ ! -d "$prebuild" ]
then
    mkdir $prebuild
fi
cd $prebuild

if [ ! -f "better-sqlite3-v$bettersqlite3_version-electron-$bettersqlite3_v-$arch.tar.gz" ]
then
    curl -L -o "better-sqlite3-v$bettersqlite3_version-electron-$bettersqlite3_v-$arch.tar.gz" \
        "https://github.com/WiseLibs/better-sqlite3/releases/download/v$bettersqlite3_version/better-sqlite3-v$bettersqlite3_version-electron-$bettersqlite3_v-$arch.tar.gz"
fi
tar -xf better-sqlite3-v$bettersqlite3_version-electron-$bettersqlite3_v-$arch.tar.gz

if [ $mode == "prod" ]
then
  if [ ! -d "$TARGET_PATH" ]
  then
      mkdir -p $TARGET_PATH
  fi
  cp -rf build $TARGET_PATH
fi
if [ ! -d "../node_modules/better-sqlite3/" ]
then
    mkdir -p "../node_modules/better-sqlite3/"
fi
cp -rf build "../node_modules/better-sqlite3/"
echo "Replaced binary for better-sqlite3"
rm -rf build
