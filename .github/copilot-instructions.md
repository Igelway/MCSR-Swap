# MCSR-Swap – Copilot Instructions

## Project overview

MCSR-Swap is a multiplayer Minecraft speedrun format where players rotate between game servers on a timer. The team goal is to beat the Ender Dragon on a configurable percentage of worlds. The project consists of two components:

- **`fabric-mod/`** – Fabric mod running on each **Minecraft 1.16.1** game server (Yarn mappings `1.16.1+build.21`)
- **`velocity-plugin/`** – Velocity proxy plugin coordinating all game servers (Velocity API 3.1.1)

## Architecture

```
Velocity proxy  ──plugin messages (mcsrswap:main)──►  Fabric mod (each game server)
                ◄──plugin messages (mcsrswap:main)──
```

All game logic is split:
- **Velocity** owns: rotation timer, player routing, game state (running/stopped), config, commands
- **Fabric mod** owns: world state, player state save/restore, scoreboard, goal detection (End portal), spectator lock

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
| `VelocitySwapPlugin` | Main plugin; owns game loop, rotation timer, player routing, config, command registration |
| `WorldSwapCommands` | All `/ms` subcommand implementations; permission check via `swap.admin` |
| `VelocityLang` | Loads and serves language strings from `plugins/mcsrswap/languages/` |

### Config (`plugins/mcsrswap/config.yml`)
All gameplay settings live here and are pushed to game servers at game start:
- `rotationTime`, `requiredPercentage`, `versus`, `language`, `gameServerPrefix`, `lobbyServerName`
- `spectateAfterWin`, `spectateTarget` (`next`/`prev`), `spectateMinTime`
- `saveHotbar`, `eyeHoverTicks`

### Commands (`/ms`)
Admin (`swap.admin`): `start`, `stop`, `forceswap`, `setrotation`, `spectate`, `setteam`, `setteamname`, `setversus`  
Player: `jointeam`

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
