#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$ROOT_DIR/local-signing/signing.properties}"
RELEASE_TAG="${RELEASE_TAG:-v2.11.29-local}"
FLAVOR="${FLAVOR:-alpha}"
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
