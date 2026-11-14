#!/usr/bin/env bash
# Build the Reverie APK and install it on the TV with plain adb.
# Usage: TV_HOST=<tv ip> [TV_PORT=5555] ./build-and-install.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# JDK 17 is required; fall back to Homebrew's when JAVA_HOME is not set
[ -n "${JAVA_HOME:-}" ] || [ ! -d /opt/homebrew/opt/openjdk@17 ] || export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd "$HERE"
. ./tv-lib.sh
./gradlew --no-daemon -q assembleDebug
ADB_APK=/data/local/tmp/reverie.apk
# push + pm install avoids `adb install` hanging on the Android 14 on-screen prompt
"$ADB_BIN" -s "$SERIAL" push app/build/outputs/apk/debug/app-debug.apk $ADB_APK >/dev/null
T "pm install -r $ADB_APK; rm -f $ADB_APK"
echo "Installed. Next: open Reverie on the TV to set it up, then ./set-screensaver.sh"
