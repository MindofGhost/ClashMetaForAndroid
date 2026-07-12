#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$ROOT_DIR/local-signing/signing.properties}"
GENERATE_SIGNING_KEY="${GENERATE_SIGNING_KEY:-false}"

BRANCH_NAME="${BRANCH_NAME:-$(git -C "$ROOT_DIR" branch --show-current 2>/dev/null || true)}"
if [ -z "$BRANCH_NAME" ]; then
  BRANCH_NAME="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)"
fi
BRANCH_NAME="$(printf '%s' "$BRANCH_NAME" | sed 's#[^A-Za-z0-9._-]#-#g')"

BASE_TAG="${BASE_TAG:-$(
  git -C "$ROOT_DIR" tag --merged HEAD --sort=-v:refname 2>/dev/null |
    sed -n '/^v[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$/ { p; q; }'
)}"
if [ -z "$BASE_TAG" ]; then
  echo "No reachable semantic version tag found (expected vMAJOR.MINOR.PATCH)" >&2
  exit 1
fi
if ! git -C "$ROOT_DIR" rev-parse --verify --quiet "$BASE_TAG^{commit}" >/dev/null; then
  echo "Invalid BASE_TAG: $BASE_TAG" >&2
  exit 1
fi

BASE_COMMIT="$(git -C "$ROOT_DIR" rev-list -n 1 "$BASE_TAG")"
if ! git -C "$ROOT_DIR" merge-base --is-ancestor "$BASE_COMMIT" HEAD; then
  echo "BASE_TAG $BASE_TAG is not reachable from HEAD" >&2
  exit 1
fi
BASE_VERSION_NAME="${BASE_TAG#v}"
VERSION_MAJOR="${BASE_VERSION_NAME%%.*}"
VERSION_REMAINDER="${BASE_VERSION_NAME#*.}"
VERSION_MINOR="${VERSION_REMAINDER%%.*}"
VERSION_PATCH="${VERSION_REMAINDER#*.}"
BASE_VERSION_CODE=$((VERSION_MAJOR * 100000 + VERSION_MINOR * 1000 + VERSION_PATCH))

PATCH_COUNT="$(
  git -C "$ROOT_DIR" rev-list --ancestry-path --count "$BASE_COMMIT"..HEAD 2>/dev/null || echo 0
)"
if [ "$PATCH_COUNT" -gt 999 ]; then
  echo "More than 999 commits since $BASE_TAG; create a new base tag before building" >&2
  exit 1
fi

BASE_VERSION_CODE=$((BASE_VERSION_CODE * 1000 + PATCH_COUNT))
COMMIT_ID="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)"
PATCH_ID="$PATCH_COUNT-$COMMIT_ID"

if ! git -C "$ROOT_DIR" diff --quiet --ignore-submodules=dirty 2>/dev/null ||
   ! git -C "$ROOT_DIR" diff --cached --quiet --ignore-submodules=dirty 2>/dev/null; then
  PATCH_ID="$PATCH_ID-dirty"
fi
RELEASE_TAG="${RELEASE_TAG:-$BASE_TAG-$BRANCH_NAME-$PATCH_ID}"
FLAVOR="${FLAVOR:-meta}"
BUILD_TYPE="${BUILD_TYPE:-Release}"

if [ ! -f "$SIGNING_PROPERTIES" ]; then
  if [ "$GENERATE_SIGNING_KEY" = "true" ]; then
    : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD before generating a production release key}"
    : "${SIGNING_CERT_DNAME:?Set SIGNING_CERT_DNAME before generating a production release key}"
    KEY_ALIAS="${KEY_ALIAS:-clash-meta-for-android-release}"
    KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
    export GENERATE_SIGNING_KEY
    export KEYSTORE_PASSWORD
    export KEY_ALIAS
    export KEY_PASSWORD
    export SIGNING_CERT_DNAME
    export SIGNING_KEY_VALIDITY_DAYS="${SIGNING_KEY_VALIDITY_DAYS:-10000}"
  else
    echo "Signing properties not found: $SIGNING_PROPERTIES" >&2
    echo "Create it first or pass SIGNING_PROPERTIES=/path/to/signing.properties" >&2
    echo "To generate a production-style key once, run with:" >&2
    echo "  GENERATE_SIGNING_KEY=true KEYSTORE_PASSWORD=... SIGNING_CERT_DNAME='CN=Clash Meta for Android, OU=Android Release, O=Your Name or Company, C=RU' $0" >&2
    exit 1
  fi
fi

export SIGNING_PROPERTIES
export RELEASE_TAG
export BASE_VERSION_NAME
export BASE_VERSION_CODE
export FLAVOR
export BUILD_TYPE
export GENERATE_SIGNING_KEY

exec "$ROOT_DIR/scripts/build-android-release-docker.sh"
