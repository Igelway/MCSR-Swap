# MCSR-Swap
MCSR-Swap is a Minecraft speedrun gamemode where players rotate between game servers on a timer. The goal is for the team to complete a configurable percentage of worlds by reaching and exiting the End portal.
You can play as one team or one team versus another team. The project consists of two files:

| File | Where it goes |
|---|---|
| `mcsrswap-fabric-mod-v1.0.12.jar` | `mods/` folder of every game server |
| `mcsrswap-velocity-plugin-v1.0.12.jar` | `plugins/` folder of the Velocity proxy |

---

## 🚀 Quick Start Options

### Option 1: Classic Setup (Manual Configuration)
**Recommended for:** Full control

See **[README-CLASSIC.md](README-CLASSIC.md)** for manual setup and configuration of gameservers.

### Option 2: Docker Dynamic Setup
**Recommended for:** Easy deployment, automatic server management

See **[README-DOCKER.md](README-DOCKER.md)** for a simplified Docker-based setup where game servers are spawned automatically on `/ms start`.

---

## Commands

All commands run through the Velocity proxy. Prefix: `/ms`

### Admin commands (requires `swap.admin` permission)

| Command | Description |
|---|---|
| `/ms start` | Start the game (reuses existing Docker containers if present) |
| `/ms start --clean` | Start the game after removing old Docker containers and volumes |
| `/ms stop` | End the game and send everyone to the lobby |
| `/ms forceswap` | Immediately rotate all players to the next server |
| `/ms setrotation <seconds>` | Change the rotation interval (minimum 10 s) |
| `/ms setteam <a\|b\|none> <player> [player2…]` | Assign players to a team (versus mode) |
| `/ms setteamname <a\|b> <name>` | Set the display name of a team |
| `/ms setversus <true\|false>` | Enable or disable versus mode |
| `/ms state` | Show the current game state |
| `/ms ignore <player>` | Toggle whether another player is excluded from game starts (lobby only) |

> **Docker mode** adds `/ms start --clean` (cleanup before start) and `/ms cleanup` (remove containers + volumes). See [README-DOCKER.md](README-DOCKER.md).

### Player commands

| Command | Description |
|---|---|
| `/ms jointeam <a\|b>` | Join a team before the game starts (lobby only) |
| `/ms ignore` | Toggle whether you are excluded from the next game start (lobby only) |

### Permissions

Admin commands require the `swap.admin` permission.

- **Console** always has full access
- **Players** need the `swap.admin` permission granted via a Velocity permissions plugin (e.g. [LuckPerms for Velocity](https://luckperms.net/))

Without a permissions plugin, only the server console can run admin commands. This means you can start/stop the game via the Velocity console without installing LuckPerms.

---

## Playing over the Internet

Only the **Velocity port (default 25565)** needs to be reachable. All game server ports stay local.

### Option A – Router port forwarding

1. Find your router's admin panel (usually `192.168.1.1` or `192.168.0.1`)
2. Forward a **TCP port** to the local IP of the machine running Velocity  
3. Find your public IP at [whatismyip.com](https://www.whatismyip.com) and share it with your friends (as `<ip>:<port>`)
4. **Security note:** Only open the one port you chose. Keep game server ports (25570–25574) closed to the internet.

> **Tip:** Avoid using the default Minecraft port 25565 on your router. Automated bots constantly scan the internet for open port 25565 and will try to connect. Using a different external port (e.g. 25999 → internal 25565) keeps your server quieter. Players just need to add the port to the address: `yourip:25999`.

### Option B – playit.gg (recommended, no router access needed)

[playit.gg](https://playit.gg) creates a stable public tunnel to your server without opening router ports.

1. Download the [playit agent](https://playit.gg/download) for your OS
2. Run it and follow the setup wizard to create a **Minecraft Java** tunnel on port 25565
3. Share the provided `something.mc.gg` address with your friends
4. No router configuration needed

> **Using Docker?** See [README-DOCKER.md](README-DOCKER.md#playitgg-tunnel-optional) for the integrated Docker Compose setup.

### Option C – VPN (e.g. ZeroTier / Tailscale)

For small groups who all install a VPN client:

- [Tailscale](https://tailscale.com) – easiest, works through NAT automatically
- [ZeroTier](https://www.zerotier.com) – more control, slightly more setup

All players join the same virtual network and connect using the host's Tailscale/ZeroTier IP.

### Security recommendations

- Keep your game server ports firewalled (they should only accept connections from `127.0.0.1`)
- Use Velocity's `online-mode = true` so only authenticated Mojang accounts can join
- Consider a whitelist (`whitelist.json`) on the Velocity proxy for private sessions
