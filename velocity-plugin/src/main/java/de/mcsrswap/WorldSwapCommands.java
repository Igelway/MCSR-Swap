package de.mcsrswap;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;

public class WorldSwapCommands {

    VelocitySwapPlugin plugin;

    WorldSwapCommands(VelocitySwapPlugin plugin) {
        this.plugin = plugin;
    }

    String getTeamNameA() {
        return plugin.teamNameA;
    }

    String getTeamNameB() {
        return plugin.teamNameB;
    }

    /**
     * Returns true if the source is allowed to use admin commands. Console always has access.
     * Players need the "swap.admin" permission (granted via a permissions plugin such as LuckPerms)
     * OR be listed in config.yml admins list.
     */
    private boolean isAdmin(CommandSource src) {
        if (src instanceof ConsoleCommandSource) {
            return true;
        }
        if (src.hasPermission("swap.admin")) {
            return true;
        }
        if (src instanceof Player) {
            Player player = (Player) src;
            String username = player.getUsername();
            String uuid = player.getUniqueId().toString();
            return plugin.adminPlayers.contains(username) || plugin.adminPlayers.contains(uuid);
        }
        return false;
    }

    /**
     * Max players per team = gameServers.size() / 2. Returns 0 if unknown (no servers detected
     * yet).
     */
    int teamCapacity() {
        plugin.detectServers();
        return plugin.gameServers.size() / 2;
    }

    void sendHelp(CommandSource src) {
        src.sendMessage(Component.text("§eWorldSwap commands:"));
        if (isAdmin(src)) {
            src.sendMessage(Component.text("§7/ms start §8| §7/ms stop §8| §7/ms forceswap"));
            src.sendMessage(Component.text("§7/ms setrotation <s> §8| §7/ms spectate <player>"));
            src.sendMessage(Component.text("§7/ms setteam <a|b|none> <player...> §8"));
            src.sendMessage(Component.text("§7/ms setteamname <a|b> <name>"));
            src.sendMessage(Component.text("§7/ms setversus <true|false> §8| §7/ms state"));
            src.sendMessage(Component.text("§7/ms cleanup §8- §7Stop Docker containers"));
        }
        src.sendMessage(Component.text("§7/ms jointeam <a|b>"));
    }

    void cmdStart(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (plugin.gameState == GameState.RUNNING) {
            src.sendMessage(Component.text("§cGame is already running!"));
            return;
        }
        if (plugin.gameState == GameState.STARTING) {
            src.sendMessage(Component.text("§cGame is already starting, please wait..."));
            return;
        }

        // Cleanup before starting in Docker mode
        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            src.sendMessage(Component.text("§7Cleaning up old containers and volumes..."));
            cmdCleanup(src, args);
        }

        cmdResume(src, args);
    }

    void cmdResume(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (plugin.gameState == GameState.RUNNING) {
            src.sendMessage(Component.text("§cGame is already running!"));
            return;
        }
        if (plugin.gameState == GameState.STARTING) {
            src.sendMessage(Component.text("§cGame is already starting, please wait..."));
            return;
        }

        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            List<Player> participants =
                    plugin.server.getAllPlayers().stream()
                            .filter(p -> !plugin.spectators.contains(p.getUniqueId()))
                            .collect(Collectors.toList());

            if (participants.isEmpty()) {
                src.sendMessage(Component.text("§cNo players to start the game!"));
                return;
            }

            int serverCount = participants.size();
            src.sendMessage(Component.text("§7Starting " + serverCount + " Docker containers…"));

            // Mark game as starting to prevent double-start
            plugin.gameState = GameState.STARTING;

            // Generate seed for versus mode (all teams get same seeds)
            Long seed = plugin.versusMode ? new java.util.Random().nextLong() : null;

            // Start servers and wait for them to become healthy
            plugin.dockerManager
                    .startServersAsync(serverCount, seed)
                    .thenAccept(
                            startedServers -> {
                                if (startedServers.isEmpty()) {
                                    src.sendMessage(
                                            Component.text("§cFailed to start Docker containers!"));
                                    plugin.gameState = GameState.LOBBY;
                                    return;
                                }

                                plugin.gameServers = startedServers;
                                src.sendMessage(
                                        Component.text("§aServers healthy, starting game…"));
                                plugin.startGame();
                            })
                    .exceptionally(
                            ex -> {
                                src.sendMessage(
                                        Component.text(
                                                "§cError starting containers: " + ex.getMessage()));
                                plugin.gameState = GameState.LOBBY;
                                return null;
                            });

            return;
        }

        plugin.detectServers();

        if (plugin.gameServers.isEmpty()) {
            src.sendMessage(
                    Component.text(
                            "§cNo game servers found (no server names starting with '"
                                    + plugin.gameServerPrefix
                                    + "')."));
            return;
        }

        // Ping all detected game servers in parallel; only start if all respond.
        src.sendMessage(Component.text("§7Checking game servers…"));

        List<CompletableFuture<Void>> pings = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();

        for (String name : plugin.gameServers) {
            plugin.server
                    .getServer(name)
                    .ifPresent(
                            rs -> {
                                CompletableFuture<Void> f =
                                        rs.ping()
                                                .thenAccept(
                                                        p -> {
                                                            /* server responded */
                                                        })
                                                .exceptionally(
                                                        ex -> {
                                                            synchronized (unreachable) {
                                                                unreachable.add(name);
                                                            }
                                                            return null;
                                                        });
                                pings.add(f);
                            });
        }

        CompletableFuture.allOf(pings.toArray(new CompletableFuture[0]))
                .thenRun(
                        () -> {
                            if (!unreachable.isEmpty()) {
                                src.sendMessage(
                                        Component.text(
                                                "§cCannot start: the following servers are not"
                                                        + " reachable:"));
                                for (String name : unreachable) {
                                    src.sendMessage(Component.text("§c  • " + name));
                                }
                            } else {
                                src.sendMessage(
                                        Component.text("§aAll servers reachable, starting game…"));
                                plugin.startGame();
                            }
                        });
    }

    void cmdStop(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (plugin.gameState == GameState.LOBBY) {
            src.sendMessage(Component.text("§cNo game is running!"));
            return;
        }
        if (plugin.gameState == GameState.STARTING) {
            src.sendMessage(
                    Component.text("§cGame is still starting, please wait or restart the server."));
            return;
        }
        // Move all players to lobby and end game
        src.sendMessage(Component.text("§7Stopping game, moving players to lobby..."));
        plugin.endGame();
        src.sendMessage(
                Component.text(
                        "§aGame stopped. Servers are still running - use §e/ms cleanup§a to stop"
                                + " them."));
    }

    void cmdCleanup(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (plugin.gameState == GameState.RUNNING) {
            src.sendMessage(Component.text("§cGame is still running! Use §e/ms stop§c first."));
            return;
        }
        if (plugin.dockerManager == null || !plugin.dockerManager.isDockerEnabled()) {
            src.sendMessage(Component.text("§cDocker mode is not enabled."));
            return;
        }
        src.sendMessage(Component.text("§7Stopping Docker containers and removing data..."));
        try {
            plugin.dockerManager.stopAllServers();
            plugin.dockerManager.removeAllData();
            src.sendMessage(
                    Component.text("§aAll game server containers stopped and data removed."));
        } catch (Exception e) {
            src.sendMessage(Component.text("§cCleanup failed. Check logs for details."));
            plugin.getLogger().error("Cleanup failed", e);
        }
    }

    void cmdForceSwap(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        plugin.forceSwap();
    }

    void cmdSetRotation(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (args.length != 1) {
            src.sendMessage(Component.text("§eUsage: /ms setrotation <seconds>"));
            return;
        }
        try {
            int newTime = Integer.parseInt(args[0]);
            if (newTime < 10) {
                src.sendMessage(Component.text("§cMinimum: 10 seconds."));
                return;
            }
            plugin.rotationTime = newTime;
            src.sendMessage(
                    Component.text(
                            "§aRotation time set to §e"
                                    + plugin.rotationTime
                                    + "s§a. §7(Current round: §e"
                                    + plugin.currentTime
                                    + "s §7remaining)"));
        } catch (NumberFormatException e) {
            src.sendMessage(Component.text("§cInvalid number: " + args[0]));
        }
    }

    void cmdSpectate(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (args.length != 1) {
            src.sendMessage(Component.text("§eUsage: /ms spectate <player>"));
            return;
        }
        plugin.server
                .getPlayer(args[0])
                .ifPresentOrElse(
                        target -> {
                            UUID uuid = target.getUniqueId();
                            if (plugin.spectators.remove(uuid)) {
                                src.sendMessage(
                                        Component.text(
                                                "§a"
                                                        + target.getUsername()
                                                        + " is now a participant."));
                                target.sendMessage(
                                        Component.text(plugin.lang.get("spectator_removed")));
                            } else {
                                plugin.spectators.add(uuid);
                                src.sendMessage(
                                        Component.text(
                                                "§7"
                                                        + target.getUsername()
                                                        + " is now a spectator."));
                                target.sendMessage(
                                        Component.text(plugin.lang.get("spectator_added")));
                                // If the game is running the player is currently on a game server –
                                // send them
                                // to the lobby immediately so they are not still actively playing.
                                if (plugin.gameState == GameState.RUNNING) {
                                    plugin.server
                                            .getServer(plugin.lobbyServerName)
                                            .ifPresent(
                                                    s ->
                                                            target.createConnectionRequest(s)
                                                                    .fireAndForget());
                                }
                            }
                        },
                        () -> src.sendMessage(Component.text("§cPlayer not found: " + args[0])));
    }

    /**
     * Resolves a user-supplied team argument to the internal id "a", "b", or "none". Accepts: "a",
     * "b", "none", the current display name of either team (case-insensitive). Returns null if the
     * input doesn't match anything.
     */
    String resolveTeamId(String input) {
        if (input == null) return null;
        String lo = input.toLowerCase();
        if (lo.equals("a") || lo.equalsIgnoreCase(plugin.teamNameA)) return "a";
        if (lo.equals("b") || lo.equalsIgnoreCase(plugin.teamNameB)) return "b";
        if (lo.equals("none")) return "none";
        return null;
    }

    void cmdSetTeam(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (args.length < 2) {
            src.sendMessage(
                    Component.text("§eUsage: /ms setteam <a|b|none> <player> [player2] ..."));
            return;
        }
        String teamArg = resolveTeamId(args[0]);
        if (teamArg == null) {
            src.sendMessage(
                    Component.text(
                            "§cFirst argument must be 'a', 'b', 'none' or a team name (§e"
                                    + plugin.teamNameA
                                    + "§c/§e"
                                    + plugin.teamNameB
                                    + "§c)"));
            return;
        }
        for (int i = 1; i < args.length; i++) {
            String targetName = args[i];
            plugin.server
                    .getPlayer(targetName)
                    .ifPresentOrElse(
                            target -> {
                                UUID uuid = target.getUniqueId();
                                if ("none".equals(teamArg)) {
                                    plugin.playerTeam.remove(uuid);
                                    src.sendMessage(
                                            Component.text(
                                                    "§7"
                                                            + target.getUsername()
                                                            + " removed from teams."));
                                    target.sendMessage(
                                            Component.text(plugin.lang.get("team_removed")));
                                } else {
                                    int cap = teamCapacity();
                                    long count =
                                            plugin.playerTeam.values().stream()
                                                    .filter(teamArg::equals)
                                                    .count();
                                    if (!teamArg.equals(plugin.playerTeam.get(uuid))
                                            && cap > 0
                                            && count >= cap) {
                                        String dn =
                                                "a".equals(teamArg)
                                                        ? plugin.teamNameA
                                                        : plugin.teamNameB;
                                        src.sendMessage(
                                                Component.text(
                                                        plugin.lang.get(
                                                                "team_full",
                                                                "team",
                                                                dn,
                                                                "count",
                                                                String.valueOf(cap),
                                                                "max",
                                                                String.valueOf(cap))));
                                        return;
                                    }
                                    plugin.playerTeam.put(uuid, teamArg);
                                    String dn =
                                            "a".equals(teamArg)
                                                    ? plugin.teamNameA
                                                    : plugin.teamNameB;
                                    src.sendMessage(
                                            Component.text(
                                                    "§a" + target.getUsername() + " → Team " + dn));
                                    target.sendMessage(
                                            Component.text(
                                                    plugin.lang.get("team_joined", "team", dn)));
                                }
                            },
                            () ->
                                    src.sendMessage(
                                            Component.text("§cPlayer not found: " + targetName)));
        }
    }

    void cmdJoinTeam(CommandSource src, String[] args) {
        if (!(src instanceof Player)) {
            src.sendMessage(Component.text("§cOnly players can use this command."));
            return;
        }
        if (args.length != 1) {
            src.sendMessage(Component.text("§eUsage: /ms jointeam <a|b>"));
            src.sendMessage(
                    Component.text(
                            "§7Teams: §a" + plugin.teamNameA + " §7| §b" + plugin.teamNameB));
            return;
        }
        String teamArg = resolveTeamId(args[0]);
        if (teamArg == null || teamArg.equals("none")) {
            src.sendMessage(Component.text("§eUsage: /ms jointeam <a|b>"));
            src.sendMessage(
                    Component.text(
                            "§7Teams: §a" + plugin.teamNameA + " §7| §b" + plugin.teamNameB));
            return;
        }
        Player player = (Player) src;
        int cap = teamCapacity();
        if (cap < 1) {
            player.sendMessage(Component.text(plugin.lang.get("team_not_enough_servers")));
            return;
        }
        long count = plugin.playerTeam.values().stream().filter(teamArg::equals).count();
        if (!teamArg.equals(plugin.playerTeam.get(player.getUniqueId()))
                && cap > 0
                && count >= cap) {
            String dn = "a".equals(teamArg) ? plugin.teamNameA : plugin.teamNameB;
            player.sendMessage(
                    Component.text(
                            plugin.lang.get(
                                    "team_full",
                                    "team",
                                    dn,
                                    "count",
                                    String.valueOf(cap),
                                    "max",
                                    String.valueOf(cap))));
            return;
        }
        plugin.playerTeam.put(player.getUniqueId(), teamArg);
        String dn = "a".equals(teamArg) ? plugin.teamNameA : plugin.teamNameB;
        player.sendMessage(Component.text(plugin.lang.get("team_joined", "team", dn)));
    }

    void cmdSetTeamName(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (args.length < 2) {
            src.sendMessage(Component.text("§eUsage: /ms setteamname <a|b> <name>"));
            src.sendMessage(
                    Component.text("§7Current: A=" + plugin.teamNameA + " B=" + plugin.teamNameB));
            return;
        }
        String which = args[0].toLowerCase();
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if ("a".equals(which)) {
            plugin.teamNameA = name;
            src.sendMessage(Component.text("§aTeam A name set to: §e" + plugin.teamNameA));
        } else if ("b".equals(which)) {
            plugin.teamNameB = name;
            src.sendMessage(Component.text("§aTeam B name set to: §e" + plugin.teamNameB));
        } else {
            src.sendMessage(Component.text("§cTeam must be 'a' or 'b'"));
        }
    }

    void cmdSetVersus(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (args.length != 1
                || (!args[0].equalsIgnoreCase("true") && !args[0].equalsIgnoreCase("false"))) {
            src.sendMessage(Component.text("§eUsage: /ms setversus <true|false>"));
            src.sendMessage(Component.text("§7Current: versus=" + plugin.versusMode));
            return;
        }
        boolean enable = args[0].equalsIgnoreCase("true");
        if (enable) {
            plugin.detectServers();
            if (plugin.gameServers.size() % 2 != 0) {
                src.sendMessage(
                        Component.text(
                                "§cCannot enable versus: odd number of game servers ("
                                        + plugin.gameServers.size()
                                        + "). Need an even number."));
                return;
            }
        }
        plugin.versusMode = enable;
        src.sendMessage(
                Component.text(
                        "§aVersus mode "
                                + (plugin.versusMode ? "§2enabled" : "§7disabled")
                                + "§a."));
    }

    void cmdState(CommandSource src) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        src.sendMessage(Component.text("§e=== MCSRSWAP State ==="));
        src.sendMessage(Component.text("§7Game State: §f" + plugin.gameState));
        src.sendMessage(Component.text("§7Docker Mode: §f" + plugin.dockerMode));
        src.sendMessage(Component.text("§7Game Servers: §f" + plugin.gameServers));
        src.sendMessage(Component.text("§7Players in game: §f" + plugin.playerServer.size()));
        src.sendMessage(Component.text("§7Spectators: §f" + plugin.spectators.size()));
        src.sendMessage(Component.text("§7Finished Servers: §f" + plugin.finishedServers));
        src.sendMessage(Component.text("§7Current Time: §f" + plugin.currentTime + "s"));
        src.sendMessage(Component.text("§7Rotation Time: §f" + plugin.rotationTime + "s"));
        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            src.sendMessage(
                    Component.text(
                            "§7Docker Containers: §f"
                                    + plugin.dockerManager.getServerContainers().size()));
        }
    }
}
