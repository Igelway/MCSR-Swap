# MCSR-Swap Docker Setup

> ⚠️ **Security Warning**: This setup requires mounting the Docker socket which grants root-equivalent access to the host system! Only use this in trusted environments or with proper security controls.

## Prerequisites

1. **Install Docker**: Follow the [official Docker installation guide](https://docs.docker.com/get-docker/)
2. **Install Just**: Follow the [installation guide](https://github.com/casey/just#installation)

## Quick Start

1. **(Optional) Create a `.env` file** to customize settings:
   ```bash
   cp .env.example .env
   ```
   If `.env` does not exist, `just up` / `just setup-env` will create it from `.env.example` automatically.
   See the [`.env` Variables](#env-variables) section below for all available settings.

2. **Start the services** (generates `.forwarding.secret` automatically if not present and asks for Minecraft EULA acceptance if needed):
   ```bash
   just up
   ```

3. **Connect to the server**: `localhost:25565`

4. **Admin commands**: Use `/ms <command>` (see [Commands](README.md#commands))

## `.env` Variables

Copy `.env.example` to `.env` and adjust as needed. All variables are optional unless noted otherwise.

### Essential

| Variable | Default | Description |
|---|---|---|
| `MINECRAFT_SERVER_EULA` | – | **Required.** Set to `true` to accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). `just up` will prompt for this automatically. |
| `VELOCITY_ONLINE_MODE` | `true` | Mojang authentication. |
| `MCSRSWAP_ADMINS` | – | Comma-separated list of admin UUIDs or usernames. Extends the `admins` list in `config.yml`. |
| `MCSRSWAP_IGNORE_PLAYERS` | – | Comma-separated UUIDs or usernames to exclude from game starts by default. |

### Game server containers

Variables prefixed with `MCSRSWAP_GAME_` are forwarded to each spawned game server container (prefix stripped). See the [itzg/minecraft-server docs](https://docker-minecraft-server.readthedocs.io/en/latest/configuration/server-properties/) for all accepted variables.

| Variable | Default | Description |
|---|---|---|
| `MCSRSWAP_GAME_OPS` | – | Comma-separated list of op (admin) players on game servers. |
| `MCSRSWAP_GAME_DIFFICULTY` | `easy` | Game difficulty (`peaceful` / `easy` / `normal` / `hard`). |
| `MCSRSWAP_GAME_VIEW_DISTANCE` | `20` | Chunk view distance. |
| `MCSRSWAP_GAME_MAX_PLAYERS` | `20` | Maximum players per game server. |
| `MCSRSWAP_GAME_MEMORY` | `2G` | JVM heap allocated to each game server container. |

### Chunky pre-generation *(optional)*

Requires `MCSRSWAP_CHUNKY_PRELOAD=true`. Carpet and Chunky mods are bundled in the game server image. The server generates chunks on startup before signalling ready, which increases container start time.

| Variable | Default | Description |
|---|---|---|
| `MCSRSWAP_CHUNKY_PRELOAD` | `false` | Enable automatic chunk pre-generation on game server startup. |
| `MCSRSWAP_CHUNKY_OW_RADIUS` | `200` | Overworld pre-generation radius (chunks). |
| `MCSRSWAP_CHUNKY_NETHER_RADIUS` | `200` | Nether pre-generation radius (chunks). |
| `MCSRSWAP_CHUNKY_END_RADIUS` | `200` | End pre-generation radius (chunks). |

### Advanced / Docker internals

| Variable | Default | Description |
|---|---|---|
| `COMPOSE_PROFILES` | – | Set to `playit` to always start the playit.gg tunnel with `just up`. |
| `GAME_DATA_DIR` | `./data` | Absolute host path for game server data directories. |
| `MCSRSWAP_PULL_GAME_IMAGE` | `true` | Set to `false` to skip the automatic image pull on startup (useful with locally built images). |
| `MCSRSWAP_VELOCITY_IMAGE` | `ghcr.io/igelway/mcsr-swap-velocity:latest` | Override the Velocity proxy image (e.g. after `just docker-build`). |
| `MCSRSWAP_GAMESERVER_IMAGE` | `ghcr.io/igelway/mcsr-swap-gameserver:latest` | Override the game server image. |
| `PUID` / `PGID` | `1000` | UID/GID for container file ownership. |

> Internal networking variables (`MCSRSWAP_DOCKER_HOST`, `MCSRSWAP_DOCKER_NETWORK`, `MCSRSWAP_LOBBY_ADDRESS`, `MCSRSWAP_LIMBO_ADDRESS`) rarely need changing outside of custom setups.

## Docker-only Commands

| Command | Description |
|---|---|
| `/ms start` | Trigger ready-check, then start the game (reuses existing containers) |
| `/ms start --clean` | Remove old containers and volumes, then start fresh |
| `/ms prepare [N]` | Pre-generate N game servers and wait for all players to confirm readiness |
| `/ms stop` | Stop the current game (containers keep running) |
| `/ms cleanup` | Stop and remove all game server containers and data directories |
| `/ms seed` | List configured world seeds |
| `/ms seed <i> <seed>` | Set the fixed seed for slot `i` (e.g. `/ms seed 1 -123456`) |
| `/ms seed <i> clear` | Remove the fixed seed for slot `i` (uses random) |
| `/ms seed clear` | Clear all fixed seeds |

In **versus mode**, seeds are mirrored between teams: setting or clearing seed slot `i` applies to both teams.
For all commands see the [main README](README.md#commands).

## Available `just` Commands

- **`just up`** — Start all servers
- **`just up --playit`** — Start all servers + playit.gg tunnel
- **`COMPOSE_PROFILES=playit` in `.env`** — Make `just up` always include the playit.gg service
- **`just down`** — Stop all servers (including tunnel if running)
- **`just console velocity`** — Attach to the Velocity console (detach with Ctrl+C)
- **`just console lobby`** — Open RCON console on the lobby server
- **`just console game1`** — Open RCON console on a game server
- **`just logs`** — Follow logs from all containers
- **`just pull`** — Pull latest Docker images

## Configuration

Configuration files are automatically created on first startup:
- Velocity config: `./data/velocity/plugins/mcsrswap/config.yml`
- Server configs are managed automatically

## Data Persistence

- Velocity data: `./data/velocity/`
- Lobby server: `./data/lobby/`
- Game servers: host directories under `GAME_DATA_DIR` (e.g. `./data/game1/`, `./data/game2/`), created automatically by the plugin

## Troubleshooting

- Check logs: `just logs` or `docker logs mcsrswap-velocity`

## playit.gg Tunnel (optional)

[playit.gg](https://playit.gg) lets you expose the Velocity port to the internet without port forwarding.

**Setup:**

1. Go to [playit.gg](https://playit.gg) → **Add Agent** → select **Docker** as the integration — copy the `SECRET_KEY` shown in the docker run / compose command
2. Start everything including the tunnel (you will be prompted to paste the key on first run):
   ```bash
   just up --playit
   ```
   After saving the key, the setup asks whether `COMPOSE_PROFILES=playit` should also be written to `.env`.
   Or set `COMPOSE_PROFILES=playit` in `.env` to make plain `just up` include the tunnel every time.
3. Share the provided `something.mc.gg` address with your friends

The key is saved to `.playit.secret` and reused on subsequent starts.
The `playit` service uses `network_mode: host` so it can reach the Velocity port on `localhost:25565`.

To stop only the tunnel without touching the game servers:
```bash
docker compose --profile playit stop playit
```
