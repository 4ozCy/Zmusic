#!/usr/bin/env bash
# Downloads the two open-licence fonts the app uses into res/font.
# CI runs this automatically. Run it once before building locally.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/main/res/font
curl -sSL -f -o app/src/main/res/font/manrope.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf"
curl -sSL -f -o app/src/main/res/font/jetbrains_mono.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
echo "Fonts ready."
