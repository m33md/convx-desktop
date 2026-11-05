#!/usr/bin/env bash
# Publishes the given files as the rolling "desktop-latest" release, built from commit $1 as
# version $2. Every file is named with its version (Convx-Setup-1.0.71.exe, Convx-1.0.71.msi,
# Convx-1.0.71-portable.zip), and the notes carry the version and commit for the app's updater.
# It's marked the latest release, so /releases/latest always leads to the newest setup.
#
# The tag points at a one-file commit rather than at the build commit: a GITHUB_TOKEN may not
# create a ref whose tree adds workflow files, and every commit on this branch carries them.
set -euo pipefail
sha=$1
version=$2
shift 2
tag=desktop-latest

files=()
named=$(mktemp -d)
# Convx's own setup (Convx-Setup.exe) is the .exe to offer when the build made one that works;
# jpackage's plain .exe stands in otherwise. The in-app updater looks for Convx-Setup-*.exe, and
# builds before 1.0.71 for the first .msi.
setup=
for file in "$@"; do
  if [ "$(basename "$file")" = Convx-Setup.exe ]; then setup=$file; fi
done
add() { cp "$1" "$named/$2"; files+=("$named/$2"); }
for file in "$@"; do
  case "$file" in
    */Convx-Setup.exe) add "$file" "Convx-Setup-$version.exe" ;;
    *.msi) add "$file" "Convx-$version.msi" ;;
    *.exe)
      if [ -z "$setup" ]; then add "$file" "Convx-$version.exe"; fi ;;
    *.zip) add "$file" "Convx-$version-portable.zip" ;;
    *) files+=("$file") ;;
  esac
done
if [ -n "$setup" ]; then
  download="Download Convx-Setup-${version}.exe below and double-click it."
elif [ -e "$named/Convx-$version.exe" ]; then
  download="Download Convx-${version}.exe below and double-click it."
else
  download="Download Convx-${version}.msi below and double-click it."
fi

gh release delete "$tag" --cleanup-tag --yes || true

tmp=$(mktemp -d)
git -C "$tmp" init -q -b release
printf 'Windows build of Convx, from commit %s.\nSource: https://github.com/%s/tree/%s\n' \
  "$sha" "$GITHUB_REPOSITORY" "$sha" > "$tmp/README.md"
git -C "$tmp" add README.md
git -C "$tmp" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -qm "Convx for Windows, built from ${sha::7}"
git -C "$tmp" push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}" "release:refs/tags/$tag"

gh release create "$tag" --verify-tag --latest \
  --title "Convx for Windows ${version}" \
  --notes "${download} If Windows says it protected your PC, click More info, then Run anyway. Version ${version}. Source: https://github.com/${GITHUB_REPOSITORY}/tree/${sha}" \
  "${files[@]}"
