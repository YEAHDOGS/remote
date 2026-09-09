#!/usr/bin/env python3
"""DOGS Remote — regression tests for transmit-failure feedback.

Runs without Android tooling. Covers the contract behind
RemoteViewModel.transmitError / clearTransmitError and the Manual screen
banner:

  1. State shape: transmitError is a mutableStateOf nullable String with a
     private setter; clearTransmitError() clears it.
  2. Single funnel: every transmit path (magic probe, manual send, blast,
     hold-to-repeat x2, macro step) routes through the private transmitAndReport helper —
     exactly one raw sender.transmit call site exists, inside the helper.
  3. Reporting semantics: a failed transmit sets transmitError to
     TRANSMIT_ERROR_MSG; a success clears it; the state write happens on
     Dispatchers.Main. The message stays honest ("may be") — it never
     claims to know why the emitter failed.
  4. UI wiring: ManualScreen reads vm.transmitError, shows it, and offers a
     Dismiss path wired to vm.clearTransmitError().

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")
MANUAL = os.path.join(SRC, "ui", "ManualScreen.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(VM) as f:
    vm = f.read()
with open(MANUAL) as f:
    manual = f.read()

# --- 1. state shape -------------------------------------------------------
check(
    re.search(
        r"var transmitError:\s*String\?\s+by\s+mutableStateOf\(null\)\s*\n\s*private set",
        vm,
    ),
    "RemoteViewModel must expose transmitError as mutableStateOf nullable String with private set",
)
check(
    re.search(r"fun clearTransmitError\(\)\s*\{\s*\n?\s*transmitError = null", vm),
    "clearTransmitError() must clear transmitError",
)

msg = re.search(r'const val TRANSMIT_ERROR_MSG\s*=\s*\n?\s*"([^"]+)"', vm)
check(msg is not None, "TRANSMIT_ERROR_MSG must be a non-empty string constant")
if msg:
    text = msg.group(1)
    check(len(text) >= 20, f"TRANSMIT_ERROR_MSG looks like a placeholder: {text!r}")
    check("may be" in text or "check" in text.lower(),
          "message must stay honest/hedged — never claim a definite cause")
    check("No IR emitter" not in text and "not found" not in text.lower(),
          "message must not contradict the real cause; the emitter may just be busy")

# --- 2. single funnel ------------------------------------------------------
raw_calls = vm.count("sender.transmit(")
check(raw_calls == 1,
      f"exactly one raw sender.transmit call site allowed (the helper); found {raw_calls}")
check("private suspend fun transmitAndReport(" in vm,
      "transmitAndReport must be a private suspend helper")
funnel_calls = vm.count("transmitAndReport(") - 1  # minus the definition
check(funnel_calls == 6,
      f"all 6 transmit paths (magic, manual, blast, repeat x2, macro step) must call transmitAndReport; found {funnel_calls}")

# --- 3. reporting semantics ------------------------------------------------
check("withContext(Dispatchers.IO) { sender.transmit(freqHz, pattern) }" in vm,
      "helper must run the blocking transmit on Dispatchers.IO")
helper_body = re.search(
    r"private suspend fun transmitAndReport\([^{]*\{(.*?)\n    \}", vm, re.S)
check(helper_body is not None and "withContext(Dispatchers.Main)" in helper_body.group(1),
      "helper must report the result on Dispatchers.Main (state writes are main-thread only)")
check("transmitError = if (ok) null else TRANSMIT_ERROR_MSG" in vm,
      "helper must set the error on failure and clear it on success")

# --- 4. UI wiring ----------------------------------------------------------
check("vm.transmitError" in manual,
      "ManualScreen must read vm.transmitError")
check("vm.clearTransmitError()" in manual,
      "ManualScreen must offer a Dismiss path wired to vm.clearTransmitError()")
check(re.search(r"if\s*\(\s*transmitError\s*!=\s*null\s*\)", manual),
      "ManualScreen must only show the banner when transmitError is non-null")

if fails:
    print(f"{len(fails)} FAILURES:")
    for f in fails:
        print(f"  - {f}")
    raise SystemExit(1)
print("OK: transmit-failure feedback regression tests passed")
