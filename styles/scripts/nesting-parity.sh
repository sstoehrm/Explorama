#!/usr/bin/env bash
# styles/scripts/nesting-parity.sh — prove lightningcss flattens nesting like sass
set -euo pipefail
cd "$(dirname "$0")/.."
FIX=scripts/fixtures/nesting-parity.scss
OUT=$(mktemp -d)
# sass output (expanded, no sourcemap)
npx sass "$FIX" "$OUT/sass.css" --style=expanded --no-source-map
# lightningcss output (same browser target as prod cssmin), then normalize whitespace for comparison
./node_modules/.bin/lightningcss --targets '>= 0.25%' "$FIX" --output-file "$OUT/lcss.css"
# Normalize both (strip blank lines + leading/trailing ws) and diff the RULE SET, not formatting
norm() { grep -v '^[[:space:]]*$' "$1" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'; }
if diff <(norm "$OUT/sass.css") <(norm "$OUT/lcss.css") > "$OUT/diff.txt"; then
  echo "PARITY: identical rule sets"; rm -rf "$OUT"; exit 0
else
  echo "DIFFERENCES (review each for equivalence):"; cat "$OUT/diff.txt"; echo "artifacts in $OUT"; exit 1
fi
