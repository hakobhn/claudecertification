"""Scores a review against the issues planted in ../sample-pr, and compares the two architectures."""

from __future__ import annotations

# Local bugs planted in the sample PR: (file, line, what). A finding matches if it is in the same
# file within 3 lines. Two of them sit in the LATE files 12-13, where dilution hurts most.
SEEDED = [
    ("03-orders.js", 7, "getOrder: no sign-in / ownership check"),
    ("06-inventory.js", 12, "off-by-one: allows one more than in stock"),
    ("07-catalog.js", 11, "SQL injection via search term"),
    ("08-shipping.js", 8, "null address on ebook orders"),
    ("10-reviews.js", 11, "SQL injection via review text"),
    ("11-wishlist.js", 7, "getWishlist: no sign-in / ownership check"),
    ("12-invoices.js", 20, "null customer on guest orders"),
    ("13-tax.js", 8, "VAT rate not divided by 100"),
]

# Cross-file issues: invisible when you look at one file. Matched by the pair of files named.
CROSS_FILE = [
    ({"04-basket.js", "05-discounts.js"}, "applyDiscount returns dollars; basket treats it as cents"),
    ({"04-basket.js", "14-receipts.js"}, "receipts imports calculateTotal; basket exports computeTotal"),
]

# Identical code in two files. Judging one and not the other is an attention dilution artefact.
PAIRS = [
    (("03-orders.js", 7), ("11-wishlist.js", 7), "missing sign-in check"),
    (("03-orders.js", 17), ("11-wishlist.js", 17), "forEach + push instead of map"),
]

WINDOW = 3


def flagged(review: dict, file: str, line: int) -> bool:
    for entry in review["files"]:
        if entry["file"] == file and any(abs(f["line"] - line) <= WINDOW for f in entry["findings"]):
            return True
    return False


def cross_caught(review: dict, files: set[str]) -> bool:
    return any(files <= set(issue["files"]) for issue in review["cross_file_issues"])


def artefacts(review: dict) -> list[str]:
    """Pairs of identical code where the review flagged one copy and not the other."""
    out = []
    for (fa, la), (fb, lb), what in PAIRS:
        a, b = flagged(review, fa, la), flagged(review, fb, lb)
        if a != b:
            out.append(f"{what}: flagged in {fa if a else fb}, passed in {fb if a else fa}")
    return out


def score(review: dict) -> dict:
    return {
        "findings": sum(len(e["findings"]) for e in review["files"]),
        "seeded_caught": [s for s in SEEDED if flagged(review, s[0], s[1])],
        "cross_caught": [c for c in CROSS_FILE if cross_caught(review, c[0])],
        "artefacts": artefacts(review),
    }


def comparison_table(files: list[str], single: dict, multi: dict) -> str:
    def count(review, f):
        return next((len(e["findings"]) for e in review["files"] if e["file"] == f), 0)

    def mark(review, f):
        bugs = [s for s in SEEDED if s[0] == f]
        hit = sum(flagged(review, f, line) for _, line, _ in bugs)
        return f"{hit}/{len(bugs)}" if bugs else "-"

    rows = ["file              | single: findings  planted | multi: findings  planted",
            "------------------+--------------------------+-------------------------"]
    for f in files:
        rows.append(f"{f:<18}| {count(single, f):>15}  {mark(single, f):>7} | {count(multi, f):>14}  {mark(multi, f):>7}")

    s, m = score(single), score(multi)
    rows += ["",
             f"total findings      single {s['findings']:>3}   multi {m['findings']:>3}",
             f"planted bugs caught single {len(s['seeded_caught'])}/{len(SEEDED)}   multi {len(m['seeded_caught'])}/{len(SEEDED)}",
             f"cross-file caught   single {len(s['cross_caught'])}/{len(CROSS_FILE)}   multi {len(m['cross_caught'])}/{len(CROSS_FILE)}",
             f"artefacts           single {s['artefacts'] or 'none'}",
             f"                    multi  {m['artefacts'] or 'none'}"]
    missed = [f"{f}:{line} {what}" for f, line, what in SEEDED if (f, line, what) not in s["seeded_caught"]]
    if missed:
        rows.append(f"missed by single    {missed}")
    missed = [f"{f}:{line} {what}" for f, line, what in SEEDED if (f, line, what) not in m["seeded_caught"]]
    if missed:
        rows.append(f"missed by multi     {missed}")
    return "\n".join(rows)
