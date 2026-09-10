#!/usr/bin/env python3
"""DOGS Remote — regression tests for the Log screen's two-step clear.

Runs without Android tooling. The attempt log is the audit trail AND the
learning source for Magic Mode's learned sweep order, so wiping it must be
a deliberate two-step action. These structural checks keep a future edit
from rewiring the Clear button straight back to vm.clearLog():

  1. LogScreen.kt holds a confirm state (a mutableStateOf boolean) that
     the Clear button sets — the button must NOT call vm.clearLog()
     directly.
  2. An AlertDialog is shown from that confirm state, with both a confirm
     and a dismiss button.
  3. vm.clearLog() is reachable only from the dialog's confirm path (the
     literal `onClick = { vm.clearLog() }` — Clear firing with no confirm —
     must not exist).
  4. The dialog copy warns that WORKED marks are deleted too, so the user
     knows the learned sweep order is affected.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
LOG_SCREEN = os.path.join(SRC, "ui", "LogScreen.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(LOG_SCREEN) as f:
    src = f.read()

# 1. confirm state exists and the Clear button arms it ------------------------
check(re.search(r"var showClearConfirm by remember \{ mutableStateOf\(false\) \}",
                src) is not None,
      "LogScreen.kt must hold a showClearConfirm dialog state")
check("OutlinedButton(onClick = { showClearConfirm = true })" in src,
      "the Clear button must arm the confirm dialog, not clear directly")

# 2. the dialog is real: AlertDialog with confirm + dismiss --------------------
check("AlertDialog(" in src,
      "LogScreen.kt must show an AlertDialog for the clear confirmation")
check(re.search(r"if \(showClearConfirm\) \{\s*\n?\s*AlertDialog\(", src) is not None,
      "the AlertDialog must be gated on the confirm state")
check("confirmButton" in src and "dismissButton" in src,
      "the confirm dialog must have both confirm and dismiss buttons")

# 3. vm.clearLog() is reachable only via the confirmed path --------------------
check("vm.clearLog()" in src,
      "the dialog's confirm path must still call vm.clearLog()")
check("onClick = { vm.clearLog() }" not in src,
      "vm.clearLog() must never be wired directly to a button onClick")

# 4. the dialog copy explains what's lost --------------------------------------
check(re.search(r"WORKED", src) is not None and
      re.search(r"learned sweep order", src) is not None,
      "the confirm dialog must warn that WORKED marks (and the learned "
      "sweep order) are deleted too")

if fails:
    print("test_log_clear_confirm.py: FAIL")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("test_log_clear_confirm.py: PASS")
