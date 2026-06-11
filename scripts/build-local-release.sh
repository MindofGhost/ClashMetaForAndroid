#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$ROOT_DIR/local-signing/signing.properties}"
PATCH_ID="${PATCH_ID:-$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)}"
if ! git -C "$ROOT_DIR" diff --quiet --ignore-submodules=dirty 2>/dev/null ||
   ! git -C "$ROOT_DIR" diff --cached --quiet --ignore-submodules=dirty 2>/dev/null; then
  PATCH_ID="$PATCH_ID-dirty"
fi
RELEASE_TAG="${RELEASE_TAG:-v2.11.30-turn-$PATCH_ID}"
FLAVOR="${FLAVOR:-meta}"
BUILD_TYPE="${BUILD_TYPE:-Release}"

if [ ! -f "$SIGNING_PROPERTIES" ]; then
  echo "Signing properties not found: $SIGNING_PROPERTIES" >&2
  echo "Create it first or pass SIGNING_PROPERTIES=/path/to/signing.properties" >&2
  exit 1
fi

export SIGNING_PROPERTIES
export RELEASE_TAG
export FLAVOR
export BUILD_TYPE

exec "$ROOT_DIR/scripts/build-android-release-docker.sh"
