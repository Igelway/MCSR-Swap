# MCSR-Swap Docker Setup

> ⚠️ **Security Warning**: This setup requires mounting the Docker socket which grants root-equivalent access to the host system! Only use this in trusted environments or with proper security controls.

## Prerequisites

1. **Install Docker**: Follow the [official Docker installation guide](https://docs.docker.com/get-docker/)
2. **Install Just**: Follow the [installation guide](https://github.com/casey/just#installation)

## Quick Start

1. **Start the services**:
   ```bash
   just up
   ```

2. **Connect to the server**: `localhost:25565`

3. **Admin commands**: Use `/ms <command>` (see main README for available commands)

## Available Commands

- **`just up`** - Start all servers
- **`just up --tunnel`** - Start all servers + playit.gg tunnel
- **`just down`** - Stop all servers  
- **`just tunnel-start`** - Start the playit.gg tunnel
- **`just tunnel-stop`** - Stop the playit.gg tunnel
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

- Check logs: `just logs`
- Reset everything: `just clean`

## playit.gg Tunnel (optional)

[playit.gg](https://playit.gg) lets you expose the Velocity port to the internet without port forwarding. The Docker Compose setup includes an optional `playit` service that can be started alongside the other containers.

**Setup:**

1. Go to [playit.gg/account/agents/new-docker](https://playit.gg/account/agents/new-docker) and create a Docker agent — copy the `SECRET_KEY` shown there
2. Copy the template and fill in your key:
   ```bash
   cp .playit.env.example .playit.env
   # then edit .playit.env and set SECRET_KEY=<your key>
   ```
3. Start everything including the tunnel:
   ```bash
   just up --tunnel
   ```
4. Share the provided `something.mc.gg` address with your friends

The `playit` service uses `network_mode: host` so it can reach the Velocity port on `localhost:25565`. The `.playit.env` file is git-ignored and never committed.

To stop only the tunnel without touching the game servers:
```bash
docker compose --profile tunnel stop playit
```