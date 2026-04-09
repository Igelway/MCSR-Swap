# MCSR-Swap CI/CD - Docker Images

This directory contains the GitHub Actions workflow for automatically building and publishing Docker images.

## Workflow: `docker-build.yml`

### Trigger
- **Tags:** Automatically runs when you push a version tag (e.g., `v1.0.0`)
- **Manual:** Can be triggered manually via GitHub Actions UI

### What it builds
1. **Fabric Mod** → `mcsrswap-fabric-mod-1.0.0.jar`
2. **Velocity Plugin** → `mcsrswap-velocity-plugin-1.0.jar`
3. **Game Server Image** → `ghcr.io/OWNER/REPO-gameserver:latest`
4. **Velocity Image** → `ghcr.io/OWNER/REPO-velocity:latest`

### Published to
**GitHub Container Registry (ghcr.io)**

Images are publicly accessible at:
```
ghcr.io/OWNER/REPO-velocity:latest
ghcr.io/OWNER/REPO-gameserver:latest
```

### Usage

#### Creating a release
```bash
git tag v1.0.0
git push origin v1.0.0
```

This will:
1. Build both Docker images
2. Push to `ghcr.io`
3. Tag as `latest`, `1.0.0`, and `1.0`

#### Using the images

Users just need to:
```bash
# Set their GitHub username/repo in .env
cp .env.example .env
# Edit .env: MCSRSWAP_VELOCITY_IMAGE=ghcr.io/your-username/mcsr-swap-velocity:latest

# Pull and run
docker-compose pull
docker-compose up -d
```

### Permissions

The workflow requires `packages: write` permission to push to GHCR.
This is automatically granted to GitHub Actions via `GITHUB_TOKEN`.

### Image Tagging Strategy

| Git Event | Image Tags |
|---|---|
| `v1.2.3` | `latest`, `1.2.3`, `1.2` |
| `v2.0.0` | `latest`, `2.0.0`, `2.0` |
| Branch push | (not published) |

### Local Testing

To test the workflow locally before pushing:
```bash
# Build Fabric Mod
cd fabric-mod && ./gradlew build && cd ..

# Build Velocity Plugin
cd velocity-plugin && mvn clean package && cd ..

# Build Docker Images
docker build -f Dockerfile.gameserver -t test-gameserver .
docker build -f Dockerfile.velocity -t test-velocity .

# Test locally
MCSRSWAP_VELOCITY_IMAGE=test-velocity docker-compose up
```
