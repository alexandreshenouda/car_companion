#!/usr/bin/env bash
# Downloads the latest unsigned .ipa (published by .github/workflows/ios.yml to the
# rolling "ios-latest" GitHub Release) into a local altserver-docker checkout's ipa/
# folder, so it can be picked up by:
#   docker exec -it altserver install iosApp-unsigned.ipa <apple-id> <app-specific-password>
#
# altserver-docker itself is NOT vendored into this repo — it's third-party infra with
# its own Dockerfile/updates, cloned separately. See README.md's "iOS port" section for
# the full one-time setup (docker compose up, USB pairing, install).
#
# Usage: ./fetch-latest-ipa.sh [path-to-altserver-docker-checkout]
set -euo pipefail

ALTSERVER_DIR="${1:-../altserver-docker}"
IPA_DIR="$ALTSERVER_DIR/ipa"

if [[ ! -d "$ALTSERVER_DIR" ]]; then
  echo "error: $ALTSERVER_DIR not found. Clone FacuM/altserver-docker there first, or pass its path as \$1." >&2
  exit 1
fi

mkdir -p "$IPA_DIR"
gh release download ios-latest \
  --repo alexandreshenouda/car_companion \
  --pattern "iosApp-unsigned.ipa" \
  --dir "$IPA_DIR" \
  --clobber

echo "Downloaded to $IPA_DIR/iosApp-unsigned.ipa"
echo "Sign + install with:"
echo "  docker exec -it altserver install iosApp-unsigned.ipa <apple-id> <app-specific-password>"
