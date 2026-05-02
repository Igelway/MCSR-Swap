package de.mcsrswap;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        return src.hasPermission("swap.admin");        
    }

    private String validateStartConfiguration(List<Player> participants) {
        if (participants.isEmpty()) {
            return "§cNo players to start the game!";
        }

        if (!plugin.versusMode) {
            if (!plugin.dockerMode && participants.size() > plugin.gameServers.size()) {
                return "§cCannot start: there are more players than available game servers ("
                        + participants.size()
                        + "/"
                        + plugin.gameServers.size()
                        + ").";
            }
            return null;
        }

        List<Player> unassigned =
                participants.stream()
                        .filter(
                                p -> {
                                    String team = plugin.playerTeam.get(p.getUniqueId());
                                    return !"a".equals(team) && !"b".equals(team);
                                })
                        .collect(Collectors.toList());
        if (!unassigned.isEmpty()) {
            String names =
                    unassigned.stream().map(Player::getUsername).collect(Collectors.joining(", "));
            return "§cCannot start versus: these players are not assigned to a team: §e" + names;
        }

        long teamACount =
                participants.stream()
                        .filter(p -> "a".equals(plugin.playerTeam.get(p.getUniqueId())))
                        .count();
        long teamBCount =
                participants.stream()
                        .filter(p -> "b".equals(plugin.playerTeam.get(p.getUniqueId())))
                        .count();
        if (teamACount != teamBCount) {
            return "§cCannot start versus: teams are uneven ("
                    + plugin.teamNameA
                    + "="
                    + teamACount
                    + ", "
                    + plugin.teamNameB
                    + "="
                    + teamBCount
                    + ").";
        }

        if (!plugin.dockerMode) {
            if (plugin.gameServers.size() % 2 != 0) {
                return "§cCannot start versus: odd number of game servers ("
                        + plugin.gameServers.size()
                        + "). Need an even number.";
            }

            int serversPerTeam = plugin.gameServers.size() / 2;
            if (teamACount > serversPerTeam) {
                return "§cCannot start versus: "
                        + plugin.teamNameA
                        + " has "
                        + teamACount
                        + " players but only "
                        + serversPerTeam
                        + " server slots.";
            }
            if (teamBCount > serversPerTeam) {
                return "§cCannot start versus: "
                        + plugin.teamNameB
                        + " has "
                        + teamBCount
                        + " players but only "
                        + serversPerTeam
                        + " server slots.";
            }
        }

        return null;
    }

    void sendHelp(CommandSource src) {
        src.sendMessage(Component.text("§eWorldSwap commands:"));
        if (isAdmin(src)) {
            boolean docker = plugin.dockerMode;
            src.sendMessage(Component.text("§7/ms start [--clean] §8| §7/ms stop §8| §7/ms forceswap"));
            src.sendMessage(Component.text("§7/ms setrotation <s>"));
            src.sendMessage(Component.text("§7/ms setteam <a|b|none> <player...> §8"));
            src.sendMessage(Component.text("§7/ms setteamname <a|b> <name>"));
            src.sendMessage(Component.text("§7/ms setversus <true|false> §8| §7/ms state §8| §7/ms player"));
            if (docker) {
                src.sendMessage(Component.text("§7/ms prepare §8- §7Pre-generate servers, then ready check"));
                src.sendMessage(Component.text("§7/ms cleanup §8- §7Stop Docker containers"));
                src.sendMessage(Component.text("§7/ms seed [<i> [<val>|clear] | <s1,s2,...>]"));
            }
        }
        src.sendMessage(Component.text("§7/ms jointeam <a|b>"));
        src.sendMessage(Component.text("§7/ms ready §8- §7Confirm readiness during ready check"));
        src.sendMessage(Component.text("§7/ms ignore [player] §8- §7Opt out of the next game start"));
    }

    void cmdStart(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        // Admin override during ready check: force-start immediately.
        if (plugin.gameState == GameState.READY_CHECK) {
            plugin.forceStartFromReadyCheck();
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
        if (plugin.gameState == GameState.PREPARING) {
            src.sendMessage(
                    Component.text("§cServers are being prepared. Wait or use §e/ms cleanup§c."));
            return;
        }

        boolean force =
                Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--clean"));

        if (force && plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            src.sendMessage(Component.text("§7Cleaning up old containers and volumes..."));
            cmdCleanup(src, args);
        }

        plugin.startedWithClean = force;

        cmdStartInternal(src, args);
    }

    private void cmdStartInternal(CommandSource src, String[] args) {
        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            List<Player> participants = plugin.getStartParticipants();
            String validationError = validateStartConfiguration(participants);
            if (validationError != null) {
                src.sendMessage(Component.text(validationError));
                return;
            }

            int serverCount = participants.size();

            // Mark game as starting to prevent double-start
            plugin.gameState = GameState.STARTING;

            // Start servers and wait for them to become healthy
            src.sendMessage(Component.text("§7Starting " + serverCount + " Docker containers…"));
            plugin.dockerManager
                    .startServersAsync(serverCount)
                    .thenAccept(
                            startedServers -> {
                                if (plugin.gameState != GameState.STARTING) {
                                    // Startup was cancelled via /ms stop
                                    return;
                                }
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

        List<Player> participants = plugin.getStartParticipants();
        String validationError = validateStartConfiguration(participants);
        if (validationError != null) {
            src.sendMessage(Component.text(validationError));
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
        if (plugin.gameState == GameState.STARTING
                || plugin.gameState == GameState.PREPARING) {
            plugin.gameState = GameState.LOBBY;
            src.sendMessage(Component.text("§7Cancelling startup…"));
            if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
                boolean removeData = plugin.startedWithClean;
                plugin.server
                        .getScheduler()
                        .buildTask(
                                plugin,
                                () -> {
                                    try {
                                        plugin.dockerManager.stopAllServers();
                                        if (removeData) plugin.dockerManager.removeAllData();
                                    } catch (Exception e) {
                                        plugin.getLogger()
                                                .error("Error during startup cancellation", e);
                                    }
                                })
                        .schedule();
            }
            src.sendMessage(
                    Component.text(
                            plugin.startedWithClean
                                    ? "§aStartup cancelled. Containers and volumes removed."
                                    : "§aStartup cancelled. Use §e/ms cleanup§a to also remove"
                                            + " containers and volumes."));
            plugin.startedWithClean = false;
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
        // Cancel any in-progress prepare/ready-check before cleaning up.
        if (plugin.gameState == GameState.PREPARING
                || plugin.gameState == GameState.READY_CHECK) {
            plugin.cancelPrepare();
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

    /**
     * /ms prepare — pre-generates game servers and then asks all participants if they are ready.
     * Requires admin permission and LOBBY state.
     */
    void cmdPrepare(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (plugin.gameState != GameState.LOBBY) {
            src.sendMessage(
                    Component.text(
                            "§c/ms prepare can only be used from the lobby. Current state: "
                                    + plugin.gameState));
            return;
        }

        List<Player> participants = plugin.getStartParticipants();
        String validationError = validateStartConfiguration(participants);
        if (validationError != null) {
            src.sendMessage(Component.text(validationError));
            return;
        }

        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            // Docker mode: cleanup any old containers first, then spawn fresh ones.
            src.sendMessage(Component.text("§7Cleaning up old containers and volumes..."));
            try {
                plugin.dockerManager.stopAllServers();
                plugin.dockerManager.removeAllData();
            } catch (Exception e) {
                plugin.getLogger().warn("Cleanup before prepare failed (continuing)", e);
            }

            int serverCount = participants.size();
            plugin.gameState = GameState.PREPARING;
            src.sendMessage(
                    Component.text("§7Starting " + serverCount + " Docker container(s) for pre-generation…"));
            plugin.dockerManager
                    .startServersAsync(serverCount)
                    .thenAccept(
                            startedServers -> {
                                if (plugin.gameState != GameState.PREPARING) return;
                                if (startedServers.isEmpty()) {
                                    src.sendMessage(
                                            Component.text("§cFailed to start Docker containers!"));
                                    plugin.gameState = GameState.LOBBY;
                                    return;
                                }
                                plugin.gameServers = startedServers;
                                src.sendMessage(
                                        Component.text(
                                                "§aAll servers ready. Waiting for players to confirm…"));
                                plugin.enterReadyCheck();
                            })
                    .exceptionally(
                            ex -> {
                                src.sendMessage(
                                        Component.text(
                                                "§cError starting containers: "
                                                        + ex.getMessage()));
                                plugin.gameState = GameState.LOBBY;
                                return null;
                            });
        } else {
            // Manual mode: detect servers and ping them, then enter ready check.
            plugin.detectServers();
            if (plugin.gameServers.isEmpty()) {
                src.sendMessage(
                        Component.text(
                                "§cNo game servers found (no server names starting with '"
                                        + plugin.gameServerPrefix
                                        + "')."));
                return;
            }

            src.sendMessage(Component.text("§7Checking game servers…"));
            List<java.util.concurrent.CompletableFuture<Void>> pings = new java.util.ArrayList<>();
            List<String> unreachable = new java.util.ArrayList<>();
            for (String name : plugin.gameServers) {
                plugin.server
                        .getServer(name)
                        .ifPresent(
                                rs -> {
                                    pings.add(
                                            rs.ping()
                                                    .thenAccept(p -> {})
                                                    .exceptionally(
                                                            ex -> {
                                                                synchronized (unreachable) {
                                                                    unreachable.add(name);
                                                                }
                                                                return null;
                                                            }));
                                });
            }
            java.util.concurrent.CompletableFuture.allOf(
                            pings.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .thenRun(
                            () -> {
                                if (!unreachable.isEmpty()) {
                                    src.sendMessage(
                                            Component.text(
                                                    "§cCannot prepare: the following servers are not"
                                                            + " reachable:"));
                                    for (String name : unreachable)
                                        src.sendMessage(Component.text("§c  • " + name));
                                } else {
                                    src.sendMessage(
                                            Component.text(
                                                    "§aAll servers reachable. Waiting for players"
                                                            + " to confirm…"));
                                    plugin.enterReadyCheck();
                                }
                            });
        }
    }

    /** /ms ready — player confirms readiness during a READY_CHECK phase. */
    void cmdReady(CommandSource src, String[] args) {
        if (!(src instanceof Player)) {
            src.sendMessage(Component.text("§cThis command can only be used by players."));
            return;
        }
        if (plugin.gameState != GameState.READY_CHECK) {
            src.sendMessage(Component.text("§cNo ready check is in progress."));
            return;
        }
        plugin.confirmReady((Player) src);
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
        if (plugin.gameState != GameState.LOBBY) {
            src.sendMessage(Component.text("§cTeam assignment is only possible in the lobby."));
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
        plugin.playerTeam.put(player.getUniqueId(), teamArg);
        String dn = "a".equals(teamArg) ? plugin.teamNameA : plugin.teamNameB;
        player.sendMessage(Component.text(plugin.lang.get("team_joined", "team", dn)));
    }

    void cmdIgnore(CommandSource src, String[] args) {
        if (plugin.gameState != GameState.LOBBY) {
            src.sendMessage(Component.text("§c/ms ignore is only available in the lobby."));
            return;
        }

        Player target;
        if (args.length == 0) {
            // Self-toggle: any player can use this
            if (!(src instanceof Player)) {
                src.sendMessage(Component.text("§eUsage: /ms ignore <player>"));
                return;
            }
            target = (Player) src;
        } else {
            // Target another player: admin only
            if (!isAdmin(src)) {
                src.sendMessage(Component.text("§cNo permission!"));
                return;
            }
            String name = args[0];
            java.util.Optional<Player> found = plugin.server.getPlayer(name);
            if (!found.isPresent()) {
                src.sendMessage(Component.text("§cPlayer not found: " + name));
                return;
            }
            target = found.get();
        }

        UUID uuid = target.getUniqueId();
        boolean nowIgnored = !plugin.ignoredPlayers.contains(uuid);
        if (nowIgnored) {
            plugin.ignoredPlayers.add(uuid);
            target.sendMessage(Component.text("§7You are §enot§7 included in the next game start."));
            if (src != target) {
                src.sendMessage(Component.text("§7" + target.getUsername() + " will be §eskipped§7 at the next game start."));
            }
        } else {
            plugin.ignoredPlayers.remove(uuid);
            target.sendMessage(Component.text("§aYou are now included in the next game start."));
            if (src != target) {
                src.sendMessage(Component.text("§a" + target.getUsername() + " is now included in the next game start."));
            }
        }
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

    void cmdPlayer(CommandSource src) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        src.sendMessage(Component.text("§e=== Player → Server Assignment ==="));

        if (plugin.activePlayers.isEmpty()) {
            src.sendMessage(Component.text("§7No players in game."));
        } else {
            for (Map.Entry<UUID, String> entry :
                    new java.util.TreeMap<>(plugin.playerServer).entrySet()) {
                UUID uuid = entry.getKey();
                if (!plugin.activePlayers.contains(uuid)) continue;
                String logicalServer = entry.getValue();

                String name =
                        plugin.server
                                .getPlayer(uuid)
                                .map(com.velocitypowered.api.proxy.Player::getUsername)
                                .orElse(uuid.toString().substring(0, 8) + "…");

                String teamPart = "";
                if (plugin.versusMode) {
                    String t = plugin.playerTeam.get(uuid);
                    if ("a".equals(t)) {
                        teamPart = " §a[" + plugin.teamNameA + "]";
                    } else if ("b".equals(t)) {
                        teamPart = " §b[" + plugin.teamNameB + "]";
                    }
                }

                String statusPart = "";
                if (plugin.watchingPlayers.containsKey(uuid)) {
                    statusPart = " §7(watching)";
                } else if (plugin.finishedServers.contains(logicalServer)) {
                    statusPart = " §a(finished)";
                }

                src.sendMessage(
                        Component.text(
                                "§f"
                                        + name
                                        + teamPart
                                        + " §8→ §e"
                                        + logicalServer
                                        + statusPart));
            }

        }

        // Show lobby players and their ignored status
        List<Player> lobbyPlayers = plugin.getLobbyPlayers();
        if (!lobbyPlayers.isEmpty()) {
            src.sendMessage(Component.text("§e=== Lobby ==="));
            for (Player p : lobbyPlayers) {
                String ignoredPart = plugin.isIgnored(p) ? " §c(ignored)" : "";
                src.sendMessage(Component.text("§7" + p.getUsername() + ignoredPart));
            }
        }
    }

    void cmdSeed(CommandSource src, String[] args) {
        if (!isAdmin(src)) {
            src.sendMessage(Component.text("§cNo permission!"));
            return;
        }
        if (!plugin.dockerMode) {
            src.sendMessage(Component.text("§cThis command is only available in Docker mode."));
            return;
        }

        // /ms seed  → list all configured seeds
        if (args.length == 0) {
            List<Long> seeds = plugin.worldSeeds;
            int slots = Math.max(seeds.size(), plugin.gameServers.size());
            if (slots == 0) {
                src.sendMessage(Component.text("§7No seeds configured and no game servers detected."));
            } else {
                src.sendMessage(Component.text("§e=== Seeds ==="));
                for (int i = 0; i < slots; i++) {
                    Long s = i < seeds.size() ? seeds.get(i) : null;
                    src.sendMessage(
                            Component.text(
                                    "§7  Game "
                                            + (i + 1)
                                            + ": "
                                            + (s != null ? "§f" + s : "§7(random)")));
                }
            }
            return;
        }

        // /ms seed clear  → clear all seeds
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            if (plugin.gameState != GameState.LOBBY) {
                src.sendMessage(Component.text("§cSeeds can only be changed while in the lobby."));
                return;
            }
            plugin.worldSeeds.clear();
            src.sendMessage(Component.text("§aAll seeds cleared (all games will use random seeds)."));
            return;
        }

        // /ms seed <s1,s2,...>  → replace the whole seed list at once
        if (args.length == 1 && args[0].contains(",")) {
            if (plugin.gameState != GameState.LOBBY) {
                src.sendMessage(Component.text("§cSeeds can only be changed while in the lobby."));
                return;
            }
            String[] parts = args[0].split(",", -1);
            List<Long> newSeeds = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty() || part.equalsIgnoreCase("random")) {
                    newSeeds.add(null);
                } else {
                    try {
                        newSeeds.add(Long.parseLong(part));
                    } catch (NumberFormatException e) {
                        src.sendMessage(
                                Component.text(
                                        "§cInvalid seed at position "
                                                + (i + 1)
                                                + ": \""
                                                + part
                                                + "\" (use a number or leave empty for random)."));
                        return;
                    }
                }
            }
            trimTrailingNulls(newSeeds);
            plugin.worldSeeds.clear();
            plugin.worldSeeds.addAll(newSeeds);
            src.sendMessage(Component.text("§a" + newSeeds.size() + " seed(s) set."));
            return;
        }

        int index;
        try {
            index = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            src.sendMessage(Component.text("§cUsage: /ms seed [<index> [<seed>|clear]]"));
            return;
        }
        if (index < 1) {
            src.sendMessage(Component.text("§cIndex must be ≥ 1."));
            return;
        }

        // /ms seed <index>  → get
        if (args.length == 1) {
            List<Long> seeds = plugin.worldSeeds;
            Long s = index <= seeds.size() ? seeds.get(index - 1) : null;
            src.sendMessage(
                    Component.text(
                            "§7Game "
                                    + index
                                    + ": "
                                    + (s != null ? "§f" + s : "§7(random – no seed configured)")));
            return;
        }

        // /ms seed <index> clear  → remove seed (use random)
        if (args[1].equalsIgnoreCase("clear")) {
            if (plugin.gameState != GameState.LOBBY) {
                src.sendMessage(Component.text("§cSeeds can only be changed while in the lobby."));
                return;
            }
            List<Long> seeds = plugin.worldSeeds;
            if (index <= seeds.size()) {
                seeds.set(index - 1, null);
                trimTrailingNulls(seeds);
            }
            src.sendMessage(
                    Component.text("§aGame " + index + " seed cleared (will use random)."));
            return;
        }

        // /ms seed <index> <seed>  → set seed
        if (plugin.gameState != GameState.LOBBY) {
            src.sendMessage(Component.text("§cSeeds can only be changed while in the lobby."));
            return;
        }
        long seed;
        try {
            seed = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            src.sendMessage(
                    Component.text("§cInvalid seed value (must be a long or \"clear\")."));
            return;
        }
        List<Long> seeds = plugin.worldSeeds;
        while (seeds.size() < index) {
            seeds.add(null);
        }
        seeds.set(index - 1, seed);
        src.sendMessage(Component.text("§aGame " + index + " seed set to §f" + seed + "§a."));
    }

    private static void trimTrailingNulls(List<Long> list) {
        while (!list.isEmpty() && list.get(list.size() - 1) == null) {
            list.remove(list.size() - 1);
        }
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
        src.sendMessage(Component.text("§7Active Players: §f" + plugin.activePlayers.size()));
        src.sendMessage(Component.text("§7Players in game: §f" + plugin.getGameParticipants().size()));
        src.sendMessage(Component.text("§7Watching Players: §f" + plugin.watchingPlayers.size()));
        src.sendMessage(Component.text("§7Finished Servers: §f" + plugin.finishedServers));
        src.sendMessage(Component.text("§7Current Time: §f" + plugin.currentTime + "s"));
        src.sendMessage(Component.text("§7Rotation Time: §f" + plugin.rotationTime + "s"));
        if (plugin.gameState == GameState.READY_CHECK) {
            src.sendMessage(
                    Component.text(
                            "§7Ready: §f"
                                    + plugin.readyConfirmed.size()
                                    + "/"
                                    + plugin.readyCheckParticipants.size()));
        }
        if (plugin.dockerManager != null && plugin.dockerManager.isDockerEnabled()) {
            src.sendMessage(
                    Component.text(
                            "§7Docker Containers: §f"
                                    + plugin.dockerManager.getServerContainers().size()));
        }
    }
}
