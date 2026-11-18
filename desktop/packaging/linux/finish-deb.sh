#!/usr/bin/env bash
# Finishes the .deb jpackage made, in place:
# - The menu entry is a file of the package (/usr/share/applications), which every desktop reads
#   and dpkg's own triggers register. jpackage's install script ran
#   xdg-desktop-menu instead, which fails, and so fails the whole install, on systems without a
#   menu folder (minimal and tiling desktops, containers).
# - The entry names the window class Convx gives its window (LinuxDesktop.WmClass).
# - It depends on the system libraries FFmpeg's own libraries (inside the app's jars, where
#   jpackage doesn't look) link against: PulseAudio everywhere, and VA-API on ARM.
#
# Usage: finish-deb.sh <.deb>
set -euo pipefail
deb=$(readlink -f "$1")
work=$(mktemp -d)
root="$work/root"
dpkg-deb -R "$deb" "$root"

entry=$(find "$root/opt" -name '*.desktop' | head -n 1)
[ -n "$entry" ] || { echo "No menu entry in $deb"; exit 1; }
name=$(basename "$entry")
grep -q '^StartupWMClass=' "$entry" || echo "StartupWMClass=${name%.desktop}" >> "$entry"
install -D -m 644 "$entry" "$root/usr/share/applications/$name"

for script in postinst prerm; do
  file="$root/DEBIAN/$script"
  [ -f "$file" ] || continue
  sed -i -E 's/^([[:space:]]*)xdg-(desktop-menu|icon-resource|mime|desktop-icon)[[:space:]].*$/\1: # the package carries its menu entry/' "$file"
  echo "-- $script"
  cat "$file"
done

control="$root/DEBIAN/control"
natives=$(mktemp -d)
for jar in $(find "$root/opt" -name 'ffmpeg-*-linux-*.jar'); do unzip -q -o "$jar" '*.so*' -d "$natives"; done
bundled=$(find "$natives" -name '*.so*' -printf '%f\n')
extra=()
for lib in $(find "$natives" -name '*.so*' -exec readelf -d {} \; | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort -u); do
  grep -qx "$lib" <<< "$bundled" && continue
  case "$lib" in
    libpulse.so.0) extra+=(libpulse0) ;;
    libva.so.2) extra+=(libva2) ;;
    libva-drm.so.2) extra+=(libva-drm2) ;;
    libdrm.so.2) extra+=(libdrm2) ;;
    libxcb.so.1) extra+=(libxcb1) ;;
    libxcb-shm.so.0) extra+=(libxcb-shm0) ;;
    libc.so.6 | libm.so.6 | libgcc_s.so.1 | libstdc++.so.6 | ld-linux-*) ;;
    *) echo "::warning::FFmpeg needs $lib, which the .deb doesn't ask for" ;;
  esac
done
rm -rf "$natives"
for dep in "${extra[@]}"; do
  grep -qE "^Depends:.*\b$dep\b" "$control" || sed -i -E "s/^(Depends:.*[^[:space:]])[[:space:]]*\$/\1, $dep/" "$control"
done
# Installed-Size grows by the copied entry.
size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
sed -i -E "s/^Installed-Size:.*/Installed-Size: $size/" "$control"
cat "$control"

dpkg-deb --root-owner-group -Zxz -b "$root" "$deb" > /dev/null
rm -rf "$work"
dpkg-deb --info "$deb" | grep -E "Depends|Installed-Size"
dpkg-deb --contents "$deb" | grep -E "usr/share/applications"
