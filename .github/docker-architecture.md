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
│  │           │                          │                    │  │
│  │           │                          │ manages            │  │
│  │           │                          ▼                    │  │
│  │           │                  ┌──────────────────┐        │  │
│  │           │                  │ mcsrswap-lobby   │        │  │
│  │           │                  │ (static server)  │        │  │
│  │           │                  └──────────────────┘        │  │
│  │           │                          │                    │  │
│  │           │                          │ manages            │  │
│  │           │                          ▼                    │  │
│  │           │          ┌───────────────┬───────────────┐   │  │
│  │           │          │ mcsrswap-game1│ mcsrswap-game2│   │  │
│  │           │          │ (dynamic)     │ (dynamic)     │...│  │
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
- **Mount**: `./data/velocity:/data` (bind mount)
- **User**: `minecraft` (UID/GID from `PUID`/`PGID` env vars, default 1000)
- **Network**: Connects to `docker-proxy:2375` for container orchestration
- **Key Files**:
  - `/data/plugins/mcsrswap/config.yml` – Plugin configuration
  - `/data/forwarding.secret` – Shared with game servers for player forwarding

### 3. mcsrswap-lobby (Static Fabric Server)
- **Purpose**: Lobby server where players wait before game start
- **Image**: `itzg/minecraft-server:java21`
- **Mount**: `./data/lobby:/data` (bind mount)
- **User**: `minecraft` (UID/GID from `PUID`/`PGID`)
- **Type**: Fabric 1.16.1 (no mods required, vanilla-like)

### 4. mcsrswap-game{N} (Dynamic Game Servers)
- **Purpose**: Speedrun game worlds spawned per player on `/ms start`
- **Image**: `ghcr.io/{org}/mcsr-swap-gameserver:latest`
- **Storage**: **Named Docker Volumes** `mcsrswap-game{N}` (not bind mounts)
- **User**: Inherits `PUID`/`PGID` from Velocity container environment
- **Lifecycle**:
  - **Created**: By Velocity plugin on `/ms start` (after cleanup)
  - **Stopped**: On `/ms stop` (containers keep running, players can still join)
  - **Removed**: On `/ms cleanup` (containers + volumes deleted)
- **Startup**:
  1. Container created with named volume
  2. Health-check waits for Minecraft server ready (max 2 min)
  3. Velocity pings server to confirm connectivity
  4. Player routed to server when both checks pass
- **Versus Mode Seeds**: In versus mode, teams get paired seeds (Team A game1 = Team B game3, same seed)

## Data Persistence

| Component | Storage Type | Host Path | Container Path | Persistence |
|-----------|--------------|-----------|----------------|-------------|
| Velocity | Bind Mount | `./data/velocity` | `/data` | Permanent |
| Lobby | Bind Mount | `./data/lobby` | `/data` | Permanent |
| Game Servers | Named Volume | `mcsrswap-game{N}` | `/data` | Until `/ms cleanup` |

## Container Orchestration Flow

### `/ms start`
1. **Cleanup existing**: Remove old game containers + volumes
2. **Generate seeds**: Random or paired (versus mode)
3. **Spawn containers**: One per player (or per team in versus)
4. **Wait for health**: Docker health-check polls `mc-health` until healthy
5. **Ping verification**: Velocity pings each server to confirm connectivity
6. **Route players**: Send each player to their assigned server
7. **Start rotation timer**: Begin countdown

### `/ms stop`
1. **Stop rotation timer**
2. **Move players to lobby**
3. **Keep containers running** (players can revisit worlds)

### `/ms cleanup`
1. **Stop all game containers**
2. **Remove containers** (force remove)
3. **Delete named volumes** `mcsrswap-game{N}`

### `docker compose down`
- **Stops**: `velocity`, `lobby`, `docker-proxy`
- **Cleanup**: Velocity shutdown hook triggers game server cleanup (stops containers)
- **Volumes**: Named volumes persist unless manually deleted with `docker volume rm`

## Security Considerations

⚠️ **Docker Socket Mounting**: Even with `docker-socket-proxy`, container orchestration from within a container is a privileged operation. The Velocity container can spawn/manage containers on the host system.

**Mitigations**:
- Socket proxy restricts API calls (no Swarm/Secrets/etc.)
- Containers run as non-root user (`minecraft`, UID 1000)
- Network isolation (`mcsrswap-network` bridge)
- Named volumes prevent host filesystem access

**Recommended**: Only deploy in trusted environments or dedicated Minecraft servers, not on shared infrastructure.

## Environment Variables

### Required (Docker Mode)
- `MCSRSWAP_DOCKER_MODE=true` – Enable Docker orchestration
- `PUID=1000` – User ID for all containers (set by `just up`)
- `PGID=1000` – Group ID for all containers (set by `just up`)

### Optional Overrides
- `MCSRSWAP_GAMESERVER_IMAGE` – Custom game server image (default: `ghcr.io/igelway/mcsr-swap-gameserver:latest`)
- `MCSRSWAP_VELOCITY_IMAGE` – Custom Velocity image (default: `ghcr.io/igelway/mcsr-swap-velocity:latest`)
- `MCSRSWAP_LOBBY_ADDRESS` – Lobby server hostname (default: `mcsrswap-lobby`)

## Future Improvements

- **Config option for bind mounts**: Allow game servers to use bind mounts instead of named volumes for easier world inspection
- **Kubernetes/Swarm support**: Would require refactoring volume management to use orchestrator-native storage
