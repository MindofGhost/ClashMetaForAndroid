#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

IMAGE="${IMAGE:-ghcr.io/cirruslabs/android-sdk:35}"
DOCKER_CONTEXT_NAME="${DOCKER_CONTEXT_NAME:-default}"
GO_VERSION="${GO_VERSION:-1.25.5}"
GOFLAGS="${GOFLAGS:--ldflags=-checklinkname=0}"
FLAVOR="${FLAVOR:-alpha}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
RELEASE_TAG="${RELEASE_TAG:-}"
BASE_VERSION_NAME="${BASE_VERSION_NAME:-}"
BASE_VERSION_CODE="${BASE_VERSION_CODE:-}"
REMOVE_SUFFIX="${REMOVE_SUFFIX:-false}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/dist/android}"
GRADLE_CACHE_DIR="${GRADLE_CACHE_DIR:-$HOME/.gradle-cmfa-docker}"
GO_CACHE_DIR="${GO_CACHE_DIR:-$HOME/.cache/go-build-cmfa-docker}"
GO_MOD_CACHE_DIR="${GO_MOD_CACHE_DIR:-$HOME/go/pkg/mod-cmfa-docker}"
TOOLCHAIN_CACHE_DIR="${TOOLCHAIN_CACHE_DIR:-$HOME/.cache/cmfa-android-toolchain}"
ANDROID_NDK_CACHE_DIR="${ANDROID_NDK_CACHE_DIR:-$TOOLCHAIN_CACHE_DIR/ndk}"
ANDROID_CMAKE_CACHE_DIR="${ANDROID_CMAKE_CACHE_DIR:-$TOOLCHAIN_CACHE_DIR/cmake}"
SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$ROOT_DIR/signing.properties}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-${SIGNING_STORE_PASSWORD:-}}"
KEY_ALIAS="${KEY_ALIAS:-${SIGNING_KEY_ALIAS:-}}"
KEY_PASSWORD="${KEY_PASSWORD:-${SIGNING_KEY_PASSWORD:-}}"
GENERATE_SIGNING_KEY="${GENERATE_SIGNING_KEY:-false}"
SIGNING_CERT_DNAME="${SIGNING_CERT_DNAME:-}"
SIGNING_KEY_VALIDITY_DAYS="${SIGNING_KEY_VALIDITY_DAYS:-10000}"
GENERATED_SIGNING_DIR="${GENERATED_SIGNING_DIR:-$ROOT_DIR/local-signing}"
GENERATED_KEYSTORE_FILE="${GENERATED_KEYSTORE_FILE:-$GENERATED_SIGNING_DIR/cmfa-release.keystore}"
GENERATED_SIGNING_PROPERTIES="$GENERATED_SIGNING_DIR/signing.properties"

if [[ -n "$RELEASE_TAG" && "$OUTPUT_DIR" == "$ROOT_DIR/dist/android" ]]; then
  OUTPUT_DIR="$OUTPUT_DIR/$RELEASE_TAG"
fi

case "$FLAVOR" in
  alpha|meta) ;;
  *)
    echo "Unsupported FLAVOR=$FLAVOR. Use alpha or meta." >&2
    exit 2
    ;;
esac

case "$BUILD_TYPE" in
  Release|Debug) ;;
  release) BUILD_TYPE="Release" ;;
  debug) BUILD_TYPE="Debug" ;;
  *)
    echo "Unsupported BUILD_TYPE=$BUILD_TYPE. Use Release or Debug." >&2
    exit 2
    ;;
esac

if [[ "$BUILD_TYPE" == "Release" && ! -f "$SIGNING_PROPERTIES" ]]; then
  if [[ "$GENERATE_SIGNING_KEY" == "true" ]]; then
    : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD before generating a release signing key}"
    : "${KEY_ALIAS:=clash-meta-for-android-release}"
    : "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"
    : "${SIGNING_CERT_DNAME:?Set SIGNING_CERT_DNAME before generating a release signing key}"
  else
    : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD or provide SIGNING_PROPERTIES=/path/to/signing.properties}"
    : "${KEY_ALIAS:?Set KEY_ALIAS or provide SIGNING_PROPERTIES=/path/to/signing.properties}"
    : "${KEY_PASSWORD:?Set KEY_PASSWORD or provide SIGNING_PROPERTIES=/path/to/signing.properties}"
  fi
fi

mkdir -p \
  "$OUTPUT_DIR" \
  "$GRADLE_CACHE_DIR" \
  "$GO_CACHE_DIR" \
  "$GO_MOD_CACHE_DIR" \
  "$TOOLCHAIN_CACHE_DIR" \
  "$ANDROID_NDK_CACHE_DIR" \
  "$ANDROID_CMAKE_CACHE_DIR" \
  "$GENERATED_SIGNING_DIR"

TASK="assemble${FLAVOR^}${BUILD_TYPE}"
USER_ID="$(id -u)"
GROUP_ID="$(id -g)"

docker_args=(
  run --rm
  -e "GO_VERSION=$GO_VERSION"
  -e "GOFLAGS=$GOFLAGS"
  -e "FLAVOR=$FLAVOR"
  -e "BUILD_TYPE=$BUILD_TYPE"
  -e "RELEASE_TAG=$RELEASE_TAG"
  -e "BASE_VERSION_NAME=$BASE_VERSION_NAME"
  -e "BASE_VERSION_CODE=$BASE_VERSION_CODE"
  -e "REMOVE_SUFFIX=$REMOVE_SUFFIX"
  -e "GENERATE_SIGNING_KEY=$GENERATE_SIGNING_KEY"
  -e "SIGNING_CERT_DNAME=$SIGNING_CERT_DNAME"
  -e "SIGNING_KEY_VALIDITY_DAYS=$SIGNING_KEY_VALIDITY_DAYS"
  -e "GENERATED_KEYSTORE_FILE=/workspace/local-signing/$(basename "$GENERATED_KEYSTORE_FILE")"
  -e "GENERATED_SIGNING_PROPERTIES=/workspace/local-signing/signing.properties"
  -e "TASK=$TASK"
  -e "USER_ID=$USER_ID"
  -e "GROUP_ID=$GROUP_ID"
  -e "GOMODCACHE=/go/pkg/mod"
  -e "GOCACHE=/root/.cache/go-build"
  -v "$ROOT_DIR:/workspace"
  -v "$OUTPUT_DIR:/out"
  -v "$GRADLE_CACHE_DIR:/root/.gradle"
  -v "$GO_CACHE_DIR:/root/.cache/go-build"
  -v "$GO_MOD_CACHE_DIR:/go/pkg/mod"
  -v "$TOOLCHAIN_CACHE_DIR:/toolchain"
  -v "$ANDROID_NDK_CACHE_DIR:/opt/android-sdk-linux/ndk"
  -v "$ANDROID_CMAKE_CACHE_DIR:/opt/android-sdk-linux/cmake"
  -w /workspace
)

if [[ -f "$SIGNING_PROPERTIES" ]]; then
  docker_args+=(-v "$SIGNING_PROPERTIES:/tmp/signing.properties:ro")
elif [[ "$BUILD_TYPE" == "Release" ]]; then
  docker_args+=(
    -e "KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
    -e "KEY_ALIAS=$KEY_ALIAS"
    -e "KEY_PASSWORD=$KEY_PASSWORD"
  )
fi

docker_args+=("$IMAGE" bash -lc '
set -euo pipefail

cleanup() {
  if [[ -f /tmp/workspace.signing.properties.bak ]]; then
    cp /tmp/workspace.signing.properties.bak /workspace/signing.properties
  elif [[ "${created_signing_properties:-false}" == "true" ]]; then
    rm -f /workspace/signing.properties
  fi

  if [[ -f /tmp/workspace.local.properties.bak ]]; then
    cp /tmp/workspace.local.properties.bak /workspace/local.properties
  elif [[ "${created_local_properties:-false}" == "true" ]]; then
    rm -f /workspace/local.properties
  fi
}
trap cleanup EXIT

created_signing_properties=false
created_local_properties=false

if [[ -f /workspace/signing.properties ]]; then
  cp /workspace/signing.properties /tmp/workspace.signing.properties.bak
fi

if [[ -f /workspace/local.properties ]]; then
  cp /workspace/local.properties /tmp/workspace.local.properties.bak
fi

cached_go_version=""
if [[ -x /toolchain/go/bin/go ]]; then
  cached_go_version="$(/toolchain/go/bin/go version | awk '"'"'{print $3}'"'"')"
fi

system_go_version=""
if command -v go >/dev/null 2>&1; then
  system_go_version="$(go version | awk '"'"'{print $3}'"'"')"
fi

if [[ "$cached_go_version" == "go${GO_VERSION}" ]]; then
  export PATH="/toolchain/go/bin:$PATH"
elif [[ "$system_go_version" != "go${GO_VERSION}" ]]; then
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) go_arch="amd64" ;;
    aarch64|arm64) go_arch="arm64" ;;
    *) echo "Unsupported container architecture: $arch" >&2; exit 2 ;;
  esac

  apt-get update
  apt-get install -y --no-install-recommends ca-certificates curl xz-utils
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${go_arch}.tar.gz" -o /tmp/go.tgz
  rm -rf /toolchain/go
  tar -C /toolchain -xzf /tmp/go.tgz
  export PATH="/toolchain/go/bin:$PATH"
else
  export PATH="/usr/local/go/bin:$PATH"
fi
git config --global --add safe.directory /workspace

if [[ -f /tmp/signing.properties ]]; then
  cp /tmp/signing.properties /workspace/signing.properties
elif [[ "$BUILD_TYPE" == "Release" && "$GENERATE_SIGNING_KEY" == "true" ]]; then
  created_signing_properties=true
  mkdir -p /workspace/local-signing

  if [[ ! -f "$GENERATED_SIGNING_PROPERTIES" ]]; then
    : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD before generating a release signing key}"
    : "${KEY_ALIAS:=clash-meta-for-android-release}"
    : "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"
    : "${SIGNING_CERT_DNAME:?Set SIGNING_CERT_DNAME before generating a release signing key}"

    keytool -genkeypair \
      -v \
      -keystore "$GENERATED_KEYSTORE_FILE" \
      -storetype PKCS12 \
      -storepass "$KEYSTORE_PASSWORD" \
      -alias "$KEY_ALIAS" \
      -keypass "$KEY_PASSWORD" \
      -keyalg RSA \
      -keysize 4096 \
      -validity "$SIGNING_KEY_VALIDITY_DAYS" \
      -dname "$SIGNING_CERT_DNAME"

    {
      printf "keystore.file=%s\n" "${GENERATED_KEYSTORE_FILE#/workspace/}"
      printf "keystore.password=%s\n" "$KEYSTORE_PASSWORD"
      printf "key.alias=%s\n" "$KEY_ALIAS"
      printf "key.password=%s\n" "$KEY_PASSWORD"
    } > "$GENERATED_SIGNING_PROPERTIES"
  fi

  cp "$GENERATED_SIGNING_PROPERTIES" /workspace/signing.properties
elif [[ "$BUILD_TYPE" == "Release" ]]; then
  created_signing_properties=true
  {
    printf "keystore.file=release.keystore\n"
    printf "keystore.password=%s\n" "$KEYSTORE_PASSWORD"
    printf "key.alias=%s\n" "$KEY_ALIAS"
    printf "key.password=%s\n" "$KEY_PASSWORD"
  } > /workspace/signing.properties
fi

if [[ "$REMOVE_SUFFIX" == "true" ]]; then
  created_local_properties=true
  if [[ -f /tmp/workspace.local.properties.bak ]]; then
    grep -v "^remove\\.suffix=" /tmp/workspace.local.properties.bak > /workspace/local.properties || true
  else
    : > /workspace/local.properties
  fi
  printf "remove.suffix=true\n" >> /workspace/local.properties
fi

./gradlew --no-daemon "$TASK"

find "/workspace/app/build/outputs/apk/$FLAVOR/${BUILD_TYPE,,}" -name "*.apk" -type f -print -exec cp {} /out/ \;

chown -R "$USER_ID:$GROUP_ID" /out /workspace/local-signing 2>/dev/null || true
')

echo "Building $TASK in Docker image $IMAGE"
if [[ -n "$RELEASE_TAG" ]]; then
  echo "Release tag: $RELEASE_TAG"
fi
echo "Docker context: $DOCKER_CONTEXT_NAME"
echo "APK output directory: $OUTPUT_DIR"
docker --context "$DOCKER_CONTEXT_NAME" "${docker_args[@]}"
