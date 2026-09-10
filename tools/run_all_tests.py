#!/usr/bin/env python3
"""DOGS Remote — run every offline regression check in one command.

Discovers every ``test_*.py`` in this directory (the Python suites are the
only thing runnable without an Android SDK) and then runs the pure-Compose
theme guard ``scripts/check-theme.sh``. New ``test_*.py`` suites are picked
up automatically — no hardcoded list to keep in sync.

Usage:
    python3 tools/run_all_tests.py

Exit status is 0 when everything passes, 1 otherwise.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PY = sys.executable or "python3"


def run_suite(name, cmd, cwd):
    """Run one check; return True when it exits 0."""
    try:
        proc = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=300,
        )
    except Exception as e:  # e.g. missing interpreter — fail loudly, not silently
        print("FAIL %s" % name)
        print("  (could not start: %s)" % e)
        return False
    ok = proc.returncode == 0
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        out = proc.stdout.strip()
        if out:
            for line in out.splitlines()[-15:]:
                print("    " + line)
    return ok


def main():
    suites = sorted(
        f for f in os.listdir(HERE)
        if f.startswith("test_") and f.endswith(".py")
        and os.path.isfile(os.path.join(HERE, f))
    )
    if not suites:
        print("run_all_tests.py: no test_*.py suites found in tools/")
        return 1

    results = []
    for suite in suites:
        ok = run_suite(suite, [PY, os.path.join(HERE, suite)], HERE)
        results.append((suite, ok))

    theme_script = os.path.join(REPO, "scripts", "check-theme.sh")
    if os.path.isfile(theme_script):
        ok = run_suite("scripts/check-theme.sh", ["bash", theme_script], REPO)
        results.append(("scripts/check-theme.sh", ok))
    else:
        print("SKIP scripts/check-theme.sh (not found)")
        results.append(("scripts/check-theme.sh", False))

    failed = [name for name, ok in results if not ok]
    print()
    print("run_all_tests.py: %d/%d checks passed"
          % (len(results) - len(failed), len(results)))
    if failed:
        print("  failed: %s" % ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
