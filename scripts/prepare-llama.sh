#!/usr/bin/env bash
# Prepare the pinned llama.cpp source in llm/vendor/llama.cpp (CI / Linux).
#
# This is the equivalent of scripts/prepare-llama.ps1 -FromUpstream: it fetches the pinned commit
# from a Git remote, so it is the one step that uses the network. The commit hash and the pristine
# state are verified afterwards, exactly like the offline script does.
#
# Usage: scripts/prepare-llama.sh [upstream-url]
set -euo pipefail

COMMIT="5e03bdd8700948b9c41c54dd1b00f28a2aebc03f"
UPSTREAM="${1:-https://github.com/ggml-org/llama.cpp}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/llm/vendor/llama.cpp"

if [ ! -d "$DEST" ]; then
  mkdir -p "$(dirname "$DEST")"
  git init --quiet "$DEST"
  git -C "$DEST" remote add origin "$UPSTREAM"
  git -C "$DEST" fetch --depth 1 origin "$COMMIT"
  git -C "$DEST" checkout --detach FETCH_HEAD
fi

ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
if [ "$ACTUAL" != "$COMMIT" ]; then
  echo "Unexpected llama.cpp revision: $ACTUAL" >&2
  exit 1
fi
DIRTY="$(git -C "$DEST" status --porcelain --untracked-files=all)"
if [ -n "$DIRTY" ]; then
  echo "llama.cpp source must be pristine" >&2
  exit 1
fi
echo "llama.cpp verified at $ACTUAL"