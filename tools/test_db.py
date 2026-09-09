#!/usr/bin/env python3
"""DOGS Remote — regression tests for the generated IR database.

Runs without Android tooling. Fails loudly on any structural problem so a
bad database can never ship inside the APK.

Checks:
  1. magic_order lists every variant id exactly once, Samsung first.
  2. Every variant has a vol_up button (magic mode's probe signal).
  3. Every pattern: sane carrier freq, starts with a mark, all words > 0,
     minimum length, and within plausible total duration (< 300ms).
  4. Known-good spot checks: Samsung vol_up decodes to E0E0E01F,
     LG power decodes to 20DF10EF (NEC-family canonical MSB-first bytes).
  5. TCL variant is flagged unverified (RCA-38 timings are inferred).
  6. Every variant carries a source_csv audit URL.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "..", "app", "src", "main", "assets", "ir_database.json")
ALLOWED_FREQS = {36000, 37000, 37900, 37917, 38000, 40000}

fails = []
def check(cond, msg):
    if not cond:
        fails.append(msg)

def canon_nec(pattern):
    """Decode NEC-family pattern to canonical MSB-first hex bytes."""
    data = pattern[2:-1]
    bits = ['1' if data[2 * i + 1] > 1000 else '0' for i in range(32)]
    out = []
    for k in range(4):
        v = 0
        for i in range(8):
            v |= (int(bits[8 * k + i]) << (7 - i))
        out.append("%02X" % v)
    return "".join(out)

db = json.load(open(DB))
vids = [v["id"] for b in db["brands"] for v in b["variants"]]
by_id = {v["id"]: v for b in db["brands"] for v in b["variants"]}

# 1. magic order
check(db["magic_order"] == vids, "magic_order != variant id order")
check(len(set(vids)) == len(vids), "duplicate variant ids")
check(vids[0].startswith("samsung"), "samsung must be first in magic order")

# 2+3. per-variant / per-button sanity
n_buttons = 0
for b in db["brands"]:
    for v in b["variants"]:
        check("vol_up" in v["buttons"], f"{v['id']}: missing vol_up")
        check(v["source_csv"].startswith(
            "https://cdn.jsdelivr.net/gh/probonopd/irdb@master/codes/"),
            f"{v['id']}: bad source_csv")
        for btn, d in v["buttons"].items():
            p = d["pattern"]
            check(d["freq"] in ALLOWED_FREQS, f"{v['id']}/{btn}: freq {d['freq']}")
            check(len(p) >= 10, f"{v['id']}/{btn}: pattern too short")
            check(all(x > 0 for x in p), f"{v['id']}/{btn}: non-positive word")
            check(sum(p) < 300_000, f"{v['id']}/{btn}: pattern > 300ms")
            n_buttons += 1

# 4. known-good spot checks
s = by_id["samsung-tv-necx2-7-7"]["buttons"]
check(canon_nec(s["vol_up"]["pattern"]) == "E0E0E01F", "samsung vol_up != E0E0E01F")
check(canon_nec(s["power"]["pattern"]) == "E0E040BF", "samsung power != E0E040BF")
lg = by_id["lg-tv-nec1-4"]["buttons"]
check(canon_nec(lg["power"]["pattern"]) == "20DF10EF", "lg power != 20DF10EF")

# 5. TCL flagged unverified
tcl = by_id["tcl-tv-rca38-15"]
check(tcl["verified"] is False, "tcl must stay verified=false (RCA-38 inferred)")
check(all(v["verified"] for vid, v in by_id.items() if vid != "tcl-tv-rca38-15"),
      "only tcl may be unverified")

if fails:
    print("TEST FAILURES:")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print(f"test_db.py: PASS ({len(vids)} variants, {n_buttons} buttons)")
