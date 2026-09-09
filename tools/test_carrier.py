#!/usr/bin/env python3
"""DOGS Remote — regression tests for IR carrier-frequency compatibility.

Runs without Android tooling. Covers the contract behind IrSender.kt's
isCarrierSupported() and RemoteViewModel's unsupportedVariantIds:

  1. The Python reference implementation must agree with the shared vectors
     in carrier_vectors.json (boundaries inclusive, fail-open on empty
     ranges, multiple ranges, gaps between ranges).
  2. DB contract: with a narrow 37-39kHz emitter, exactly the Philips
     (36kHz) and Sony (40kHz) variants are flagged unsupported; with a wide
     30-60kHz emitter, nothing is flagged.
  3. Structural: the Kotlin wiring exists — IrSender.kt defines the pure
     isCarrierSupported, RemoteViewModel.kt builds unsupportedVariantIds
     from it, and both screens surface the flag.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS = os.path.join(HERE, "carrier_vectors.json")
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
DB = os.path.join(HERE, "..", "app", "src", "main", "assets", "ir_database.json")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


def is_carrier_supported(freq_hz, ranges):
    """Python mirror of IrSender.kt isCarrierSupported()."""
    if not ranges:
        return True
    return any(lo <= freq_hz <= hi for lo, hi in ranges)


# 1. shared vectors
vectors = json.load(open(VECTORS))["vectors"]
check(len(vectors) >= 8, "carrier_vectors.json should hold a healthy set of vectors")
for v in vectors:
    got = is_carrier_supported(v["freq"], [tuple(r) for r in v["ranges"]])
    check(got == v["expected"],
          "vector failed (%s): freq=%s ranges=%s expected=%s got=%s"
          % (v["why"], v["freq"], v["ranges"], v["expected"], got))

# 2. DB contract against the same logic
db = json.load(open(DB))
variants = [v for b in db["brands"] for v in b["variants"]]


def unsupported_ids(ranges):
    return {v["id"] for v in variants
            if any(not is_carrier_supported(d["freq"], ranges)
                   for d in v["buttons"].values())}


check(unsupported_ids([(37000, 39000)]) == {"philips-tv-rc5-0", "sony-tv-sony12-1"},
      "narrow 37-39kHz emitter must flag exactly philips (36k) + sony (40k): got %s"
      % sorted(unsupported_ids([(37000, 39000)])))
check(unsupported_ids([(30000, 60000)]) == set(),
      "wide 30-60kHz emitter must flag nothing")
check(unsupported_ids([]) == set(),
      "unknown caps must flag nothing (fail-open)")

# 3. structural: Kotlin wiring present
def src_text(*path):
    with open(os.path.join(SRC, *path)) as f:
        return f.read()


ir_sender = src_text("ir", "IrSender.kt")
view_model = src_text("ui", "RemoteViewModel.kt")
magic = src_text("ui", "MagicScreen.kt")
manual = src_text("ui", "ManualScreen.kt")

check("fun isCarrierSupported(freqHz: Int, ranges: List<IntRange>): Boolean" in ir_sender,
      "IrSender.kt must define the pure isCarrierSupported()")
check("fun carrierRanges(): List<IntRange>" in ir_sender,
      "IrSender.kt must expose carrierRanges()")
check("val unsupportedVariantIds: Set<String>" in view_model,
      "RemoteViewModel must build unsupportedVariantIds")
check("isCarrierSupported" in view_model,
      "RemoteViewModel must use isCarrierSupported")
check("unsupportedVariantIds" in magic,
      "MagicScreen must surface the carrier warning")
check("unsupportedVariantIds" in manual,
      "ManualScreen must surface the carrier warning")

if fails:
    print("test_carrier.py: FAIL")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("test_carrier.py: PASS (%d vectors, %d variants)" % (len(vectors), len(variants)))
