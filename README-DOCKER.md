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
   - Image overrides if you built locally

2. **Start the services** (generates `VELOCITY_SECRET` automatically if not set):
   ```bash
   just up
   ```

3. **Connect to the server**: `localhost:25565`

4. **Admin commands**: Use `/ms <command>` (see below)

## Game Commands (Docker mode)

| Command | Description |
|---|---|
| `/ms start` | Start the game, reusing existing containers |
| `/ms start --clean` | Clean up old containers/volumes, then start fresh |
| `/ms cleanup` | Stop and remove all game server containers and volumes |
| `/ms seed` | List configured world seeds |
| `/ms seed <i> <seed>` | Set the fixed seed for slot `i` (e.g. `/ms seed 1 -123456`) |
| `/ms seed <i> clear` | Remove the fixed seed for slot `i` (uses random) |
| `/ms seed clear` | Clear all fixed seeds for all slots (all games use random seeds) |

In **versus mode**, seeds are mirrored between teams: setting or clearing seed slot `i` applies to the corresponding slot on both Team A and Team B. In other words, the second half of server slots reuses the first half's seeds.
For all other commands see the [main README](README.md#commands).

## Available `just` Commands

- **`just up`** - Start all servers
- **`just up --playit`** - Start all servers + playit.gg tunnel
- **`just down`** - Stop all servers  
- **`just attach <service>`** - Attach to server console (e.g. `just attach velocity`, `just attach lobby`, `just attach game1`)

## Configuration

Configuration files are automatically created on first startup:
- Velocity config: `./data/velocity/plugins/mcsrswap/config.yml`  
- Server configs are managed automatically

## Data Persistence

- Velocity data: `./data/velocity/`
- Lobby server: `./data/lobby/`  
- Game servers: Docker named volumes (managed automatically)

## Troubleshooting

- Check logs: `docker logs <container>`

## playit.gg Tunnel (optional)

[playit.gg](https://playit.gg) lets you expose the Velocity port to the internet without port forwarding. The Docker Compose setup includes an optional `playit` service that can be started alongside the other containers.

**Setup:**

1. Go to [playit.gg/account/agents/new-docker](https://playit.gg/account/agents/new-docker) and create a Docker agent — copy the `SECRET_KEY` shown there
2. Add the key to your `.env` file:
   ```bash
   PLAYIT_SECRET=<your key>
   ```
3. Start everything including the tunnel:
   ```bash
   just up --playit
   ```
4. Share the provided `something.mc.gg` address with your friends

The `playit` service uses `network_mode: host` so it can reach the Velocity port on `localhost:25565`.

To stop only the tunnel without touching the game servers:
```bash
docker compose --profile tunnel stop playit
```
