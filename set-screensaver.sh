#!/usr/bin/env bash
# Make Reverie the system screensaver on the TV.   Usage: TV_HOST=<tv ip> ./set-screensaver.sh
set -euo pipefail
. "$(dirname "$0")/tv-lib.sh"
C=app.reverie/app.reverie.PhotoDream
T "settings put secure screensaver_components $C"
T "settings put secure screensaver_default_component $C"
echo "Screensaver: $(T 'settings get secure screensaver_components')"
