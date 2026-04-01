#!/bin/bash
set -e

echo "==================================="
echo "MCSR-Swap Docker Build Script"
echo "==================================="

# Build Fabric Mod
echo ""
echo "[1/4] Building Fabric Mod..."
cd fabric-mod
./gradlew build --quiet
cd ..
echo "✓ Fabric mod built: fabric-mod/build/libs/mcsrswap-fabric-mod-1.0.0.jar"

# Build Velocity Plugin
echo ""
echo "[2/4] Building Velocity Plugin..."
cd velocity-plugin
mvn clean package -q
cd ..
echo "✓ Velocity plugin built: velocity-plugin/target/mcsrswap-velocity-plugin-1.0.jar"

# Build Docker Image
echo ""
echo "[3/4] Building Docker image for game servers..."
docker build -f Dockerfile.gameserver -t mcsrswap-gameserver:latest .
echo "✓ Docker image built: mcsrswap-gameserver:latest"

# Setup directories
echo ""
echo "[4/4] Setting up configuration directories..."
mkdir -p velocity-config/plugins/mcsrswap/languages
mkdir -p lobby-data

# Copy default config if not exists
if [ ! -f velocity-config/velocity.toml ]; then
    cat > velocity-config/velocity.toml <<'EOF'
config-version = "2.6"
bind = "0.0.0.0:25577"
motd = "§e§lMCSR-Swap"
show-max-players = 100

[servers]
lobby = "lobby:25565"
try = ["lobby"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000

[query]
enabled = false

[forwarding]
mode = "none"
EOF
    echo "✓ Created velocity-config/velocity.toml"
else
    echo "✓ velocity-config/velocity.toml already exists"
fi

if [ ! -f velocity-config/forwarding.secret ]; then
    echo "$(openssl rand -hex 16)" > velocity-config/forwarding.secret
    echo "✓ Created velocity-config/forwarding.secret"
else
    echo "✓ velocity-config/forwarding.secret already exists"
fi

# Copy language files
cp velocity-plugin/src/main/resources/languages/*.yml velocity-config/plugins/mcsrswap/languages/ 2>/dev/null || true
echo "✓ Copied language files"

echo ""
echo "==================================="
echo "✓ Build complete!"
echo "==================================="
echo ""
echo "==================================="
echo "IMPORTANT: Choose your mode!"
echo "==================================="
echo ""
echo "Two modes available:"
echo ""
echo "1) CLASSIC MODE (Default - Manual Servers)"
echo "   → No Docker environment needed"
echo "   → Setup game servers manually (like before)"
echo ""
echo "2) DOCKER MODE (Auto-detected)"
echo "   → ENV MCSRSWAP_DOCKER_MODE=true (set in docker-compose.yml)"
echo "   → Game servers spawn automatically on /ms start"
echo "   → No manual config.yml changes needed!"
echo ""
echo "Next steps for Docker mode:"
echo "  1. Start the stack: docker-compose up -d"
echo "  2. Connect to localhost:25577"
echo "  3. Run /ms start"
echo "  → Docker mode activates automatically!"
echo ""
echo "Next steps for Classic mode:"
echo "  1. Setup game servers manually"
echo "  2. Start Velocity with the plugin"
echo "  3. Run /ms start"
echo ""
