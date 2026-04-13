#!/usr/bin/env bash
# Bumps version across all project files and creates a git tag.
# Usage: release.sh [version]  (e.g. release.sh 1.2.3 — omit to auto-increment patch)
set -euo pipefail

VERSION="${1:-}"

if [ -z "$VERSION" ]; then
    CURRENT=$(git describe --tags --abbrev=0 2>/dev/null || echo "v1.0.0")
    VERSION=$(echo "$CURRENT" | sed 's/^v//' | awk -F. '{print $1"."$2"."$3+1}')
    echo "→ Auto-incrementing from $CURRENT to v$VERSION" >&2
else
    # Strip leading 'v' if provided (e.g. v1.0.4 → 1.0.4)
    VERSION="${VERSION#v}"
    if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-].+)?$ ]]; then
        echo "Error: version must be in format X.Y.Z or X.Y.Z-suffix (got: $VERSION)" >&2
        exit 1
    fi
    echo "→ Setting version to v$VERSION" >&2
fi

echo "→ Updating pom.xml..." >&2
sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" pom.xml

echo "→ Updating velocity-plugin/pom.xml..." >&2
sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" velocity-plugin/pom.xml

echo "→ Updating fabric-mod/build.gradle..." >&2
sed -i "s/^version = .*/version = '$VERSION'/" fabric-mod/build.gradle

echo "→ Updating fabric-mod/src/main/resources/fabric.mod.json..." >&2
sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" fabric-mod/src/main/resources/fabric.mod.json

echo "→ Updating fabric-mod/bin/main/fabric.mod.json..." >&2
sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" fabric-mod/bin/main/fabric.mod.json 2>/dev/null || true

echo "→ Updating velocity-plugin/src/main/resources/velocity-plugin.json..." >&2
sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" velocity-plugin/src/main/resources/velocity-plugin.json

echo "→ Updating README.md..." >&2
sed -i "s/mcsrswap-fabric-mod-v[0-9.]*\.jar/mcsrswap-fabric-mod-v$VERSION.jar/g" README.md
sed -i "s/mcsrswap-velocity-plugin-v[0-9.]*\.jar/mcsrswap-velocity-plugin-v$VERSION.jar/g" README.md

echo "→ Committing and tagging..." >&2
git add pom.xml velocity-plugin/pom.xml fabric-mod/build.gradle \
    fabric-mod/src/main/resources/fabric.mod.json \
    fabric-mod/bin/main/fabric.mod.json \
    velocity-plugin/src/main/resources/velocity-plugin.json \
    README.md 2>/dev/null || true
git commit --allow-empty -m "chore: bump version to $VERSION" || true
git tag "v$VERSION"

echo "$VERSION"
