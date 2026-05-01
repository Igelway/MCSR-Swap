package de.mcsrswap;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

public class VelocitySwapPlugin {

    final ProxyServer server;
    final Path dataDirectory;
    private final Logger logger;

    // =========================
    // SETTINGS (Config)
    // =========================

    int rotationTime = 120;
    double requiredPercentage = 1.0;
    boolean versusMode = false;
    String gameServerPrefix = "game";
    String lobbyServerName = "lobby";
    String limboServerName = "limbo";
    final VelocityLang lang = new VelocityLang();
    final Set<String> adminPlayers = new HashSet<>();

    int currentTime;
    GameState gameState = GameState.LOBBY;

    final Map<UUID, String> playerServer = new HashMap<>();
    final Set<String> finishedServers = new HashSet<>();

    /** Players currently participating in the active round, including temporary watchers. */
    final Set<UUID> activePlayers = new HashSet<>();

    /**
     * Players who have opted out of the next game start. Populated from config/env at load time
     * and toggled at runtime via {@code /ms ignore}. Persists until toggled back.
     */
    final Set<UUID> ignoredPlayers = new HashSet<>();

    /**
     * Config-based ignore entries (names or UUID strings). Checked alongside {@code ignoredPlayers}
     * so that offline players listed in config are also excluded.
     */
    final Set<String> configIgnoredPlayers = new HashSet<>();

    /** Versus: team assignment "a" or "b" per player (persists across game starts). */
    final Map<UUID, String> playerTeam = new HashMap<>();

    /** Versus: server lists per team (populated in startGame()). */
    List<String> teamAServers = new ArrayList<>();

    List<String> teamBServers = new ArrayList<>();
    final Set<String> finishedServersA = new HashSet<>();
    final Set<String> finishedServersB = new HashSet<>();

    /** Versus: Display names for each team. */
    String teamNameA = "A";

    String teamNameB = "B";

    boolean spectateAfterWin = false;
    String spectateTarget = "next"; // "next" or "prev" – which adjacent server to watch
    boolean saveHotbar = true; // rearrange new player's hotbar to match predecessor's hotbar layout
    int eyeHoverTicks = 80; // lifetime of a thrown Eye of Ender in ticks

    /**
     * Players currently watching another server after finishing their own world. Maps player UUID →
     * their logical server assignment (their position in the rotation).
     */
    final Map<UUID, String> watchingPlayers = new HashMap<>();

    final Set<UUID> pendingReset = new HashSet<>();

    /**
     * Players who reconnected mid-game and are currently in the lobby waiting to be forwarded to
     * their assigned game server. Set by {@link #onChooseInitialServer}; consumed by {@link
     * #onServerConnected}.
     */
    private final Set<UUID> pendingReconnect = new HashSet<>();

    /** Servers that have sent a "ready" signal after startup. */
    final Set<String> readyServers = new HashSet<>();

    public Logger getLogger() {
        return logger;
    }

    List<String> gameServers = new ArrayList<>();

    final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("mcsrswap:main");

    final WorldSwapCommands commands = new WorldSwapCommands(this);
    DockerServerManager dockerManager;
    boolean dockerMode = false;
    boolean startedWithClean = false;
    private PluginConfig config;
    /** Mutable per-slot seed overrides (0-based). Extends/shrinks as needed via /ms seed. */
    List<Long> worldSeeds = new ArrayList<>();

    @Inject
    public VelocitySwapPlugin(
            ProxyServer server, @DataDirectory Path dataDirectory, Logger logger) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {

        loadConfig();
        setupAdminPermissions();

        // Add shutdown hook to cleanup game servers
        if (dockerMode) {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        logger.info("Shutting down - cleaning up game servers...");
                                        try {
                                            dockerManager.stopAllServers();
                                        } catch (Exception e) {
                                            logger.error("Error during shutdown cleanup", e);
                                        }
                                    }));
            // Pull latest image in background during startup (skip if MCSRSWAP_PULL_GAME_IMAGE=false)
            String pullEnv = System.getenv("MCSRSWAP_PULL_GAME_IMAGE");
            boolean pullImage = pullEnv == null || (!pullEnv.equalsIgnoreCase("false") && !pullEnv.equals("0"));
            if (pullImage) {
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            try {
                                dockerManager.pullImage();
                            } catch (Exception e) {
                                logger.warn("Background image pull failed: {}", e.getMessage());
                            }
                        });
            } else {
                logger.info("Image pull skipped (MCSRSWAP_PULL_GAME_IMAGE=false).");
            }
        }

        server.getChannelRegistrar().register(CHANNEL);

        detectServers();
        registerCommands();
        startTimer();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down...");
        if (dockerManager != null) {
            dockerManager.shutdown();
        }
    }

    // =========================
    // CONFIG
    // =========================

    private void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configFile = dataDirectory.resolve("config.yml");

            // Ensure config directory exists
            Files.createDirectories(configFile.getParent());

            if (!Files.exists(configFile)) {
                String defaultConfig =
                        "rotationTime: 120\n"
                                + "requiredPercentage: 1.0\n"
                                + "versus: false\n"
                                + "language: en_US\n"
                                + "gameServerPrefix: game\n"
                                + "lobbyServerName: lobby\n"
                                + "spectateAfterWin: false\n"
                                + "spectateTarget: next\n"
                                + "saveHotbar: true\n"
                                + "eyeHoverTicks: 80\n"
                                + "admins:\n"
                                + "  # List of admin players (username or UUID)\n"
                                + "  # - \"YourMinecraftName\"\n"
                                + "  # - \"UUID-HERE\"\n"
                                + "docker:\n"
                                + "  enabled: false\n"
                                + "  image: ghcr.io/igelway/mcsr-swap-gameserver:latest\n"
                                + "  network: mcsrswap-network\n"
                                + "  dataPath: ./data\n"
                                + " ./data/game{N}), "
                                + " (~/.local/share/mcsrswap/servers)\n";

                Files.writeString(configFile, defaultConfig);
            }

            Yaml yaml = new Yaml();
            Map<String, Object> rawConfig;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> loaded = yaml.load(Files.newInputStream(configFile));
                rawConfig = loaded != null ? loaded : new java.util.HashMap<>();
            } catch (Exception e) {
                logger.error(
                        "Failed to parse config.yml – using defaults. Fix the file and reload."
                                + " Error: {}",
                        e.getMessage());
                rawConfig = new java.util.HashMap<>();
            }

            // Build typed config and use it as primary source for all known fields
            PluginConfig cfg = PluginConfig.fromMap(rawConfig);
            this.config = cfg; // Store the config

            rotationTime = cfg.rotationTime;
            requiredPercentage = cfg.requiredPercentage;
            versusMode = cfg.versus;

            // use typed config values
            gameServerPrefix = cfg.gameServerPrefix;
            lobbyServerName = cfg.lobbyServerName;
            limboServerName = cfg.limboServerName;
            spectateAfterWin = cfg.spectateAfterWin;
            spectateTarget = cfg.spectateTarget;
            saveHotbar = cfg.saveHotbar;
            eyeHoverTicks = cfg.eyeHoverTicks;
            worldSeeds = new ArrayList<>(cfg.worldSeeds);

            // language from typed config (normalize to filename)
            String languageFile =
                    cfg.language.endsWith(".yml")
                            ? cfg.language
                            : cfg.language.toLowerCase() + ".yml";
            lang.load(dataDirectory, languageFile);

            // Allow overriding the lobby server via environment variable (set in docker-compose)
            String envLobby = System.getenv("MCSRSWAP_LOBBY_ADDRESS");
            if (envLobby != null && !envLobby.isBlank()) {
                lobbyServerName = envLobby;
                logger.info("Overriding lobby server name from env: {}", lobbyServerName);
            }

            // Register limbo with Velocity if not already present in velocity.toml.
            // In Docker mode the limbo hostname equals limboServerName (Docker DNS) on port 25565.
            if (server.getServer(limboServerName).isEmpty()) {
                String limboHost = limboServerName;
                int limboPort = 25565;
                try {
                    java.net.InetSocketAddress limboAddr =
                            new java.net.InetSocketAddress(limboHost, limboPort);
                    server.registerServer(new ServerInfo(limboServerName, limboAddr));
                    logger.info(
                            "Registered limbo server '{}' at {}:{}",
                            limboServerName,
                            limboHost,
                            limboPort);
                } catch (Exception e) {
                    logger.warn("Could not register limbo server '{}': {}", limboServerName, e.getMessage());
                }
            }

            // Load admin list from typed config
            adminPlayers.clear();
            if (!cfg.admins.isEmpty()) {
                adminPlayers.addAll(cfg.admins);
            }
            logger.info("Loaded {} admin(s): {}", adminPlayers.size(), adminPlayers);

            // Load ignored-players list from config
            configIgnoredPlayers.clear();
            configIgnoredPlayers.addAll(cfg.ignorePlayers);
            if (!configIgnoredPlayers.isEmpty()) {
                logger.info("Loaded {} ignored player(s) from config", configIgnoredPlayers.size());
            }

            // Docker config: allow env overrides for image/mode
            PluginConfig.Docker dockerCfg = cfg.docker;
            String envDockerMode = System.getenv("MCSRSWAP_DOCKER_MODE");
            String envImage = System.getenv("MCSRSWAP_GAMESERVER_IMAGE");
            boolean enabled =
                    dockerCfg.enabled
                            || (envDockerMode != null && envDockerMode.equalsIgnoreCase("true"));
            String image = (envImage != null && !envImage.isBlank()) ? envImage : dockerCfg.image;

            if (enabled) {
                dockerMode = true;
                dockerManager = new DockerServerManager(server, logger, this);
                Map<String, Object> dockerInit = new HashMap<>();
                dockerInit.put("enabled", true);
                dockerInit.put("image", image);
                dockerInit.put("network", dockerCfg.network);
                dockerManager.initialize(dockerInit);
            }

            logger.info(
                    "Config loaded: rotation={}, percent={}, versus={}, language={}, gamePrefix={},"
                            + " lobby={}, spectateAfterWin={}, spectateTarget={},"
                            + " saveHotbar={}, eyeHoverTicks={}",
                    rotationTime,
                    requiredPercentage,
                    versusMode,
                    languageFile,
                    gameServerPrefix,
                    lobbyServerName,
                    spectateAfterWin,
                    spectateTarget,
                    saveHotbar,
                    eyeHoverTicks);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================
    // ADMIN PERMISSIONS
    // =========================

    void setupAdminPermissions() {
        if (adminPlayers.isEmpty()) {
            logger.info("No admins configured in config.yml");
            return;
        }

        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            try {
                                var luckPerms = server.getPluginManager().getPlugin("luckperms");
                                if (luckPerms.isEmpty()) {
                                    logger.warn(
                                            "LuckPerms not loaded - admin permissions not set!");
                                    return;
                                }

                                net.luckperms.api.LuckPerms api =
                                        net.luckperms.api.LuckPermsProvider.get();

                                for (String playerName : adminPlayers) {
                                    try {
                                        UUID uuid;
                                        try {
                                            uuid = UUID.fromString(playerName);
                                        } catch (IllegalArgumentException e) {
                                            uuid =
                                                    api.getUserManager()
                                                            .lookupUniqueId(playerName)
                                                            .get();
                                        }
                                        if (uuid == null) {
                                            logger.warn(
                                                    "Could not find UUID for admin: {}",
                                                    playerName);
                                            continue;
                                        }

                                        var user = api.getUserManager().loadUser(uuid).get();
                                        if (user == null) {
                                            logger.warn(
                                                    "Could not load user data for admin: {}",
                                                    playerName);
                                            continue;
                                        }

                                        user.data()
                                                .add(
                                                        net.luckperms.api.node.Node.builder(
                                                                        "swap.admin")
                                                                .build());
                                        api.getUserManager().saveUser(user);
                                        logger.info(
                                                "Granted swap.admin permission to: {} ({})",
                                                playerName,
                                                uuid);

                                    } catch (Exception e) {
                                        logger.error(
                                                "Failed to set admin permissions for {}: {}",
                                                playerName,
                                                e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.error(
                                        "Failed to setup admin permissions: {}", e.getMessage());
                            }
                        })
                .delay(2, TimeUnit.SECONDS)
                .schedule();
    }

    // =========================
    // SERVER DETECTION
    // =========================

    void detectServers() {
        if (dockerManager != null && dockerManager.isDockerEnabled()) {
            gameServers = dockerManager.getRunningServers();
        } else {
            gameServers.clear();
            for (RegisteredServer rs : server.getAllServers()) {
                String name = rs.getServerInfo().getName();
                if (name.startsWith(gameServerPrefix)) {
                    gameServers.add(name);
                }
            }
        }

        gameServers.sort(Comparator.naturalOrder());

        logger.info("Game servers: {}", gameServers);
    }

    // =========================
    // TIMER
    // =========================

    private void startTimer() {
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            if (gameState != GameState.RUNNING) return;

                            currentTime--;

                            if (currentTime <= 0) {
                                // Show 0 on the old server to complete the visual countdown,
                                // then trigger rotation. The new countdown is sent to the new
                                // server via sendTimeToPlayer() in ServerConnectedEvent.
                                currentTime = 0;
                                broadcastTime();
                                rotatePlayers();
                                currentTime = rotationTime;
                                return;
                            } else if (currentTime == 2
                                    && spectateAfterWin
                                    && !watchingPlayers.isEmpty()) {
                                preRotation();
                            }

                            broadcastTime();
                        })
                .delay(300, TimeUnit.MILLISECONDS)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    // =========================
    // ROTATION
    // =========================

    private void rotatePlayers() {

        // Advance each player's logical server assignment.
        // Route to lobby first via connect() and, in the completion callback, immediately route
        // to the next game server. The vanilla disconnect-save fires synchronously when leaving the
        // game server, so the slot .dat is guaranteed to be current by the time the callback runs.
        for (Player player : server.getAllPlayers()) {
            UUID uuid = player.getUniqueId();
            if (!activePlayers.contains(uuid)) continue;
            if (!playerServer.containsKey(uuid)) continue;

            List<String> servers = getTeamServers(uuid);
            String current = playerServer.get(uuid);
            int index = servers.indexOf(current);
            if (index < 0) index = 0;
            index = (index + 1) % servers.size();
            String next = servers.get(index);
            playerServer.put(uuid, next);

            watchingPlayers.remove(uuid);

            // Player is mid-reconnect (currently in lobby or en-route): advance their assignment
            // but skip the lobby-connection request. forwardFromLobby() will be called from
            // onServerConnected when they land on the lobby.
            if (pendingReconnect.contains(uuid)) {
                continue;
            }

            final String nextServer = next;
            server.getServer(getTransitServer())
                    .ifPresent(
                            lobby ->
                                    player.createConnectionRequest(lobby)
                                            .connect()
                                            .thenAcceptAsync(
                                                    result -> {
                                                        if (!result.isSuccessful()) {
                                                            logger.warn(
                                                                    "Failed to route {} to transit"
                                                                            + " server during"
                                                                            + " rotation",
                                                                    player.getUsername());
                                                            return;
                                                        }
                                                        // Player arrived at transit server; slot .dat was
                                                        // written on game-server disconnect.
                                                        // Now forward to next game server.
                                                        forwardFromLobby(player, nextServer);
                                                    },
                                                    runnable ->
                                                            server.getScheduler()
                                                                    .buildTask(this, runnable)
                                                                    .schedule()));
        }

        logger.info("Swapping!");
    }

    private void forwardFromLobby(Player player, String nextServer) {
        if (spectateAfterWin
                && getTeamFinished(player.getUniqueId()).contains(nextServer)) {
            String spectateServer =
                    findSpectateTarget(player.getUniqueId(), nextServer);
            if (spectateServer != null) {
                watchingPlayers.put(player.getUniqueId(), nextServer);
                server.getServer(spectateServer)
                        .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
                return;
            }
        }
        server.getServer(nextServer)
                .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
    }

    /**
     * Called 2 seconds before rotation. Routes every watching player back to their own assigned
     * server so they arrive as a normal survival player. Sends {@code prepare_return} first to
     * release the spectator camera lock on the Fabric side before the connection is switched.
     */
    private void preRotation() {
        for (Map.Entry<UUID, String> entry : new ArrayList<>(watchingPlayers.entrySet())) {
            UUID watcherUuid = entry.getKey();
            watchingPlayers.remove(watcherUuid);
            String home = playerServer.get(watcherUuid);
            if (home == null) continue;
            server.getPlayer(watcherUuid)
                    .ifPresent(
                            p -> {
                                // Release camera lock on the spectate server first.
                                sendToBackend(
                                        p,
                                        buildMessage(
                                                out -> {
                                                    out.writeUTF("prepare_return");
                                                    out.writeUTF(p.getUniqueId().toString());
                                                }));
                                // Short delay lets the client receive the camera-release packet
                                // before we disconnect them.
                                server.getServer(home)
                                        .ifPresent(
                                                s ->
                                                        server.getScheduler()
                                                                .buildTask(
                                                                        this,
                                                                        () ->
                                                                                p.createConnectionRequest(
                                                                                                s)
                                                                                        .fireAndForget())
                                                                .delay(300, TimeUnit.MILLISECONDS)
                                                                .schedule());
                            });
        }
    }

    /**
     * Returns the server the player should spectate given their current logical server. Prefers
     * servers that still have an active (non-finished) player. Returns null if no suitable server
     * is found.
     */
    private String findSpectateTarget(UUID uuid, String logicalServer) {
        List<String> servers = getTeamServers(uuid);
        int index = servers.indexOf(logicalServer);
        if (index < 0 || servers.size() <= 1) return null;

        int step = "prev".equals(spectateTarget) ? -1 : 1;
        int target = (index + step + servers.size()) % servers.size();
        if (target == index) return null;

        // Prefer a non-finished server with active players
        Set<String> teamFinished = getTeamFinished(uuid);
        for (int i = 1; i < servers.size(); i++) {
            int candidate = (index + step * i + servers.size() * servers.size()) % servers.size();
            String name = servers.get(candidate);
            if (!name.equals(logicalServer) && !teamFinished.contains(name)) {
                return name;
            }
        }
        // All other servers are finished – fall back to the adjacent one anyway
        return servers.get(target);
    }

    private Set<String> getTeamFinished(UUID uuid) {
        if (versusMode) {
            String team = playerTeam.get(uuid);
            if ("a".equals(team)) return finishedServersA;
            if ("b".equals(team)) return finishedServersB;
        }
        return finishedServers;
    }

    /** Returns the server list relevant for this player. */
    private List<String> getTeamServers(UUID uuid) {
        if (!versusMode) return gameServers;
        String team = playerTeam.get(uuid);
        if ("a".equals(team)) return teamAServers;
        if ("b".equals(team)) return teamBServers;
        return gameServers;
    }

    // =========================
    // BROADCAST
    // =========================

    /** Returns all players currently assigned to a game server (active game participants). */
    List<Player> getGameParticipants() {
        return server.getAllPlayers().stream()
                .filter(p -> activePlayers.contains(p.getUniqueId()))
                .filter(p -> playerServer.containsKey(p.getUniqueId()))
                .collect(Collectors.toList());
    }

    /** Returns all players currently on the lobby server. */
    List<Player> getLobbyPlayers() {
        return server.getAllPlayers().stream()
                .filter(
                        p ->
                                p.getCurrentServer()
                                        .map(
                                                cs ->
                                                        cs.getServerInfo()
                                                                .getName()
                                                                .equals(lobbyServerName))
                                        .orElse(false))
                .collect(Collectors.toList());
    }

    /**
     * Returns the transit server used to buffer players between game servers during rotation.
     * Uses the limbo server if it is registered with Velocity; falls back to the lobby.
     */
    private String getTransitServer() {
        if (server.getServer(limboServerName).isPresent()) {
            return limboServerName;
        }
        return lobbyServerName;
    }

    /** Returns lobby players who should participate in the next game start. */
    List<Player> getStartParticipants() {
        return getLobbyPlayers().stream()
                .filter(p -> !isIgnored(p))
                .collect(Collectors.toList());
    }

    /**
     * Returns true if a player is currently opted out of the next game start, either via a runtime
     * {@code /ms ignore} toggle or via the {@code ignorePlayers} config entry.
     */
    boolean isIgnored(Player player) {
        if (ignoredPlayers.contains(player.getUniqueId())) return true;
        String name = player.getUsername();
        String uuid = player.getUniqueId().toString();
        return configIgnoredPlayers.contains(name) || configIgnoredPlayers.contains(uuid);
    }

    private void sendToBackend(Player player, byte[] data) {
        player.getCurrentServer()
                .ifPresent(conn -> conn.sendPluginMessage(CHANNEL, data));
    }

    private byte[] buildMessage(java.util.function.Consumer<ByteArrayDataOutput> writer) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        writer.accept(out);
        return out.toByteArray();
    }

    private void sendProgressToPlayer(Player player) {
        Set<String> teamFinished;
        List<String> teamServers;
        if (versusMode) {
            String team = playerTeam.get(player.getUniqueId());
            if ("a".equals(team)) {
                teamFinished = finishedServersA;
                teamServers = teamAServers;
            } else if ("b".equals(team)) {
                teamFinished = finishedServersB;
                teamServers = teamBServers;
            } else {
                teamFinished = finishedServers;
                teamServers = gameServers;
            }
        } else {
            teamFinished = finishedServers;
            teamServers = gameServers;
        }
        int required = (int) Math.ceil(teamServers.size() * requiredPercentage);
        final int done = teamFinished.size();
        sendToBackend(
                player,
                buildMessage(
                        out -> {
                            out.writeUTF("progress");
                            out.writeInt(done);
                            out.writeInt(required);
                        }));
    }

    private void sendTimeToPlayer(Player player) {
        sendToBackend(
                player,
                buildMessage(
                        out -> {
                            out.writeUTF("time");
                            out.writeInt(currentTime);
                        }));
    }

    private void broadcastTime() {
        for (Player player : getGameParticipants()) {
            sendTimeToPlayer(player);
        }
    }

    private void broadcastProgress() {
        for (Player player : getGameParticipants()) {
            sendProgressToPlayer(player);
        }
    }

    private void broadcastReset() {
        for (Player player : getGameParticipants()) {
            sendToBackend(player, buildMessage(out -> out.writeUTF("reset")));
        }
    }

    // =========================
    // MESSAGE
    // =========================

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {

        if (!event.getIdentifier().equals(CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection)) return;

        ServerConnection connection = (ServerConnection) event.getSource();
        String serverName = connection.getServerInfo().getName();

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String sub = in.readUTF();

        if (sub.equals("ready")) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            readyServers.add(serverName);
            logger.info(
                    "Server '{}' is ready ({}/{} servers ready)",
                    serverName,
                    readyServers.size(),
                    gameServers.size());
        } else if (sub.equals("finish")) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            handleFinish(serverName);
        }
    }

    // When a player arrives on a game server: send reset + current status.
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // Player arrived at lobby as part of a rotation: forward immediately to their next server.
        // This is handled via the connect() callback chain in rotatePlayers() – no action needed.
        // Exception: reconnecting players use pendingReconnect to transit through the lobby.
        if (!gameServers.contains(serverName)) {
            UUID uuid = player.getUniqueId();
            if (pendingReconnect.remove(uuid) && playerServer.containsKey(uuid)) {
                forwardFromLobby(player, playerServer.get(uuid));
            }
            return;
        }
        if (!activePlayers.contains(player.getUniqueId())) return;

        // Watching player: tell the Fabric server to put them in locked spectator mode.
        // incoming_spectator was already sent as a pre-notification (see handleFinish), so the
        // Fabric ENTITY_LOAD handler can act immediately. This message is the fallback.
        if (watchingPlayers.containsKey(player.getUniqueId())) {
            final UUID uuid = player.getUniqueId();
            final byte[] becomeSpectatorMsg =
                    buildMessage(
                            out -> {
                                out.writeUTF("become_spectator");
                                out.writeUTF(uuid.toString());
                            });
            // Retry at three increasing delays: getCurrentServer() is not populated at time 0,
            // and on slower servers ENTITY_LOAD may not have fired yet at 50 ms.
            for (int delayMs : new int[] {50, 200, 500}) {
                server.getScheduler()
                        .buildTask(this, () -> sendToBackend(player, becomeSpectatorMsg))
                        .delay(delayMs, TimeUnit.MILLISECONDS)
                        .schedule();
            }
            return; // no state sync for watchers
        }

        // Always send server-specific config when connecting to any game server.
        // Schedule with a short delay: ServerConnectedEvent fires before
        // player.getCurrentServer() is populated, so a direct send would fail.
        final int eyeTicks = eyeHoverTicks;
        final String slotUuidStr =
                java.util.UUID.nameUUIDFromBytes(
                                ("mcsrswap-slot-" + serverName)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        // On game start only: savehotbar + reset
        final boolean doReset =
                gameState == GameState.RUNNING && pendingReset.remove(player.getUniqueId());
        final boolean hotbar = saveHotbar;
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            sendToBackend(
                                    player,
                                    buildMessage(
                                            out -> {
                                                out.writeUTF("eyehoverticks");
                                                out.writeInt(eyeTicks);
                                            }));
                            sendToBackend(
                                    player,
                                    buildMessage(
                                            out -> {
                                                out.writeUTF("slot_uuid");
                                                out.writeUTF(slotUuidStr);
                                            }));
                            if (doReset) {
                                sendToBackend(
                                        player,
                                        buildMessage(
                                                out -> {
                                                    out.writeUTF("savehotbar");
                                                    out.writeBoolean(hotbar);
                                                }));
                                sendToBackend(
                                        player, buildMessage(out -> out.writeUTF("reset")));
                            }
                        })
                .delay(50, TimeUnit.MILLISECONDS)
                .schedule();

        // Always send the current state – with a short delay so the backend connection
        // is stable and ENTITY_LOAD on the Fabric server has already fired.
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            sendProgressToPlayer(player);
                            sendTimeToPlayer(player);
                        })
                .delay(50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void handleFinish(String serverName) {

        if (gameState != GameState.RUNNING) return;

        if (versusMode) {
            if (teamAServers.contains(serverName)) {
                if (finishedServersA.add(serverName)) {
                    broadcastProgress();
                    logger.info(
                            "Team A progress: {}/{}",
                            finishedServersA.size(),
                            (int) Math.ceil(teamAServers.size() * requiredPercentage));
                    if (finishedServersA.size()
                            >= (int) Math.ceil(teamAServers.size() * requiredPercentage)) {
                        teamWins("A");
                        return;
                    }
                }
            } else if (teamBServers.contains(serverName)) {
                if (finishedServersB.add(serverName)) {
                    broadcastProgress();
                    logger.info(
                            "Team B progress: {}/{}",
                            finishedServersB.size(),
                            (int) Math.ceil(teamBServers.size() * requiredPercentage));
                    if (finishedServersB.size()
                            >= (int) Math.ceil(teamBServers.size() * requiredPercentage)) {
                        teamWins("B");
                        return;
                    }
                }
            }
        } else {
            if (!finishedServers.add(serverName)) return;
            int required = (int) Math.ceil(gameServers.size() * requiredPercentage);
            logger.info("Progress: {}/{}", finishedServers.size(), required);
            broadcastProgress();
            if (finishedServers.size() >= required) {
                winGame();
                return;
            }
        }

        // If spectateAfterWin is enabled and there is enough time left in the rotation,
        // send the player who just finished to spectate an adjacent server.
        if (spectateAfterWin && currentTime >= 5) {
            for (Map.Entry<UUID, String> e : playerServer.entrySet()) {
                if (!serverName.equals(e.getValue())) continue;
                UUID uuid = e.getKey();
                if (watchingPlayers.containsKey(uuid)) continue;
                if (!activePlayers.contains(uuid)) continue;

                String spectateServer = findSpectateTarget(uuid, serverName);
                if (spectateServer == null) continue;

                // Pre-notify the spectate server BEFORE the watcher connects, so the Fabric
                // ENTITY_LOAD handler can immediately put them in spectator mode and skip the
                // normal state restore. We relay the message through any player already there.
                final UUID finalUuid = uuid;
                server.getServer(spectateServer)
                        .ifPresent(
                                rs ->
                                        rs.getPlayersConnected().stream()
                                                .findFirst()
                                                .ifPresent(
                                                        relay ->
                                                                sendToBackend(
                                                                        relay,
                                                                        buildMessage(
                                                                                out -> {
                                                                                    out.writeUTF(
                                                                                            "incoming_spectator");
                                                                                    out.writeUTF(
                                                                                            finalUuid
                                                                                                    .toString());
                                                                                }))));

                watchingPlayers.put(uuid, serverName);
                server.getPlayer(uuid)
                        .ifPresent(
                                p ->
                                        server.getServer(spectateServer)
                                                .ifPresent(
                                                        rs ->
                                                                p.createConnectionRequest(rs)
                                                                        .fireAndForget()));
                logger.info("Player {} finished {}; watching {}", uuid, serverName, spectateServer);
            }
        }
    }

    // =========================
    // COMMANDS
    // =========================

    /**
     * When a player reconnects to the proxy mid-game, route them to the lobby first. From there,
     * {@link #onServerConnected} will forward them to their current assigned game server via {@link
     * #forwardFromLobby}. This ensures that even if a swap happened while the player was
     * disconnected, they always land on the correct server.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (gameState != GameState.RUNNING) return;
        if (!activePlayers.contains(uuid)) return;
        if (!playerServer.containsKey(uuid)) return;
        server.getServer(getTransitServer())
                .ifPresent(
                        lobby -> {
                            pendingReconnect.add(uuid);
                            event.setInitialServer(lobby);
                        });
    }

    private void registerCommands() {

        CommandManager manager = server.getCommandManager();

        // All commands are subcommands of /ms
        CommandMeta wsMeta = manager.metaBuilder("ms").build();

        final List<String> ADMIN_SUBS =
                Arrays.asList(
                        "start",
                        "stop",
                        "forceswap",
                        "setrotation",
                        "setteam",
                        "setteamname",
                        "setversus",
                        "state",
                        "player",
                        "seed",
                        "cleanup");
        final List<String> PLAYER_SUBS = Arrays.asList("jointeam", "ignore");
        final List<String> ALL_SUBS;
        {
            List<String> tmp = new ArrayList<>(ADMIN_SUBS);
            tmp.addAll(PLAYER_SUBS);
            ALL_SUBS = Collections.unmodifiableList(tmp);
        }

        manager.register(
                wsMeta,
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        CommandSource src = invocation.source();
                        String[] args = invocation.arguments();
                        if (args.length == 0) {
                            commands.sendHelp(src);
                            return;
                        }
                        String sub = args[0].toLowerCase();
                        String[] rest = Arrays.copyOfRange(args, 1, args.length);
                        switch (sub) {
                            case "start":
                                commands.cmdStart(src, rest);
                                break;
                            case "stop":
                                commands.cmdStop(src, rest);
                                break;
                            case "forceswap":
                                commands.cmdForceSwap(src, rest);
                                break;
                            case "setrotation":
                                commands.cmdSetRotation(src, rest);
                                break;
                            case "setteam":
                                commands.cmdSetTeam(src, rest);
                                break;
                            case "jointeam":
                                commands.cmdJoinTeam(src, rest);
                                break;
                            case "ignore":
                                commands.cmdIgnore(src, rest);
                                break;
                            case "setteamname":
                                commands.cmdSetTeamName(src, rest);
                                break;
                            case "setversus":
                                commands.cmdSetVersus(src, rest);
                                break;
                            case "state":
                                commands.cmdState(src);
                                break;
                            case "player":
                                commands.cmdPlayer(src);
                                break;
                            case "seed":
                                commands.cmdSeed(src, rest);
                                break;
                            case "cleanup":
                                commands.cmdCleanup(src, rest);
                                break;
                            default:
                                commands.sendHelp(src);
                                break;
                        }
                    }

                    @Override
                    public List<String> suggest(Invocation invocation) {
                        CommandSource src = invocation.source();
                        String[] args = invocation.arguments();
                        boolean admin = isAdmin(src);

                        // First token: suggest subcommand names
                        if (args.length <= 1) {
                            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                            List<String> subs = new ArrayList<>(admin ? ALL_SUBS : PLAYER_SUBS);
                            if (!dockerMode) {
                                subs.remove("seed");
                                subs.remove("cleanup");
                            }
                            // Filter by game state
                            GameState state = gameState;
                            if (state == GameState.RUNNING) {
                                // Game is active – only ops that make sense mid-game
                                subs.retainAll(
                                        Arrays.asList(
                                                "stop",
                                                "forceswap",
                                                "setrotation",
                                                "state",
                                                "player",
                                                "seed"));
                            } else if (state == GameState.STARTING) {
                                // Containers starting – almost nothing useful
                                subs.retainAll(Arrays.asList("stop", "state", "player", "seed"));
                            } else {
                                // LOBBY – pre-game config; remove runtime-only commands
                                subs.removeAll(Arrays.asList("forceswap"));
                            }
                            return subs.stream()
                                    .filter(s -> s.startsWith(prefix))
                                    .collect(Collectors.toList());
                        }

                        String sub = args[0].toLowerCase();
                        String partial = args[args.length - 1].toLowerCase();

                        // Only admins get argument suggestions for admin-only subcommands.
                        if (!admin
                                && !sub.equals("jointeam")
                                && !sub.equals("ignore")) {
                            return Collections.emptyList();
                        }

                        switch (sub) {
                            case "start":
                                if (args.length == 2)
                                    return filterPrefix(
                                            Collections.singletonList("--clean"), partial);
                                break;

                            case "setteam":
                                if (args.length == 2) return teamArgs(partial);
                                if (args.length >= 3) return onlinePlayers(partial);
                                break;

                            case "jointeam":
                                if (args.length == 2) return teamArgs(partial);
                                break;

                            case "setteamname":
                                if (args.length == 2)
                                    return filterPrefix(Arrays.asList("a", "b"), partial);
                                break;

                            case "setversus":
                                if (args.length == 2)
                                    return filterPrefix(Arrays.asList("true", "false"), partial);
                                break;

                            case "ignore":
                                // admins can target other players; self-toggle needs no arg
                                if (args.length == 2 && admin)
                                    return onlinePlayers(partial);
                                break;

                            case "setrotation":
                                // Offer a few common rotation times as hints
                                if (args.length == 2)
                                    return filterPrefix(
                                            Arrays.asList("60", "90", "120", "180", "300"),
                                            partial);
                                break;

                            case "seed":
                                if (args.length == 2) {
                                    int next = worldSeeds.size() + 1;
                                    List<String> slots = new ArrayList<>();
                                    if (gameState == GameState.LOBBY) {
                                        slots.add("clear");
                                        // hint: comma-separated full list based on current seeds
                                        if (!worldSeeds.isEmpty()) {
                                            StringBuilder csv = new StringBuilder();
                                            for (int i = 0; i < worldSeeds.size(); i++) {
                                                if (i > 0) csv.append(",");
                                                Long s = worldSeeds.get(i);
                                                csv.append(s != null ? s : "");
                                            }
                                            slots.add(csv.toString());
                                        }
                                    }
                                    for (int i = 1; i <= next; i++) slots.add(String.valueOf(i));
                                    return filterPrefix(slots, partial);
                                }
                                if (args.length == 3 && gameState == GameState.LOBBY)
                                    return filterPrefix(
                                            Collections.singletonList("clear"), partial);
                                break;
                        }
                        return Collections.emptyList();
                    }

                    private List<String> onlinePlayers(String partial) {
                        return server.getAllPlayers().stream()
                                .map(p -> p.getUsername())
                                .filter(n -> n.toLowerCase().startsWith(partial))
                                .collect(Collectors.toList());
                    }

                    private List<String> teamArgs(String partial) {
                        return filterPrefix(
                                Arrays.asList(
                                        "a", "b", commands.getTeamNameA(), commands.getTeamNameB()),
                                partial);
                    }

                    private List<String> filterPrefix(List<String> options, String partial) {
                        return options.stream()
                                .filter(s -> s.toLowerCase().startsWith(partial))
                                .collect(Collectors.toList());
                    }
                });
    }

    // =========================
    // GAME START
    // =========================

    void startGame() {

        detectServers();

        // In Docker mode, servers are already healthy (checked by DockerServerManager)
        // Mark them as ready
        if (dockerMode && dockerManager != null) {
            for (String serverName : gameServers) {
                readyServers.add(serverName);
            }
            logger.info(
                    "All {} game servers are ready (Docker health check passed)",
                    gameServers.size());
        }

        startGameInternal();
    }

    private void startGameInternal() {
        // Invariant: gameServers must be non-empty for the rotation modulo to be well-defined.
        // detectServers() runs before every startGameInternal() call; if it produced an empty
        // list the game cannot start.
        if (gameServers.isEmpty()) {
            logger.error("Cannot start game: no game servers detected. Check your config.");
            return;
        }

        List<Player> players = new ArrayList<>(getStartParticipants());

        activePlayers.clear();
        for (Player player : players) {
            activePlayers.add(player.getUniqueId());
        }
        playerServer.clear();
        finishedServers.clear();
        watchingPlayers.clear();
        pendingReconnect.clear();
        finishedServersA.clear();
        finishedServersB.clear();

        if (versusMode) {
            if (gameServers.size() % 2 != 0) {
                versusMode = false;
                logger.warn(
                        "Odd number of game servers ({}) – switching to non-versus mode.",
                        gameServers.size());
            }
        }

        if (versusMode) {
            int half = gameServers.size() / 2;
            teamAServers = new ArrayList<>(gameServers.subList(0, half));
            teamBServers = new ArrayList<>(gameServers.subList(half, gameServers.size()));
            logger.info("Team A servers: {}", teamAServers);
            logger.info("Team B servers: {}", teamBServers);

            List<Player> teamAPlayers =
                    players.stream()
                            .filter(p -> "a".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());
            List<Player> teamBPlayers =
                    players.stream()
                            .filter(p -> "b".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());

            assignPlayersToServers(teamAPlayers, teamAServers);
            assignPlayersToServers(teamBPlayers, teamBServers);

            // Send team info + roster to all assigned players
            String rosterA =
                    teamAPlayers.stream().map(Player::getUsername).collect(Collectors.joining(", "));
            String rosterB =
                    teamBPlayers.stream().map(Player::getUsername).collect(Collectors.joining(", "));
            String rosterMsg =
                    "§7" + teamNameA + ": §f" + rosterA + " §7| " + teamNameB + ": §f" + rosterB;
            for (Player p : players) {
                if (!playerServer.containsKey(p.getUniqueId())) continue;
                String t = playerTeam.get(p.getUniqueId());
                String assignedName = "a".equals(t) ? teamNameA : teamNameB;
                p.sendMessage(Component.text(lang.get("team_assignment", "team", assignedName)));
                p.sendMessage(Component.text(rosterMsg));
            }
        } else {
            int index = 0;
            for (Player player : players) {
                if (index >= gameServers.size()) {
                    sendToLobby(player);
                    continue;
                }
                String target = gameServers.get(index);
                playerServer.put(player.getUniqueId(), target);
                server.getServer(target)
                        .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
                index++;
            }
        }

        currentTime = rotationTime;
        gameState = GameState.RUNNING;
        pendingReset.addAll(playerServer.keySet());

        // Players already on their assigned server won't trigger onServerConnected –
        // send their reset + progress immediately so state is clean.
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            for (Map.Entry<UUID, String> entry :
                                    new HashMap<>(playerServer).entrySet()) {
                                server.getPlayer(entry.getKey())
                                        .ifPresent(
                                                p -> {
                                                    String current =
                                                            p.getCurrentServer()
                                                                    .map(
                                                                            c ->
                                                                                    c.getServerInfo()
                                                                                            .getName())
                                                                    .orElse("");
                                                    if (current.equals(entry.getValue())
                                                            && pendingReset.remove(
                                                                    entry.getKey())) {
                                                        sendToBackend(
                                                                p,
                                                                buildMessage(
                                                                        out ->
                                                                                out.writeUTF(
                                                                                        "reset")));
                                                        sendProgressToPlayer(p);
                                                        sendTimeToPlayer(p);
                                                    }
                                                });
                            }
                        })
                .delay(200, TimeUnit.MILLISECONDS)
                .schedule();

        logger.info("Game started!");
        for (Player p : getGameParticipants()) {
            p.sendMessage(Component.text(lang.get("game_started")));
        }
    }

    /** Distributes players across servers; excess players are sent to the lobby. */
    private void assignPlayersToServers(List<Player> players, List<String> servers) {
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            if (i >= servers.size()) {
                sendToLobby(player);
                continue;
            }
            String target = servers.get(i);
            playerServer.put(player.getUniqueId(), target);
            server.getServer(target)
                    .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
        }
    }

    // =========================

    void forceSwap() {
        if (gameState != GameState.RUNNING) return;
        rotatePlayers();
        currentTime = rotationTime;
    }

    private void teamWins(String team) {
        gameState = GameState.LOBBY;
        String winnerName = "a".equalsIgnoreCase(team) ? teamNameA : teamNameB;
        List<Player> participants = new ArrayList<>(getGameParticipants());
        for (Player player : participants) {
            String t = playerTeam.get(player.getUniqueId());
            if (team.toLowerCase().equals(t)) {
                player.sendMessage(Component.text(lang.get("game_team_wins", "team", winnerName)));
            } else {
                player.sendMessage(Component.text(lang.get("game_team_loses", "team", winnerName)));
            }
            sendToLobby(player);
        }
        watchingPlayers.clear();
        activePlayers.clear();
        pendingReconnect.clear();
    }

    private void winGame() {
        endGame();
    }

    void endGame() {
        gameState = GameState.LOBBY;
        List<Player> participants = new ArrayList<>(getGameParticipants());
        watchingPlayers.clear();
        byte[] resetMsg = buildMessage(out -> out.writeUTF("reset"));
        for (Player player : participants) {
            player.getCurrentServer()
                    .ifPresent(
                            cs -> {
                                if (gameServers.contains(cs.getServerInfo().getName())) {
                                    cs.sendPluginMessage(CHANNEL, resetMsg);
                                }
                            });
            player.sendMessage(Component.text(lang.get("game_finished")));
            sendToLobby(player);
        }
        activePlayers.clear();
        pendingReconnect.clear();
    }

    private void sendToLobby(Player player) {
        server.getServer(lobbyServerName)
                .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
    }

    private boolean isAdmin(CommandSource source) {
        if (source == this.server.getConsoleCommandSource()) {
            return true;
        }
        // Check LuckPerms permission
        return source.hasPermission("swap.admin");
    }

    public PluginConfig getPluginConfig() {
        return config;
    }
}
