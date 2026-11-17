#!/usr/bin/env bash
# Prints the version people see (About, the setup window, the release and its file names): 1.1.20
# for the first commit after aa77a0e, then one up for each commit on main that changes Convx. The
# Windows and Mac builds both take it from here, so they always agree. The updater goes by commits,
# so it never compares these numbers.
set -euo pipefail
base=aa77a0e80c547908dc77cefd6e507bb9b153604d
if git merge-base --is-ancestor "$base" HEAD; then
  count=$(git rev-list --count --first-parent "$base..HEAD" -- installer desktop innertube lyrics canvas integrations spine .github/workflows/desktop.yml .github/workflows/desktop-macos.yml)
  echo "1.1.$((19 + count))"
else
  echo "$CONVX_PACKAGE_VERSION"
fi
