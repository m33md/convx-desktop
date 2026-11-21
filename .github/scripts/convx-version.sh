#!/usr/bin/env bash
# Prints the version people see (About, the setup window, the release and its file names). Each
# commit on main that changes Convx is one version: 1.2 is the first two after 567a556 (the second,
# the Mac side panel fix, went out as 1.2 too), then the count goes in tenths: 1.2.1 up to 1.2.9,
# then 1.3, 1.3.1 up to 1.3.9, then 1.4, and so on (after 1.9.9 comes 2.0). Before that, 1.1.20 was
# the first after aa77a0e and each one went up by one. The Windows and Mac builds both take it from
# here, so they always agree. The updater goes by commits, so it never compares these numbers.
set -euo pipefail
paths=(installer desktop innertube lyrics canvas integrations spine .github/workflows/desktop.yml .github/workflows/desktop-macos.yml)
v12=567a556af31dc0865859afa9776c7b19ad9a1d2c
v11=aa77a0e80c547908dc77cefd6e507bb9b153604d
if git merge-base --is-ancestor "$v12" HEAD && [ "$(git rev-parse HEAD)" != "$v12" ]; then
  count=$(git rev-list --count --first-parent "$v12..HEAD" -- "${paths[@]}")
  # 1.2 as 120, each version after it one more: the digits are major, minor and patch.
  n=$((120 + (count > 2 ? count - 2 : 0)))
  major=$((n / 100)) minor=$(((n / 10) % 10)) patch=$((n % 10))
  if [ "$patch" -eq 0 ]; then echo "$major.$minor"; else echo "$major.$minor.$patch"; fi
elif git merge-base --is-ancestor "$v11" HEAD; then
  count=$(git rev-list --count --first-parent "$v11..HEAD" -- "${paths[@]}")
  echo "1.1.$((19 + count))"
else
  echo "$CONVX_PACKAGE_VERSION"
fi
