#!/bin/sh
set -eu

BINARY="${BINARY:-/app/vk-turn-proxy}"
ADDRESS="${ADDRESS:-127.0.0.1:56000}"
TIMEOUT="${TIMEOUT:-5s}"
WRAP_MODE="${WRAP_MODE:-false}"
WRAP_KEY="${WRAP_KEY:-}"

set -- -healthcheck "$ADDRESS" -healthcheck-timeout "$TIMEOUT"

if [ "$WRAP_MODE" = "true" ]; then
  : "${WRAP_KEY:?WRAP_KEY is required when WRAP_MODE=true}"
  set -- "$@" -wrap -wrap-key "$WRAP_KEY"
fi

exec "$BINARY" "$@"
