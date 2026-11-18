#!/usr/bin/env bash
# Makes a Convx AppImage from the app folder jpackage made (bin/Convx and lib/): one file that runs
# on any Linux without installing. AppRun starts the launcher inside it, and the menu entry and
# icon are the ones AppImage tools add to the menu, named like the .deb's and .rpm's so the
# window matches it (LinuxDesktop.WmClass).
#
# Usage: make-appimage.sh <app folder> <icon .png> <output .AppImage>
set -euo pipefail
app=$(cd "$1" && pwd)
icon=$(readlink -f "$2")
out=$(readlink -f "$3")
arch=$(uname -m)
work=$(mktemp -d)
dir="$work/Convx.AppDir"

mkdir -p "$dir"
cp -a "$app/." "$dir/"
cat > "$dir/AppRun" <<'RUN'
#!/bin/sh
here=$(dirname "$(readlink -f "$0")")
exec "$here/bin/Convx" "$@"
RUN
chmod +x "$dir/AppRun"
cp "$icon" "$dir/convx.png"
ln -s convx.png "$dir/.DirIcon"
cat > "$dir/convx-Convx.desktop" <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Convx
Comment=Convx music player
Exec=Convx
Icon=convx
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=convx-Convx
ENTRY

tool="$work/appimagetool"
curl -fsSL --retry 3 -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/1.9.0/appimagetool-$arch.AppImage"
chmod +x "$tool"
# Unpacked rather than mounted, so the build machine needs no FUSE.
ARCH=$arch APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream "$dir" "$out"
ls -l "$out"
