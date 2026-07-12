#!/usr/bin/env python3
"""Usage: search_diff.py <labelA> <labelB> -- exit 0 iff captures identical.

Compares two search_capture.sh captures: search-normal-<A>.png vs
search-normal-<B>.png (and search-error-<A>.png vs search-error-<B>.png, if
both exist -- the error state is not currently reachable headlessly, see
search_capture.sh's header, so this pair is normally absent and is skipped
with a note rather than treated as a failure).

Unlike harness_diff.py there is no per-node computed-style JSON for the app
screens (this is a full-page app screenshot, not the ui_base render harness),
so the diff surface is pixel-only: MD5 first (cheap, exact), and on mismatch
a PIL pixel-diff reporting the count of differing pixels and their bounding
box, so a real regression (spread across the page) can be told apart from
localized/dynamic-content noise (e.g. the welcome page's tips-and-tricks
carousel text, which is expected to vary and is NOT part of any batch-2
search screen -- search_capture.sh does not stop on the welcome page).
"""
import hashlib
import pathlib
import sys

art = pathlib.Path(__file__).resolve().parents[2] / "docs/superpowers/artifacts/tailwind"


def compare(name_a, name_b, label):
    path_a = art / name_a
    path_b = art / name_b
    if not path_a.exists() or not path_b.exists():
        print(f"{label}: SKIPPED ({name_a if not path_a.exists() else name_b} not found)")
        return None

    bytes_a = path_a.read_bytes()
    bytes_b = path_b.read_bytes()
    md5_a = hashlib.md5(bytes_a).hexdigest()
    md5_b = hashlib.md5(bytes_b).hexdigest()
    if md5_a == md5_b:
        print(f"{label}: IDENTICAL (md5 {md5_a})")
        return True

    try:
        from PIL import Image
    except ImportError:
        print(f"{label}: DIFFERS (md5 {md5_a} -> {md5_b}); PIL not installed, no pixel breakdown available")
        return False

    img_a = Image.open(path_a).convert("RGB")
    img_b = Image.open(path_b).convert("RGB")
    if img_a.size != img_b.size:
        print(f"{label}: DIFFERS -- size changed {img_a.size} -> {img_b.size}")
        return False

    px_a = img_a.load()
    px_b = img_b.load()
    w, h = img_a.size
    diff_count = 0
    min_x, min_y, max_x, max_y = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if px_a[x, y] != px_b[x, y]:
                diff_count += 1
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    pct = 100.0 * diff_count / (w * h)
    bbox = f"x:[{min_x},{max_x}] y:[{min_y},{max_y}]" if diff_count else "n/a"
    print(f"{label}: DIFFERS -- {diff_count}/{w * h} px ({pct:.4f}%), bbox {bbox}")
    return False


def main():
    if len(sys.argv) != 3:
        print("usage: search_diff.py <labelA> <labelB>", file=sys.stderr)
        sys.exit(2)
    label_a, label_b = sys.argv[1], sys.argv[2]

    results = [
        compare(f"search-normal-{label_a}.png", f"search-normal-{label_b}.png", "search-normal"),
        compare(f"search-error-{label_a}.png", f"search-error-{label_b}.png", "search-error"),
    ]
    failed = any(r is False for r in results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
