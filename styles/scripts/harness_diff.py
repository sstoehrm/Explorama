#!/usr/bin/env python3
"""Usage: harness_diff.py <labelA> <labelB> -- exit 0 iff captures identical.

Compares two harness captures produced by harness_capture.sh: the PNG by md5
and the per-node computed-style JSON property-by-property. Prints every style
difference (node key :: property: old -> new) and a PNG verdict. This is the
regression gate for the ui_base -> tailwind component migration: a migrated
component must leave the computed styles (and pixels) unchanged.
"""
import json, sys, hashlib, pathlib

art = pathlib.Path(__file__).resolve().parents[2] / "docs/superpowers/artifacts/tailwind"
a, b = sys.argv[1], sys.argv[2]

png_a = (art / f"harness-{a}.png").read_bytes()
png_b = (art / f"harness-{b}.png").read_bytes()
png_same = hashlib.md5(png_a).hexdigest() == hashlib.md5(png_b).hexdigest()
print(f"PNG: {'IDENTICAL' if png_same else 'DIFFERS'}")

sa = json.loads((art / f"harness-{a}.styles.json").read_text())
sb = json.loads((art / f"harness-{b}.styles.json").read_text())
diffs = 0
for key in sorted(set(sa) | set(sb)):
    if key not in sa or key not in sb:
        print(f"NODE {'+' if key in sb else '-'} {key}"); diffs += 1; continue
    for prop in sorted(set(sa[key]) | set(sb[key])):
        va, vb = sa[key].get(prop), sb[key].get(prop)
        if va != vb:
            print(f"{key} :: {prop}: {va!r} -> {vb!r}"); diffs += 1
print(f"style diffs: {diffs}")
sys.exit(0 if (png_same and diffs == 0) else 1)
