#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(pwd)/build/lb-module.zip"
mkdir -p build
rm -f "$OUT"
(cd app/src/main/assets/module && zip -qr "$OUT" .)
echo "built: $OUT"
unzip -l "$OUT"
