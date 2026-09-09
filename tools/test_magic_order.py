#!/usr/bin/env python3
"""DOGS Remote — regression tests for the learned Magic Mode sweep order.

Runs without Android tooling. Covers the contract behind
ir/MagicOrder.kt's magicSweepOrder() and its wiring in
ui/RemoteViewModel.kt:

  1. magicSweepOrder() is pure and deterministic: no Android imports, no
     side effects, (variants, attempts) -> reordered variants.
  2. WORKED attempts only influence the order — mere attempts are not
     evidence anything responds.
  3. Worked variants come first, newest worked ts first; unknown variant
     ids in the log are ignored; never-worked variants keep the shipped
     magic order behind them; empty history yields the shipped order.
  4. startMagic() sweeps in learned order (captured before launch so a
     sweep in flight can't be rewired); Blast Mode stays on the shipped
     magic order (it is the exhaustive manual sweep, not the smart one).
  5. The reset affordance: clearedAtMs filters out pre-reset WORKED
     attempts; RemoteViewModel persists the reset timestamp and gates the
     UI affordance on hasLearnedOrder.

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly. The Python ORDER() function
below mirrors the documented contract and runs the fixtures through it,
so the expected behavior is pinned in two independent forms.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
ORDER = os.path.join(SRC, "ir", "MagicOrder.kt")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(ORDER) as f:
    order = f.read()
with open(VM) as f:
    vm = f.read()

# --- 1. purity --------------------------------------------------------------
check("fun magicSweepOrder(" in order, "magicSweepOrder() must exist")
check("import android" not in order, "magicSweepOrder must have no Android imports (pure)")
m = re.search(r"fun magicSweepOrder\((.*?)\): List<IrVariant> \{", order, re.S)
check(m is not None and "magicVariants" in m.group(1) and "attempts" in m.group(1),
      "magicSweepOrder must take (variants, attempts) and return ordered variants")
check("import net.dogs.remote.data.Attempt" in order, "must take the app's Attempt type")

# --- 2. worked-only influence -------------------------------------------------
check(re.search(r"\.filter\s*\{\s*it\.worked\s*(&&\s*it\.ts\s*>=\s*clearedAtMs\s*)?\}", order) is not None,
      "only WORKED attempts may influence the order — mere attempts are not evidence")

# --- 3. ordering semantics ----------------------------------------------------
check(".groupBy { it.variantId }" in order, "worked attempts must be grouped by variantId")
check("maxOf { it.ts }" in order, "a variant's rank must be its most-recent worked timestamp")
check(re.search(r"\.filter\s*\{\s*it\.worked\s*&&\s*it\.ts\s*>=\s*clearedAtMs\s*\}", order) is not None,
      "reset support: WORKED attempts older than clearedAtMs must not influence the order")
check(re.search(r"clearedAtMs:\s*Long\s*=\s*0", order) is not None,
      "clearedAtMs must default to 0 so the old two-arg call keeps full history")
check(".partition { it.id in lastWorkedTs }" in order or
      re.search(r"\.partition\s*\{\s*it\.id in \w+\s*\}", order) is not None,
      "variants must be partitioned into known-worked vs never-worked")
check("sortedByDescending" in order, "known variants must sort newest-first by worked ts")
lm = re.search(r"return known\w* \+ unknown\w*", order)
check(lm is not None, "known-worked variants must come first, never-worked after them")

# --- 4. wiring ----------------------------------------------------------------
check("import net.dogs.remote.ir.magicSweepOrder" in vm,
      "RemoteViewModel must import magicSweepOrder")
sm = re.search(r"fun startMagic\(\) \{(.*?)\n    \}\n\n    fun stopMagic", vm, re.S)
check(sm is not None, "startMagic() body must be parseable")
sbody = sm.group(1) if sm else ""
check("magicSweepOrder(db.magicVariants, attempts, magicOrderResetTs)" in sbody,
      "startMagic() must capture the learned order up front, honoring the reset timestamp")
check("db.magicVariants" not in sbody.replace("magicSweepOrder(db.magicVariants, attempts, magicOrderResetTs)", ""),
      "startMagic() must not fall back to raw magic order elsewhere")
bm = re.search(r"fun startBlast\(buttonId: String\) \{(.*?)\n    \}", vm, re.S)
check(bm is not None and "val variants = db.magicVariants" in bm.group(1),
      "Blast Mode must stay on the shipped magic order (exhaustive, not smart)")

# --- contract fixtures (Python mirror of the documented contract) -------------
def ORDER_PURE(variants, attempts, cleared_at_ms=0):
    last = {}
    for a in attempts:
        if a["worked"] and a["ts"] >= cleared_at_ms:
            last[a["variantId"]] = max(last.get(a["variantId"], 0), a["ts"])
    known = sorted([v for v in variants if v in last],
                   key=lambda v: last[v], reverse=True)
    return known + [v for v in variants if v not in last]

V = ["samsung", "lg", "sony", "philips"]
A = [
    {"variantId": "samsung", "ts": 999, "worked": False},  # attempt != evidence
    {"variantId": "lg", "ts": 100, "worked": True},
    {"variantId": "sony", "ts": 300, "worked": True},
    {"variantId": "bogus", "ts": 500, "worked": True},     # unknown id ignored
]
check(ORDER_PURE(V, A) == ["sony", "lg", "samsung", "philips"],
      f"contract: worked-first newest-first, unknown ids ignored, rest in magic order; got {ORDER_PURE(V, A)}")
check(ORDER_PURE(V, []) == V, "contract: empty history yields the shipped order unchanged")
check(ORDER_PURE(V, [{"variantId": "philips", "ts": 0, "worked": True}]) == ["philips", "samsung", "lg", "sony"],
      "contract: a single worked variant jumps to the front")
check(ORDER_PURE(V, A, cleared_at_ms=1000) == V,
      "contract: a reset clears learned order — pre-reset WORKED attempts are ignored")
check(ORDER_PURE(V, A, cleared_at_ms=200) == ["sony", "samsung", "lg", "philips"],
      "contract: reset cuts off at the timestamp — lg (ts 100) forgotten, sony (ts 300) kept")

# --- 5. reset affordance wiring ------------------------------------------------
check("fun resetLearnedOrder()" in vm, "RemoteViewModel must expose resetLearnedOrder()")
check('"magic_order_reset_ts"' in vm, "reset timestamp must be persisted under a dedicated prefs key")
check("magicPrefs.edit().putLong(MAGIC_ORDER_RESET_KEY, now).apply()" in vm,
      "resetLearnedOrder() must persist the reset timestamp before updating state")
check(re.search(r"val hasLearnedOrder.*\n.*attempts\.any \{ it\.worked && it\.ts >= magicOrderResetTs \}", vm) is not None,
      "hasLearnedOrder must be true only when a WORKED attempt newer than the reset exists")

if fails:
    print(f"{len(fails)} FAILURES:")
    for f in fails:
        print(f"  - {f}")
    raise SystemExit(1)
print("OK: magic-order regression tests passed")
