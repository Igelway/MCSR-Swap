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
   See [`.env` Variables](env-variables.md) for examples how to customize the gameserver and velocity settings.

2. **Start the services** (generates `.forwarding.secret` automatically if not present and asks for Minecraft EULA acceptance if needed):
   ```bash
   just up
   ```

3. **Connect to the server**: `localhost:25565`

4. **Admin commands**: Use `/ms <command>` (see [Commands](README.md#commands))

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

## Lobby Auto-Stop

When `MCSRSWAP_AUTO_STOP_LOBBY=true`, the lobby container is stopped automatically 15 seconds after the game starts (once all players have left it) and restarted when the game ends. This frees the memory the lobby server was using for the game servers while a session is running.

**Player experience:** At game end, players are briefly held in the NanoLimbo transit server while the lobby container starts back up (up to 120 s timeout). Once the lobby is ready they are moved there automatically.

**Important:** The lobby container uses `restart: unless-stopped` in Compose, so `docker stop` does not cause Docker to auto-restart it — only the plugin brings it back.

Enable in `.env`:
```env
MCSRSWAP_AUTO_STOP_LOBBY=true
```

Or in `config.yml`:
```yaml
docker:
  autoStopLobby: true
```

---

## Hybrid Mode (Docker + External Servers)

Hybrid mode lets you mix Docker-managed game servers on the main host with externally-hosted game servers running on other machines (or bare-metal). Velocity registers all servers and includes them in the game rotation.

**Use case:** You have two physical machines. Machine A runs Velocity, the lobby, and two Docker game servers. Machine B runs two additional game servers on fixed ports. With hybrid mode all four participate in the same rotation.

### Setup

**Option A – `.env`:**
```env
# Comma-separated; format: name:host:port
MCSRSWAP_EXTERNAL_SERVERS=game3:192.168.1.200:25602,game4:192.168.1.200:25603
```

**Option B – `config.yml`:**
```yaml
docker:
  externalServers:
    - name: game3
      host: 192.168.1.200
      port: 25602
    - name: game4
      host: 192.168.1.200
      port: 25603
```

### How it works

- External servers are registered with Velocity at startup (not dynamically spawned/removed).
- `/ms prepare N` only spawns N **Docker** containers. The external servers are always included and counted towards the total.
- Velocity pings both Docker containers and external servers before declaring all servers ready.
- In **versus mode**, the total server count (Docker + external) must be even.
- `/ms cleanup` only affects Docker containers and volumes — external servers are unaffected.

### Requirements for external game servers

External game servers must be set up exactly like classic game servers (see [README-CLASSIC.md](README-CLASSIC.md)):
- Minecraft 1.16.1 Fabric server
- `mcsrswap-fabric-mod-*.jar` installed
- FabricProxy 1.3.4 with the same Velocity forwarding secret
- `online-mode=false` in `server.properties`
- Reachable from the Velocity container's network (VPN or LAN)

---



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
