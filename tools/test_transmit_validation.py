#!/usr/bin/env python3
"""DOGS Remote — regression tests for the emitter trust boundary.

Runs without Android tooling. Covers the contract behind IrSender.kt's
isValidTransmit() + the guard in transmit():

  1. The Python reference implementation must agree with a set of attack
     vectors: frequencies outside the realistic IR band are rejected,
     empty/oversized patterns are rejected, and non-positive durations
     are rejected — the emitter is never asked to fire a malformed burst.
  2. DB contract: every button in the shipped ir_database.json must PASS
     the gate — the guard is defense-in-depth, not a behavior change for
     legitimate traffic (IrDatabase's parse already validates the same
     bounds at load).
  3. Structural: IrSender.kt defines the pure isValidTransmit(), and
     transmit() runs every payload through it BEFORE touching the
     ConsumerIrManager — a malformed payload returns false instead of
     relying on the framework's IllegalArgumentException.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
SENDER = os.path.join(SRC, "ir", "IrSender.kt")
DB = os.path.join(HERE, "..", "app", "src", "main", "assets", "ir_database.json")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


def is_valid_transmit(freq_hz, pattern):
    """Python mirror of IrSender.kt isValidTransmit()."""
    if not (10_000 <= freq_hz <= 100_000):
        return False
    if len(pattern) == 0 or len(pattern) > 10_000:
        return False
    return all(d > 0 for d in pattern)


# 1. attack vectors -----------------------------------------------------------
GOOD = [4500, 4500, 560, 560]

vectors = [
    # (freq, pattern, expected, why)
    (38_000, GOOD, True, "normal 38kHz burst passes"),
    (36_000, GOOD, True, "band floor-ish carrier passes"),
    (10_000, GOOD, True, "band lower bound passes"),
    (100_000, GOOD, True, "band upper bound passes"),
    (0, GOOD, False, "zero carrier rejected"),
    (-1, GOOD, False, "negative carrier rejected"),
    (9_999, GOOD, False, "just below band rejected"),
    (100_001, GOOD, False, "just above band rejected"),
    (150_000, GOOD, False, "absurd carrier rejected"),
    (38_000, [], False, "empty pattern rejected"),
    (38_000, [4500, 0, 560], False, "zero duration rejected"),
    (38_000, [4500, -100, 560], False, "negative duration rejected"),
    (38_000, [560] * 10_000, True, "max-size pattern passes"),
    (38_000, [560] * 10_001, False, "oversized pattern rejected"),
]

for freq, pattern, expected, why in vectors:
    got = is_valid_transmit(freq, pattern)
    check(got == expected,
          "vector failed (%s): freq=%s words=%s expected=%s got=%s"
          % (why, freq, len(pattern), expected, got))

# 2. DB contract — the gate never blocks the shipped data --------------------
db = json.load(open(DB))
buttons = [(v["id"], name, d)
           for b in db["brands"] for v in b["variants"]
           for name, d in v["buttons"].items()]
check(len(buttons) > 0, "database must contain buttons to validate")
blocked = [vid + "/" + name for vid, name, d in buttons
           if not is_valid_transmit(d["freq"], d["pattern"])]
check(not blocked,
      "the gate must pass every shipped button; blocked: %s" % blocked[:5])

# 3. structural: Kotlin wiring present ----------------------------------------
with open(SENDER) as f:
    sender = f.read()

check(re.search(
    r"fun isValidTransmit\(freqHz: Int, pattern: IntArray\): Boolean",
    sender) is not None,
    "IrSender.kt must define the pure isValidTransmit()")
check("IR_TRANSMIT_FREQ_MIN_HZ" in sender and "IR_TRANSMIT_FREQ_MAX_HZ" in sender,
      "IrSender.kt must bound the carrier to the realistic IR band")
check("IR_TRANSMIT_PATTERN_MAX_WORDS" in sender,
      "IrSender.kt must cap pattern length")
check("if (freqHz !in IR_TRANSMIT_FREQ_MIN_HZ..IR_TRANSMIT_FREQ_MAX_HZ) return false"
      in sender,
      "isValidTransmit must reject out-of-band carriers")
check("pattern.all { it > 0 }" in sender or "pattern.all { it > 0 }" in sender.replace("  ", " "),
      "isValidTransmit must reject non-positive durations")
check(re.search(
    r"fun transmit\(freqHz: Int, pattern: IntArray\): Boolean \{\s*\n"
    r"\s*if \(!isValidTransmit\(freqHz, pattern\)\)",
    sender) is not None,
    "transmit() must run the payload through isValidTransmit before anything else")

if fails:
    print("test_transmit_validation.py: FAIL")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("test_transmit_validation.py: PASS (%d vectors, %d DB buttons)" % (len(vectors), len(buttons)))
