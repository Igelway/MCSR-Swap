# MCSR-Swap Docker Setup

> ⚠️ **Security Warning**: This setup requires mounting the Docker socket which grants root-equivalent access to the host system! Only use this in trusted environments or with proper security controls.

## Prerequisites

1. **Install Docker**: Follow the [official Docker installation guide](https://docs.docker.com/get-docker/)
2. **Install Just**: Follow the [installation guide](https://github.com/casey/just#installation)

## Quick Start

1. **Create your `.env` file** (copy the example and adjust as needed):
   ```bash
   cp .env.example .env
   ```
   Key settings in `.env`:
   - `VELOCITY_ONLINE_MODE=true` — set to `false` for LAN/offline play
   - `PUID` / `PGID` — your host user's UID/GID (`id -u` / `id -g`)
   - `GAME_DATA_DIR` — absolute host path where game server data is stored. Defaults to `<project-dir>/data` — only set this if you want a custom location.
   - Image overrides if you built locally

2. **Start the services** (generates `.forwarding.secret` automatically if not present):
   ```bash
   just up
   ```

3. **Connect to the server**: `localhost:25565`

4. **Admin commands**: Use `/ms <command>` (see below)

## Game Commands (Docker mode)

| Command | Description |
|---|---|
| `/ms start` | Start the game (reuses existing containers) |
| `/ms start --clean` | Clean up old containers/data, then start fresh |
| `/ms stop` | Stop the current game (containers keep running) |
| `/ms cleanup` | Stop and remove all game server containers and data directories |
| `/ms seed` | List configured world seeds |
| `/ms seed <i> <seed>` | Set the fixed seed for slot `i` (e.g. `/ms seed 1 -123456`) |
| `/ms seed <i> clear` | Remove the fixed seed for slot `i` (uses random) |
| `/ms seed clear` | Clear all fixed seeds (all games use random seeds) |

In **versus mode**, seeds are mirrored between teams: setting or clearing seed slot `i` applies to both Team A and Team B.
For all other commands see the [main README](README.md#commands).

## Available `just` Commands

- **`just up`** — Start all servers
- **`just up --playit`** — Start all servers + playit.gg tunnel
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
3. Share the provided `something.mc.gg` address with your friends

The key is saved to `.playit.secret` and reused on subsequent starts.
The `playit` service uses `network_mode: host` so it can reach the Velocity port on `localhost:25565`.

To stop only the tunnel without touching the game servers:
```bash
docker compose --profile tunnel stop playit
```
