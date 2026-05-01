#!/bin/sh
set -e

MODS_DIR="${1:-/opt/app-files/mods}"
cd "$MODS_DIR"

# Download a single mod and verify its SHA-256 checksum.
# Usage: download <output-file> <url> <expected-sha256>
download() {
  file="$1" url="$2" expected="$3"
  echo "Downloading $file..."
  curl -fsSL --retry 3 -o "$file" "$url"
  actual=$(sha256sum "$file" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "Checksum mismatch for $file: expected $expected, got $actual" >&2
    exit 1
  fi
}

echo "Downloading required mods to $MODS_DIR..."

# Fabric API
download fabric-api.jar \
  https://github.com/FabricMC/fabric-api/releases/download/0.18.0%2Bbuild.387-1.16.1/fabric-api-0.18.0+build.387-1.16.1.jar \
  ed274825dd3b106a0de04b5fc4686927b4018a0de48484f56f64c87791ed4dbb

# FabricProxy (Velocity support)
download fabric-proxy.jar \
  https://github.com/OKTW-Network/FabricProxy/releases/download/v1.3.4/FabricProxy-1.3.4.jar \
  634cc66b1fc5ab5f3bb22e4824b9c7d3e0873df744dd9146ee06d679681d3e19

# Legal speedrun mods
download antigone.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/antigone/1.16.1/antigone-1.16.1-2.0.0.jar \
  b2e5058596048618e864dd9645fad12789b5cf7d4d635f437f1ffc07df584cf6

download lithium.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/lithium/1.16.1/lithium-1.0+backport-0.6.7+1.16.1.jar \
  1708decd72c329646b359f795224f888d18a9de3c98df4079c3199c72de9fbc3

download voyager.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/voyager/1.14-1.16.5/voyager-1.0.1.jar \
  9f64a2d34452770a225f284d1948aea8af05866c5ba7a4fc3b8836997b4de844

download lazydfu.jar \
  https://cdn.modrinth.com/data/hvFnDODi/versions/0.1.2/lazydfu-0.1.2.jar \
  e78eb1492e20bcd8c998d4142dd437c2517393c5d02f582204ad8a38341e75e8

download krypton.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/krypton/1.15-1.16.1/krypton-1.16.1-backport-0.1.3-SNAPSHOT+2021-02-20.jar \
  647a8865773408e53242afebaf5d18b8412616c1c260f1ff5ddcf1c0cbf50c87

download starlight.jar \
  https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/2ba63fc475270404e4a1c1f910f22bdc9bc14186/legal-mods/starlight/1.16-1.16.5/starlight-1.3.0+1.16.x-backport-1.1.3.jar \
  8492378aefe02917a659e82b24c447ab805f08ca79cd584bc3e884ff08864568

echo "Successfully downloaded and verified all mods"

# Carpet (tick-freeze support) and Chunky (preload support) are always
# included in the image. Carpet only acts when /tick freeze is called;
# Chunky only runs when /chunky start is issued by the mod at startup.
download carpet.jar \
  "https://cdn.modrinth.com/data/TQTTVgYE/versions/cI14KY8A/fabric-carpet-1.16.1-1.4.0%2Bv200623_build2.jar" \
  f4b3440067eb44725034aed9751626c834d51577bc3b0a86231a5efd4e102f71

download chunky.jar \
  "https://cdn.modrinth.com/data/fALzjamp/versions/1.2.54/Chunky-1.2.54.jar" \
  e56a2e4febffce8435b04d602b7d19a13d5fbb2e07504f094a5911d9b40f8ef5

echo "Downloaded Carpet + Chunky"
