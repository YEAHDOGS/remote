#!/usr/bin/env python3
"""DOGS Remote — regression tests for the AttemptLog read-path hardening.

Runs without Android tooling. Covers the contract behind
data/AttemptLog.kt:

  1. Corrupt prefs data must never crash the app at startup — RemoteViewModel
     builds its state from list() at init, so list() must catch bad JSON and
     return an empty list.
  2. One malformed entry must not nuke the rest — per-entry parsing is
     isolated, bad rows are skipped.
  3. The parsed list is capped at MAX even if the stored array grew past it
     by other means.
  4. sanitize() exists on both sides: log() drops invalid attempts on
     write; list() re-sanitizes every loaded entry so legacy/stale rows
     can't resurrect blank ids or overlong labels.

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
LOG = os.path.join(SRC, "data", "AttemptLog.kt")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(LOG) as f:
    log = f.read()
with open(VM) as f:
    vm = f.read()

# --- the crash contract ---------------------------------------------------
# RemoteViewModel reads the log at init, so list() failing = app fails to start.
check("attemptLog.list()" in vm,
      "RemoteViewModel must still build its attempts state from attemptLog.list()")

lm = re.search(r"fun list\(\): List<Attempt> \{(.*?)\n    \}\n\n    /\*\*", log, re.S)
if lm is None:
    lm = re.search(r"fun list\(\): List<Attempt> \{(.*?)\n    \}\n\n    fun markWorked", log, re.S)
check(lm is not None, "list() body must be parseable")
lbody = lm.group(1) if lm else ""

# --- 1. corrupt JSON tolerance ----------------------------------------------
check("catch" in lbody and "return emptyList()" in lbody,
      "list() must catch corrupt JSON and return an empty list instead of crashing")

# --- 2. per-entry isolation ---------------------------------------------------
check(re.search(r"try\s*\{\s*parseAttempt\(arr\.getJSONObject\(i\)\)", lbody) is not None,
      "list() must isolate each entry's parse in try/catch")
check("mapNotNull { it }" in lbody or "mapNotNull{ it }" in lbody or ".mapNotNull { it }" in lbody,
      "list() must skip malformed entries instead of propagating the failure")
check("private fun parseAttempt" in log,
      "per-entry parsing must be isolated in parseAttempt so one bad row can't nuke the rest")

# --- 3. load-side cap ---------------------------------------------------------
check("minOf(arr.length(), MAX)" in lbody,
      "list() must cap the parsed list at MAX even if storage grew past it")
m = re.search(r"const val MAX\s*=\s*(\d+)", log)
check(m is not None and 1 <= int(m.group(1)) <= 1000,
      "MAX must be a sane append-only cap")

# --- write side: sanitize on log() --------------------------------------------
check("fun sanitize(attempt: Attempt): Attempt?" in log,
      "AttemptLog must expose a sanitizer that can reject an attempt")
check("val clean = sanitize(attempt) ?: return" in log,
      "log() must sanitize on write and drop invalid attempts")
check("attempt.variantId.isBlank() || attempt.button.isBlank()" in log,
      "sanitize must reject attempts with blank variant or button ids")
check("variantLabel.trim().take(LABEL_MAX_LEN)" in log,
      "sanitize must trim and cap the variant label")
check("button.trim().take(LABEL_MAX_LEN)" in log,
      "sanitize must trim and cap the button id")
check("ts.coerceAtLeast(0L)" in log,
      "sanitize must clamp negative timestamps to 0 (botched data only)")
m2 = re.search(r"const val LABEL_MAX_LEN\s*=\s*(\d+)", log)
check(m2 is not None and 1 <= int(m2.group(1)) <= 128,
      "LABEL_MAX_LEN must be a sane label cap")

# --- read side: re-sanitize on load -----------------------------------------
check(re.search(r"\.mapNotNull\s*\{\s*it\s*\}\s*\.mapNotNull\s*\{\s*sanitize\(it\)\s*\}", lbody) is not None,
      "list() must re-sanitize every loaded entry (legacy/stale rows)")

# --- write side unchanged -----------------------------------------------------
check(".take(MAX)" in log, "log() must still cap persisted rows at MAX")

if fails:
    print(f"{len(fails)} FAILURES:")
    for f in fails:
        print(f"  - {f}")
    raise SystemExit(1)
print("OK: attempt-log hardening regression tests passed")
