#!/usr/bin/env bash
# Prints the version people see (About, the setup window, the release and its file names). Each
# commit on main that changes Convx is one version, counted from 567a556: the first is 1.2, then the
# count goes in tenths: 1.2.1 up to 1.2.9, then 1.3, 1.3.1 up to 1.3.9, then 1.4, and so on (after
# 1.9.9 comes 2.0). The commits listed in `again` by their place in that count went out under the
# same version as the one before them: the 2nd as 1.2 (the Mac side panel fix), and the 4th and
# 5th as 1.2.1 (downloads from modules, then the download folder and formats). Before 567a556,
# 1.1.20 was the first after aa77a0e and each one went up by one. The Windows and Mac builds both
# take it from here, so they always agree. The updater goes by commits, so it never compares these
# numbers.
set -euo pipefail
paths=(installer desktop innertube lyrics canvas integrations spine .github/workflows/desktop.yml .github/workflows/desktop-macos.yml)
v12=567a556af31dc0865859afa9776c7b19ad9a1d2c
v11=aa77a0e80c547908dc77cefd6e507bb9b153604d
if git merge-base --is-ancestor "$v12" HEAD && [ "$(git rev-parse HEAD)" != "$v12" ]; then
  count=$(git rev-list --count --first-parent "$v12..HEAD" -- "${paths[@]}")
  again=(2 4 5)
  repeated=0
  for place in "${again[@]}"; do
    if [ "$count" -ge "$place" ]; then repeated=$((repeated + 1)); fi
  done
  # 1.2 as 120, each version after it one more: the digits are major, minor and patch.
  n=$((120 + count - 1 - repeated))
  major=$((n / 100)) minor=$(((n / 10) % 10)) patch=$((n % 10))
  if [ "$patch" -eq 0 ]; then echo "$major.$minor"; else echo "$major.$minor.$patch"; fi
elif git merge-base --is-ancestor "$v11" HEAD; then
  count=$(git rev-list --count --first-parent "$v11..HEAD" -- "${paths[@]}")
  echo "1.1.$((19 + count))"
else
  echo "$CONVX_PACKAGE_VERSION"
fi
