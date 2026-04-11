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

# Generate .env with VELOCITY_SECRET if not already set.
# If data/velocity/forwarding.secret already exists (Velocity ran before), that value is used.
# Otherwise a new random secret is generated and written to both places.
setup-env:
    #!/usr/bin/env bash
    set -euo pipefail

    if grep -q "^VELOCITY_SECRET=" .env 2>/dev/null; then
        echo "✓ VELOCITY_SECRET already set in .env"
        exit 0
    fi

    mkdir -p data/velocity

    if [ -f "data/velocity/forwarding.secret" ]; then
        SECRET=$(cat "data/velocity/forwarding.secret")
        echo "→ Using existing forwarding.secret"
    else
        SECRET=$(openssl rand -base64 12 | tr -d '+/=\n')
        printf '%s' "$SECRET" > data/velocity/forwarding.secret
        echo "→ Generated new secret and wrote data/velocity/forwarding.secret"
    fi

    printf 'VELOCITY_SECRET=%s\n' "$SECRET" >> .env
    echo "✓ Wrote VELOCITY_SECRET to .env"

# Start Docker Compose setup
up: setup-env
    #!/usr/bin/env bash
    mkdir -p data/{velocity,lobby}
    PUID=$(id -u) PGID=$(id -g) docker compose up -d

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
