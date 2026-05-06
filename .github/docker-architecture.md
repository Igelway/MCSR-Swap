# Docker Architecture

## Overview

MCSR-Swap supports an optional Docker deployment mode where the Velocity plugin dynamically spawns and manages Fabric game server containers. This mode is enabled by setting `MCSRSWAP_DOCKER_MODE=true`.

## Container Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Host System                                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Docker Network: mcsrswap-network                        │  │
│  │                                                            │  │
│  │  ┌──────────────────┐       ┌─────────────────────────┐  │  │
│  │  │ docker-proxy     │       │ mcsrswap-velocity       │  │  │
│  │  │ (socket proxy)   │◄──────│ (Velocity + plugin)     │  │  │
│  │  └──────────────────┘       └─────────────────────────┘  │  │
│  │           │                    │         │                │  │
│  │           │              routes│         │ spawns         │  │
│  │           │                    ▼         ▼                │  │
│  │           │          ┌──────────────┐ ┌──────────────┐   │  │
│  │           │          │mcsrswap-lobby│ │mcsrswap-limbo│   │  │
│  │           │          │(static)      │ │(NanoLimbo)   │   │  │
│  │           │          └──────────────┘ └──────────────┘   │  │
│  │           │                                               │  │
│  │           │          ┌───────────────┬───────────────┐   │  │
│  │           │          │ mcsrswap-game1│ mcsrswap-game2│   │  │
│  │           │          │ (dynamic)     │ (dynamic)   ...│  │  │
│  │           │          └───────────────┴───────────────┘   │  │
│  │           │                                               │  │
│  └───────────┼───────────────────────────────────────────────┘  │
│              │                                                  │
│              ▼                                                  │
│     /var/run/docker.sock                                       │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### 1. docker-proxy (tecnativa/docker-socket-proxy)
- **Purpose**: Security layer for Docker socket access
- **Why**: Direct socket mounting grants root-equivalent host access; proxy restricts API calls
- **Permissions**: `CONTAINERS=1`, `IMAGES=1`, `NETWORKS=1`, `VOLUMES=1`, `POST=1`, `ALLOW_START=1`, `ALLOW_STOP=1`
- **Mount**: `/var/run/docker.sock:/var/run/docker.sock:ro`

### 2. mcsrswap-velocity (Velocity Proxy + Plugin)
- **Purpose**: Central coordinator; spawns/manages game server containers
- **Mount**: `./data/velocity:/data` (bind mount); `${GAME_DATA_DIR:-./data}:/managed-data` (game server data root)
- **User**: `minecraft` (UID/GID from `PUID`/`PGID` env vars, default 1000)
- **Network**: Connects to `docker-proxy:2375` for container orchestration
- **Key Files**:
  - `/data/plugins/mcsrswap/config.yml` – Plugin configuration
  - `/run/secrets/forwarding_secret` – Shared with game servers for Velocity Modern Forwarding

### 3. mcsrswap-lobby (Static Fabric Server)
- **Purpose**: Lobby server where players wait before and between games
- **Image**: `itzg/minecraft-server:java21`
- **Mount**: `./data/lobby:/data` (bind mount)
- **User**: `minecraft` (UID/GID from `PUID`/`PGID`)
- **Type**: Fabric 1.16.1

### 4. mcsrswap-limbo (NanoLimbo Transit Server)
- **Purpose**: Lightweight transit server used during player rotation and reconnects. Players are briefly sent here between game servers so the previous server can save their state before they arrive at the next one.
- **Image**: `itzg/minecraft-server` with `TYPE=NANOLIMBO` (BoomEaro/NanoLimbo, downloaded at first start)
- **Mount**: `${GAME_DATA_DIR:-./data}/limbo:/data` (bind mount; NanoLimbo jar cached here after first pull)
- **Config**: `settings.yml` written at container start by `docker/nanolimbo-entrypoint.sh` using the Velocity forwarding secret; itzg's start script reuses it unchanged
- **Why not lobby**: Using a dedicated limbo avoids lobby-related events firing during transit

### 5. mcsrswap-game{N} (Dynamic Game Servers)
- **Purpose**: Speedrun game worlds spawned per player on `/ms start`
- **Image**: `ghcr.io/{org}/mcsr-swap-gameserver:latest` (Fabric 1.16.1 + mods)
- **Mods included**:
  - **Carpet** – Enables `/tick freeze` for world-freeze between rotations
  - **Chunky** – Pre-generates chunks before game start (if `MCSRSWAP_CHUNKY_PRELOAD=true`)
- **Storage**: Bind-mount directories under `GAME_DATA_DIR` (e.g. `./data/game1/`)
- **User**: Inherits `PUID`/`PGID` from Velocity container environment
- **Lifecycle**:
  - **Created**: By Velocity plugin on `/ms start`
  - **Kept running**: After game ends (containers stay up; players can still connect)
  - **Removed**: On `/ms cleanup` (containers + data directories deleted)
- **Versus Mode Seeds**: In versus mode, teams get paired seeds (Team A game1 = Team B game3, same seed)

## Data Persistence

| Component | Storage Type | Host Path | Container Path | Persistence |
|-----------|--------------|-----------|----------------|-------------|
| Velocity | Bind Mount | `./data/velocity` | `/data` | Permanent |
| Lobby | Bind Mount | `./data/lobby` | `/data` | Permanent |
| Limbo | Bind Mount | `./data/limbo` | `/data` | Permanent (settings.yml only) |
| Game Servers | Bind Mount | `${GAME_DATA_DIR}/game{N}` | `/data` | Until `/ms cleanup` |

## Container Orchestration Flow

### `/ms start`
1. **Cleanup existing**: Remove old game containers + data directories
2. **Generate seeds**: Random or paired (versus mode)
3. **Spawn containers**: One per player (or per team in versus)
4. **Wait for health**: Docker health-check polls until server is ready (max 2 min)
5. **Ping verification**: Velocity pings each server to confirm connectivity
6. **Chunky preload** *(if `MCSRSWAP_CHUNKY_PRELOAD=true`)*: Send `/chunky start` via RCON, poll for completion file; proceeds after all worlds are pre-generated or timeout is reached
7. **Route players**: Send each player to their assigned server; Fabric mod freezes world (Carpet `/tick freeze`) until the player arrives
8. **Start rotation timer**: Begin countdown

### `/ms stop`
1. **Stop rotation timer**
2. **Move players to lobby**
3. **Keep containers running** (players can revisit worlds)

### `/ms cleanup`
1. **Stop all game containers**
2. **Remove containers** (force remove)
3. **Delete game data directories** under `GAME_DATA_DIR`

### `docker compose down`
- **Stops**: `velocity`, `lobby`, `limbo`, `docker-proxy`
- **Cleanup**: Velocity shutdown hook triggers game server cleanup (stops containers)
- **Data**: Bind-mount directories under `GAME_DATA_DIR` persist until manually deleted

## Player Routing

```
Connect/Reconnect mid-game:
  Player ──► mcsrswap-limbo (transit) ──► mcsrswap-game{N}

Normal rotation:
  Player ──► mcsrswap-limbo (transit, save slot .dat) ──► mcsrswap-game{N+1}

Game not running:
  Player ──► mcsrswap-lobby
```

The limbo server is intentionally lightweight (NanoLimbo) — players are never meant to stay there. The Fabric mod on the game server applies Carpet tick-freeze while no runner is present, and unfreezes on player arrival.

## Building Images Locally

```bash
# Build both images (velocity + gameserver)
just docker-build

# Build individual images
just docker-build-velocity
just docker-build-gameserver
```

To use locally built images instead of pulling from ghcr.io, set in `.env`:
```env
MCSRSWAP_VELOCITY_IMAGE=ghcr.io/local/mcsr-swap-velocity:latest
MCSRSWAP_GAMESERVER_IMAGE=ghcr.io/local/mcsr-swap-gameserver:latest
```

## Security Considerations

⚠️ **Docker Socket Mounting**: Even with `docker-socket-proxy`, container orchestration from within a container is a privileged operation. The Velocity container can spawn/manage containers on the host system.

**Mitigations**:
- Socket proxy restricts API calls (no Swarm/Secrets/etc.)
- Containers run as non-root user (`minecraft`, UID 1000)
- Network isolation (`mcsrswap-network` bridge)
- Velocity forwarding secret passed via Docker secrets (not environment variables)

**Recommended**: Only deploy in trusted environments or dedicated Minecraft servers, not on shared infrastructure.

## Environment Variables

### Required (Docker Mode)
- `MCSRSWAP_DOCKER_MODE=true` – Enable Docker orchestration
- `PUID=1000` – User ID for all containers (set by `just up`)
- `PGID=1000` – Group ID for all containers (set by `just up`)

### Optional Overrides
- `MCSRSWAP_GAMESERVER_IMAGE` – Custom game server image (default: `ghcr.io/igelway/mcsr-swap-gameserver:latest`)
- `MCSRSWAP_VELOCITY_IMAGE` – Custom Velocity image (default: `ghcr.io/igelway/mcsr-swap-velocity:latest`)
- `MCSRSWAP_LOBBY_ADDRESS` – Lobby server hostname (default: `lobby`)
- `MCSRSWAP_LIMBO_ADDRESS` – Limbo server hostname (default: `limbo`)
- `MCSRSWAP_CHUNKY_PRELOAD` – Enable Chunky chunk pre-generation before game start (default: `false`)
- `MCSRSWAP_CHUNKY_OW_RADIUS` – Overworld pre-gen radius in chunks (default: `200`)
- `MCSRSWAP_CHUNKY_NETHER_RADIUS` – Nether pre-gen radius in chunks (default: `200`)
- `MCSRSWAP_CHUNKY_END_RADIUS` – End pre-gen radius in chunks (default: `200`)
- `GAME_DATA_DIR` – Host path for game server data directories (default: `./data`)
- `MCSRSWAP_AUTO_STOP_LOBBY` – Stop the lobby container on game start, restart on game end (default: `false`)
- `MCSRSWAP_EXTERNAL_SERVERS` – Comma-separated external servers in `name:host:port` format for hybrid mode (default: none)

## Future Improvements

- **Config option for bind mounts**: Allow game servers to use bind mounts instead of named volumes for easier world inspection
- **Kubernetes/Swarm support**: Would require refactoring volume management to use orchestrator-native storage

## Lobby Auto-Stop

When `MCSRSWAP_AUTO_STOP_LOBBY=true`, the Velocity plugin stops the `mcsrswap-{lobbyServerName}` container 15 seconds after `launchGame()` completes (only if empty) and restarts it via `docker start` when `endGame()` is called. Players are held in NanoLimbo during the restart and automatically forwarded to the lobby once it passes health/ping checks (120 s timeout).

The lobby container uses `restart: unless-stopped` in Compose — `docker stop` puts it in stopped state and Docker will **not** auto-restart it until the plugin explicitly calls `docker start`.

## Hybrid Mode

Hybrid mode combines Docker-managed game servers with externally-hosted game servers on other machines. External servers are configured via `docker.externalServers` in `config.yml` or the `MCSRSWAP_EXTERNAL_SERVERS` environment variable.

### Architecture

```
Host A (Velocity + Docker)         Host B (external game servers)
┌──────────────────────────┐       ┌────────────────────────────┐
│ mcsrswap-velocity        │       │ game3 (port 25602)          │
│   ├── mcsrswap-game1 ◄───┼───────┤ game4 (port 25603)          │
│   └── mcsrswap-game2     │  LAN  │ (Fabric + FabricProxy)      │
│ mcsrswap-lobby           │       └────────────────────────────┘
└──────────────────────────┘
```

### Behaviour

- External servers are registered with Velocity at plugin startup and are never removed.
- `startServersAsync(N)` spawns N Docker containers; external servers are appended to the returned server list so `gameServers` includes both.
- `waitForServersReady()` pings both Docker containers and external servers before the ready-check proceeds.
- `getRunningServers()` returns Docker container names + external server names; both are visible to `detectServers()`.
- Versus mode even-check uses the **total** count (Docker + external).
- `/ms cleanup` only removes Docker containers and volumes — external servers are untouched.
