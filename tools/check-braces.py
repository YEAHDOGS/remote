#!/usr/bin/env python3
"""Brace/paren/bracket balance guard for Kotlin sources.

Fails fast (non-zero exit) when any *.kt file has unbalanced delimiters,
so CI catches a stray-brace typo BEFORE the slow Gradle build runs.
Catches the class of bug that broke the APK build across 5 takes on 2026-09-09.

Handles Kotlin lexical details:
  - line comments (// ...) and nested block comments (/* ... */)
  - double-quoted strings with escapes and ${...} string templates
    (braces inside templates count; the template itself is balanced too)
  - triple-quoted raw strings (Kotlin's three-quote strings), which also allow ${...}
  - char literals ('x', '\\n', '\\u0041')

Usage: python3 tools/check-braces.py [paths...]
Defaults to every *.kt file under the repo root (excluding build/ outputs).

Local copy: ~/workspace/remote/tools/check-braces.py
"""
import os
import sys

OPENERS = {"{": "}", "(": ")", "[": "]"}
CLOSERS = {v: k for k, v in OPENERS.items()}


def find_kt_files(roots):
    found = []
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            # skip build outputs and VCS dirs
            dirnames[:] = [d for d in dirnames if d not in ("build", ".git", ".gradle")]
            for f in filenames:
                if f.endswith(".kt"):
                    found.append(os.path.join(dirpath, f))
    return sorted(found)


def check_file(path):
    """Return list of error strings; empty means balanced."""
    errors = []
    try:
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
    except OSError as e:
        return [f"{path}: cannot read file: {e}"]

    stack = []  # (opener, line)
    # mode stack: 'code', or ('str', tmpl_depth), ('raw', tmpl_depth)
    modes = ["code"]
    i, n = 0, len(src)
    line = 1

    def cur_line():
        return line

    while i < n:
        mode = modes[-1]
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        if ch == "\n":
            line += 1

        if mode == "line_comment":
            if ch == "\n":
                modes.pop()
            i += 1
            continue

        if mode == "block_comment":
            if ch == "/" and nxt == "*":
                modes.append("block_comment")  # nested
                i += 2
                continue
            if ch == "*" and nxt == "/":
                modes.pop()
                i += 2
                continue
            i += 1
            continue

        if mode == "char":
            if ch == "\\":
                i += 2
                continue
            if ch == "'":
                modes.pop()
                i += 1
                continue
            if ch == "\n":
                errors.append(f"{path}:{cur_line()}: unterminated char literal")
                modes.pop()
                i += 1
                continue
            i += 1
            continue

        if mode[0] in ("str", "raw"):
            kind, depth = mode
            if ch == "\\" and kind == "str":
                i += 2  # escape inside "..." string
                continue
            if ch == "$" and nxt == "{":
                # enter a ${...} template expression: record delimiter-stack
                # depth so the matching '}' can be told apart from real braces
                modes.append(("tmpl", len(stack)))
                modes.append("code")
                i += 2
                continue
            if kind == "str" and ch == '"':
                modes.pop()
                i += 1
                continue
            if kind == "raw" and ch == '"' and src[i : i + 3] == '"""':
                modes.pop()
                i += 3
                continue
            i += 1
            continue

        # mode == "code"
        if ch == "/" and nxt == "/":
            modes.append("line_comment")
            i += 2
            continue
        if ch == "/" and nxt == "*":
            modes.append("block_comment")
            i += 2
            continue
        if ch == '"':
            if src[i : i + 3] == '"""':
                modes.append(("raw", 0))
                i += 3
            else:
                modes.append(("str", 0))
                i += 1
            continue
        if ch == "'":
            modes.append("char")
            i += 1
            continue
        if ch in OPENERS:
            stack.append((ch, cur_line()))
            i += 1
            continue
        if ch in CLOSERS:
            # a '}' at the template entry depth closes the ${...} template
            # itself, not a real brace — hand control back to the string
            if (
                ch == "}"
                and len(modes) >= 2
                and isinstance(modes[-2], tuple)
                and modes[-2][0] == "tmpl"
                and len(stack) == modes[-2][1]
            ):
                modes.pop()  # "code"
                modes.pop()  # ("tmpl", ...)
                i += 1
                continue
            if not stack:
                errors.append(f"{path}:{cur_line()}: stray closing '{ch}' (nothing to close)")
                i += 1
                continue
            opener, oline = stack.pop()
            if opener != CLOSERS[ch]:
                errors.append(
                    f"{path}:{cur_line()}: mismatched '{ch}' "
                    f"(opened '{opener}' at line {oline})"
                )
            i += 1
            continue
        i += 1

    # unterminated lexical states
    top = modes[-1]
    if top == "line_comment":
        pass  # EOF inside line comment is fine
    elif top == "block_comment":
        errors.append(f"{path}:{line}: unterminated block comment")
    elif top == "char":
        errors.append(f"{path}:{line}: unterminated char literal")
    elif isinstance(top, tuple) and top[0] in ("str", "raw", "tmpl"):
        errors.append(f"{path}:{line}: unterminated string/template")

    for opener, oline in stack:
        errors.append(
            f"{path}:{oline}: unclosed '{opener}' (expected '{OPENERS[opener]}')"
        )
    return errors


def main(argv):
    roots = argv[1:] if len(argv) > 1 else ["."]
    files = find_kt_files(roots)
    if not files:
        print("check-braces: no *.kt files found")
        return 0
    all_errors = []
    for f in files:
        all_errors.extend(check_file(f))
    print(f"check-braces: checked {len(files)} Kotlin file(s)")
    if all_errors:
        for e in all_errors:
            print("  ERROR " + e)
        print(f"check-braces: FAILED ({len(all_errors)} problem(s))")
        return 1
    print("check-braces: OK — all delimiters balanced")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
