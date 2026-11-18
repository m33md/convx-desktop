#!/usr/bin/env bash
# Finishes the .deb jpackage made, in place:
# - The menu entry is a file of the package (/usr/share/applications), which every desktop reads
#   and dpkg's own triggers register. jpackage's install script ran
#   xdg-desktop-menu instead, which fails, and so fails the whole install, on systems without a
#   menu folder (minimal and tiling desktops, containers).
# - The entry names the window class Convx gives its window (LinuxDesktop.WmClass).
# - It depends on libpulse0, which FFmpeg's device library inside the app links against.
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
grep -q 'libpulse0' "$control" || sed -i -E 's/^(Depends:.*[^[:space:]])[[:space:]]*$/\1, libpulse0/' "$control"
# Installed-Size grows by the copied entry.
size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
sed -i -E "s/^Installed-Size:.*/Installed-Size: $size/" "$control"
cat "$control"

dpkg-deb --root-owner-group -Zxz -b "$root" "$deb" > /dev/null
rm -rf "$work"
dpkg-deb --info "$deb" | grep -E "Depends|Installed-Size"
dpkg-deb --contents "$deb" | grep -E "usr/share/applications"
