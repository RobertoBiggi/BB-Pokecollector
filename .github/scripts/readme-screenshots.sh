#!/usr/bin/env bash
# Runs on the emulator started by .github/workflows/screenshots.yml: installs the app and the test, takes the
# screenshots of the README (ReadmeScreenshots) and copies them to docs/screenshots.
set -euo pipefail

PKG=com.robertobiggi.cardcollection
OUT=docs/screenshots

adb install -r app/build/outputs/apk/debug/*.apk
adb install -r app/build/outputs/apk/androidTest/debug/*.apk
# Avoids the system dialog asking for notifications on top of the first screenshot.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
# Screenshots in Italian, as the README (per-app language, Android 13+).
adb shell cmd locale set-app-locales "$PKG" --locales it-IT

adb shell am instrument -w -e class "$PKG.ReadmeScreenshots" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.log

mkdir -p "$OUT"
for f in $(adb shell run-as "$PKG" ls files/screenshots | tr -d '\r'); do
  # Only the names written by the test (e.g. 01-collezioni.png): nothing else ends up in the repo.
  if [[ "$f" =~ ^[0-9]{2}-[a-z-]+\.png$ ]]; then
    adb exec-out run-as "$PKG" cat "files/screenshots/$f" > "$OUT/$f"
  fi
done
ls -la "$OUT"
# "am instrument" exits with 0 even when the test fails. The screenshots taken so far are copied anyway, to see where it stopped.
grep -q "OK (1 test)" instrument.log
