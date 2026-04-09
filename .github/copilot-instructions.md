# MCSR-Swap – Copilot Instructions

## Project overview

MCSR-Swap is a multiplayer Minecraft speedrun format where players rotate between game servers on a timer. The team goal is to beat the Ender Dragon on a configurable percentage of worlds. The project consists of two components:

- **`fabric-mod/`** – Fabric mod running on each **Minecraft 1.16.1** game server (Yarn mappings `1.16.1+build.21`)
- **`velocity-plugin/`** – Velocity proxy plugin coordinating all game servers (Velocity API 3.1.1)

## Deployment Modes

The project supports two deployment modes and **both must remain fully supported**:

### 1. Manual Mode (Traditional)
- Game servers are **externally managed** (started manually or via systemd/scripts)
- Servers run on **fixed ports** in a configurable range (e.g., localhost:25600-25650)
- Worlds are **pre-generated** before gameplay starts
- Lobby server also runs on a fixed port (e.g., localhost:25565)
- Velocity plugin connects to these existing servers
- **Deployment flexibility**: Velocity/Lobby can run anywhere (bare metal, Docker, etc.)

### 2. Docker Mode (Recommended for convenience)
- Velocity plugin **dynamically spawns/stops** game server containers on-demand
- Uses **Docker named volumes** for game server data (`mcsrswap-game{N}`)
- Velocity/Lobby typically run in Docker Compose setup, but **not required**
- Security: Uses `tecnativa/docker-socket-proxy` to restrict Docker API access
- Commands: `/ms start` (cleanup + spawn), `/ms resume` (reuse existing), `/ms cleanup` (remove containers + volumes)
- Container lifecycle: Servers persist after game ends; manual cleanup via `/ms cleanup`

**See [docker-architecture.md](docker-architecture.md) for detailed Docker deployment documentation.**

## Architecture

```
Velocity proxy  ──plugin messages (mcsrswap:main)──►  Fabric mod (each game server)
                ◄──plugin messages (mcsrswap:main)──
```

All game logic is split:
- **Velocity** owns: rotation timer, player routing, game state (LOBBY/STARTING/RUNNING), config, commands, Docker orchestration
- **Fabric mod** owns: world state, player state save/restore, scoreboard, goal detection (End portal), spectator lock, server freeze

Communication uses Minecraft plugin messaging (`ByteArrayDataInput`/`ByteArrayDataOutput` via Guava `ByteStreams`). Messages are identified by a leading `writeUTF(type)` string.

### Key message types (Velocity → Fabric)
| Message | Payload | Purpose |
|---|---|---|
| `reset` | – | New round started; freeze time, clear finished state |
| `time` | `int` | Current rotation countdown in seconds |
| `progress` | `int, int` | completedWorlds, requiredWorlds |
| `save` | – | Immediate state save before rotation |
| `savehotbar` | `boolean` | Enable/disable hotbar preference feature |
| `eyehoverticks` | `int` | Eye of Ender lifetime override |
| `become_spectator` | `UUID string` | Lock player into spectator + camera onto active player |
| `prepare_return` | `UUID string` | Teleport watcher to Y=100000, switch to survival before rotation |

### Key message types (Fabric → Velocity)
| Message | Payload | Purpose |
|---|---|---|
| `finish` | – | Player exited End portal; world is beaten |
| `mode` | `UUID string, "spectator"\|"survival"` | Game-mode change notification |

## Fabric mod

### Build
```bash
cd fabric-mod && ./gradlew build
```
Output: `fabric-mod/build/libs/mcsrswap-fabric-mod-1.0.0.jar`

### Key classes
| Class | Role |
|---|---|
| `SwapMod` | `ModInitializer`; registers all events, handles incoming messages, owns game state flags |
| `StateManager` | Saves/restores `PlayerState`; owns `saveHotbar`, `hotbarPreferences`, `clearRegenAfter` |
| `PlayerState` | Data class: position, inventory, enderChest, health, food, XP, fire, nether portal cooldown, vehicle, effects, spawn point |
| `ScoreboardManager` | Sidebar scoreboard showing time, progress, open worlds |
| `ModConfig` | Static holder for `eyeHoverTicks` (set at runtime via plugin message; no config file) |
| `Lang` | Per-player locale; reads `.properties` language files from `config/mcsrswap/languages/` |

### Mixins (`de.mcsrswap.mixin`)
| Mixin | Target | Purpose |
|---|---|---|
| `EndPortalMixin` | `EndPortalBlock.onEntityCollision` | Sole goal trigger: detects living survival player stepping into End exit portal |
| `SpectatorLockMixin` | `ServerPlayerEntity.setCameraEntity` | Prevents Shift-escape from locked spectator camera |
| `EyeOfEnderEntityMixin` | `EyeOfEnderEntity.tick` | Replaces vanilla constant 80 with `ModConfig.eyeHoverTicks` |
| `HungerManagerAccessor` | `HungerManager` | Exposes `setSaturationLevel()` |
| `EntityFlagInvoker` | `Entity` | Exposes `setFlag(int, boolean)` for elytra flight flag |
| `BrainMemoriesAccessor` | `Brain` | Exposes memory map for mob-anger TTL transfer |
| `MemoryExpiryAccessor` | `Memory` | Exposes expiry TTL value |
| `LocaleCaptureMixin` | `ClientSettingsC2SPacket` | Captures per-player locale for `Lang` |
| `JoinQuitMessageMixin` | `PlayerManager` | Suppresses vanilla join/quit chat messages |
| `DisconnectMessageMixin` | `ServerPlayNetworkHandler` | Suppresses vanilla disconnect chat messages |

### Important implementation details

- **State restore is deferred 2 ticks** (`RESTORE_DELAY_TICKS = 2`) after `ENTITY_LOAD` to let the connection stabilise. Hotbar preference is captured at `ENTITY_LOAD` before the restore fires.
- **`ENTITY_LOAD` also fires on dimension changes and respawns** – only restore state on the actual first join (tracked via `connectedPlayers` set).
- **XP packet quirk**: `ExperienceBarUpdateS2CPacket(float barProgress, int experienceLevel, int experience)` — vanilla calls it as `(expProgress, totalExperience, expLevel)`. The last two constructor args are swapped vs the wire order. Always mirror vanilla's call order.
- **Goal detection**: only `EndPortalMixin` triggers `onPlayerExitEnd()`. There is intentionally no tick-based fallback (it was removed to prevent double-firing after `reset`).
- **Death → item duplication prevention**: on first tick of death (`currentlyDead` set), `stateManager.clearInventory()` wipes the saved inventory/XP so the next player inherits nothing.
- **`SimpleInventory` (ender chest)** has no `serialize()`/`deserialize()` — use manual slot iteration with `ItemStack.toTag()` / `ItemStack.fromTag()` and store the slot index as `"Slot"` byte in the `CompoundTag`.
- **`netherPortalCooldown`** is a public field on `Entity`; saved and restored to prevent portal re-use exploit after swap.
- **Spectator lock**: `spectatorCameras` map (watcher UUID → target UUID) is re-locked every 20 ticks via `SetCameraEntityS2CPacket`. `SpectatorLockMixin` cancels `setCameraEntity(self)` while locked.

## Velocity plugin

### Build
```bash
cd velocity-plugin && mvn clean package
```
Output: `velocity-plugin/target/mcsrswap-velocity-plugin-1.0.jar`

### Key classes
| Class | Role |
|---|---|
| `VelocitySwapPlugin` | Main plugin; owns game loop, rotation timer, player routing, config, command registration, GameState management, Docker orchestration (when enabled) |
| `WorldSwapCommands` | All `/ms` subcommand implementations; permission check via `swap.admin` or `admins` config list |
| `VelocityLang` | Loads and serves language strings from `plugins/mcsrswap/languages/` |
| `PluginConfig` | Type-safe config class; loads from `plugins/mcsrswap/config.yml`, applies ENV overrides (e.g., `MCSRSWAP_LOBBY_ADDRESS`, `MCSRSWAP_GAMESERVER_IMAGE`), sanitizes values (e.g., language file extension, percentage validation) |
| `DockerServerManager` | (Docker mode only) Container orchestration; spawns/stops game server containers, manages named volumes, health checks, seed generation for versus mode |
| `GameState` | Enum: `LOBBY` (default), `STARTING` (containers starting), `RUNNING` (active game) |

### Config (`plugins/mcsrswap/config.yml`)
All gameplay settings are loaded from YAML into `PluginConfig` and pushed to game servers at game start:
- `rotationTime`, `requiredPercentage`, `versus`, `language`, `gameServerPrefix`, `lobbyServerName`
- `spectateAfterWin`, `spectateTarget` (`next`/`prev`), `spectateMinTime`
- `saveHotbar`, `eyeHoverTicks`
- `admins` – List of player UUIDs or usernames with `swap.admin` permission (alternative to LuckPerms)
- Config values can be **sanitized** in `PluginConfig` constructor (e.g., stripping `.yml` from language, clamping percentages to 0.0-1.0)

#### Manual Mode (default)
When `docker.enabled: false` or unset:
- `gameServers` – List of server names that must be pre-registered in Velocity's `velocity.toml`
- Servers run on fixed ports (e.g., localhost:25600, localhost:25601, etc.)
- All servers must be started **before** `/ms start` is issued

#### Docker Mode (optional)
When `docker.enabled: true`:
- **`DockerServerManager`** dynamically spawns/stops game server containers via `tecnativa/docker-socket-proxy`
- Automatically triggered on `/ms start` (with cleanup) or `/ms resume` (reuse existing containers)
- Containers use `ghcr.io/{owner}/mcsr-swap-gameserver:v{version}` with Fabric mod pre-installed
- Game server data persists in **Docker named volumes** (`mcsrswap-game{N}`)
- Velocity/Lobby data in **bind mounts** (`./data/velocity`, `./data/lobby`)
- `/ms cleanup` stops containers and deletes volumes
- **Security Note:** Requires Docker socket access (via proxy) which should be restricted to trusted environments

### Commands (`/ms`)
Admin (`swap.admin` permission OR `admins` config): `start`, `resume`, `stop`, `forceswap`, `setrotation`, `spectate`, `setteam`, `setteamname`, `setversus`, `state`, `cleanup`
Player: `jointeam`

- **`/ms start`**: Cleanup old containers/volumes, then start fresh game
- **`/ms resume`**: Resume game with existing containers/volumes (no cleanup)
- **`/ms cleanup`**: Stop containers and delete volumes (Docker mode only)

Tab completion is implemented via `SimpleCommand.suggest()`.

### Game flow
1. `/ms start` → `startGame()`: assign players to servers, `pendingReset` population, send players to game servers
2. On `ServerConnectedEvent` (game server): send `savehotbar` + `eyehoverticks` + `reset` (first join only via `pendingReset`)
3. Timer ticks every second; at T=5 (if `spectateAfterWin`): `preRotation()` sends `prepare_return` to watchers
4. At T=0: `rotatePlayers()` sends `save` to all servers, swaps `playerServer` map, routes each player to next server
5. Fabric mod sends `finish` → `handleFinish()` → increments `finishedServers`, checks win condition, optionally triggers spectate

### Important data structures
- `playerServer: Map<UUID, String>` – logical server assignment (advances each rotation)
- `watchingPlayers: Map<UUID, String>` – players in spectate mode → their logical server (not updated until next rotation)
- `finishedServers: Set<String>` – logical servers where the goal has been reached
- `spectators: Set<UUID>` – players permanently in spectator (not participants)
- `pendingReset: Set<UUID>` – players who need a reset+config message on their next game server join

## Conventions

- Plugin message channel: `mcsrswap:main` (`new Identifier("mcsrswap", "main")` on Fabric; `MinecraftChannelIdentifier.from("mcsrswap:main")` on Velocity)
- All Fabric field access uses **Yarn 1.16.1+build.21** mapped names
- No config file on the Fabric mod — all settings come from Velocity at game start
- Language keys follow snake_case (e.g. `game_finished`, `new_round`)
- Fabric: `ServerTickEvents.END_SERVER_TICK` for all periodic logic; avoid `START_SERVER_TICK`
- Avoid creative/spectator intermediate states during swap; always end in `GameMode.SURVIVAL`

## Docker & CI/CD

### Docker Images
Built via GitHub Actions on tag push (`v*`):
- **`ghcr.io/igelway/mcsr-swap-velocity:latest`** – Velocity proxy + plugin (Java 21)
- **`ghcr.io/igelway/mcsr-swap-gameserver:latest`** – Fabric 1.16.1 server + mod (Java 17)

### CI/CD Workflows
- **`docker-build.yml`** – Builds both images on version tags, pushes to GitHub Container Registry
- **`release.yml`** – Creates GitHub release with compiled JARs on version tags

### Tech Stack
- Java 21 (Velocity plugin + build tools)
- Java 17 (Minecraft 1.16.1 servers, Fabric mod runtime)
- Gradle 8.5 (Fabric mod)
- Maven 3.9+ (Velocity plugin)
- Docker Java API client 3.4.1 (optional, for dynamic server spawning via `tecnativa/docker-socket-proxy`)
- Spotless (code formatting: Google Java Format AOSP style)
