#!/usr/bin/env python3
"""Usage: dr_diff.py <labelA> <labelB> -- exit 0 iff all present captures match.

Compares dr_capture.sh captures across all four screens (dr-welcome-,
dr-dashboard-overview-, dr-report-, dr-presentation-<label>.png) between two
labels. Mirrors search_diff.py's shape: MD5 first (cheap, exact), and on
mismatch a PIL pixel-diff reporting the count of differing pixels and their
bounding box, so a real regression can be told apart from localized/dynamic
noise (e.g. the welcome page's tips-and-tricks carousel).

A screen missing on either side is reported SKIPPED, not a failure -- not
every screen is guaranteed reachable in every capture run (see
dr_capture.sh's header for the batch-3 reachability findings).
"""
import hashlib
import pathlib
import sys

art = pathlib.Path(__file__).resolve().parents[2] / "docs/superpowers/artifacts/tailwind"

SCREENS = ["welcome", "dashboard-overview", "report", "presentation"]


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
        print("usage: dr_diff.py <labelA> <labelB>", file=sys.stderr)
        sys.exit(2)
    label_a, label_b = sys.argv[1], sys.argv[2]

    results = [
        compare(f"dr-{screen}-{label_a}.png", f"dr-{screen}-{label_b}.png", f"dr-{screen}")
        for screen in SCREENS
    ]
    failed = any(r is False for r in results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
