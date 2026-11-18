#!/usr/bin/env bash
# Publishes the given files as the rolling "desktop-latest" release, built from commit $1 as
# version $2. Every file is named with its version (Convx-Setup-1.0.71.exe, Convx-1.0.71.msi,
# Convx-1.0.71-portable.zip, the Mac disk images Convx-1.0.71-mac-apple-silicon.dmg and
# Convx-1.0.71-mac-intel.dmg, and the Linux packages Convx-1.0.71-linux-x64.deb, .rpm and .AppImage,
# with -linux-arm64 ones for ARM), and the notes carry the version and commit for the app's updater.
# It's marked the latest release, so /releases/latest always leads to the newest setup.
# With DRY_RUN set it only prints the release it would make.
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
    */Convx-macos-arm64.dmg) add "$file" "Convx-$version-mac-apple-silicon.dmg" ;;
    */Convx-macos-intel.dmg) add "$file" "Convx-$version-mac-intel.dmg" ;;
    */Convx-linux-*) name=$(basename "$file"); add "$file" "Convx-$version-${name#Convx-}" ;;
    *) files+=("$file") ;;
  esac
done
mac_arm=$([ -e "$named/Convx-$version-mac-apple-silicon.dmg" ] && echo "Convx-$version-mac-apple-silicon.dmg" || true)
mac_intel=$([ -e "$named/Convx-$version-mac-intel.dmg" ] && echo "Convx-$version-mac-intel.dmg" || true)
if [ -n "$setup" ]; then
  download="Download Convx-Setup-${version}.exe below and double-click it."
elif [ -e "$named/Convx-$version.exe" ]; then
  download="Download Convx-${version}.exe below and double-click it."
else
  download="Download Convx-${version}.msi below and double-click it."
fi

# The Mac disk images, when the Mac builds made them.
platforms=Windows
mac=
if [ -n "$mac_arm" ] || [ -n "$mac_intel" ]; then
  platforms="Windows and Mac"
  if [ -n "$mac_arm" ] && [ -n "$mac_intel" ]; then
    which="On a Mac, download ${mac_arm} if it has Apple silicon (M1 or newer), or ${mac_intel} if it has an Intel chip."
  elif [ -n "$mac_arm" ]; then
    which="On a Mac with Apple silicon (M1 or newer), download ${mac_arm}."
  else
    which="On a Mac, download ${mac_intel} (made for Intel Macs; Macs with Apple silicon run it through Rosetta)."
  fi
  mac="${which} Open it and drag Convx into Applications. The first time you open Convx, macOS says it can't check it for malware: click Done, then open System Settings, Privacy & Security, and click Open Anyway."
  download="On Windows, ${download,}"
fi

# The Linux packages, when the Linux builds made them.
linux=
linux_x64=$([ -e "$named/Convx-$version-linux-x64.deb" ] && echo yes || true)
linux_arm=$([ -e "$named/Convx-$version-linux-arm64.deb" ] && echo yes || true)
if [ -n "$linux_x64" ] || [ -n "$linux_arm" ]; then
  if [ -n "$mac" ]; then platforms="Windows, Mac and Linux"; else platforms="Windows and Linux"; fi
  arch=$([ -n "$linux_x64" ] && echo x64 || echo arm64)
  linux="On Linux, download Convx-${version}-linux-${arch}.deb for Ubuntu, Debian or Mint, Convx-${version}-linux-${arch}.rpm for Fedora or openSUSE, or Convx-${version}-linux-${arch}.AppImage for any other (allow it to run as a program in its Properties, then open it)."
  if [ -n "$linux_x64" ] && [ -n "$linux_arm" ]; then
    linux+=" On an ARM computer, take the -linux-arm64 files instead."
  elif [ -z "$linux_x64" ]; then
    linux+=" These are for ARM computers."
  fi
  if [ -z "$mac" ]; then download="On Windows, ${download,}"; fi
fi

# The version's commit message is its "What's new". It comes after the line the updater reads,
# which takes the first "Version" and the first /tree/ link in the notes.
notes="${download} If Windows says it protected your PC, click More info, then Run anyway."
if [ -n "$mac" ]; then notes+=$'\n\n'"${mac}"; fi
if [ -n "$linux" ]; then notes+=$'\n\n'"${linux}"; fi
notes+=$'\n\n'"Version ${version}. Source: https://github.com/${GITHUB_REPOSITORY}/tree/${sha}"
changes=$(git log -1 --format=%B "$sha" 2>/dev/null | grep -viE '^(co-authored-by|claude-session):' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' || true)
if [ -n "$changes" ]; then
  notes+=$'\n\n'"## What's new"$'\n\n'"${changes}"
fi
title="Convx for ${platforms} ${version}"

if [ -n "${DRY_RUN:-}" ]; then
  echo "Dry run: the release this would publish."
  echo "Title: ${title}"
  echo "Files:"
  for file in "${files[@]}"; do printf '  %s (%s bytes)\n' "$(basename "$file")" "$(wc -c < "$file" | tr -d ' ')"; done
  echo "Notes:"
  printf '%s\n' "$notes"
  exit 0
fi

gh release delete "$tag" --cleanup-tag --yes || true

tmp=$(mktemp -d)
git -C "$tmp" init -q -b release
printf 'Convx for %s, from commit %s.\nSource: https://github.com/%s/tree/%s\n' \
  "$platforms" "$sha" "$GITHUB_REPOSITORY" "$sha" > "$tmp/README.md"
git -C "$tmp" add README.md
git -C "$tmp" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -qm "Convx for ${platforms}, built from ${sha::7}"
git -C "$tmp" push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}" "release:refs/tags/$tag"

gh release create "$tag" --verify-tag --latest \
  --title "$title" \
  --notes "$notes" \
  "${files[@]}"
