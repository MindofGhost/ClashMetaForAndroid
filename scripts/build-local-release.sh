#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$ROOT_DIR/local-signing/signing.properties}"

BRANCH_NAME="${BRANCH_NAME:-$(git -C "$ROOT_DIR" branch --show-current 2>/dev/null || true)}"
if [ -z "$BRANCH_NAME" ]; then
  BRANCH_NAME="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)"
fi
BRANCH_NAME="$(printf '%s' "$BRANCH_NAME" | sed 's#[^A-Za-z0-9._-]#-#g')"

BASE_REF="${BASE_REF:-}"
if [ -z "$BASE_REF" ]; then
  if git -C "$ROOT_DIR" show-ref --verify --quiet refs/remotes/origin/main; then
    BASE_REF="origin/main"
  elif git -C "$ROOT_DIR" show-ref --verify --quiet refs/heads/main; then
    BASE_REF="main"
  elif git -C "$ROOT_DIR" show-ref --verify --quiet refs/remotes/origin/master; then
    BASE_REF="origin/master"
  elif git -C "$ROOT_DIR" show-ref --verify --quiet refs/heads/master; then
    BASE_REF="master"
  else
    BASE_REF="$(git -C "$ROOT_DIR" describe --tags --abbrev=0 2>/dev/null || true)"
  fi
fi

if [ -n "$BASE_REF" ]; then
  BASE_COMMIT="$(git -C "$ROOT_DIR" merge-base HEAD "$BASE_REF" 2>/dev/null || true)"
else
  BASE_COMMIT=""
fi

if [ -n "$BASE_COMMIT" ]; then
  PATCH_ID="$(git -C "$ROOT_DIR" rev-list --count "$BASE_COMMIT"..HEAD 2>/dev/null || echo 0)"
else
  PATCH_ID="$(git -C "$ROOT_DIR" rev-list --count HEAD 2>/dev/null || echo 0)"
fi
COMMIT_ID="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)"
PATCH_ID="$PATCH_ID-$COMMIT_ID"

if ! git -C "$ROOT_DIR" diff --quiet --ignore-submodules=dirty 2>/dev/null ||
   ! git -C "$ROOT_DIR" diff --cached --quiet --ignore-submodules=dirty 2>/dev/null; then
  PATCH_ID="$PATCH_ID-dirty"
fi
RELEASE_TAG="${RELEASE_TAG:-v2.11.30-$BRANCH_NAME-$PATCH_ID}"
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
