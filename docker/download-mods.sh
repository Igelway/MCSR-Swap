#!/bin/sh
set -e

MODS_DIR="${1:-/opt/app-files/mods}"
cd "$MODS_DIR"

echo "Downloading required mods to $MODS_DIR..."

# Fabric API
curl -Lo fabric-api.jar \
  https://github.com/FabricMC/fabric-api/releases/download/0.18.0%2Bbuild.387-1.16.1/fabric-api-0.18.0+build.387-1.16.1.jar

# FabricProxy (Velocity support)
curl -Lo fabric-proxy.jar \
  https://github.com/OKTW-Network/FabricProxy/releases/download/v1.3.4/FabricProxy-1.3.4.jar

# Legal speedrun mods
curl -Lo antigone.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/antigone/1.16.1/antigone-1.16.1-2.0.0.jar

curl -Lo lithium.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/lithium/1.16.1/lithium-1.0+backport-0.6.7+1.16.1.jar

curl -Lo voyager.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/voyager/1.14-1.16.5/voyager-1.0.1.jar

curl -Lo lazydfu.jar \
  https://cdn.modrinth.com/data/hvFnDODi/versions/0.1.2/lazydfu-0.1.2.jar

curl -Lo krypton.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/krypton/1.15-1.16.1/krypton-1.16.1-backport-0.1.3-SNAPSHOT+2021-02-20.jar

curl -Lo starlight.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/starlight/1.16-1.16.5/starlight-1.3.0+1.16.x-backport-1.1.3.jar

echo "Successfully downloaded all mods"
