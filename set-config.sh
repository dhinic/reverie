#!/usr/bin/env bash
# Write the NAS settings into the app's private storage on the TV, instead of typing them with the remote.
# Works because the app is a debug build (run-as). The password comes from the environment and is never
# written to a file on this machine.
#
# Usage: TV_HOST=<tv ip> NAS_SERVER=<nas ip> NAS_SHARE=<share> [NAS_USER=<user> NAS_PASSWORD='...'] \
#          ./set-config.sh [folder inside the share] [seconds per photo]
#   No NAS_USER means guest login. No folder means the whole share. Default is 10 seconds per photo.
set -euo pipefail
. "$(dirname "$0")/tv-lib.sh"
: "${NAS_SERVER:?set NAS_SERVER to the NAS address}" "${NAS_SHARE:?set NAS_SHARE to the share name}"
NAS_USER="${NAS_USER:-}"
[ -z "$NAS_USER" ] || : "${NAS_PASSWORD:?set NAS_PASSWORD for $NAS_USER}"
FOLDER="${1:-}"; SECS="${2:-10}"
JSON=$(python3 -I -c 'import json,sys; print(json.dumps(dict(server=sys.argv[1], share=sys.argv[2], path=sys.argv[3], user=sys.argv[4], password=sys.argv[5], secondsPerPhoto=int(sys.argv[6])), separators=(",",":")))' \
  "$NAS_SERVER" "$NAS_SHARE" "$FOLDER" "$NAS_USER" "${NAS_PASSWORD:-}" "$SECS")
B64=$(printf '%s' "$JSON" | base64 | tr -d '\n')
T "run-as app.reverie sh -c 'mkdir -p files; rm -f files/list2.txt; echo $B64 | base64 -d > files/config.json'"
echo "Config written for \\\\$NAS_SERVER\\$NAS_SHARE\\$FOLDER (photo list cache cleared; it is rebuilt on the next start)."
