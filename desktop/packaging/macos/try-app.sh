#!/usr/bin/env bash
# Tries the built Convx.app on this Mac: how macOS sees its signature, whether its FFmpeg loads
# and decodes, and whether it opens and stays open the way it does when a person double-clicks
# it. Keeps a screenshot of the screen, the app's log and any crash report in the output folder.
# Exits 1 if the app quits or crashes, or FFmpeg decodes nothing.
#
# Usage: try-app.sh <Convx.app> <output folder>
set -uo pipefail
app=$(cd "$1" && pwd)
out=$2
mkdir -p "$out"
out=$(cd "$out" && pwd)
failed=0

echo "== Signature"
{
  echo "-- codesign -dv"
  codesign -dv --verbose=2 "$app" 2>&1
  echo "-- codesign --verify --deep --strict"
  codesign --verify --deep --strict --verbose=2 "$app" 2>&1 && echo "valid"
  echo "-- spctl (Gatekeeper)"
  spctl --assess --type execute --verbose=4 "$app" 2>&1
  echo "-- syspolicy_check distribution"
  syspolicy_check distribution "$app" 2>&1
} > "$out/signature.txt"
cat "$out/signature.txt"

echo "== FFmpeg"
java="$app/Contents/runtime/Contents/Home/bin/java"
classes=$(mktemp -d)
here=$(cd "$(dirname "$0")" && pwd)
if javac -cp "$app/Contents/app/*" -d "$classes" "$here/FfmpegCheck.java" &&
  "$java" -cp "$classes:$app/Contents/app/*" FfmpegCheck "$classes/tone.m4a" > "$out/ffmpeg.txt" 2>&1; then
  tail -n 1 "$out/ffmpeg.txt"
else
  tail -n 40 "$out/ffmpeg.txt" 2>/dev/null
  echo "::error::FFmpeg didn't load or decode in the Mac app"
  failed=1
fi

echo "== Opening Convx"
logs="$HOME/Library/Caches/Convx/logs"
open -n --stdout "$out/stdout.txt" --stderr "$out/stderr.txt" "$app"
pid=
for _ in $(seq 1 30); do
  pid=$(pgrep -f "$app/Contents/MacOS/Convx" | head -n 1)
  [ -n "$pid" ] && break
  sleep 1
done
if [ -z "$pid" ]; then
  echo "::error::Convx didn't start"
  failed=1
else
  echo "Convx is running (pid $pid); giving it 40 seconds"
  sleep 40
  screencapture -x "$out/screen.png" || echo "No screenshot: $?"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Still open after 40 seconds"
    ps -o pid,rss,%cpu,etime,command -p "$pid" | cut -c1-160
    kill "$pid"
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  else
    echo "::error::Convx quit by itself within 40 seconds"
    failed=1
  fi
fi

# What the app wrote, and whatever macOS or Java kept of a crash.
cp -R "$logs" "$out/logs" 2>/dev/null || true
find "$HOME/Library/Logs/DiagnosticReports" -iname 'Convx*' -exec cp {} "$out/" \; 2>/dev/null || true
find "$HOME" /tmp -maxdepth 1 -name 'hs_err_pid*.log' -exec cp {} "$out/" \; 2>/dev/null || true
if [ -f "$out/logs/convx.log" ]; then
  echo "== The app's log (window and errors)"
  grep -E "MacWindow|MacSidebar|MacGlass|MacMedia| E/| W/|Exception|Error" "$out/logs/convx.log" | head -n 60
fi
for crash in "$out"/Convx*.ips "$out"/hs_err_pid*.log "$out"/logs/crash-report.txt; do
  [ -f "$crash" ] || continue
  echo "::error::Crash report: $(basename "$crash")"
  head -n 60 "$crash"
  failed=1
done
exit $failed
