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
    private final Path dataDirectory;
    private final Logger logger;

    // =========================
    // SETTINGS (Config)
    // =========================

    int rotationTime = 120;
    double requiredPercentage = 1.0;
    boolean versusMode = false;
    String gameServerPrefix = "game";
    String lobbyServerName = "lobby";
    final VelocityLang lang = new VelocityLang();
    final Set<String> adminPlayers = new HashSet<>();

    int currentTime;
    GameState gameState = GameState.LOBBY;
    List<String> admins = new ArrayList<>();

    final Map<UUID, String> playerServer = new HashMap<>();
    final Set<String> finishedServers = new HashSet<>();

    /** Players marked as spectators/observers – they are not rotated. */
    final Set<UUID> spectators = new HashSet<>();

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
    int spectateMinTime = 15; // only activate spectate if >= this many seconds left
    boolean saveHotbar = true; // rearrange new player's hotbar to match predecessor's hotbar layout
    int eyeHoverTicks = 80; // lifetime of a thrown Eye of Ender in ticks

    /**
     * Players currently watching another server after finishing their own world. Maps player UUID →
     * their logical server assignment (their position in the rotation).
     */
    final Map<UUID, String> watchingPlayers = new HashMap<>();

    final Set<UUID> pendingReset = new HashSet<>();

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
    private PluginConfig config;

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
                                + "spectateMinTime: 15\n"
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
            @SuppressWarnings("unchecked")
            Map<String, Object> rawConfig = yaml.load(Files.newInputStream(configFile));
            if (rawConfig == null) rawConfig = Map.of();

            // Build typed config and use it as primary source for all known fields
            PluginConfig cfg = PluginConfig.fromMap(rawConfig);
            this.config = cfg; // Store the config

            rotationTime = cfg.rotationTime;
            requiredPercentage = cfg.requiredPercentage;
            versusMode = cfg.versus;

            // use typed config values
            gameServerPrefix = cfg.gameServerPrefix;
            lobbyServerName = cfg.lobbyServerName;
            spectateAfterWin = cfg.spectateAfterWin;
            spectateTarget = cfg.spectateTarget;
            spectateMinTime = cfg.spectateMinTime;
            saveHotbar = cfg.saveHotbar;
            eyeHoverTicks = cfg.eyeHoverTicks;

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

            // Load admin list from typed config
            adminPlayers.clear();
            if (!cfg.admins.isEmpty()) {
                adminPlayers.addAll(cfg.admins);
            }
            logger.info("Loaded {} admin(s): {}", adminPlayers.size(), adminPlayers);

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
                try {
                    dockerManager = new DockerServerManager(server, logger, this);
                    // pass a simple map to initialize (keeps initialize signature unchanged)
                    Map<String, Object> dockerInit = new HashMap<>();
                    dockerInit.put("enabled", true);
                    dockerInit.put("image", image);
                    dockerInit.put("network", dockerCfg.network);
                    dockerManager.initialize(dockerInit);
                } catch (NoClassDefFoundError e) {
                    logger.error(
                            "Docker mode is enabled but docker-java dependencies are not available!");
                    logger.error(
                            "Please ensure docker-java libraries are in the classpath when using Docker mode.");
                    throw new RuntimeException("Docker dependencies missing", e);
                }
            }

            logger.info(
                    "Config loaded: rotation={}, percent={}, versus={}, language={}, gamePrefix={},"
                            + " lobby={}, spectateAfterWin={}, spectateTarget={}, spectateMinTime={},"
                            + " saveHotbar={}, eyeHoverTicks={}",
                    rotationTime,
                    requiredPercentage,
                    versusMode,
                    languageFile,
                    gameServerPrefix,
                    lobbyServerName,
                    spectateAfterWin,
                    spectateTarget,
                    spectateMinTime,
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
                                        UUID uuid =
                                                api.getUserManager()
                                                        .lookupUniqueId(playerName)
                                                        .get();
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
                                rotatePlayers();
                                currentTime = rotationTime;
                            } else if (currentTime == 5
                                    && spectateAfterWin
                                    && !watchingPlayers.isEmpty()) {
                                preRotation();
                            }

                            broadcastTime();
                        })
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    // =========================
    // ROTATION
    // =========================

    private void rotatePlayers() {

        // 1. Send a save signal to all current player servers
        //    so the state is as up to date as possible (no more than 1 s old).
        //    Skip watching players – they are spectators on a foreign server and must not
        //    overwrite that server's saved state.
        byte[] saveMsg = buildMessage(out -> out.writeUTF("save"));
        for (Player player : server.getAllPlayers()) {
            if (!playerServer.containsKey(player.getUniqueId())) continue;
            if (spectators.contains(player.getUniqueId())) continue;
            if (watchingPlayers.containsKey(player.getUniqueId())) continue;
            sendToBackend(player, saveMsg);
        }

        // 2. Wait 50 ms, then perform the actual rotation.
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            for (Player player : server.getAllPlayers()) {

                                if (!playerServer.containsKey(player.getUniqueId())) continue;
                                boolean isWatcher =
                                        watchingPlayers.containsKey(player.getUniqueId());
                                if (!isWatcher && spectators.contains(player.getUniqueId()))
                                    continue;

                                List<String> servers = getTeamServers(player.getUniqueId());
                                String current = playerServer.get(player.getUniqueId());
                                int index = servers.indexOf(current);
                                if (index < 0) index = 0;
                                index = (index + 1) % servers.size();

                                String next = servers.get(index);
                                playerServer.put(player.getUniqueId(), next);

                                // Watching players were already moved to their spectate target at
                                // preRotation.
                                // Just advance their logical server; do not re-connect (they will
                                // be connected
                                // to 'next' when they finish their spectating period, i.e. at
                                // preRotation of the
                                // next cycle or when they were moved by preRotation already).
                                if (watchingPlayers.containsKey(player.getUniqueId())) {
                                    // Update logical server. If the new target is still finished,
                                    // keep watching.
                                    Set<String> teamFinished =
                                            getTeamFinished(player.getUniqueId());
                                    if (spectateAfterWin && teamFinished.contains(next)) {
                                        // Stay watching; connect to the spectate target for the new
                                        // logical server
                                        String spectateServer =
                                                findSpectateTarget(player.getUniqueId(), next);
                                        if (spectateServer != null) {
                                            watchingPlayers.put(player.getUniqueId(), next);
                                            final Player p = player;
                                            server.getServer(spectateServer)
                                                    .ifPresent(
                                                            rs ->
                                                                    p.createConnectionRequest(rs)
                                                                            .fireAndForget());
                                            continue;
                                        }
                                    }
                                    // Target is no longer finished – send them there normally
                                    watchingPlayers.remove(player.getUniqueId());
                                    server.getServer(next)
                                            .ifPresent(
                                                    s ->
                                                            player.createConnectionRequest(s)
                                                                    .fireAndForget());
                                    continue;
                                }

                                // Normal player: rotate to next server, optionally as watcher if
                                // it's finished
                                if (spectateAfterWin
                                        && getTeamFinished(player.getUniqueId()).contains(next)) {
                                    String spectateServer =
                                            findSpectateTarget(player.getUniqueId(), next);
                                    if (spectateServer != null) {
                                        watchingPlayers.put(player.getUniqueId(), next);
                                        final Player p = player;
                                        server.getServer(spectateServer)
                                                .ifPresent(
                                                        rs ->
                                                                p.createConnectionRequest(rs)
                                                                        .fireAndForget());
                                        continue;
                                    }
                                }

                                server.getServer(next)
                                        .ifPresent(
                                                s ->
                                                        player.createConnectionRequest(s)
                                                                .fireAndForget());
                            }

                            logger.info("Swapping!");
                        })
                .delay(50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * Called 5 seconds before rotation. Sends "prepare_return" to every watching player's current
     * backend server so the Fabric mod can teleport them far above (clearing spectator visuals) and
     * switch them to Survival. Velocity will rotate them at T=0 as normal.
     */
    private void preRotation() {
        byte[] empty = buildMessage(out -> {}); // placeholder; message is per-player below
        for (Map.Entry<UUID, String> entry : new ArrayList<>(watchingPlayers.entrySet())) {
            server.getPlayer(entry.getKey())
                    .ifPresent(
                            p -> {
                                sendToBackend(
                                        p,
                                        buildMessage(
                                                out -> {
                                                    out.writeUTF("prepare_return");
                                                    out.writeUTF(p.getUniqueId().toString());
                                                }));
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
    private List<Player> getGameParticipants() {
        return server.getAllPlayers().stream()
                .filter(p -> playerServer.containsKey(p.getUniqueId()))
                .collect(Collectors.toList());
    }

    /** Returns all players currently on the lobby server. */
    private List<Player> getLobbyPlayers() {
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

    private void sendToBackend(Player player, byte[] data) {
        player.getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(CHANNEL, data));
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
        } else if (sub.equals("mode")) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            UUID uuid = UUID.fromString(in.readUTF());
            String mode = in.readUTF();
            if ("spectator".equals(mode)) {
                // Do not track watching players in the spectators set – they are handled
                // separately by watchingPlayers and must not be skipped during rotation.
                if (!watchingPlayers.containsKey(uuid)) {
                    spectators.add(uuid);
                }
            } else {
                spectators.remove(uuid);
            }
        }
    }

    // When a player arrives on a game server: send reset + current status.
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        if (!gameServers.contains(serverName)) return;
        if (spectators.contains(player.getUniqueId())) return; // observer: no state sync

        // Watching player: tell the Fabric server to put them in locked spectator mode.
        // incoming_spectator was already sent as a pre-notification (see handleFinish), so the
        // Fabric ENTITY_LOAD handler can act immediately. This message is the fallback.
        if (watchingPlayers.containsKey(player.getUniqueId())) {
            final UUID uuid = player.getUniqueId();
            sendToBackend(
                    player,
                    buildMessage(
                            out -> {
                                out.writeUTF("become_spectator");
                                out.writeUTF(uuid.toString());
                            }));
            return; // no state sync for watchers
        }

        // On game start: send config + reset to initialise the game server state.
        if (gameState == GameState.RUNNING && pendingReset.remove(player.getUniqueId())) {
            final boolean hotbar = saveHotbar;
            final int eyeTicks = eyeHoverTicks;
            sendToBackend(
                    player,
                    buildMessage(
                            out -> {
                                out.writeUTF("savehotbar");
                                out.writeBoolean(hotbar);
                            }));
            sendToBackend(
                    player,
                    buildMessage(
                            out -> {
                                out.writeUTF("eyehoverticks");
                                out.writeInt(eyeTicks);
                            }));
            sendToBackend(player, buildMessage(out -> out.writeUTF("reset")));
        }

        // Always send the current state – with a short delay so the backend connection
        // is stable and ENTITY_LOAD on the Fabric server has already fired.
        server.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            sendProgressToPlayer(player);
                            sendTimeToPlayer(player);
                        })
                .delay(500, TimeUnit.MILLISECONDS)
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
        if (spectateAfterWin && currentTime >= spectateMinTime) {
            for (Map.Entry<UUID, String> e : playerServer.entrySet()) {
                if (!serverName.equals(e.getValue())) continue;
                UUID uuid = e.getKey();
                if (watchingPlayers.containsKey(uuid)) continue;
                if (spectators.contains(uuid)) continue;

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
     * When a player reconnects to the proxy mid-game, route them directly to their assigned game
     * server instead of the default (lobby) server.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (gameState != GameState.RUNNING) return;
        if (!playerServer.containsKey(uuid)) return;
        if (spectators.contains(uuid)) return;
        String targetServer = playerServer.get(uuid);
        server.getServer(targetServer).ifPresent(event::setInitialServer);
    }

    private void registerCommands() {

        CommandManager manager = server.getCommandManager();

        // All commands are subcommands of /ms
        CommandMeta wsMeta = manager.metaBuilder("ms").build();

        final List<String> ADMIN_SUBS =
                Arrays.asList(
                        "start",
                        "resume",
                        "stop",
                        "forceswap",
                        "setrotation",
                        "spectate",
                        "setteam",
                        "setteamname",
                        "setversus",
                        "state",
                        "cleanup");
        final List<String> PLAYER_SUBS = Collections.singletonList("jointeam");
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
                            case "resume":
                                commands.cmdResume(src, rest);
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
                            case "spectate":
                                commands.cmdSpectate(src, rest);
                                break;
                            case "setteam":
                                commands.cmdSetTeam(src, rest);
                                break;
                            case "jointeam":
                                commands.cmdJoinTeam(src, rest);
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
                            List<String> subs = admin ? ALL_SUBS : PLAYER_SUBS;
                            return subs.stream()
                                    .filter(s -> s.startsWith(prefix))
                                    .collect(Collectors.toList());
                        }

                        String sub = args[0].toLowerCase();
                        String partial = args[args.length - 1].toLowerCase();

                        switch (sub) {
                            case "spectate":
                                if (args.length == 2) return onlinePlayers(partial);
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

                            case "setrotation":
                                // Offer a few common rotation times as hints
                                if (args.length == 2)
                                    return filterPrefix(
                                            Arrays.asList("60", "90", "120", "180", "300"),
                                            partial);
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
        List<Player> players = new ArrayList<>(getLobbyPlayers());

        playerServer.clear();
        finishedServers.clear();
        spectators.clear();
        watchingPlayers.clear();
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

            // Already-assigned players (only those currently connected)
            List<Player> teamAPlayers =
                    players.stream()
                            .filter(p -> "a".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());
            List<Player> teamBPlayers =
                    players.stream()
                            .filter(p -> "b".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());

            // Random distribution of unassigned (non-spectator) players into remaining slots.
            // Balanced: always assign to whichever team currently has fewer players,
            // so the final groups end up as equal as possible.
            int slotsA = Math.max(0, teamAServers.size() - teamAPlayers.size());
            int slotsB = Math.max(0, teamBServers.size() - teamBPlayers.size());
            List<Player> unassigned =
                    players.stream()
                            .filter(
                                    p ->
                                            !playerTeam.containsKey(p.getUniqueId())
                                                    && !spectators.contains(p.getUniqueId()))
                            .collect(Collectors.toList());
            Collections.shuffle(unassigned);
            Set<UUID> randomlyAssigned = new HashSet<>();
            int curA = teamAPlayers.size();
            int curB = teamBPlayers.size();
            for (Player p : unassigned) {
                // Prefer the team with fewer total players; fall back to the other if full.
                boolean preferA = (curA <= curB && slotsA > 0) || slotsB == 0;
                if (preferA && slotsA > 0) {
                    playerTeam.put(p.getUniqueId(), "a");
                    randomlyAssigned.add(p.getUniqueId());
                    curA++;
                    slotsA--;
                } else if (slotsB > 0) {
                    playerTeam.put(p.getUniqueId(), "b");
                    randomlyAssigned.add(p.getUniqueId());
                    curB++;
                    slotsB--;
                } else {
                    sendToLobby(p); // both teams full
                }
            }

            // Re-collect after random assignments
            List<Player> finalTeamA =
                    players.stream()
                            .filter(p -> "a".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());
            List<Player> finalTeamB =
                    players.stream()
                            .filter(p -> "b".equals(playerTeam.get(p.getUniqueId())))
                            .collect(Collectors.toList());

            assignPlayersToServers(finalTeamA, teamAServers);
            assignPlayersToServers(finalTeamB, teamBServers);

            // Players without a team assignment (spectators, etc.) stay in the lobby
            for (Player p : players) {
                if (!playerServer.containsKey(p.getUniqueId())) {
                    sendToLobby(p);
                }
            }

            // Send team info + roster to all assigned players
            String rosterA =
                    finalTeamA.stream().map(Player::getUsername).collect(Collectors.joining(", "));
            String rosterB =
                    finalTeamB.stream().map(Player::getUsername).collect(Collectors.joining(", "));
            String rosterMsg =
                    "§7" + teamNameA + ": §f" + rosterA + " §7| " + teamNameB + ": §f" + rosterB;
            for (Player p : players) {
                if (!playerServer.containsKey(p.getUniqueId())) continue;
                String t = playerTeam.get(p.getUniqueId());
                String assignedName = "a".equals(t) ? teamNameA : teamNameB;
                String msgKey =
                        randomlyAssigned.contains(p.getUniqueId())
                                ? "team_assignment_random"
                                : "team_assignment";
                p.sendMessage(Component.text(lang.get(msgKey, "team", assignedName)));
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
        for (Player player : getGameParticipants()) {
            String t = playerTeam.get(player.getUniqueId());
            if (team.toLowerCase().equals(t)) {
                player.sendMessage(Component.text(lang.get("game_team_wins", "team", winnerName)));
            } else {
                player.sendMessage(Component.text(lang.get("game_team_loses", "team", winnerName)));
            }
            sendToLobby(player);
        }
    }

    private void winGame() {
        endGame();
    }

    void endGame() {
        gameState = GameState.LOBBY;
        watchingPlayers.clear();
        byte[] resetMsg = buildMessage(out -> out.writeUTF("reset"));
        for (Player player : getGameParticipants()) {
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
    }

    private void sendToLobby(Player player) {
        server.getServer(lobbyServerName)
                .ifPresent(s -> player.createConnectionRequest(s).fireAndForget());
    }

    private boolean isAdmin(CommandSource source) {
        if (source == this.server.getConsoleCommandSource()) {
            return true;
        }

        if (source instanceof Player player) {
            // Check config-based admins
            String name = player.getUsername();
            String uuid = player.getUniqueId().toString();
            if (admins.contains(name) || admins.contains(uuid)) {
                return true;
            }

            // Check LuckPerms permission
            return source.hasPermission("swap.admin");
        }

        return false;
    }

    public PluginConfig getPluginConfig() {
        return config;
    }
}
