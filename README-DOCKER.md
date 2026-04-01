# MCSR-Swap Docker Setup

## Two Modes Available

### Mode 1: Classic (Default) - Manual Server Setup
- **Use case:** Traditional setup with manually configured game servers
- **Config:** `docker.enabled: false` (default)
- **Setup:** Configure game servers manually (like before)
- **Benefit:** Full control, works on any platform

### Mode 2: Docker Dynamic - Automatic Container Spawning
- **Use case:** Easy deployment, dynamic scaling
- **Config:** `docker.enabled: true`
- **Setup:** Only Velocity + Lobby running, game servers spawn on demand
- **Benefit:** Simple setup, automatic cleanup

---

## Prerequisites (Docker Mode Only)

- Docker 20.10+
- Docker Compose 1.29+
- 4GB+ RAM available
- Linux host (for `/var/run/docker.sock` mounting)

---

## Quick Start - Classic Mode (Default)

### 1. Build the Projects

```bash
# Build Fabric Mod
cd fabric-mod
./gradlew build
cd ..

# Build Velocity Plugin
cd velocity-plugin
mvn clean package
cd ..
```

### 2. Setup Velocity + Game Servers Manually

Follow the original README.md for manual server setup. The plugin works exactly as before.

**Config:**
```yaml
docker:
  enabled: false  # This is the default
```

---

## Quick Start - Docker Dynamic Mode

### 1. Build the Projects

```bash
# Build Fabric Mod
cd fabric-mod
./gradlew build
cd ..

# Build Velocity Plugin  
cd velocity-plugin
mvn clean package
cd ..
```

### 2. Build the Game Server Image

```bash
docker build -f Dockerfile.gameserver -t mcsrswap-gameserver:latest .
```

### 3. Configure Environment

Copy and edit `.env.example`:
```bash
cp .env.example .env
# Edit .env and set your GitHub username/repo:
# MCSRSWAP_VELOCITY_IMAGE=ghcr.io/your-username/mcsr-swap-velocity:latest
```

Or for local builds, set in docker-compose.yml directly.

### 4. Configure Velocity

Create `velocity-config/velocity.toml`:

```toml
[servers]
lobby = "lobby:25565"
try = ["lobby"]

[forwarding]
mode = "none"
```

Create `velocity-config/forwarding.secret` (any random string):

```
your-random-secret-here
```

### 5. Start the Stack

```bash
docker-compose up -d
```

**That's it!** Docker mode is automatically enabled via the `MCSRSWAP_DOCKER_MODE=true` environment variable in docker-compose.yml.

### 6. Connect & Play

1. Connect to `localhost:25577` (Velocity proxy)
2. You'll spawn in the lobby
3. Run `/ms start` to dynamically create game servers
4. Players will be distributed across Docker containers

---

## Using Pre-built Images from GitHub

If images are published to GitHub Container Registry, you can skip building:

```bash
# Just configure .env
cp .env.example .env
# Edit: MCSRSWAP_VELOCITY_IMAGE=ghcr.io/OWNER/REPO-velocity:latest

# Pull and run
docker-compose pull
docker-compose up -d
```

Images are automatically built on GitHub when a version tag is pushed.

---

## How It Works

### Static Containers (docker-compose.yml)
- **velocity**: Proxy server with Docker socket access
- **lobby**: Permanent lobby server

### Dynamic Containers (created by Velocity plugin)
- Game servers are spawned on `/ms start`
- Named: `mcsrswap-game1`, `mcsrswap-game2`, etc.
- Ports: 25600-25650 (configurable)
- Automatically removed on `/ms stop`

## Architecture

```
Player → Velocity Proxy (25577)
            ↓
         Lobby Server (static)
            ↓
    /ms start (by admin)
            ↓
    Docker API spawns containers:
    - game1:25600
    - game2:25601
    - game3:25602
    ...
```

## Configuration

### Docker Settings

In `config.yml`:

- `docker.enabled`: Enable dynamic container spawning
- `docker.gameServerImage`: Image name for game servers
- `docker.network`: Docker network name
- `docker.minPort` / `docker.maxPort`: Port range for containers
- `docker.dataPath`: Host directory for persistent server data (default: auto-detect via XDG or `./server-data`)

**Data Path Auto-Detection:**
- **Empty/not set**: Uses XDG Base Directory (`$XDG_DATA_HOME/mcsrswap/servers` or `~/.local/share/mcsrswap/servers`)
- **Relative path** (e.g., `./my-servers`): Relative to working directory
- **Absolute path** (e.g., `/opt/mcsrswap`): Exact path

**Server Data Structure:**
```
~/.local/share/mcsrswap/servers/  # or ./server-data/
├── game1/    # Persistent world data for game server 1
├── game2/    # Persistent world data for game server 2  
└── game3/    # etc.
```

Each game server gets its own directory mounted as `/data` inside the container.

### Permissions

Grant `swap.admin` permission via LuckPerms or similar:

```
/lp user <username> permission set swap.admin true
```

## Commands

- `/ms start` - Start game (spawns Docker containers)
- `/ms stop` - End game (removes containers)
- `/ms forceswap` - Trigger immediate rotation
- `/ms setrotation <seconds>` - Change rotation time

## Troubleshooting

### Containers not starting

Check Docker daemon:
```bash
docker ps
docker logs mcsrswap-velocity
```

### Port conflicts

Adjust `minPort`/`maxPort` in config.yml

### Velocity can't access Docker

Ensure `/var/run/docker.sock` is mounted (Linux only). For Windows/Mac, use Docker-in-Docker or remote Docker API.

### Game servers not reachable

Verify network:
```bash
docker network inspect mcsrswap-network
```

## Development

### Rebuild after code changes

```bash
# Rebuild mod
cd fabric-mod && ./gradlew build && cd ..
docker build -f Dockerfile.gameserver -t mcsrswap-gameserver:latest .

# Rebuild plugin
cd velocity-plugin && mvn clean package && cd ..
docker-compose restart velocity
```

## Production Notes

- Increase `MEMORY` settings for game servers
- Use persistent volumes for world data if needed
- Consider resource limits in Docker Compose
- Monitor with `docker stats`

## Cleanup

```bash
# Stop and remove all containers
docker-compose down

# Remove game server containers
docker rm -f $(docker ps -aq --filter "label=mcsrswap.server")

# Remove network
docker network rm mcsrswap-network
```
