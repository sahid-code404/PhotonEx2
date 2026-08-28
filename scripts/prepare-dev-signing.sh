#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$root/tools/dev-signing/photonex2-dev.jks.b64"
target="$root/tools/dev-signing/photonex2-dev.jks"
base64 --decode "$source_file" > "$target"
chmod 600 "$target"
keytool -list -keystore "$target" -storepass photonex2dev -alias photonex2-dev >/dev/null
