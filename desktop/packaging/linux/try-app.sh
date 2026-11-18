#!/usr/bin/env bash
# Tries the Linux build on this machine as a person would. The FFmpeg inside the app has to load
# and decode. The .deb has to install with apt here and in a Debian container, and the .rpm with
# dnf in a Fedora container. Convx has to open on a virtual screen from the installed package and
# from the AppImage, and stay open for 40 seconds with its window named for its menu entry.
# Screenshots, the app's log and what each package installed are kept in the output folder.
# Exits 1 if a package doesn't install, the app quits or crashes, or FFmpeg decodes nothing.
#
# Usage: try-app.sh <app folder> <.deb> <.rpm> <.AppImage> <output folder>
set -uo pipefail
app=$(cd "$1" && pwd)
deb=$(readlink -f "$2")
rpm=$(readlink -f "$3")
appimage=$(readlink -f "$4")
out=$5
mkdir -p "$out"
out=$(cd "$out" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
logs="$HOME/.cache/Convx/logs"
failed=0

# The app's runtime has no java command of its own, so this machine's Java 21 runs the app's jars;
# the FFmpeg libraries are the ones inside them.
echo "== FFmpeg"
classes=$(mktemp -d)
if javac -cp "$app/lib/app/*" -d "$classes" "$here/../macos/FfmpegCheck.java" &&
  java -cp "$classes:$app/lib/app/*" FfmpegCheck "$classes/tone.m4a" > "$out/ffmpeg.txt" 2>&1; then
  tail -n 1 "$out/ffmpeg.txt"
else
  tail -n 40 "$out/ffmpeg.txt" 2>/dev/null
  echo "::error::FFmpeg didn't load or decode in the Linux app"
  failed=1
  # Which native jars the app has, and JavaCPP's own account of the loading.
  ls "$app/lib/app" | grep -E "ffmpeg|javacpp" | tee "$out/ffmpeg-jars.txt"
  java -Dorg.bytedeco.javacpp.logger.debug=true -cp "$classes:$app/lib/app/*" FfmpegCheck "$classes/tone.m4a" > "$out/ffmpeg-debug.txt" 2>&1
  grep -iE "platform|extract|load|error" "$out/ffmpeg-debug.txt" | head -n 40
fi

echo "== Packages"
{
  echo "-- .deb"
  dpkg-deb --info "$deb"
  echo "-- .rpm"
  rpm -qpi "$rpm" 2>&1
  echo "-- .rpm requires"
  rpm -qpR "$rpm" 2>&1
} > "$out/packages.txt" 2>&1
grep -E "Package:|Version:|Depends:|Installed-Size:" "$out/packages.txt"

echo "== Installing the .deb here"
if sudo apt-get install -y "$deb" > "$out/deb-install.txt" 2>&1; then
  dpkg -L convx | grep -vE '^/opt/convx/lib/(app|runtime)/' > "$out/deb-files.txt"
  entry=$(grep -E '\.desktop$' "$out/deb-files.txt" | head -n 1)
  echo "Installed; menu entry $entry"
  [ -n "$entry" ] && cat "$entry" | tee "$out/menu-entry.txt"
  ls /usr/share/applications | grep -i convx || echo "::warning::No Convx entry in /usr/share/applications"
else
  tail -n 30 "$out/deb-install.txt"
  echo "::error::The .deb didn't install"
  failed=1
fi

# Debian and Fedora as they come, each with only what its package asks for.
echo "== Installing in containers"
pkgs=$(dirname "$deb")
if docker run --rm -v "$pkgs:/pkgs:ro" debian:12 bash -c \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq /pkgs/$(basename "$deb") > /dev/null && dpkg -s convx | head -n 3 && ls /opt/convx/bin" \
  > "$out/debian-12.txt" 2>&1; then
  echo "Debian 12: installed"
else
  tail -n 20 "$out/debian-12.txt"
  echo "::error::The .deb didn't install on Debian 12"
  failed=1
fi
if docker run --rm -v "$(dirname "$rpm"):/pkgs:ro" fedora:42 bash -c \
  "dnf install -y -q /pkgs/$(basename "$rpm") > /dev/null && rpm -q convx && ls /opt/convx/bin" \
  > "$out/fedora-42.txt" 2>&1; then
  echo "Fedora 42: installed"
else
  tail -n 20 "$out/fedora-42.txt"
  echo "::error::The .rpm didn't install on Fedora 42"
  failed=1
fi

# A virtual screen and a window manager, as a desktop has.
echo "== Screen"
export DISPLAY=:99
Xvfb :99 -screen 0 1600x1000x24 > /dev/null 2>&1 &
sleep 2
openbox > /dev/null 2>&1 &
sleep 1

# Opens Convx with the given command, gives it 40 seconds, and keeps what it showed and wrote.
try_open() {
  local name=$1
  shift
  echo "== Opening Convx ($name)"
  rm -rf "$logs"
  setsid "$@" > "$out/$name-output.txt" 2>&1 &
  local pid=$!
  sleep 40
  import -window root "$out/$name.png" 2>/dev/null || echo "No screenshot"
  xwininfo -root -tree > "$out/$name-windows.txt" 2>&1
  if kill -0 "$pid" 2>/dev/null && pgrep -x Convx > /dev/null; then
    echo "Still open after 40 seconds"
    ps -o pid,rss,%cpu,etime,args -C Convx | cut -c1-160
    if grep -q '"convx-Convx"' "$out/$name-windows.txt"; then
      echo "Its window is named convx-Convx, like its menu entry"
    else
      echo "::warning::The window isn't named for the menu entry: $(grep -i convx "$out/$name-windows.txt" | head -n 3)"
    fi
  else
    echo "::error::Convx ($name) quit by itself within 40 seconds"
    tail -n 30 "$out/$name-output.txt"
    failed=1
  fi
  kill -- -"$pid" 2>/dev/null
  pkill -x Convx 2>/dev/null
  for _ in $(seq 1 10); do pgrep -x Convx > /dev/null || break; sleep 1; done
  pkill -9 -x Convx 2>/dev/null
  cp -R "$logs" "$out/$name-logs" 2>/dev/null || true
  if [ -f "$out/$name-logs/convx.log" ]; then
    grep -E " E/| W/|Exception|Error" "$out/$name-logs/convx.log" | head -n 40
  fi
}

if [ -x /opt/convx/bin/Convx ]; then try_open deb /opt/convx/bin/Convx; fi

chmod +x "$appimage"
# As people open it: mounted with FUSE. A machine without FUSE unpacks it instead.
if [ -e /dev/fuse ] && { command -v fusermount3 || command -v fusermount; } > /dev/null; then
  try_open appimage "$appimage"
else
  echo "No FUSE here; opening the AppImage unpacked"
  APPIMAGE_EXTRACT_AND_RUN=1 try_open appimage "$appimage"
fi

find "$HOME" /tmp -maxdepth 1 -name 'hs_err_pid*.log' -exec cp {} "$out/" \; 2>/dev/null || true
for crash in "$out"/hs_err_pid*.log "$out"/*-logs/crash-report.txt; do
  [ -f "$crash" ] || continue
  echo "::error::Crash report: $crash"
  head -n 60 "$crash"
  failed=1
done
exit $failed
