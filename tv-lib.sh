# Sourced by the other scripts. Needs TV_HOST (TV_PORT defaults to 5555; ADB overrides the adb binary).
: "${TV_HOST:?set TV_HOST to the TV IP}"
ADB_BIN="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
SERIAL="$TV_HOST:${TV_PORT:-5555}"
"$ADB_BIN" connect "$SERIAL" >/dev/null
T() { "$ADB_BIN" -s "$SERIAL" shell "$@"; }
