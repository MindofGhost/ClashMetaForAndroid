#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SOURCE_DIR="${SOURCE_DIR:-$ROOT_DIR/third_party/vk-turn-proxy/android-binaries}"
TARGET_DIR="${TARGET_DIR:-$ROOT_DIR/service/src/main/jniLibs}"

copy_binary() {
  abi="$1"
  source="$SOURCE_DIR/$abi/client-android"
  target="$TARGET_DIR/$abi/libvkturnproxy.so"

  if [ ! -f "$source" ]; then
    echo "Missing VK TURN binary for $abi: $source" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$target")"
  cp "$source" "$target"
  chmod 0644 "$target"
  echo "$source -> $target"
}

copy_binary arm64-v8a
copy_binary armeabi-v7a
