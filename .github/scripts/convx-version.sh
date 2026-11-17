#!/usr/bin/env bash
# Prints the version people see (About, the setup window, the release and its file names). Each
# commit on main that changes Convx is one version: 1.2 is the first after 567a556, then 1.2.1,
# 1.2.2 and on; before that, 1.1.20 was the first after aa77a0e and each one went up by one. The
# Windows and Mac builds both take it from here, so they always agree. The updater goes by commits,
# so it never compares these numbers.
set -euo pipefail
paths=(installer desktop innertube lyrics canvas integrations spine .github/workflows/desktop.yml .github/workflows/desktop-macos.yml)
v12=567a556af31dc0865859afa9776c7b19ad9a1d2c
v11=aa77a0e80c547908dc77cefd6e507bb9b153604d
if git merge-base --is-ancestor "$v12" HEAD && [ "$(git rev-parse HEAD)" != "$v12" ]; then
  count=$(git rev-list --count --first-parent "$v12..HEAD" -- "${paths[@]}")
  if [ "$count" -le 1 ]; then echo "1.2"; else echo "1.2.$((count - 1))"; fi
elif git merge-base --is-ancestor "$v11" HEAD; then
  count=$(git rev-list --count --first-parent "$v11..HEAD" -- "${paths[@]}")
  echo "1.1.$((19 + count))"
else
  echo "$CONVX_PACKAGE_VERSION"
fi
