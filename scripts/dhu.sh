#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Start or stop the Android Auto Desktop Head Unit for bench testing (no car needed).
#
#   scripts/dhu.sh start   forward the port and open the DHU window (on Windows via WSLg)
#   scripts/dhu.sh stop    close it again
#
# On the phone first: Android Auto settings -> tap the version 10 times (developer mode), developer settings ->
# "Unknown sources" on, overflow menu -> "Start head unit server", and keep the phone unlocked.
#
# The DHU reads commands from stdin, so stdin is held open with `tail -f`. Both processes run in their own process
# group, which is stopped by its ID; never use pkill/pgrep -f here: they also match the shell that runs them.
set -euo pipefail
source "$(dirname "$0")/../env.sh" 2>/dev/null || true
DHU="${ANDROID_HOME:?run: source env.sh}/extras/google/auto"
STATE="${TMPDIR:-/tmp}/voicenotes-dhu.pgid"
LOG="${TMPDIR:-/tmp}/voicenotes-dhu.log"

stop() {
  if [ -f "$STATE" ]; then
    kill -- "-$(cat "$STATE")" 2>/dev/null || true
    rm -f "$STATE"
  fi
  adb forward --remove tcp:5277 2>/dev/null || true
}

case "${1:-}" in
  start)
    stop
    adb forward tcp:5277 tcp:5277
    cd "$DHU"
    # setsid makes the new shell the leader of its own group, so its process ID is the group ID.
    setsid bash -c 'tail -f /dev/null | ./desktop-head-unit' > "$LOG" 2>&1 < /dev/null &
    echo $! > "$STATE"
    echo "DHU started (group $(cat "$STATE")); log: $LOG (ALSA warnings are harmless: WSL has no sound card)"
    ;;
  stop) stop; echo "DHU stopped" ;;
  *) echo "usage: $0 start|stop"; exit 1 ;;
esac
