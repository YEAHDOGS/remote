#!/usr/bin/env python3
"""DOGS Remote — regression tests for the IR database read-path hardening.

Runs without Android tooling. Covers the contract behind ir/IrDatabase.kt:

  1. A missing/unreadable asset must never crash the app at startup —
     RemoteViewModel builds its state from loadIrDatabase() at init, so the
     asset read must catch and return an empty IrDatabase.
  2. Malformed top-level JSON must yield an empty IrDatabase, not throw.
  3. One malformed brand/variant must not nuke the rest — per-entry parsing
     is isolated and bad rows are skipped.
  4. Duplicate variant ids collapse to the first (associateBy would silently
     drop, so the parse dedupes explicitly).
  5. Button validation: blank ids dropped, freq must be in the realistic IR
     band (10kHz–100kHz), patterns must be non-empty, length-bounded, and all
     positive — nothing malformed reaches the emitter.
  6. magic_order skips blank/duplicate ids; unknown ids never break magic
     mode (resolved through variantsById).

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly. The behavioral contract is checked
with a Python mirror of parseIrDatabase() run against adversarial fixtures.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
IR = os.path.join(SRC, "ir", "IrDatabase.kt")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(IR) as f:
    ir = f.read()
with open(VM) as f:
    vm = f.read()

# --- the startup-crash contract ---------------------------------------------
check("loadIrDatabase(app)" in vm or "val db: IrDatabase = loadIrDatabase(app)" in vm,
      "RemoteViewModel must still build its database from loadIrDatabase(app)")

# --- 1. asset read tolerance ------------------------------------------------
loader = re.search(r"fun loadIrDatabase\(context: Context\): IrDatabase \{(.*?)\n\}\n\n/\*\*", ir, re.S)
check(loader is not None, "loadIrDatabase body must be parseable")
lbody = loader.group(1) if loader else ""
check("context.assets.open(\"ir_database.json\")" in lbody,
      "loadIrDatabase must read the ir_database.json asset")
check(re.search(r"catch \(t: Throwable\)", lbody) is not None,
      "asset read must be wrapped in try/catch")
check("return IrDatabase(emptyList(), emptyList())" in lbody,
      "missing/unreadable asset must yield an empty IrDatabase, not throw")

# --- 2. corrupt JSON tolerance ----------------------------------------------
check("parseIrDatabase" in lbody and "JSONObject(text)" in lbody,
      "loadIrDatabase must route the raw text through a parse step")
check(re.search(r"return IrDatabase\(emptyList\(\), emptyList\(\)\)", lbody) is not None and
      lbody.count("catch (t: Throwable)") >= 2,
      "malformed JSON must also yield an empty IrDatabase, not throw")

# --- 3. per-entry isolation ---------------------------------------------------
parser = re.search(r"internal fun parseIrDatabase\(root: JSONObject\): IrDatabase \{(.*?)\n\}\n\n/\*\*", ir, re.S)
check(parser is not None, "parseIrDatabase body must be parseable")
pbody = parser.group(1) if parser else ""
check("optJSONArray" in pbody and "getJSONArray" not in pbody,
      "parseIrDatabase must use optJSONArray (missing arrays are tolerated, not fatal)")
check("optString" in pbody and "getString" not in pbody,
      "parseIrDatabase must use optString (missing fields are tolerated, not fatal)")
check("catch (t: Throwable)" in pbody and "skipping malformed IR variant" in pbody,
      "a malformed variant must be skipped without killing the rest")

# --- 4. duplicate variant ids -----------------------------------------------
check("seenVariantIds" in pbody or "seen" in pbody,
      "parse must dedupe variant ids explicitly (associateBy would silently collapse them)")

# --- 5. button validation ---------------------------------------------------
btn = re.search(r"private fun parseIrButtons\(btnObj: JSONObject\?\): Map<String, IrButton> \{(.*?)\n\}", ir, re.S)
check(btn is not None, "parseIrButtons body must be parseable")
bbody = btn.group(1) if btn else ""
check("key.isBlank()" in bbody, "blank button ids must be dropped")
check("IR_FREQ_MIN_HZ" in bbody and "IR_FREQ_MAX_HZ" in bbody,
      "buttons must be validated against the IR frequency band")
check("IR_PATTERN_MAX_WORDS" in bbody and "pArr.length() == 0" in bbody,
      "patterns must be non-empty and length-bounded")
check("it <= 0" in bbody,
      "patterns containing non-positive words must be rejected (emitter safety)")

# --- 6. magic order hygiene ---------------------------------------------------
check("id !in magicOrder" in pbody or "distinct" in pbody,
      "magic_order must not contain duplicates")
check("id.isNotEmpty()" in pbody, "magic_order must skip blank ids")

# --- structural: UI already tolerates an empty database ----------------------
check("db.magicVariants.firstOrNull()" in vm,
      "RemoteViewModel must fall back through magicVariants.firstOrNull() (empty-DB safe)")

# --- behavioral: Python mirror of parseIrDatabase against adversarial fixtures
FREQ_MIN, FREQ_MAX, PAT_MAX = 10000, 100000, 10000


def parse_buttons(btn_obj):
    out = {}
    if not isinstance(btn_obj, dict):
        return out
    for key, d in btn_obj.items():
        if not isinstance(key, str) or not key.strip():
            continue
        if not isinstance(d, dict):
            continue
        freq = d.get("freq")
        if not isinstance(freq, int) or isinstance(freq, bool) or not (FREQ_MIN <= freq <= FREQ_MAX):
            continue
        pat = d.get("pattern")
        if not isinstance(pat, list) or not pat or len(pat) > PAT_MAX:
            continue
        words = []
        ok = True
        for w in pat:
            if not isinstance(w, int) or isinstance(w, bool) or w <= 0:
                ok = False
                break
            words.append(w)
        if not ok:
            continue
        out[key] = {"id": key, "freqHz": freq, "pattern": words}
    return out


def parse_ir_database(root):
    if not isinstance(root, dict):
        return {"brands": [], "magic_order": []}
    brands, seen = [], set()
    brand_arr = root.get("brands")
    if not isinstance(brand_arr, list):
        return {"brands": [], "magic_order": []}
    for b in brand_arr:
        if not isinstance(b, dict):
            continue
        name = b.get("brand")
        if not isinstance(name, str) or not name.strip():
            continue
        variants = []
        var_arr = b.get("variants")
        if isinstance(var_arr, list):
            for v in var_arr:
                if not isinstance(v, dict):
                    continue
                vid = v.get("id")
                if not isinstance(vid, str) or not vid.strip():
                    continue
                if vid in seen:
                    continue
                seen.add(vid)
                variants.append({
                    "id": vid,
                    "buttons": parse_buttons(v.get("buttons")),
                })
        brands.append({"name": name, "variants": variants})
    order, order_arr = [], root.get("magic_order")
    if isinstance(order_arr, list):
        for i in order_arr:
            i = i.strip() if isinstance(i, str) else ""
            if i and i not in order:
                order.append(i)
    return {"brands": brands, "magic_order": order}


def resolve_magic(db):
    by_id = {v["id"]: v for b in db["brands"] for v in b["variants"]}
    return [by_id[i] for i in db["magic_order"] if i in by_id]


def fixture():
    return {
        "brands": [
            {"brand": "Good", "variants": [
                {"id": "good_v1", "label": "Good One", "buttons": {
                    "power": {"freq": 38000, "pattern": [9000, 4500, 560, 560]}}},
                {"id": "good_v1", "label": "Duplicate — must be dropped", "buttons": {}},
                {"id": "", "label": "Blank id — dropped", "buttons": {}},
                {"id": "bad_buttons", "label": "Bad buttons", "buttons": {
                    "": {"freq": 38000, "pattern": [100, 100]},
                    "weird_freq": {"freq": 50, "pattern": [100, 100]},
                    "empty_pat": {"freq": 38000, "pattern": []},
                    "zero_word": {"freq": 38000, "pattern": [100, 0, 100]},
                    "ok": {"freq": 38000, "pattern": [100, 100]}}},
                "not-a-dict",
                {"id": "no_buttons", "label": "No buttons at all"},
            ]},
            "not-a-dict",
            {"brand": "", "variants": []},
            {"brand": "Empty", "variants": []},
        ],
        "magic_order": ["good_v1", "good_v1", "", "missing_id"],
    }


db = parse_ir_database(fixture())
good_brand = db["brands"][0]
check(len(db["brands"]) == 2, f"only two valid brands survive; got {len(db['brands'])}")
check(len(good_brand["variants"]) == 3,
      f"duplicate + blank-id variants dropped, bad rows kept isolated; got {len(good_brand['variants'])}")
check(good_brand["variants"][0]["buttons"]["power"]["freqHz"] == 38000,
      "the good variant's good button survives")
bad = [v for v in good_brand["variants"] if v["id"] == "bad_buttons"][0]
check(list(bad["buttons"].keys()) == ["ok"],
      f"only the valid button survives validation; got {list(bad['buttons'].keys())}")
check(db["magic_order"] == ["good_v1", "missing_id"],
      f"magic_order deduped and blank-stripped; got {db['magic_order']}")
check(len(resolve_magic(db)) == 1,
      "unknown magic_order ids resolve to nothing instead of breaking magic mode")

# corrupt top-level JSON must yield an empty database, not throw
try:
    broken = json.loads("{not json")
    failed = True
except json.JSONDecodeError:
    broken, failed = None, False
check(broken is None and not failed, "corrupt JSON is caught before parsing")
check(parse_ir_database(broken) == {"brands": [], "magic_order": []},
      "non-dict root yields an empty database")
check(parse_ir_database({"brands": "nope"}) == {"brands": [], "magic_order": []},
      "wrong-typed brands yields an empty database")

# the shipped database must still parse fully under the hardened contract
with open(os.path.join(HERE, "..", "app", "src", "main", "assets", "ir_database.json")) as f:
    shipped = parse_ir_database(json.load(f))
nvar = sum(len(b["variants"]) for b in shipped["brands"])
nbtn = sum(len(v["buttons"]) for b in shipped["brands"] for v in b["variants"])
check(nvar == 11 and nbtn == 146,
      f"shipped database fully parses under hardened contract (11 variants, 146 buttons); got {nvar}/{nbtn}")

if fails:
    print(f"{len(fails)} FAILURES:")
    for f in fails:
        print(f"  - {f}")
    raise SystemExit(1)
print("OK: IR database read-path regression tests passed")
