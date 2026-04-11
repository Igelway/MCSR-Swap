#!/bin/sh
set -e

MODS_DIR="${1:-/data/mods}"
mkdir -p "$MODS_DIR"
cd "$MODS_DIR"

echo "Downloading FabricProxy to $MODS_DIR..."

# FabricProxy (Velocity support)
curl -Lo fabric-proxy.jar \
  https://github.com/OKTW-Network/FabricProxy/releases/download/v1.3.4/FabricProxy-1.3.4.jar

echo "Successfully downloaded FabricProxy"
