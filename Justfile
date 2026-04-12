# Default recipe: show available commands
default:
    @just --list

# Bump version and create release tag (optional: specify version like "1.2.3")
release version="":
    #!/usr/bin/env bash
    set -euo pipefail

    # Determine new version
    if [ -z "{{version}}" ]; then
        # Auto-increment patch version
        CURRENT=$(git describe --tags --abbrev=0 2>/dev/null || echo "v1.0.0")
        VERSION=$(echo "$CURRENT" | sed 's/^v//' | awk -F. '{print $1"."$2"."$3+1}')
        echo "→ Auto-incrementing from $CURRENT to v$VERSION" >&2
    else
        VERSION="{{version}}"
        echo "→ Setting version to v$VERSION" >&2
    fi

    # Update version in root pom.xml
    echo "→ Updating pom.xml..." >&2
    sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" pom.xml

    # Update version in velocity-plugin/pom.xml
    echo "→ Updating velocity-plugin/pom.xml..." >&2
    sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" velocity-plugin/pom.xml

    # Update version in fabric-mod/build.gradle
    echo "→ Updating fabric-mod/build.gradle..." >&2
    sed -i "s/^version = .*/version = '$VERSION'/" fabric-mod/build.gradle

    # Update version in fabric.mod.json (both src and bin)
    echo "→ Updating fabric-mod/src/main/resources/fabric.mod.json..." >&2
    sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" fabric-mod/src/main/resources/fabric.mod.json

    echo "→ Updating fabric-mod/bin/main/fabric.mod.json..." >&2
    sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" fabric-mod/bin/main/fabric.mod.json 2>/dev/null || true

    # Update version in velocity-plugin.json
    echo "→ Updating velocity-plugin/src/main/resources/velocity-plugin.json..." >&2
    sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" velocity-plugin/src/main/resources/velocity-plugin.json

    # Update version in README.md
    echo "→ Updating README.md..." >&2
    sed -i "s/mcsrswap-fabric-mod-[0-9.]*\.jar/mcsrswap-fabric-mod-$VERSION.jar/g" README.md
    sed -i "s/mcsrswap-velocity-plugin-[0-9.]*\.jar/mcsrswap-velocity-plugin-$VERSION.jar/g" README.md

    # Commit and tag
    echo "→ Committing and tagging..." >&2
    git add pom.xml velocity-plugin/pom.xml fabric-mod/build.gradle fabric-mod/src/main/resources/fabric.mod.json fabric-mod/bin/main/fabric.mod.json velocity-plugin/src/main/resources/velocity-plugin.json README.md 2>/dev/null || true
    git commit --allow-empty -m "chore: bump version to $VERSION" || true
    git tag "v$VERSION"

    echo "$VERSION"

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
setup-env:
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -f ".forwarding.secret" ]; then
        echo "✓ .forwarding.secret already exists"
    else
        openssl rand -base64 12 | tr -d '+/=\n' > .forwarding.secret
        echo "→ Generated .forwarding.secret"
    fi

# Set up the playit.gg agent key by entering it interactively.
# Get your key at: https://playit.gg/account/agents/new-docker
init-playit:
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -f ".playit.secret" ]; then
        echo "⚠ .playit.secret already exists. Overwrite? [y/N]"
        read -r CONFIRM
        [ "${CONFIRM}" = "y" ] || [ "${CONFIRM}" = "Y" ] || { echo "Aborted."; exit 0; }
    fi
    printf "Paste your playit.gg agent key: "
    read -r KEY
    if [ -z "${KEY}" ]; then
        echo "No key entered, aborting." >&2
        exit 1
    fi
    printf '%s' "${KEY}" > .playit.secret
    echo "→ Saved to .playit.secret"

# Start Docker Compose setup (use --tunnel to also start the playit.gg tunnel)
[arg("tunnel", long="tunnel", value="true")]
up tunnel="false": setup-env
    #!/usr/bin/env bash
    if [ "{{tunnel}}" = "true" ] && [ ! -f ".playit.secret" ]; then
        just init-playit
    fi
    mkdir -p data/{velocity,lobby}
    # Ensure forwarding-secret-file points to the Docker secret mount path
    if [ -f "data/velocity/velocity.toml" ]; then
        sed -i 's|^forwarding-secret-file = .*|forwarding-secret-file = "/run/secrets/forwarding_secret"|' data/velocity/velocity.toml
        echo "✓ Patched forwarding-secret-file in data/velocity/velocity.toml"
        if [ -n "${VELOCITY_FORWARDING_MODE:-}" ]; then
            sed -i "s|^player-info-forwarding-mode = .*|player-info-forwarding-mode = \"${VELOCITY_FORWARDING_MODE}\"|" data/velocity/velocity.toml
            echo "✓ Patched player-info-forwarding-mode=${VELOCITY_FORWARDING_MODE}"
        fi
    fi
    if [ "{{tunnel}}" = "true" ]; then
        PUID=$(id -u) PGID=$(id -g) docker compose --profile tunnel up -d
    else
        PUID=$(id -u) PGID=$(id -g) docker compose up -d
    fi

# Stop Docker Compose setup
down:
    docker compose down

# View logs from all containers
logs:
    docker compose logs -f

# Attach to server console (velocity, lobby, game1, game2, etc.)
attach service:
    docker attach "mcsrswap-{{service}}"

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
