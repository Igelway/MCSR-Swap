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

3. **Admin commands**: Use `/ms <command>` (see below)

## Game Commands (Docker mode)

| Command | Description |
|---|---|
| `/ms start` | Start the game, reusing existing containers |
| `/ms start --clean` | Clean up old containers/volumes, then start fresh |
| `/ms cleanup` | Stop and remove all game server containers and volumes |

For all other commands see the [main README](README.md#commands).

## Available `just` Commands

- **`just up`** - Start all servers
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

- Check logs: `just logs`
- Reset everything: `just clean`