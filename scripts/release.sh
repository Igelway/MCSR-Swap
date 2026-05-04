#!/usr/bin/env bash
set -euo pipefail

# 1. Determine version
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    CURRENT=$(git describe --tags --abbrev=0 2>/dev/null || echo "v1.0.0")
    VERSION=$(echo "$CURRENT" | sed 's/^v//' | awk -F. '{print $1"."$2"."$3+1}')
fi
echo "🚀 Target Version: v$VERSION"

# 2. update files
echo "→ Patching files..."
# Maven
[ -f "pom.xml" ] && sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" pom.xml
[ -f "velocity-plugin/pom.xml" ] && sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$VERSION<\/version>/" velocity-plugin/pom.xml

# Gradle & JSONs
[ -f "fabric-mod/build.gradle" ] && sed -i "s/^version = .*/version = '$VERSION'/" fabric-mod/build.gradle
[ -f "fabric-mod/src/main/resources/fabric.mod.json" ] && sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" fabric-mod/src/main/resources/fabric.mod.json
[ -f "velocity-plugin/src/main/resources/velocity-plugin.json" ] && sed -i "s/\"version\": \".*\"/\"version\": \"$VERSION\"/" velocity-plugin/src/main/resources/velocity-plugin.json

# Readmes
sed -i "s/fabric-mod-v*[0-9.]*\.jar/fabric-mod-$VERSION.jar/g" README.md README-CLASSIC.md 2>/dev/null || true
sed -i "s/velocity-plugin-v*[0-9.]*\.jar/velocity-plugin-$VERSION.jar/g" README.md README-CLASSIC.md 2>/dev/null || true

# 3. Git operations
echo "→ Committing and Pushing..."

git add README.md README-CLASSIC.md fabric-mod/build.gradle fabric-mod/src/main/resources/fabric.mod.json \
        velocity-plugin/pom.xml velocity-plugin/src/main/resources/velocity-plugin.json 
[ -f "pom.xml" ] && git add pom.xml

git commit -m "chore: bump version to $VERSION"
git push origin main

echo "→ Tagging..."
git tag -a "v$VERSION" -m "Release v$VERSION"
git push origin "v$VERSION"

echo "$VERSION"