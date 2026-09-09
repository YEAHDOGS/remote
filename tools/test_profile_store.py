#!/usr/bin/env python3
"""DOGS Remote — regression tests for the ProfileStore read-path hardening.

Runs without Android tooling. Covers the contract behind
data/ProfileStore.kt:

  1. Corrupt prefs data must never crash the app at startup — RemoteViewModel
     builds its state from list() at init, so list() must catch bad JSON and
     return an empty list.
  2. One malformed entry must not nuke the rest — per-entry parsing is
     isolated, bad rows are skipped.
  3. Legacy rows are re-sanitized on every load: blank ids/names/variant ids
     are dropped, names are trimmed and capped at NAME_MAX_LEN.
  4. The parsed list is capped at MAX_PROFILES even if the stored array grew
     past it by other means.
  5. The at-most-one-default invariant survives a botched write — multiple
     stored defaults collapse to the first on load.
  6. Writes sanitize too: save() and rename() never persist an unsanitized
     name, so corruption can't be reintroduced from the UI path.

Structural checks parse the Kotlin source directly — this test and the app
can't drift apart without failing loudly.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "net", "dogs", "remote")
STORE = os.path.join(SRC, "data", "ProfileStore.kt")
VM = os.path.join(SRC, "ui", "RemoteViewModel.kt")

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


with open(STORE) as f:
    store = f.read()
with open(VM) as f:
    vm = f.read()

# --- the crash contract ---------------------------------------------------
# RemoteViewModel reads profiles at init, so list() failing = app fails to start.
check("profileStore.list()" in vm,
      "RemoteViewModel must still build its profiles state from profileStore.list()")

lm = re.search(r"fun list\(\): List<TvProfile> \{(.*?)\n    \}\n\n    /\*\*", store, re.S)
check(lm is not None, "list() body must be parseable")
lbody = lm.group(1) if lm else ""

# --- 1. corrupt JSON tolerance --------------------------------------------
check(re.search(r"try\s*\{\s*\n?\s*JSONArray\(prefs\.getString\(KEY", lbody) is not None,
      "list() must wrap the JSONArray parse in try/catch")
check("catch (e: Exception)" in lbody and "return emptyList()" in lbody,
      "corrupt JSON must yield an empty list, not throw")

# --- 2. per-entry isolation -------------------------------------------------
check("parseProfile(arr.getJSONObject(i))" in lbody,
      "list() must parse each entry through a dedicated parseProfile()")
check(re.search(r"try\s*\{\s*\n?\s*parseProfile", lbody) is not None,
      "each entry parse must be individually try/caught")
check(".mapNotNull { it }.mapNotNull { sanitize(it) }" in lbody,
      "bad rows must be skipped (mapNotNull) and surviving rows re-sanitized")

# --- 3. sanitize-on-load contract -------------------------------------------
check("fun sanitize(profile: TvProfile): TvProfile?" in store,
      "companion must expose sanitize(profile): TvProfile?")
check('profile.name.trim().take(NAME_MAX_LEN)' in store,
      "sanitize must trim names and cap at NAME_MAX_LEN")
check("profile.id.isBlank() || name.isEmpty() || profile.variantId.isBlank()" in store,
      "sanitize must reject blank id, blank name, or blank variantId")


def const(name):
    m = re.search(r"const val %s\s*=\s*(\d+)" % name, store)
    return int(m.group(1)) if m else None


max_profiles = const("MAX_PROFILES")
name_max = const("NAME_MAX_LEN")
check(max_profiles is not None and 1 <= max_profiles <= 100,
      "MAX_PROFILES must be a sane positive cap (got %r)" % max_profiles)
check(name_max is not None and 1 <= name_max <= 80,
      "NAME_MAX_LEN must be a sane positive cap (got %r)" % name_max)

# --- 4. load cap -------------------------------------------------------------
check("minOf(arr.length(), MAX_PROFILES)" in lbody,
      "list() must cap the parsed array at MAX_PROFILES")

# --- 5. at-most-one-default on load ------------------------------------------
check("seenDefault" in lbody and "p.copy(isDefault = false)" in lbody,
      "list() must collapse multiple stored defaults to the first")

# --- 6. write-side sanitization ----------------------------------------------
check("val clean = sanitize(profile) ?: return" in store,
      "save() must sanitize before persisting")
check('name.trim().take(NAME_MAX_LEN)' in store and "if (cleanName.isEmpty()) return" in store,
      "rename() must trim/cap the name and refuse blank names")

# --- delete() still keeps exactly one default --------------------------------
check("remaining.none { it.isDefault }" in store,
      "delete() must still promote a default when the default was deleted")

if fails:
    print("FAIL: profile-store hardening regression tests")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("OK: profile-store hardening regression tests passed")
