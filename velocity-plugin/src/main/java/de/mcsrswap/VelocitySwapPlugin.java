package de.mcsrswap;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
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
import java.time.Duration;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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

    /**
     * Monotonic deadline for the current round. The visible countdown is derived from this value
     * instead of decrementing a counter once per scheduler tick, so scheduler jitter does not make
     * rotations drift.
     */
    private long roundDeadlineNanos;

    /** True once the pre-rotation camera release has been sent for the current round. */
    private boolean preRotationDone;

    /** Servers whose Fabric mod has announced save-before-rotation support. */
    private final Set<String> saveAckServers = ConcurrentHashMap.newKeySet();

    /** In-flight save acknowledgements, keyed by request id. */
    private final Map<String, CompletableFuture<Boolean>> pendingSaveAcks =
            new ConcurrentHashMap<>();

    /**
     * Experimental: when true, a swap may save the slot on the old server and connect directly to
     * the next one if (and only if) the target server has no active player. A normal ring rotation
     * always keeps the target occupied for a moment, so it uses the limbo transit instead — this
     * avoids showing the outgoing player's body to the arriving player. Enable with {@code
     * MCSRSWAP_DIRECT_ROTATION=true}.
     */
    private boolean directRotation = false;

    final Map<UUID, String> playerServer = new HashMap<>();
    final Set<String> finishedServers = new HashSet<>();

    /** Players currently participating in the active round, including temporary watchers. */
    final Set<UUID> activePlayers = new HashSet<>();

    /**
     * Players who have opted out of the next game start. Populated from config/env at load time and
     * toggled at runtime via {@code /ms ignore}. Persists until toggled back.
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

    /**
     * Players who have clicked "Yes" in the ready-check prompt (populated during READY_CHECK).
     * Cleared when entering or leaving READY_CHECK.
     */
    final Set<UUID> readyConfirmed = new HashSet<>();

    /**
     * Ordered list of participant UUIDs at the time the ready-check was started. Used to determine
     * when all players have confirmed. Cleared alongside {@link #readyConfirmed}.
     */
    final List<UUID> readyCheckParticipants = new ArrayList<>();

    /** Guards against double-triggering the pre-game countdown. */
    volatile boolean countdownRunning = false;

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
            // Pull latest image in background during startup (skip if
            // MCSRSWAP_PULL_GAME_IMAGE=false)
            String pullEnv = System.getenv("MCSRSWAP_PULL_GAME_IMAGE");
            boolean pullImage =
                    pullEnv == null || (!pullEnv.equalsIgnoreCase("false") && !pullEnv.equals("0"));
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
                                + "  # Absolute path on the Docker host. Defaults to GAME_DATA_DIR.\n"
                                + "  # gameDataDir: /absolute/path/to/game-data\n"
                                + "  # autoStopLobby: false\n"
                                + "  # externalServers:\n"
                                + "  #   - name: game3\n"
                                + "  #     host: 192.168.1.200\n"
                                + "  #     port: 25602\n";

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
            String envDirectRotation = System.getenv("MCSRSWAP_DIRECT_ROTATION");
            if (envDirectRotation != null && !envDirectRotation.isBlank()) {
                directRotation = envDirectRotation.equalsIgnoreCase("true");
            }

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
                    logger.warn(
                            "Could not register limbo server '{}': {}",
                            limboServerName,
                            e.getMessage());
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
                            // Check before touching LuckPermsHook so its LuckPerms classes are
                            // never loaded on servers without LuckPerms.
                            if (server.getPluginManager().getPlugin("luckperms").isEmpty()) {
                                logger.warn("LuckPerms not loaded - admin permissions not set!");
                                return;
                            }
                            LuckPermsHook.setupAdmins(logger, adminPlayers);
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

                            int remaining = secondsRemaining();

                            if (remaining <= 0) {
                                // Show 0 on the old server to complete the visual countdown,
                                // then trigger rotation. The new countdown starts immediately.
                                currentTime = 0;
                                broadcastTime();
                                rotatePlayers();
                                startRound();
                                return;
                            }

                            if (!preRotationDone
                                    && remaining <= 2
                                    && spectateAfterWin
                                    && !watchingPlayers.isEmpty()) {
                                preRotationDone = true;
                                preRotation();
                            }

                            currentTime = remaining;
                            broadcastTime();
                        })
                .delay(300, TimeUnit.MILLISECONDS)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    /** Resets the round clock. The countdown is derived from a monotonic deadline. */
    private void startRound() {
        currentTime = rotationTime;
        preRotationDone = false;
        roundDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(rotationTime);
    }

    /** Whole seconds left in the current round, rounded up. */
    private int secondsRemaining() {
        long remaining = roundDeadlineNanos - System.nanoTime();
        return (int) Math.max(0L, (remaining + 999_999_999L) / 1_000_000_000L);
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

            boolean wasWatching = watchingPlayers.remove(uuid) != null;
            if (wasWatching) {
                // The scheduled pre-rotation did not cover this watcher (e.g. they finished very
                // late). Release the camera lock now; the connection switch follows immediately.
                sendPrepareReturn(player);
            }

            // Player is mid-reconnect (currently in lobby or en-route): advance their assignment
            // but skip the connection request. onServerConnected() forwards them once they land.
            if (pendingReconnect.contains(uuid)) {
                continue;
            }

            final String nextServer = next;
            if (canRotateDirectly(player, nextServer)) {
                requestSave(player)
                        .thenAcceptAsync(
                                saved -> {
                                    if (saved) {
                                        connectDirect(player, nextServer);
                                    } else {
                                        connectViaTransit(player, nextServer);
                                    }
                                },
                                this::runOnVelocityThread);
            } else {
                connectViaTransit(player, nextServer);
            }
        }

        logger.info("Swapping!");
    }

    /**
     * A direct Game→Game switch is only safe when the target server is empty. In the normal ring
     * rotation the outgoing player of the target is still connected for a moment, and the arriving
     * player would see their body. Waiting for that player to leave would deadlock the ring, so
     * occupied targets always go through the limbo transit.
     */
    private boolean canRotateDirectly(Player player, String nextServer) {
        if (!directRotation || !canSaveBeforeRotation(player)) return false;
        return server.getServer(nextServer)
                .map(
                        target ->
                                target.getPlayersConnected().stream()
                                        .noneMatch(
                                                other ->
                                                        activePlayers.contains(
                                                                other.getUniqueId())))
                .orElse(false);
    }

    /** True if the player's current backend can save its slot synchronously before a swap. */
    private boolean canSaveBeforeRotation(Player player) {
        return player.getCurrentServer()
                .map(
                        connection -> {
                            String name = connection.getServerInfo().getName();
                            return gameServers.contains(name) && saveAckServers.contains(name);
                        })
                .orElse(false);
    }

    /**
     * Asks the current backend to flush the slot .dat and waits for its acknowledgement. This is a
     * query, not a fixed delay: a server that does not understand the request never announces
     * support and therefore never takes this path.
     */
    private CompletableFuture<Boolean> requestSave(Player player) {
        java.util.Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) return CompletableFuture.completedFuture(false);

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> acknowledgement = new CompletableFuture<>();
        pendingSaveAcks.put(requestId, acknowledgement);

        boolean sent =
                connection
                        .get()
                        .sendPluginMessage(
                                CHANNEL,
                                buildMessage(
                                        out -> {
                                            out.writeUTF("save");
                                            out.writeUTF(player.getUniqueId().toString());
                                            out.writeUTF(requestId);
                                        }));
        if (!sent) {
            pendingSaveAcks.remove(requestId);
            return CompletableFuture.completedFuture(false);
        }

        return acknowledgement
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .exceptionally(
                        error -> {
                            pendingSaveAcks.remove(requestId);
                            logger.warn(
                                    "Timed out waiting for save acknowledgement from {};"
                                            + " falling back to transit",
                                    connection.get().getServerInfo().getName());
                            return false;
                        });
    }

    /** Game → game without the limbo hop. The slot was saved by {@link #requestSave}. */
    private void connectDirect(Player player, String nextServer) {
        if (!player.isActive()) return;
        String target = resolveTarget(player, nextServer);
        server.getServer(target)
                .ifPresent(
                        registered ->
                                player.createConnectionRequest(registered)
                                        .connect()
                                        .thenAcceptAsync(
                                                result -> {
                                                    if (result.isSuccessful()) return;
                                                    logger.warn(
                                                            "Direct rotation of {} to {} failed;"
                                                                    + " falling back to transit",
                                                            player.getUsername(),
                                                            nextServer);
                                                    connectViaTransit(player, nextServer);
                                                },
                                                this::runOnVelocityThread));
    }

    /** Game → limbo → game. Used when the old backend cannot save on request. */
    private void connectViaTransit(Player player, String nextServer) {
        if (!player.isActive()) return;
        server.getServer(getTransitServer())
                .ifPresent(
                        transit ->
                                player.createConnectionRequest(transit)
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
                                                    forwardFromLobby(player, nextServer);
                                                },
                                                this::runOnVelocityThread));
    }

    private void runOnVelocityThread(Runnable runnable) {
        server.getScheduler().buildTask(this, runnable).schedule();
    }

    /**
     * Second half of the transit rotation. Waits until the target server no longer has an active
     * player, so the outgoing player's body has been removed before the new player arrives. The
     * wait is a query with a short upper bound; an empty target connects immediately.
     */
    private void forwardFromLobby(Player player, String nextServer) {
        String target = resolveTarget(player, nextServer);
        waitForTargetFree(player, target, 0);
    }

    private void waitForTargetFree(Player player, String target, int attempt) {
        if (!player.isActive()) return;

        boolean free =
                server.getServer(target)
                        .map(
                                registered ->
                                        registered.getPlayersConnected().stream()
                                                .noneMatch(
                                                        other ->
                                                                activePlayers.contains(
                                                                        other.getUniqueId())))
                        .orElse(true);

        if (!free && attempt < 15) {
            server.getScheduler()
                    .buildTask(this, () -> waitForTargetFree(player, target, attempt + 1))
                    .delay(50, TimeUnit.MILLISECONDS)
                    .schedule();
            return;
        }

        server.getServer(target).ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
    }

    /**
     * Resolves the server the player should actually land on. If their next world is already
     * finished and spectating is enabled, this is the spectate target instead.
     */
    private String resolveTarget(Player player, String nextServer) {
        if (spectateAfterWin && getTeamFinished(player.getUniqueId()).contains(nextServer)) {
            String spectateServer = findSpectateTarget(player.getUniqueId(), nextServer);
            if (spectateServer != null) {
                watchingPlayers.put(player.getUniqueId(), nextServer);
                // Pre-notify so the Fabric side never loads the slot data for the watcher.
                server.getServer(spectateServer)
                        .ifPresent(
                                rs ->
                                        sendToServer(
                                                rs,
                                                buildMessage(
                                                        out -> {
                                                            out.writeUTF("incoming_spectator");
                                                            out.writeUTF(
                                                                    player.getUniqueId()
                                                                            .toString());
                                                        })));
                return spectateServer;
            }
        }
        return nextServer;
    }

    /**
     * Called shortly before rotation. Releases the spectator camera lock on the spectate server so
     * the client is not stuck when the server switch happens. The reconnection itself is handled by
     * {@link #rotatePlayers()} without an intermediate hop back through the old server.
     */
    private void preRotation() {
        for (UUID watcherUuid : new ArrayList<>(watchingPlayers.keySet())) {
            watchingPlayers.remove(watcherUuid);
            server.getPlayer(watcherUuid).ifPresent(this::sendPrepareReturn);
        }
    }

    private void sendPrepareReturn(Player player) {
        sendToBackend(
                player,
                buildMessage(
                        out -> {
                            out.writeUTF("prepare_return");
                            out.writeUTF(player.getUniqueId().toString());
                        }));
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
     * Returns the transit server used to buffer players between game servers during rotation. Uses
     * the limbo server if it is registered with Velocity; falls back to the lobby.
     */
    private String getTransitServer() {
        if (server.getServer(limboServerName).isPresent()) {
            return limboServerName;
        }
        return lobbyServerName;
    }

    /** Returns lobby players who should participate in the next game start. */
    List<Player> getStartParticipants() {
        return getLobbyPlayers().stream().filter(p -> !isIgnored(p)).collect(Collectors.toList());
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
        player.getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(CHANNEL, data));
    }

    /**
     * Sends directly to a known server connection. Unlike {@link #sendToBackend(Player, byte[])},
     * this does not re-resolve {@code player.getCurrentServer()} and is safe to call from a
     * connection event.
     */
    private boolean sendToServer(RegisteredServer target, byte[] data) {
        if (target == null) return false;
        boolean sent = target.sendPluginMessage(CHANNEL, data);
        if (!sent) {
            logger.warn(
                    "Could not send plugin message to server {}", target.getServerInfo().getName());
        }
        return sent;
    }

    private byte[] buildMessage(java.util.function.Consumer<ByteArrayDataOutput> writer) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        writer.accept(out);
        return out.toByteArray();
    }

    private void sendProgressToPlayer(Player player) {
        sendToBackend(player, progressMessage(player));
    }

    private byte[] progressMessage(Player player) {
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
        return buildMessage(
                out -> {
                    out.writeUTF("progress");
                    out.writeInt(done);
                    out.writeInt(required);
                });
    }

    private void sendTimeToPlayer(Player player) {
        sendToBackend(player, timeMessage(currentTime));
    }

    private byte[] timeMessage(int time) {
        return buildMessage(
                out -> {
                    out.writeUTF("time");
                    out.writeInt(time);
                });
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
    // PLAYER LOGIN
    // =========================

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String name = player.getUsername();
        String uuidStr = player.getUniqueId().toString();
        if (!adminPlayers.contains(name) && !adminPlayers.contains(uuidStr)) return;

        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            if (server.getPluginManager().getPlugin("luckperms").isEmpty()) {
                                return;
                            }
                            LuckPermsHook.grantAdmin(logger, player);
                        })
                .delay(1, TimeUnit.SECONDS)
                .schedule();
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
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        switch (sub) {
            case "hello":
                if (saveAckServers.add(serverName)) {
                    logger.info("Server '{}' supports save-before-rotation", serverName);
                }
                return;
            case "save_ack":
                {
                    CompletableFuture<Boolean> pending = pendingSaveAcks.remove(in.readUTF());
                    if (pending != null) pending.complete(true);
                    return;
                }
            case "finish":
                handleFinish(serverName);
                return;
            default:
                logger.debug("Ignoring unknown plugin message '{}' from {}", sub, serverName);
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
                final String nextServer = playerServer.get(uuid);
                // Schedule without delay: calling createConnectionRequest() directly from
                // ServerConnectedEvent causes "already trying to connect" because Velocity's
                // connection state for the current hop hasn't been fully committed yet.
                // Yielding to the scheduler (even with no delay) moves execution past the
                // current event-processing cycle, making it ping-independent.
                server.getScheduler()
                        .buildTask(
                                this,
                                () ->
                                        server.getServer(nextServer)
                                                .ifPresent(
                                                        s ->
                                                                player.createConnectionRequest(s)
                                                                        .connect()
                                                                        .thenAcceptAsync(
                                                                                result -> {
                                                                                    if (!result
                                                                                            .isSuccessful()) {
                                                                                        logger.warn(
                                                                                                "Failed to forward reconnecting player {} to {}",
                                                                                                player
                                                                                                        .getUsername(),
                                                                                                nextServer);
                                                                                    }
                                                                                },
                                                                                runnable ->
                                                                                        server.getScheduler()
                                                                                                .buildTask(
                                                                                                        this,
                                                                                                        runnable)
                                                                                                .schedule())))
                        .schedule();
            }
            return;
        }
        if (!activePlayers.contains(player.getUniqueId())) return;

        RegisteredServer target = event.getServer();

        // Watching player: tell the Fabric server to put them in locked spectator mode.
        // incoming_spectator was already sent as a pre-notification (see handleFinish), so the
        // Fabric ENTITY_LOAD handler can act immediately. This message is the fallback.
        if (watchingPlayers.containsKey(player.getUniqueId())) {
            UUID uuid = player.getUniqueId();
            sendToServer(
                    target,
                    buildMessage(
                            out -> {
                                out.writeUTF("become_spectator");
                                out.writeUTF(uuid.toString());
                            }));
            return; // no state sync for watchers
        }

        // Always send server-specific config when connecting to any game server. The event's
        // server is the established target connection, so no delay or re-resolution is needed.
        final int eyeTicks = eyeHoverTicks;
        final String slotUuidStr =
                java.util
                        .UUID
                        .nameUUIDFromBytes(
                                ("mcsrswap-slot-" + serverName)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        final boolean doReset =
                gameState == GameState.RUNNING && pendingReset.contains(player.getUniqueId());
        final boolean hotbar = saveHotbar;

        sendToServer(
                target,
                buildMessage(
                        out -> {
                            out.writeUTF("eyehoverticks");
                            out.writeInt(eyeTicks);
                        }));
        sendToServer(
                target,
                buildMessage(
                        out -> {
                            out.writeUTF("slot_uuid");
                            out.writeUTF(slotUuidStr);
                        }));
        if (doReset) {
            sendToServer(
                    target,
                    buildMessage(
                            out -> {
                                out.writeUTF("savehotbar");
                                out.writeBoolean(hotbar);
                            }));
            sendToServer(target, buildMessage(out -> out.writeUTF("reset")));
            pendingReset.remove(player.getUniqueId());
        }

        sendToServer(target, progressMessage(player));
        sendToServer(target, timeMessage(currentTime));
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
        if (spectateAfterWin && secondsRemaining() >= 5) {
            for (Map.Entry<UUID, String> e : playerServer.entrySet()) {
                if (!serverName.equals(e.getValue())) continue;
                UUID uuid = e.getKey();
                if (watchingPlayers.containsKey(uuid)) continue;
                if (!activePlayers.contains(uuid)) continue;

                String spectateServer = findSpectateTarget(uuid, serverName);
                if (spectateServer == null) continue;

                // Pre-notify the spectate server BEFORE the watcher connects, so the Fabric
                // ENTITY_LOAD handler can immediately put them in spectator mode and skip the
                // normal state restore. Send through the server connection itself instead of
                // relying on a relay player being online.
                final UUID finalUuid = uuid;
                server.getServer(spectateServer)
                        .ifPresent(
                                rs ->
                                        sendToServer(
                                                rs,
                                                buildMessage(
                                                        out -> {
                                                            out.writeUTF("incoming_spectator");
                                                            out.writeUTF(finalUuid.toString());
                                                        })));

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
                        "prepare",
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
        final List<String> PLAYER_SUBS = Arrays.asList("jointeam", "ignore", "ready");
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
                            case "prepare":
                                commands.cmdPrepare(src, rest);
                                break;
                            case "ready":
                                commands.cmdReady(src, rest);
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
                            } else if (state == GameState.PREPARING) {
                                // /ms prepare in progress – can stop or clean up
                                subs.retainAll(Arrays.asList("stop", "cleanup", "state", "player"));
                            } else if (state == GameState.READY_CHECK) {
                                // Waiting for players – admin can force-start, players can ready up
                                subs.retainAll(
                                        Arrays.asList(
                                                "start", "cleanup", "state", "player", "ready"));
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
                                && !sub.equals("ignore")
                                && !sub.equals("ready")) {
                            return Collections.emptyList();
                        }

                        switch (sub) {
                            case "start":
                                if (args.length == 2)
                                    return filterPrefix(
                                            Collections.singletonList("--clean"), partial);
                                break;

                            case "prepare":
                                if (args.length == 2) {
                                    // Suggest the current participant count as a convenience hint.
                                    int hint = getStartParticipants().size();
                                    if (hint > 0)
                                        return filterPrefix(
                                                Collections.singletonList(String.valueOf(hint)),
                                                partial);
                                }
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
                                if (args.length == 2 && admin) return onlinePlayers(partial);
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

        // In Docker mode, servers are already healthy (checked by DockerServerManager).
        if (dockerMode && dockerManager != null) {
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
        // Ensure a non-LOBBY state so the countdown guard works even for manual-mode starts
        // (Docker mode already sets STARTING before this is reached).
        if (gameState == GameState.LOBBY) {
            gameState = GameState.STARTING;
        }
        scheduleCountdownThenLaunch();
    }

    /**
     * Assigns participants to servers, connects them, sets up the timer, and transitions to {@link
     * GameState#RUNNING}. May be called from {@link #startGameInternal()} (normal start or Docker
     * immediate) or directly from the READY_CHECK → RUNNING transition. Requires {@link
     * #gameServers} to be non-empty.
     */
    void launchGame() {
        readyConfirmed.clear();
        readyCheckParticipants.clear();

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
        // Servers announce their support again when the first player joins this round.
        saveAckServers.clear();

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
                    teamAPlayers.stream()
                            .map(Player::getUsername)
                            .collect(Collectors.joining(", "));
            String rosterB =
                    teamBPlayers.stream()
                            .map(Player::getUsername)
                            .collect(Collectors.joining(", "));
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

        startRound();
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
                .schedule();

        logger.info("Game started!");
        for (Player p : getGameParticipants()) {
            p.sendMessage(Component.text(lang.get("game_started")));
        }

        // In Docker mode with autoStopLobby enabled: stop the lobby container once all players
        // have left it.
        if (dockerManager != null
                && dockerManager.isDockerEnabled()
                && config.docker.autoStopLobby) {
            server.getScheduler()
                    .buildTask(
                            this,
                            () -> {
                                if (gameState != GameState.RUNNING) return;
                                boolean lobbyEmpty =
                                        server.getServer(lobbyServerName)
                                                .map(rs -> rs.getPlayersConnected().isEmpty())
                                                .orElse(true);
                                if (lobbyEmpty) {
                                    dockerManager.stopLobbyContainer();
                                } else {
                                    logger.debug(
                                            "Lobby still has players after game start –"
                                                    + " not stopping lobby container");
                                }
                            })
                    .delay(15, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    /**
     * Transitions to {@link GameState#READY_CHECK}. Stores current participants and sends each a
     * clickable "Bereit? [Ja]" prompt. Called after all servers are healthy (Docker or manual).
     */
    void enterReadyCheck() {
        gameState = GameState.READY_CHECK;
        readyConfirmed.clear();
        readyCheckParticipants.clear();
        List<Player> participants = getStartParticipants();
        for (Player p : participants) {
            readyCheckParticipants.add(p.getUniqueId());
        }
        for (Player p : participants) {
            sendReadyCheckPrompt(p);
        }
        logger.info("Ready check started for {} players.", readyCheckParticipants.size());
    }

    /** Sends the clickable ready-check prompt to a single player. */
    void sendReadyCheckPrompt(Player p) {
        net.kyori.adventure.text.Component yesButton =
                net.kyori.adventure.text.Component.text("[" + lang.get("ready_yes") + "]")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                        .clickEvent(
                                net.kyori.adventure.text.event.ClickEvent.runCommand("/ms ready"))
                        .hoverEvent(
                                net.kyori.adventure.text.event.HoverEvent.showText(
                                        net.kyori.adventure.text.Component.text(
                                                lang.get("ready_yes"))));
        net.kyori.adventure.text.Component msg =
                net.kyori.adventure.text.Component.text(lang.get("ready_check_prompt") + " ")
                        .append(yesButton);
        p.sendMessage(msg);
    }

    /**
     * Marks a player as ready. If all participants have confirmed, launches the game immediately.
     * Safe to call from any thread.
     */
    void confirmReady(Player player) {
        if (gameState != GameState.READY_CHECK) return;
        if (readyCheckParticipants.isEmpty()) return; // already started or cancelled
        UUID uuid = player.getUniqueId();
        if (!readyCheckParticipants.contains(uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(lang.get("ready_already")));
            return;
        }
        if (!readyConfirmed.add(uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(lang.get("ready_already")));
            return;
        }
        int count = readyConfirmed.size();
        int total = readyCheckParticipants.size();
        String msg =
                lang.get(
                        "ready_player_ready",
                        "player",
                        player.getUsername(),
                        "count",
                        String.valueOf(count),
                        "total",
                        String.valueOf(total));
        for (Player p : server.getAllPlayers()) {
            p.sendMessage(net.kyori.adventure.text.Component.text(msg));
        }
        if (count >= total) {
            for (Player p : server.getAllPlayers()) {
                p.sendMessage(net.kyori.adventure.text.Component.text(lang.get("ready_all_ready")));
            }
            scheduleCountdownThenLaunch();
        }
    }

    /** Admin override: immediately launches the game regardless of ready confirmations. */
    void forceStartFromReadyCheck() {
        if (gameState != GameState.READY_CHECK) return;
        for (Player p : server.getAllPlayers()) {
            p.sendMessage(net.kyori.adventure.text.Component.text(lang.get("ready_forced")));
        }
        scheduleCountdownThenLaunch();
    }

    /**
     * Cancels an in-progress PREPARING or READY_CHECK phase, resetting state to LOBBY. Also aborts
     * any pending countdown (the countdown lambda checks for LOBBY state). Does NOT perform Docker
     * cleanup — callers should call {@link WorldSwapCommands#cmdCleanup} separately.
     */
    void cancelPrepare() {
        if (gameState != GameState.PREPARING && gameState != GameState.READY_CHECK) return;
        countdownRunning = false;
        gameState = GameState.LOBBY;
        readyConfirmed.clear();
        readyCheckParticipants.clear();
        for (Player p : server.getAllPlayers()) {
            p.sendMessage(
                    net.kyori.adventure.text.Component.text(lang.get("ready_check_cancelled")));
        }
    }

    /**
     * Shows a 3-2-1 title countdown to all players, then calls {@link #launchGame()}. Idempotent:
     * if a countdown is already in progress this call is a no-op. The launch is aborted if the game
     * state returns to LOBBY before the countdown ends (e.g. because an admin ran /ms stop or /ms
     * cleanup during the 3-second window).
     */
    void scheduleCountdownThenLaunch() {
        if (countdownRunning) return;
        countdownRunning = true;

        Title.Times times =
                Title.Times.of(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(300));
        for (int i = 3; i >= 1; i--) {
            final long delaySec = 3 - i;
            final Title title = Title.title(Component.text("§a" + i), Component.empty(), times);
            server.getScheduler()
                    .buildTask(
                            this,
                            () -> {
                                for (Player p : server.getAllPlayers()) {
                                    p.showTitle(title);
                                }
                            })
                    .delay(delaySec, TimeUnit.SECONDS)
                    .schedule();
        }

        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            countdownRunning = false;
                            if (gameState == GameState.LOBBY) return; // cancelled
                            launchGame();
                        })
                .delay(3, TimeUnit.SECONDS)
                .schedule();
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
        if (spectateAfterWin && !watchingPlayers.isEmpty()) {
            preRotationDone = true;
            preRotation();
        }
        rotatePlayers();
        startRound();
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
        }
        watchingPlayers.clear();
        activePlayers.clear();
        pendingReconnect.clear();

        if (dockerManager != null
                && dockerManager.isDockerEnabled()
                && config.docker.autoStopLobby) {
            for (Player player : participants) {
                sendToTransit(player);
            }
            dockerManager
                    .startLobbyContainerAsync()
                    .thenAccept(
                            ok -> {
                                if (!ok) {
                                    logger.error(
                                            "Lobby container failed to start — players will"
                                                    + " remain in limbo");
                                    return;
                                }
                                server.getScheduler()
                                        .buildTask(
                                                this,
                                                () -> {
                                                    for (Player p : server.getAllPlayers()) {
                                                        p.getCurrentServer()
                                                                .filter(
                                                                        cs ->
                                                                                cs.getServerInfo()
                                                                                        .getName()
                                                                                        .equals(
                                                                                                limboServerName))
                                                                .ifPresent(cs -> sendToLobby(p));
                                                    }
                                                })
                                        .delay(10, TimeUnit.SECONDS)
                                        .schedule();
                            });
        } else {
            for (Player player : participants) {
                sendToLobby(player);
            }
        }
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
        }
        activePlayers.clear();
        pendingReconnect.clear();

        if (dockerManager != null
                && dockerManager.isDockerEnabled()
                && config.docker.autoStopLobby) {
            // lobby container and move them there once it's ready.
            for (Player player : participants) {
                sendToTransit(player);
            }
            dockerManager
                    .startLobbyContainerAsync()
                    .thenAccept(
                            ok -> {
                                if (!ok) {
                                    logger.error(
                                            "Lobby container failed to start — players will"
                                                    + " remain in limbo");
                                    return;
                                }
                                // Extra grace period for Minecraft to accept connections.
                                server.getScheduler()
                                        .buildTask(
                                                this,
                                                () -> {
                                                    for (Player p : server.getAllPlayers()) {
                                                        p.getCurrentServer()
                                                                .filter(
                                                                        cs ->
                                                                                cs.getServerInfo()
                                                                                        .getName()
                                                                                        .equals(
                                                                                                limboServerName))
                                                                .ifPresent(cs -> sendToLobby(p));
                                                    }
                                                })
                                        .delay(10, TimeUnit.SECONDS)
                                        .schedule();
                            });
        } else {
            for (Player player : participants) {
                sendToLobby(player);
            }
        }
    }

    private void sendToLobby(Player player) {
        server.getServer(lobbyServerName)
                .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
    }

    /** Sends a player to the transit server (limbo if registered, otherwise lobby). */
    private void sendToTransit(Player player) {
        server.getServer(getTransitServer())
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
