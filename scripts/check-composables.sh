#!/usr/bin/env bash
# check-composables.sh — regression guard for the pure-Compose UI.
#
# History: a past commit (f52525d) broke the build because MainActivity.kt
# contained a bare `return` inside a @Composable function body. The Kotlin
# compiler prohibits non-local returns in composables; the fix was to
# restructure to an if/else branch. This script makes that mistake fail
# loudly at commit time instead of at compile time.
#
# Fails (exit 1) if any BARE `return` (not `return@label`) appears inside a
# @Composable function body. Labeled returns (return@forEach, return@launch,
# ...) are legal and deliberately ignored, as are the word `return` inside
# comments and string literals.
#
# Heuristic, not a full parser: it strips comments/string literals, then
# tracks braces per @Composable function body. Local (non-composable) helper
# functions nested inside a composable body would be scanned too — avoid that
# pattern or restructure; a hit there wants a human look anyway.
#
# Safe to run offline; no Android SDK needed.

set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PYEOF'
import re, sys, pathlib

BARE_RETURN = re.compile(r'(?<![\w$.])return(?![\w$@])')

def strip_code(text):
    """Strip block/line comments, string/char literals, triple-quoted strings."""
    out, i, n = [], 0, len(text)
    in_block = False
    while i < n:
        if in_block:
            j = text.find('*/', i)
            i = n if j == -1 else j + 2
            in_block = False
            continue
        if text.startswith('/*', i):
            in_block = True; i += 2; continue
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j == -1 else j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j == -1 else j + 3
            continue
        c = text[i]
        if c in ('"', "'"):
            j = i + 1
            while j < n:
                if text[j] == '\\': j += 2; continue
                if text[j] == c: break
                if c == '"' and text[j] == '\n': break
                j += 1
            i = j + 1
            continue
        out.append(c); i += 1
    return ''.join(out)

def check(path):
    raw = pathlib.Path(path).read_text()
    code = strip_code(raw)
    fails = []
    pat = re.compile(r'@Composable\s*(?:@\w+\s*)*?'
                     r'(?:private\s+|public\s+|internal\s+|protected\s+)?fun\b')
    for m in pat.finditer(code):
        brace = code.find('{', m.end())
        if brace == -1:
            continue
        depth, j = 0, brace
        while j < len(code):
            if code[j] == '{': depth += 1
            elif code[j] == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        body = code[brace:j]
        body_start_line = code.count('\n', 0, brace) + 1
        for li, line in enumerate(body.split('\n'), start=body_start_line):
            if BARE_RETURN.search(line):
                fails.append(f"{path}:{li}: bare 'return' inside @Composable body")
    return fails

fails = []
for p in sorted(pathlib.Path('app/src/main').rglob('*.kt')):
    fails += check(p)

for f in fails:
    print(f"check-composables FAIL: {f}")
if fails:
    print("Bare 'return' is not allowed in a @Composable function — "
          "restructure to if/else (see commit f52525d).")
    sys.exit(1)
print("check-composables OK: no bare returns in @Composable bodies.")
PYEOF
