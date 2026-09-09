#!/usr/bin/env python3
"""DOGS Remote — regression tests for the Magic Mode probe-signal selector.

Runs without Android tooling. Covers the contract behind RemoteViewModel's
magicProbeButtonId / setMagicProbeButtonId and the Magic screen chips:

  1. Default probe unchanged: MAGIC_PROBE_DEFAULT is "vol_up" and no magic
     path still hard-codes the "vol_up" literal (the only literals left are
     the selector list itself and the default constant).
  2. Selector -> IR vector mapping: every option in MAGIC_PROBE_OPTIONS
     exists on every magic variant in ir_database.json with a sane carrier
     freq, so a probe choice can never silently skip variants.
  3. Guard rails: setMagicProbeButtonId rejects ids outside the option list,
     and magicWorked falls back to the default for an unknown probe id —
     the attempt log can never hold a bogus button id.
  4. Carrier warnings still fire: unsupportedVariantIds still flags a variant
     on ANY out-of-range button (probe-independent), and MagicScreen still
     surfaces the warning. A narrow 37-39kHz emitter flags exactly philips
     (36k) + sony (40k) no matter which probe is selected.

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly.
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
DB = os.path.join(HERE, "..", "app", "src", "main", "assets", "ir_database.json")
ALLOWED_FREQS = {36000, 37000, 37900, 37917, 38000, 40000}

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


def src_text(*path):
    with open(os.path.join(SRC, *path)) as f:
        return f.read()


view_model = src_text("ui", "RemoteViewModel.kt")
magic = src_text("ui", "MagicScreen.kt")

# 1. default probe unchanged ---------------------------------------------
check('const val MAGIC_PROBE_DEFAULT = "vol_up"' in view_model,
      "MAGIC_PROBE_DEFAULT must be \"vol_up\" (historical behavior)")

start_magic = view_model.split("fun startMagic()")[1].split("\n    }\n")[0]
check('"vol_up"' not in start_magic,
      "startMagic must not hard-code the \"vol_up\" literal anymore; "
      "it should use the captured probe id")

# 2. selector -> IR vector mapping -----------------------------------------
m = re.search(r"val MAGIC_PROBE_OPTIONS = listOf\(([^)]*)\)", view_model)
check(m is not None, "MAGIC_PROBE_OPTIONS listOf not found in RemoteViewModel.kt")
options = re.findall(r'"([a-z_0-9]+)"', m.group(1)) if m else []
check(options == ["mute", "vol_up", "vol_down", "power"],
      "probe options must be exactly [mute, vol_up, vol_down, power], got %s" % options)

db = json.load(open(DB))
variants = [v for b in db["brands"] for v in b["variants"]]
check(len(variants) > 0, "no variants in ir_database.json")
for opt in options:
    missing = [v["id"] for v in variants if opt not in v["buttons"]]
    check(not missing,
          "probe option %r missing from variants %s — the sweep would skip them"
          % (opt, missing))
    for v in variants:
        btn = v["buttons"].get(opt)
        if btn is not None:
            check(btn["freq"] in ALLOWED_FREQS,
                  "%s %s has unexpected carrier freq %s" % (v["id"], opt, btn["freq"]))
            check(isinstance(btn.get("pattern"), list) and len(btn["pattern"]) > 0,
                  "%s %s has no IR pattern" % (v["id"], opt))

# 3. guard rails ------------------------------------------------------------
check("fun setMagicProbeButtonId(buttonId: String)" in view_model,
      "setMagicProbeButtonId setter missing")
check("if (buttonId in MAGIC_PROBE_OPTIONS) magicProbeButtonId = buttonId" in view_model,
      "setMagicProbeButtonId must clamp to MAGIC_PROBE_OPTIONS")
check("if (probeButtonId in MAGIC_PROBE_OPTIONS) probeButtonId else MAGIC_PROBE_DEFAULT"
      in view_model,
      "magicWorked must fall back to the default for unknown probe ids")

# magicWorked callers: UI must pass the tap-time-captured probe id
check("vm.magicWorked(" in magic and "workedProbeButtonId" in magic,
      "MagicScreen must pass the tap-time-captured probe id to magicWorked")

# selector UI renders one chip per option, labels from BUTTON_LABELS
check("FilterChip" in magic and "MAGIC_PROBE_OPTIONS" in magic
      and "vm.setMagicProbeButtonId(id)" in magic,
      "MagicScreen must render a FilterChip per probe option wired to setMagicProbeButtonId")


def is_carrier_supported(freq_hz, ranges):
    """Python mirror of IrSender.kt isCarrierSupported() (fail-open)."""
    if not ranges:
        return True
    return any(lo <= freq_hz <= hi for lo, hi in ranges)


# 4. carrier warnings still fire, probe-independently ------------------------
def unsupported_ids(ranges):
    return {v["id"] for v in variants
            if any(not is_carrier_supported(d["freq"], ranges)
                   for d in v["buttons"].values())}


check(unsupported_ids([(37000, 39000)]) == {"philips-tv-rc5-0", "sony-tv-sony12-1"},
      "narrow 37-39kHz emitter must still flag exactly philips (36k) + sony (40k) "
      "regardless of probe choice: got %s" % sorted(unsupported_ids([(37000, 39000)])))

check("v.buttons.values.any" in view_model,
      "unsupportedVariantIds must keep flagging on ANY out-of-range button "
      "(probe choice must not narrow the warning)")
check("vm.unsupportedVariantIds" in magic,
      "MagicScreen must keep surfacing the carrier warning")

if fails:
    print("test_probe_selector.py: FAIL")
    for f in fails:
        print("  -", f)
    raise SystemExit(1)
print("test_probe_selector.py: PASS (%d options, %d variants)" % (len(options), len(variants)))
