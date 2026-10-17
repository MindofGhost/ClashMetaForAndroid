#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ca_dir="$ROOT_DIR/service/src/main/res/raw"
mkdir -p "$ca_dir"
temp_dir="$(mktemp -d "$ca_dir/.ca-update.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT

echo "Updating Mozilla CA certificates..."
for name in cacert.pem cacert.pem.sha256; do
  curl --fail --silent --show-error --location \
    --proto '=https' --proto-redir '=https' \
    --connect-timeout 10 --max-time 60 --retry 2 \
    "https://curl.se/ca/$name" -o "$temp_dir/$name"
done
read -r expected_hash remainder < "$temp_dir/cacert.pem.sha256"
case "$expected_hash" in
  ''|*[!0-9a-fA-F]*) echo "Invalid CA bundle checksum" >&2; exit 1 ;;
esac
if [ "${#expected_hash}" -ne 64 ]; then
  echo "Invalid CA bundle checksum length" >&2
  exit 1
fi
if ! printf '%s  %s\n' "$expected_hash" "$temp_dir/cacert.pem" | sha256sum --check --status; then
  echo "CA bundle checksum mismatch" >&2
  exit 1
fi
openssl x509 -in "$temp_dir/cacert.pem" -noout
openssl crl2pkcs7 -nocrl -certfile "$temp_dir/cacert.pem" -out /dev/null

if cmp -s "$temp_dir/cacert.pem" "$ca_dir/browser_ca.pem"; then
  echo "Mozilla CA certificates are up to date"
else
  chmod 644 "$temp_dir/cacert.pem"
  mv -f "$temp_dir/cacert.pem" "$ca_dir/browser_ca.pem"
  echo "Mozilla CA certificates updated: SHA-256 $expected_hash"
fi
