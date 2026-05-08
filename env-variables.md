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
| `MCSRSWAP_GAME_VIEW_DISTANCE` | `20` | Server View distance. |
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
| `MCSRSWAP_AUTO_STOP_LOBBY` | `false` | Stop the lobby container 15 s after game start and restart it automatically when the game ends. Frees memory for game servers while a session is running. |
| `MCSRSWAP_EXTERNAL_SERVERS` | – | Comma-separated list of externally-hosted servers in `name:host:port` format (e.g. `game3:192.168.1.200:25602`). See [Hybrid Mode](#hybrid-mode-docker--external-servers) below. |

> Internal networking variables (`MCSRSWAP_DOCKER_HOST`, `MCSRSWAP_DOCKER_NETWORK`, `MCSRSWAP_LOBBY_ADDRESS`, `MCSRSWAP_LIMBO_ADDRESS`) rarely need changing outside of custom setups.
