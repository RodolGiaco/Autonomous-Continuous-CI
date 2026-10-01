#!/usr/bin/env bash
# The spec record of every issue: specs valid in strict mode, every archived
# change complete, and no change left open. The version is pinned so this
# machine and CI judge with the same rules.
set -uo pipefail
export OPENSPEC_TELEMETRY=0
OPENSPEC_VERSION=1.14.0
OPENSPEC=(npx -y "@fission-ai/openspec@${OPENSPEC_VERSION}")

"${OPENSPEC[@]}" validate --all --strict || exit 1
"${OPENSPEC[@]}" validate --archived || exit 1
"${OPENSPEC[@]}" list --json | jq -e '.changes == []' >/dev/null \
  || { echo "spec-check: a change is still open; archive it before the final commit" >&2; exit 1; }
echo "spec-check: specs valid, archived changes complete, no open change"
