#!/usr/bin/env python3
"""Usage: harness_diff.py <labelA> <labelB> -- exit 0 iff captures identical.

Compares two harness captures produced by harness_capture.sh: the PNG by md5
and the per-node computed-style JSON property-by-property. Prints every style
difference (node key :: property: old -> new) and a PNG verdict. This is the
regression gate for the ui_base -> tailwind component migration: a migrated
component must leave the computed styles (and pixels) unchanged.
"""
import json, sys, hashlib, pathlib, re, math

art = pathlib.Path(__file__).resolve().parents[2] / "docs/superpowers/artifacts/tailwind"
a, b = sys.argv[1], sys.argv[2]

# Standard properties newly enumerated by a Chromium upgrade (None on the
# old side, a value on the new side) are enumeration churn, same as the
# --* case above -- but ONLY for the explicitly-listed properties, so a
# genuinely new emission is still reported. Chromium 150: flex-line-count,
# text-fit (see phase3-batch1-verification.md).
CHURN_PROPS = {"flex-line-count", "text-fit"}

# Tailwind phase-3 FINAL-SWAP (Task 1, sass -> lightningcss pipeline swap):
# for a `--custom-property`, getComputedStyle returns the winning
# declaration's value VERBATIM (custom properties are unparsed token
# streams, never "computed"/canonicalized by the browser) -- so simply
# re-compiling the SAME source value with a different CSS serializer
# (lightningcss vs sass) surfaces as a computed-style string diff here,
# even though the value is mathematically/visually identical (verified:
# every occurrence in this project is a leading-zero, rgb/rgba
# fractional-channel rounding, or quote-style respelling -- see Task 1's
# report). This is the exact same class of "tool churn, not a regression"
# the CHURN_PROPS mechanism above already exists to suppress; extended
# here to custom properties specifically, and ONLY when the two values are
# numerically/lexically equivalent under normalization (a genuinely
# different value -- e.g. a real color change -- still reports).
_LEADING_ZERO = re.compile(r"(?<![0-9.])0\.(\d)")
_RGB_FN = re.compile(r"^rgba?\(\s*([^)]+?)\s*\)$")
_HEX = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def _normalize_spelling(v):
    v = _LEADING_ZERO.sub(r".\1", v)
    v = re.sub(r"'([^']*)'", r'"\1"', v)
    return v


def _round_half_up(x):
    # Python's round() is banker's rounding (round-half-to-even); CSS color
    # serializers round half AWAY FROM ZERO (118.5 -> 119, not 118).
    return int(math.floor(x + 0.5)) if x >= 0 else -int(math.floor(-x + 0.5))


def _parse_color(v):
    """Parse a hex or rgb()/rgba() color string into an (r, g, b, a) tuple
    (0-255 ints, alpha 0-255 rounded) for format-independent comparison, or
    None if `v` isn't (only) a color."""
    v = v.strip()
    m = _HEX.match(v)
    if m:
        h = m.group(1)
        if len(h) == 3:
            r, g, b, a = h[0] * 2, h[1] * 2, h[2] * 2, "ff"
        elif len(h) == 6:
            r, g, b, a = h[0:2], h[2:4], h[4:6], "ff"
        else:
            r, g, b, a = h[0:2], h[2:4], h[4:6], h[6:8]
        return (int(r, 16), int(g, 16), int(b, 16), int(a, 16))
    m = _RGB_FN.match(v)
    if m:
        parts = [p.strip() for p in m.group(1).split(",")]
        if len(parts) not in (3, 4):
            return None
        try:
            r, g, b = (_round_half_up(float(p)) for p in parts[:3])
            a = _round_half_up(float(parts[3]) * 255) if len(parts) == 4 else 255
        except ValueError:
            return None
        return (r, g, b, a)
    return None


def custom_prop_values_equivalent(va, vb):
    """True iff two --custom-property string values are the same modulo a
    known-safe lightningcss respelling (leading zero, quote style, rgb()
    channel rounding, and rgb()/rgba()<->hex format). Both values must be
    non-None strings."""
    if va == vb:
        return True
    ca, cb = _parse_color(va), _parse_color(vb)
    if ca is not None and cb is not None:
        return ca == cb
    return _normalize_spelling(va) == _normalize_spelling(vb)

png_a = (art / f"harness-{a}.png").read_bytes()
png_b = (art / f"harness-{b}.png").read_bytes()
png_same = hashlib.md5(png_a).hexdigest() == hashlib.md5(png_b).hexdigest()
print(f"PNG: {'IDENTICAL' if png_same else 'DIFFERS'}")

sa = json.loads((art / f"harness-{a}.styles.json").read_text())
sb = json.loads((art / f"harness-{b}.styles.json").read_text())
diffs = 0
suppressed = 0
suppressed_respelling = 0
for key in sorted(set(sa) | set(sb)):
    if key not in sa or key not in sb:
        print(f"NODE {'+' if key in sb else '-'} {key}"); diffs += 1; continue
    for prop in sorted(set(sa[key]) | set(sb[key])):
        va, vb = sa[key].get(prop), sb[key].get(prop)
        if va != vb:
            # Custom properties starting with -- suppress diff if one side is None
            if prop.startswith("--"):
                if va is None or vb is None:
                    # Enumeration churn: suppress
                    suppressed += 1
                    continue
                if custom_prop_values_equivalent(va, vb):
                    # Known-safe lightningcss respelling (Task 1): suppress
                    suppressed_respelling += 1
                    continue
            if prop in CHURN_PROPS and (va is None or vb is None):
                suppressed += 1
                continue
            # Real property or both sides have values: report diff
            print(f"{key} :: {prop}: {va!r} -> {vb!r}"); diffs += 1
if suppressed > 0:
    print(f"suppressed --* enumeration diffs: {suppressed}")
if suppressed_respelling > 0:
    print(f"suppressed --* known-equivalent respelling diffs: {suppressed_respelling}")
print(f"style diffs: {diffs}")
sys.exit(0 if (png_same and diffs == 0) else 1)
