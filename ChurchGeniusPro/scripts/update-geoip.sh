#!/usr/bin/env bash
# Download / refresh the MaxMind GeoLite2 City database used by GeoIpService to turn a
# login IP address into city / state / country for the security audit trail.
#
# The database is NOT in this repository: MaxMind's licence does not permit
# redistribution, and the file is ~60MB. The application runs perfectly well without it —
# it logs one warning at startup and records city/state/country as "-".
#
# ── One-time setup ───────────────────────────────────────────────────────────────
#   1. Create a free account:  https://www.maxmind.com/en/geolite2/signup
#   2. Generate a licence key: Account -> Manage License Keys -> Generate new licence key
#   3. Export it (never commit it):  export MAXMIND_LICENSE_KEY=xxxxxxxx
#
# ── Where the file goes ──────────────────────────────────────────────────────────
#   Default:  $HOME/data/GeoLite2-City.mmdb
#   On Azure App Service $HOME is the persistent Azure Files share, so the database
#   survives restarts and redeployments and does NOT need to be part of the build
#   artifact. Override with GEOIP_DB_PATH (the same variable the app reads).
#
# ── Running it on App Service ────────────────────────────────────────────────────
#   Open the Kudu/SSH console (Development Tools -> SSH, or https://<app>.scm.azurewebsites.net)
#   and run this script there, or simply:
#     mkdir -p /home/data && cd /home/data
#     curl -sSL "https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-City&license_key=$MAXMIND_LICENSE_KEY&suffix=tar.gz" \
#       | tar xz --strip-components=1 --wildcards '*/GeoLite2-City.mmdb'
#   Then restart the app so GeoIpService picks it up.
#
# ── Keeping it current ───────────────────────────────────────────────────────────
#   MaxMind republishes twice a week and stale data slowly loses accuracy. A monthly
#   refresh is plenty for an audit trail. This script is safe to re-run: it downloads to
#   a temporary file and only swaps it in once the download has fully succeeded.

set -euo pipefail

: "${MAXMIND_LICENSE_KEY:?Set MAXMIND_LICENSE_KEY first — see the header of this script}"

TARGET="${GEOIP_DB_PATH:-${HOME}/data/GeoLite2-City.mmdb}"
TARGET_DIR="$(dirname "$TARGET")"
EDITION="GeoLite2-City"
URL="https://download.maxmind.com/app/geoip_download?edition_id=${EDITION}&license_key=${MAXMIND_LICENSE_KEY}&suffix=tar.gz"

mkdir -p "$TARGET_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> Downloading ${EDITION} from MaxMind"
# --fail so an auth error is not silently written out as a 40-byte 'database'
curl -sSL --fail "$URL" -o "$TMP_DIR/geoip.tar.gz"

echo "==> Extracting"
tar xzf "$TMP_DIR/geoip.tar.gz" -C "$TMP_DIR"
FOUND="$(find "$TMP_DIR" -name "${EDITION}.mmdb" -type f | head -n1)"
if [ -z "$FOUND" ]; then
  echo "ERROR: ${EDITION}.mmdb not found in the downloaded archive." >&2
  exit 1
fi

# Sanity-check before replacing a working database with something broken.
SIZE_BYTES="$(stat -c%s "$FOUND" 2>/dev/null || stat -f%z "$FOUND")"
if [ "$SIZE_BYTES" -lt 10000000 ]; then
  echo "ERROR: downloaded database is only ${SIZE_BYTES} bytes — expected ~60MB. Refusing to install it." >&2
  exit 1
fi

# Move into place atomically (same filesystem) so the app never sees a half-written file.
mv "$FOUND" "${TARGET}.new"
mv "${TARGET}.new" "$TARGET"

echo "==> Installed $(du -h "$TARGET" | cut -f1) database at $TARGET"
echo "    Restart the application for GeoIpService to load it."
