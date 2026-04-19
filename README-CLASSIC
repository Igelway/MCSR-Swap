# MCSR-Swap Classic Setup

## Requirements

| Component | Version |
|---|---|
| Velocity proxy | 3.x (tested with 3.5.0-SNAPSHOT) |
| Game servers | Minecraft **1.16.1**, **Fabric** server |
| Fabric Loader | ≥ 0.12.0 |
| Fabric API | 0.18.0+build.387-1.16.1 (must match 1.16.1) |
| Java | 17 or newer |

### Required mods on every game server

These must be present in `mods/` alongside `mcsrswap-fabric-mod-v1.0.12.jar`:

- [`fabric-api-0.18.0+build.387-1.16.1.jar`](https://modrinth.com/mod/fabric-api/version/0.18.0+build.387-1.16.1)
- FabricProxy 1.3.4 – enables Velocity modern forwarding for 1.16.1.  
  Download from GitHub: [OKTW-Network/FabricProxy → v1.3.4](https://github.com/OKTW-Network/FabricProxy/releases/tag/v1.3.4)

Recommended mods (optional but advised):

- Lithium – server-side optimisations, significant performance improvement
- Krypton – network stack optimisation, reduces bandwidth and tick overhead
- Starlight – rewrites the light engine, greatly speeds up chunk loading
- LazyDFU – skips unnecessary DataFixerUpper initialisation on startup
- Voyager – fixes a rare ConcurrentModificationException (CME) when running Java 11 or above (affects 1.14+)
- antigone – fixes a rare 1.16.1 server deadlock caused by a strider spawning during chunk generation with a zombified piglin baby chicken jockey as a passenger, creating a chunk-generation dependency loop

> Many of these mods only exist as backports or are maintained specifically for the speedrunning community. **[mods.tildejustin.dev](https://mods.tildejustin.dev/)** is the canonical source for 1.16.1 speedrunning mods.

---

## Step 1 – Velocity setup

### 1.1 Install the plugin

Copy `mcsrswap-velocity-plugin-v1.0.12.jar` into `plugins/`.

### 1.2 Configure `velocity.toml`

A ready-to-use starting point is provided in [`defaults/velocity/velocity.toml`](defaults/velocity/velocity.toml). Copy it into your Velocity working directory and adjust the server addresses:

```bash
cp defaults/velocity/velocity.toml <velocity-dir>/velocity.toml
```

Key values to review:

```toml
online-mode = true                        # set to false for LAN / offline play
player-info-forwarding-mode = "modern"    # required — do not change
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"
game1 = "127.0.0.1:25600"
game2 = "127.0.0.1:25601"
```

> **Important:** Server names must match `gameServerPrefix` (default: `game`) and `lobbyServerName` (default: `lobby`) in the MCSR-Swap config.

### 1.3 Generate forwarding secret

Velocity generates `forwarding.secret` automatically on first run. Note the value – you will need it for each game server.

### 1.4 MCSR-Swap plugin config

On first start, Velocity creates:

```
plugins/mcsrswap/config.yml
plugins/mcsrswap/languages/en_us.yml
plugins/mcsrswap/languages/de_de.yml
```

A fully-documented starting point is provided in [`defaults/velocity/plugins/mcsrswap/config.yml`](defaults/velocity/plugins/mcsrswap/config.yml). Copy it into place before the first start to avoid having to hunt for option names:

```bash
cp defaults/velocity/plugins/mcsrswap/config.yml <velocity-dir>/plugins/mcsrswap/config.yml
```

**`config.yml`:**

```yaml
rotationTime: 120          # Seconds per rotation
requiredPercentage: 1.0    # Fraction of worlds that must be beaten to win (1.0 = all)
versus: false              # Versus mode: two teams compete against each other
language: en_us.yml        # Language file to use for player-facing messages
gameServerPrefix: game     # Velocity server names starting with this prefix are treated as game servers
lobbyServerName: lobby     # Exact Velocity server name used as the lobby
spectateAfterWin: false    # After finishing a world, spectate an adjacent server until the next rotation
spectateTarget: next       # Which adjacent server to spectate: "next" or "prev"
spectateMinTime: 15        # Only activate spectate if at least this many seconds remain in the rotation
saveHotbar: true           # On player swap, rearrange the server's hotbar items to match the joining player's own preferred hotbar layout
eyeHoverTicks: 80          # Total Eye of Ender lifetime in ticks (vanilla: 80 ≈ 2 s hover phase)
# admins:                  # Players that bypass permission checks; accepts UUIDs or usernames
#   - SomeUsername
# ignorePlayers:           # Players excluded from game starts by default; accepts UUIDs or usernames
#   - SpectatorName
```

> **Why increase `eyeHoverTicks`?**  
> On a multiplayer server you cannot pause the game to freeze the Eye of Ender (pause-buffering), which is a common singleplayer technique for [NinjabrainBot](https://github.com/Ninjabrain1/Ninjabrain-Bot) readings. Increasing the hover duration gives players more time to read the values before the eye drops. This value is pushed to all game servers at game start.

#### Custom language files

Add any `.yml` file to `plugins/mcsrswap/languages/`. Set which file to use via the `language` key in `config.yml` (e.g. `language: de_de.yml`). This applies to all players – the Velocity plugin has no way to detect individual client languages. The setting takes effect on the next server start.

---

## Step 2 – Game server setup

Do the following for **each** game server (game1, game2, …).

### 2.1 server.properties

A minimal starting point is provided in [`defaults/gameserver/server.properties`](defaults/gameserver/server.properties). Copy it to each game server's working directory:

```bash
cp defaults/gameserver/server.properties <gameN-dir>/server.properties
```

Then set the port for each server:

```properties
server-port=25600   # 25600 for game1, 25601 for game2, etc.
```

> **`online-mode=false` is mandatory** — authentication is handled by Velocity. Leaving it at the vanilla default (`true`) causes UUID mismatches and players cannot connect.

### 2.2 Enable Velocity modern forwarding (FabricProxy)

**FabricProxy 1.3.4** is required (see section *Required mods* above).

The config file is generated on first server start. You then need to set these values in `config/FabricProxy.toml`:

```toml
BungeeCord = false
Velocity = true
secret = "PASTE_YOUR_FORWARDING_SECRET_HERE"
```

Replace the secret with the value from Velocity's `forwarding.secret` file.

### 2.3 Install mods

Place the following in `mods/`:

```
mcsrswap-fabric-mod-v1.0.12.jar
fabric-api-0.18.0+build.387-1.16.1.jar
FabricProxy-1.3.4.jar
```

The mod has no configuration file of its own. All settings are configured centrally in the Velocity `plugins/mcsrswap/config.yml` and pushed to the game servers at game start.

---

## Step 3 – Lobby server setup

Use a **Fabric** or **Paper/Purpur** server on port 25570. Plain Vanilla is not recommended as it lacks velocity modern forwarding mode support. Players land here when they connect and between games.

`server.properties`:

```properties
server-port=25570
online-mode=false
```

For a Fabric lobby, install FabricProxy 1.3.4 (same as game servers) to get correct player UUIDs.  
For a Paper/Purpur lobby, enable Velocity modern forwarding in `paper.yml`:

```yaml
settings:
  velocity-support:
    enabled: true
    online-mode: true
    secret: "PASTE_YOUR_FORWARDING_SECRET_HERE"
```

---

## Versus mode

When `versus: true` or `/ms setversus true` is used:

- Game servers are split in half: the first half goes to Team A, the second half to Team B (e.g. with 4 servers: Team A → game1, game2 · Team B → game3, game4)
- Each team's progress is tracked separately
- The team that beats the required percentage of their worlds first wins
- Players can pre-assign themselves with `/ms jointeam` or be randomly assigned at game start (balanced distribution)
