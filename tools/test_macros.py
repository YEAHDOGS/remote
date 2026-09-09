#!/usr/bin/env python3
"""DOGS Remote — regression tests for the macro (sequence playback) system.

Runs without Android tooling. Covers the contract behind
data/MacroStore.kt, RemoteViewModel macro playback, and the Macros tab:

  1. Bounds: macros are capped, steps per macro are capped, and per-step
     delays are clamped to a sane window — a macro can never hang the
     emitter for minutes or spam the log unboundedly.
  2. Sanitization: blank names rejected, blank button ids dropped, delays
     coerced, names trimmed/limited. Playback must never trust stored data.
  3. Playback safety: a single cancellable macroJob (guarded re-entry),
     ensureActive() in the loop, the transmitAndReport funnel (so failure
     feedback still works), delay re-clamped at playback, stale button ids
     skipped, every fired step logged, state written with the progress.
  4. UI wiring: MacrosScreen reads vm.macros / vm.macroState, wires Run to
     vm.runMacro and Stop to vm.stopMacro, and the Macros tab is routed in
     MainActivity.

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
STORE = os.path.join(SRC, "data", "MacroStore.kt")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")
MACROS_SCREEN = os.path.join(SRC, "ui", "MacrosScreen.kt")
MAIN = os.path.join(SRC, "MainActivity.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(STORE) as f:
    store = f.read()
with open(VM) as f:
    vm = f.read()
with open(MACROS_SCREEN) as f:
    screen = f.read()
with open(MAIN) as f:
    main = f.read()

# --- 1. bounds ------------------------------------------------------------
def const(name):
    m = re.search(r"const val %s\s*=\s*(\d+)" % name, store)
    return int(m.group(1)) if m else None


max_macros = const("MAX_MACROS")
max_steps = const("MAX_STEPS")
delay_min = const("STEP_DELAY_MIN_MS")
delay_max = const("STEP_DELAY_MAX_MS")
check(max_macros is not None and 1 <= max_macros <= 50,
      f"MAX_MACROS must be a sane cap (1..50); got {max_macros}")
check(max_steps is not None and 1 <= max_steps <= 24,
      f"MAX_STEPS must bound a playback run (1..24); got {max_steps}")
check(delay_min is not None and delay_min >= 50,
      f"STEP_DELAY_MIN_MS must avoid zero/pointless delays; got {delay_min}")
check(delay_max is not None and delay_max <= 10000,
      f"STEP_DELAY_MAX_MS must cap worst-case hang (<=10s); got {delay_max}")
check("NAME_MAX_LEN" in store, "macro names must have a length cap")

# --- 2. sanitization --------------------------------------------------------
check("fun sanitize(macro: IrMacro): IrMacro?" in store,
      "MacroStore must expose a sanitizer that can reject a macro")
check("name.isEmpty()) return null" in store or "name.isEmpty())" in store,
      "sanitize must reject blank names")
check(".take(MAX_STEPS)" in store, "sanitize must cap steps")
check("coerceIn(STEP_DELAY_MIN_MS, STEP_DELAY_MAX_MS)" in store,
      "sanitize must clamp delays into bounds")
check("filter { it.buttonId.isNotBlank() }" in store,
      "sanitize must drop steps with blank button ids")
check("steps.isEmpty()) return null" in store,
      "sanitize must reject macros with no usable steps")
check("fun save(macro: IrMacro): Boolean" in store,
      "save must report success/failure so the UI can surface a rejection")
check("if (isNew && current.size >= MAX_MACROS) return false" in store,
      "save must refuse brand-new macros past the cap (updates always allowed)")

# --- 3. playback safety -----------------------------------------------------
check("var macros by mutableStateOf(" in vm and "private set" in vm,
      "RemoteViewModel must expose macros state")
check("sealed interface MacroState" in vm, "MacroState must exist")
check("data class Running(val macroId: String, val name: String, val done: Int, val total: Int)" in vm,
      "MacroState.Running must carry id, name and progress")
check("private var macroJob: Job? = null" in vm,
      "macro playback must be a single Job field")
check("if (macroJob?.isActive == true) return" in vm,
      "runMacro must guard against a second concurrent playback")
check("fun stopMacro()" in vm and "macroJob?.cancel()" in vm,
      "stopMacro must cancel the playback job")
# playback loop safety, inside runMacro
run = re.search(r"fun runMacro\([^{]*\{(.*?)\n    \}\n\n    fun stopMacro", vm, re.S)
if run is None:
    run = re.search(r"fun runMacro\([^{]*\{(.*?)macroState = MacroState\.Idle\n        \}\n    \}", vm, re.S)
check(run is not None, "runMacro body must be parseable")
body = run.group(1) if run else ""
check("ensureActive()" in body, "macro playback loop must be cancellable via ensureActive()")
check("transmitAndReport(" in body,
      "macro steps must funnel through transmitAndReport (failure feedback + single funnel)")
check("sender.transmit(" not in body,
      "macro playback must not bypass the funnel with a raw sender call")
check("step.delayAfterMs.coerceIn(" in body,
      "runMacro must re-clamp the delay at playback (stored data may predate the bounds)")
check("variant.buttons[step.buttonId]" in body,
      "runMacro must resolve each step against the current database")
check("if (btn != null)" in body,
      "runMacro must skip stale button ids instead of crashing")
check("attemptLog.log(" in body,
      "runMacro must log every fired step like any other transmit")
check("MacroState.Running(" in body and "macroState = MacroState.Idle" in body,
      "runMacro must report progress and reset to Idle when done")
check("fun addMacro(" in vm and "fun updateMacro(" in vm and "fun deleteMacro(" in vm,
      "RemoteViewModel must expose add/update/delete macro CRUD")
check("db.variantsById[variantId] == null) return false" in vm,
      "add/update must reject unknown variant ids")
check("macroState is MacroState.Running" in vm,
      "deleteMacro must stop a running playback of the macro being deleted")

# --- 4. UI wiring -----------------------------------------------------------
check("vm.macros" in screen, "MacrosScreen must read vm.macros")
check("vm.macroState" in screen, "MacrosScreen must read vm.macroState")
check("vm.runMacro(" in screen, "MacrosScreen must wire Run to vm.runMacro")
check("vm.stopMacro()" in screen, "MacrosScreen must wire Stop to vm.stopMacro")
check("vm.deleteMacro(" in screen, "MacrosScreen must wire Delete to vm.deleteMacro")
check("MacroStore.MAX_STEPS" in screen,
      "the editor must enforce the MAX_STEPS cap in the UI")
check("MacroStore.STEP_DELAY_MAX_MS" in screen,
      "the editor must not accept unbounded pauses")
check("fun MacrosScreen(vm: RemoteViewModel)" in screen,
      "MacrosScreen must take the shared ViewModel")
check('Macros("Macros")' in main, "MainActivity must define the Macros tab")
check("Tab.Macros -> MacrosScreen(vm)" in main,
      "MainActivity must route the Macros tab to MacrosScreen")
check("import net.dogs.remote.ui.MacrosScreen" in main,
      "MainActivity must import MacrosScreen")

# --- 5. read-path hardening -------------------------------------------------
# list() feeds ViewModel init — corrupt prefs data must never crash the app
# at startup, and legacy rows must be re-sanitized on load.
lm = re.search(r"fun list\(\): List<IrMacro> \{(.*?)\n    \n    \}", store, re.S)
if lm is None:
    lm = re.search(r"fun list\(\): List<IrMacro> \{(.*?)\n    \}\n\n    /\*\*", store, re.S)
check(lm is not None, "list() body must be parseable")
lbody = lm.group(1) if lm else ""
check("catch" in lbody and "return emptyList()" in lbody,
      "list() must catch corrupt JSON and return an empty list instead of crashing")
check("minOf(arr.length(), MAX_MACROS)" in lbody,
      "list() must cap the parsed list at MAX_MACROS even if storage grew past it")
check(re.search(r"\.mapNotNull\s*\{\s*sanitize\(it\)\s*\}", lbody) is not None,
      "list() must re-sanitize every loaded entry (legacy/stale rows)")
check("parseMacro" in lbody and "private fun parseMacro" in store,
      "per-entry parsing must be isolated so one malformed entry doesn't nuke the rest")

if fails:
    print(f"{len(fails)} FAILURES:")
    for f in fails:
        print(f"  - {f}")
    raise SystemExit(1)
print("OK: macro system regression tests passed")
