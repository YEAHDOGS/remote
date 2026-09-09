#!/usr/bin/env bash
# check-theme.sh — regression guard for the pure-Compose theme setup.
#
# History: a past build broke because themes.xml used
#   parent="Theme.Material3.DayNight.NoActionBar"
# which is a VIEW-based Material Components theme (com.google.android.material),
# a dependency this app deliberately does NOT have. This is a pure-Compose app:
# the only legitimate Material library is androidx.compose.material3 (Compose),
# which this script intentionally ignores.
#
# Fails (exit 1) if any view-based Material Components reference sneaks back in.
# Safe to run offline; no Android SDK needed.

set -euo pipefail
cd "$(dirname "$0")/.."

FAIL=0
report() { echo "check-theme FAIL: $1"; FAIL=1; }

# 1. No view-based Material Components / Material3 view theme as a style parent.
#    Legitimate parents start with "android:" (framework) or "@style/".
while IFS= read -r line; do
  report "view-based Material Components theme parent: ${line}"
done < <(grep -rnE 'parent="(Theme\.(MaterialComponents|Material3)|Widget\.(MaterialComponents|Material3))' app/src/main/res/ 2>/dev/null || true)

# 2. The app theme must exist and be a framework theme (android: prefix).
if ! grep -rqE '<style name="Theme\.DogsRemote" parent="android:' app/src/main/res/values/themes.xml 2>/dev/null; then
  report "Theme.DogsRemote must exist in themes.xml with an android: framework parent"
fi

# 3. No com.google.android.material dependency in any Gradle file.
while IFS= read -r line; do
  report "com.google.android.material dependency: ${line}"
done < <(grep -rn "com.google.android.material" --include='*.gradle' --include='*.gradle.kts' --include='*.toml' . 2>/dev/null | grep -v '^Binary' || true)

if [ "$FAIL" -ne 0 ]; then
  echo "A view-based Material Components theme reference was found."
  echo "This app is pure Compose — use android: framework themes in XML"
  echo "and androidx.compose.material3 in Kotlin."
  exit 1
fi

echo "check-theme OK: no view-based Material Components references."
