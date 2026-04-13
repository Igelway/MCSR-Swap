set dotenv-load

# Absolute path to the game-server data root on the HOST.
# Override via GAME_DATA_DIR in .env or environment to use a custom location.
game_data_dir := env("GAME_DATA_DIR", justfile_directory() / "data")

# Default recipe: show available commands
default:
    @just --list

# Bump version and create release tag (optional: specify version like "1.2.3")
release version="":
    scripts/release.sh {{version}}

# Push release tag to remote
push:
    #!/usr/bin/env bash
    set -e
    TAG=$(git describe --tags --abbrev=0)
    echo "Pushing $TAG and main branch..."
    git push origin main
    git push origin "$TAG"
    echo "✓ Pushed $TAG - CI/CD will build and release"

# Create release and push in one go
release-push version="":
    #!/usr/bin/env bash
    set -euo pipefail
    VERSION=$(just release {{version}})
    echo "✓ Created release v$VERSION"
    just push

# Build Fabric mod locally
build-fabric:
    cd fabric-mod && ./gradlew clean build

# Build Velocity plugin locally
build-velocity:
    cd velocity-plugin && mvn clean package

# Build both JARs locally
build: build-fabric build-velocity

# Generate .forwarding.secret if not already present.
[private]
setup-env playit="false":
    scripts/setup-env.sh {{ if playit == "true" { "--playit" } else { "" } }}

# Start Docker Compose setup (use --playit to also start the playit.gg tunnel)
[arg("playit", long="playit", value="true")]
up playit="false": (setup-env playit)
    PUID=${PUID:-$(id -u)} PGID=${PGID:-$(id -g)} GAME_DATA_DIR="{{game_data_dir}}" docker compose {{ if playit == "true" { "--profile tunnel" } else { "" } }} up -d

# Stop Docker Compose setup
down:
    docker compose down

# View logs from all containers
logs:
    docker compose logs -f

# Open an RCON console on a server (lobby, game1, game2, etc.)
# For Velocity, attaches directly to the container console (detach with Ctrl+P Ctrl+Q).
console service="lobby":
    #!/usr/bin/env bash
    if [ "{{service}}" = "velocity" ]; then
        echo "Attaching to Velocity console. Detach with Ctrl+C (will NOT stop the server)."
        docker attach --sig-proxy=false "mcsrswap-velocity"
    else
        docker exec -it "mcsrswap-{{service}}" rcon-cli
    fi

# Pull latest Docker images
pull:
    docker compose pull

# Build Docker images locally (uses .env if present)
docker-build: build
    #!/usr/bin/env bash
    set -e
    # Load .env if exists
    if [ -f .env ]; then
        source .env
    fi
    VELOCITY_TAG="${MCSRSWAP_VELOCITY_IMAGE:-mcsr-swap-velocity:latest}"
    GAMESERVER_TAG="${MCSRSWAP_GAMESERVER_IMAGE:-mcsr-swap-gameserver:latest}"
    echo "Building Velocity image: $VELOCITY_TAG"
    docker build -f docker/Dockerfile.velocity -t "$VELOCITY_TAG" .
    echo "Building Gameserver image: $GAMESERVER_TAG"
    docker build -f docker/Dockerfile.gameserver -t "$GAMESERVER_TAG" .

# Build only Velocity Docker image locally
docker-build-velocity: build-velocity
    #!/usr/bin/env bash
    set -e
    if [ -f .env ]; then
        source .env
    fi
    TAG="${MCSRSWAP_VELOCITY_IMAGE:-mcsr-swap-velocity:latest}"
    echo "Building Velocity image: $TAG"
    docker build -f docker/Dockerfile.velocity -t "$TAG" .

# Build only Gameserver Docker image locally
docker-build-gameserver: build-fabric
    #!/usr/bin/env bash
    set -e
    if [ -f .env ]; then
        source .env
    fi
    TAG="${MCSRSWAP_GAMESERVER_IMAGE:-mcsr-swap-gameserver:latest}"
    echo "Building Gameserver image: $TAG"
    docker build -f docker/Dockerfile.gameserver -t "$TAG" .

format-java:
    cd fabric-mod && ./gradlew spotlessApply
    cd velocity-plugin && mvn spotless:apply

# Check Java code formatting
format-java-check:
    cd fabric-mod && ./gradlew spotlessCheck
    cd velocity-plugin && mvn spotless:check
