#!/usr/bin/env bash
# Brings changes from the Android TV app (open-Stremio/android-native) into the
# desktop sources.
#
# The desktop app is a port of those Kotlin sources, not a fork that drifted:
# desktop/UPSTREAM_COMMIT records the TV commit it matches. This script takes
# the TV repository's diff since then — Kotlin sources, tests and res/ — and
# applies it here with a 3-way merge, so desktop-specific edits survive and
# real conflicts show up as conflict markers.
#
# Usage: scripts/sync-upstream.sh [path/to/open-Stremio checkout] [target-ref]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="${1:-$ROOT/../open_Stremio/open Stremio}"
TARGET="${2:-HEAD}"
FROM="$(cat "$ROOT/desktop/UPSTREAM_COMMIT")"
TO="$(git -C "$UPSTREAM" rev-parse "$TARGET")"

if [ "$FROM" = "$TO" ]; then
  echo "Already at $TO"
  exit 0
fi
echo "==> TV app $FROM..$TO"
git -C "$UPSTREAM" log --oneline "$FROM..$TO" | sed 's/^/    /'

MODULES="core/model core/network core/database core/data core/ui player torrent app"
PATCH="$(mktemp)"
trap 'rm -f "$PATCH"' EXIT

apply() { # <upstream dir> <desktop dir>
  git -C "$UPSTREAM" diff --binary "$FROM" "$TO" --relative="$1" -- "$1" > "$PATCH"
  if [ -s "$PATCH" ]; then
    echo "    $1 -> $2"
    git -C "$ROOT" apply --3way --directory="$2" "$PATCH" || echo "    !! conflicts in $2 — resolve the markers"
  fi
}

for m in $MODULES; do
  apply "android-native/$m/src/main/kotlin" "desktop/src/main/kotlin"
  apply "android-native/$m/src/test/kotlin" "desktop/src/test/kotlin"
done
apply "android-native/app/src/main/res" "desktop/res/app"
apply "android-native/core/ui/src/main/res" "desktop/res/core-ui"

echo "$TO" > "$ROOT/desktop/UPSTREAM_COMMIT"
cat <<EOF
==> Done. Next:
    * files the desktop replaced (player/PlaybackController, the Media3 and
      WorkManager pieces) may receive changes that need porting by hand —
      look for new files under desktop/src/main/kotlin/com/mdblisthub/tv/player;
    * ./gradlew :desktop:test && ./gradlew :desktop:run
EOF
