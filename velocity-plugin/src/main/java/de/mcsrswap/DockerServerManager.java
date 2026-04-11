package de.mcsrswap;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DockerServerManager {

    private final ProxyServer server;
    private final Logger logger;
    private final VelocitySwapPlugin plugin;

    private DockerClient dockerClient;
    private boolean dockerEnabled = false;
    private String gameServerImage = "mcsrswap-gameserver:latest";
    private String networkName = "mcsrswap-network";
    // Host-side data path root. This is derived from the MCSRSWAP_HOST_ROOT environment variable
    // and always points to <HOST_ROOT>/data. When running against the host Docker socket this
    // environment variable MUST be provided to avoid writing into container-local paths.
    private String dataPath;

    private final Map<String, String> serverContainers = new ConcurrentHashMap<>();

    public DockerServerManager(ProxyServer server, Logger logger, VelocitySwapPlugin plugin) {
        this.server = server;
        this.logger = logger;
        this.plugin = plugin;
    }

    public void initialize(Map<String, Object> dockerConfig) {
        if (dockerConfig == null) {
            dockerConfig = new HashMap<>();
        }

        // Auto-detect Docker environment via ENV variable
        String envDocker = System.getenv("MCSRSWAP_DOCKER_MODE");
        if (envDocker != null && envDocker.equalsIgnoreCase("true")) {
            logger.info("Docker environment detected via MCSRSWAP_DOCKER_MODE env variable");
            dockerEnabled = true;
        } else {
            dockerEnabled = (boolean) dockerConfig.getOrDefault("enabled", false);
        }

        if (!dockerEnabled) {
            logger.info("Docker integration disabled");
            return;
        }

        // Determine game server image. Priority: config.image > env MCSRSWAP_GAMESERVER_IMAGE >
        // derived from plugin version
        if (dockerConfig.containsKey("image")) {
            gameServerImage = dockerConfig.get("image").toString();
        } else if (System.getenv("MCSRSWAP_GAMESERVER_IMAGE") != null
                && !System.getenv("MCSRSWAP_GAMESERVER_IMAGE").isEmpty()) {
            gameServerImage = System.getenv("MCSRSWAP_GAMESERVER_IMAGE");
        } else {
            String base = "ghcr.io/relacibo/mcsr-swap-gameserver";
            String ver = getPluginVersion();
            if (ver == null || ver.isEmpty()) ver = "latest";
            gameServerImage = base + ":" + ver;
        }
        networkName = dockerConfig.getOrDefault("network", "mcsrswap-network").toString();

        // Initialize Docker client first so we can inspect containers if needed
        try {
            // Build Docker client config with optional host override
            DefaultDockerClientConfig.Builder configBuilder =
                    DefaultDockerClientConfig.createDefaultConfigBuilder();

            // Check for MCSRSWAP_DOCKER_HOST or fall back to DOCKER_HOST
            String dockerHost = System.getenv("MCSRSWAP_DOCKER_HOST");
            if (dockerHost == null || dockerHost.isEmpty()) {
                dockerHost = System.getenv("DOCKER_HOST");
            }
            if (dockerHost != null && !dockerHost.isEmpty()) {
                configBuilder.withDockerHost(dockerHost);
            }

            // Check for MCSRSWAP_DOCKER_NETWORK
            String dockerNetwork = System.getenv("MCSRSWAP_DOCKER_NETWORK");
            if (dockerNetwork != null && !dockerNetwork.isEmpty()) {
                networkName = dockerNetwork;
            }

            DefaultDockerClientConfig config = configBuilder.build();
            ApacheDockerHttpClient httpClient =
                    new ApacheDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .maxConnections(100)
                            .connectionTimeout(Duration.ofSeconds(30))
                            .responseTimeout(Duration.ofSeconds(45))
                            .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            logger.error("Failed to connect to Docker daemon. Disabling Docker integration.", e);
            dockerEnabled = false;
            return;
        }

        // Determine host-side data path. Try env MCSRSWAP_HOST_ROOT first; if not set try to derive
        // from the container's bind mount for /data by inspecting this container via Docker API.
        String hostRootEnv = System.getenv("MCSRSWAP_HOST_ROOT");
        if (hostRootEnv != null && !hostRootEnv.isEmpty()) {
            if (hostRootEnv.endsWith("/"))
                hostRootEnv = hostRootEnv.substring(0, hostRootEnv.length() - 1);
            this.dataPath = hostRootEnv + "/data";
        } else {
            try {
                // Find current container id from /proc/self/cgroup or hostname fallback
                String containerId = null;
                java.io.File cgroup = new java.io.File("/proc/self/cgroup");
                if (cgroup.exists()) {
                    try (java.io.BufferedReader br =
                            new java.io.BufferedReader(new java.io.FileReader(cgroup))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            int idx = line.lastIndexOf('/');
                            if (idx != -1) {
                                String candidate = line.substring(idx + 1);
                                if (candidate.length() >= 12) {
                                    containerId = candidate;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (containerId == null) {
                    try {
                        containerId = java.net.InetAddress.getLocalHost().getHostName();
                    } catch (Exception ignore) {
                    }
                }

                if (containerId == null)
                    throw new IllegalStateException(
                            "Cannot determine current container id to derive host path");

                var inspect = dockerClient.inspectContainerCmd(containerId).exec();
                var mounts = inspect.getMounts();
                String hostDataMount = null;
                if (mounts != null) {
                    for (Object m : mounts) {
                        // Debug: print mount information to help diagnose host mount issues
                        try {
                            String src = null;
                            String dest = null;
                            try {
                                java.lang.reflect.Method getSource =
                                        m.getClass().getMethod("getSource");
                                Object s = getSource.invoke(m);
                                if (s != null) src = s.toString();
                            } catch (Exception __e) {
                                // ignore
                            }
                            try {
                                java.lang.reflect.Method getDestination =
                                        m.getClass().getMethod("getDestination");
                                Object d = getDestination.invoke(m);
                                if (d != null) dest = d.toString();
                            } catch (Exception __e) {
                                // ignore
                            }

                            if (src != null || dest != null) {
                                logger.info("Found mount: source='{}' destination='{}'", src, dest);
                            } else {
                                logger.debug("Mount info: {}", m.toString());
                            }

                            if ("/data".equals(dest)) {
                                hostDataMount = src;
                                break;
                            }
                        } catch (Throwable __t) {
                            logger.debug("Mount info: {}", m.toString());
                        }
                    }
                }
                if (hostDataMount == null || hostDataMount.isEmpty()) {
                    // Fallback to a relative './data' path when host mount cannot be determined.
                    logger.warn(
                            "Could not find host mount for /data in this container; falling back to"
                                + " './data' relative path");
                    this.dataPath = "./data";
                } else {
                    java.nio.file.Path hostMountPath =
                            java.nio.file.Paths.get(hostDataMount).toAbsolutePath().normalize();
                    // If the mount source points to a subfolder like ".../data/velocity",
                    // prefer the parent (the actual host ./data) as the data root. If the
                    // mount already points to the host data folder, use it directly.
                    String fileName =
                            hostMountPath.getFileName() != null
                                    ? hostMountPath.getFileName().toString()
                                    : "";
                    if ("velocity".equals(fileName)) {
                        java.nio.file.Path hostRoot = hostMountPath.getParent();
                        if (hostRoot != null) {
                            this.dataPath = hostRoot.toString();
                        } else {
                            logger.warn(
                                    "Invalid host mount path for /data: {}. Falling back to"
                                        + " './data'",
                                    hostDataMount);
                            this.dataPath = "./data";
                        }
                    } else if ("data".equals(fileName)) {
                        // mount already points to the host data folder
                        this.dataPath = hostMountPath.toString();
                    } else {
                        // otherwise assume the host root is parent and append 'data'
                        java.nio.file.Path hostRoot = hostMountPath.getParent();
                        if (hostRoot != null) {
                            this.dataPath = hostRoot.resolve("data").toString();
                        } else {
                            logger.warn(
                                    "Unexpected host mount path for /data: {}. Falling back to"
                                        + " './data'",
                                    hostDataMount);
                            this.dataPath = "./data";
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn(
                        "Failed to derive host data path from container mount: {}. Falling back to"
                            + " './data'",
                        e.getMessage());
                this.dataPath = "./data";
            }
        }

        logger.info("Using data path: {}", this.dataPath);

        logger.info(
                "Docker integration enabled: image={}, network={}", gameServerImage, networkName);
    }

    private String getPluginVersion() {
        try (java.io.InputStream is =
                plugin.getClass().getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            if (is == null) return null;
            java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String content = s.hasNext() ? s.next() : "";
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"")
                            .matcher(content);
            if (m.find()) return m.group(1);
        } catch (Exception e) {
            logger.debug("Unable to read plugin version: {}", e.getMessage());
        }
        return null;
    }

    public boolean isDockerEnabled() {
        return dockerEnabled;
    }

    /** Pull the game server image from the registry. Always pulls to ensure the latest version. */
    public void pullImage() {
        logger.info("Pulling Docker image '{}'…", gameServerImage);
        try {
            dockerClient
                    .pullImageCmd(gameServerImage)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>())
                    .awaitCompletion(5, TimeUnit.MINUTES);
            logger.info("Successfully pulled image: {}", gameServerImage);
        } catch (Exception e) {
            logger.error("Failed to pull Docker image '{}': {}", gameServerImage, e.getMessage());
            throw new RuntimeException("Image pull failed: " + gameServerImage, e);
        }
    }

    /**
     * Start game servers and return a future that completes when all servers are healthy. Returns
     * the list of server names immediately, and the future completes when ready.
     */
    public java.util.concurrent.CompletableFuture<List<String>> startServersAsync(int count) {
        List<String> serverNames = startServersInternal(count);
        if (serverNames.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(serverNames);
        }
        return waitForServersReady(serverNames)
                .thenApply(
                        healthy -> {
                            if (!healthy) {
                                logger.error(
                                        "Servers failed to become healthy. Aborting game start.");
                                return Collections.<String>emptyList();
                            }
                            return serverNames;
                        });
    }

    public List<String> startServers(int count) {
        return startServersInternal(count);
    }

    private List<String> startServersInternal(int count) {
        if (!dockerEnabled) {
            logger.warn("Docker is disabled, cannot start servers");
            return Collections.emptyList();
        }

        // Get config from plugin
        PluginConfig config = plugin.getPluginConfig();

        // Image was already pulled before containers are started; nothing to do here.

        List<String> serverNames = new ArrayList<>();

        // Build seed list: use configured seeds first, fill remainder with random values
        java.util.Random random = new java.util.Random();
        List<Long> configSeeds = plugin.worldSeeds;
        List<Long> seeds = new ArrayList<>();
        boolean versus = plugin.versusMode;
        int uniqueSeedCount = versus ? (count / 2) : count;
        for (int i = 0; i < uniqueSeedCount; i++) {
            Long configured = i < configSeeds.size() ? configSeeds.get(i) : null;
            seeds.add(configured != null ? configured : random.nextLong());
        }

        for (int i = 0; i < count; i++) {
            String serverName = config.gameServerPrefix + (i + 1);
            // In versus mode, pair seeds: servers 0&2, 1&3 get same seed
            int seedIndex = versus ? (i % uniqueSeedCount) : i;
            long worldSeed = seeds.get(seedIndex);
            try {
                String containerId = createGameServer(serverName, worldSeed);
                serverContainers.put(serverName, containerId);

                // Use container name as hostname (Docker network) with internal port 25565
                String containerName = "mcsrswap-" + serverName;
                InetSocketAddress address = new InetSocketAddress(containerName, 25565);
                ServerInfo serverInfo = new ServerInfo(serverName, address);
                server.registerServer(serverInfo);

                serverNames.add(serverName);
                logger.info(
                        "Started game server: {} (container: {})",
                        serverName,
                        containerId.substring(0, 12));

            } catch (Exception e) {
                logger.error(
                        "Failed to start server {} (image: {})", serverName, gameServerImage, e);
            }
        }

        return serverNames;
    }

    private String createGameServer(String serverName, long seed) {
        String containerName = "mcsrswap-" + serverName;
        String volumeNameForServer = "mcsrswap-" + serverName;
        String seedStr = String.valueOf(seed);

        // Check if container already exists
        try {
            List<Container> existing =
                    dockerClient
                            .listContainersCmd()
                            .withNameFilter(Collections.singletonList(containerName))
                            .withShowAll(true)
                            .exec();

            for (Container c : existing) {
                // Docker name filter matches substrings, so verify exact match
                // Container names have a leading slash in the API
                boolean exactMatch = false;
                for (String name : c.getNames()) {
                    if (name.equals("/" + containerName) || name.equals(containerName)) {
                        exactMatch = true;
                        break;
                    }
                }
                if (!exactMatch) {
                    continue;
                }

                String containerId = c.getId();
                String state = c.getState();
                Map<String, String> labels = c.getLabels();

                // Check if this is our managed container
                boolean isManaged = labels != null && "true".equals(labels.get("mcsrswap.managed"));

                if (!isManaged) {
                    // Container exists but isn't ours - remove it
                    logger.warn(
                            "Removing orphaned container {} (not managed by us)", containerName);
                    try {
                        if ("running".equals(state)) {
                            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
                        }
                        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    } catch (Exception e) {
                        logger.error(
                                "Failed to remove orphaned container {}: {}",
                                containerName,
                                e.getMessage());
                    }
                    continue;
                }

                if ("running".equals(state)) {
                    logger.info("Container {} is already running", containerName);
                    return containerId;
                } else {
                    // Container exists but not running - try to start it
                    logger.info("Starting existing container: {}", containerName);
                    try {
                        dockerClient.startContainerCmd(containerId).exec();
                        return containerId;
                    } catch (Exception startError) {
                        logger.warn(
                                "Failed to start existing container {}: {}. Removing and"
                                        + " recreating.",
                                containerName,
                                startError.getMessage());
                        try {
                            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                        } catch (Exception removeError) {
                            logger.error(
                                    "Failed to remove broken container {}: {}",
                                    containerName,
                                    removeError.getMessage());
                        }
                        // Fall through to create new container
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to check existing container {}: {}", containerName, e.getMessage());
        }

        // Use named volume for gameserver data (Docker manages it automatically)

        java.nio.file.Path secretFile =
                java.nio.file.Paths.get(
                        System.getenv()
                                .getOrDefault(
                                        "VELOCITY_SECRET_FILE",
                                        plugin.dataDirectory
                                                .resolve("../../forwarding.secret")
                                                .toAbsolutePath()
                                                .normalize()
                                                .toString()));
        String fabricProxySecret = "";
        try {
            fabricProxySecret = java.nio.file.Files.readString(secretFile).strip();
        } catch (Exception e) {
            logger.warn(
                    "Could not read Velocity forwarding secret from {} – game servers will have"
                            + " an empty FABRIC_PROXY_SECRET: {}",
                    secretFile,
                    e.getMessage());
        }

        List<String> env =
                new java.util.ArrayList<>(
                        List.of(
                                "EULA=TRUE",
                                "ONLINE_MODE=FALSE",
                                "SERVER_PORT=25565",
                                "MEMORY=2G",
                                "TYPE=FABRIC",
                                "VERSION=1.16.1",
                                "FABRIC_PROXY_VELOCITY=true",
                                "FABRIC_PROXY_SECRET=" + fabricProxySecret,
                                "SEED=" + seedStr,
                                "PUID=" + System.getenv().getOrDefault("PUID", "1000"),
                                "PGID=" + System.getenv().getOrDefault("PGID", "1000")));

        CreateContainerResponse container =
                dockerClient
                        .createContainerCmd(gameServerImage)
                        .withName("mcsrswap-" + serverName)
                        .withLabels(
                                Map.of("mcsrswap.managed", "true", "mcsrswap.server", serverName))
                        .withEnv(env.toArray(new String[0]))
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withNetworkMode(networkName)
                                        .withMemory(2147483648L)
                                        .withBinds(new Bind(volumeNameForServer, new Volume("/data"))))
                        .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    /**
     * Wait for all servers to become healthy (using Docker health check). Returns a
     * CompletableFuture that completes when all servers are ready.
     */
    public java.util.concurrent.CompletableFuture<Boolean> waitForServersReady(
            List<String> serverNames) {
        logger.info("Waiting for {} servers to become healthy...", serverNames.size());

        java.util.concurrent.CompletableFuture<Boolean> future =
                new java.util.concurrent.CompletableFuture<>();
        Set<String> pendingServers = ConcurrentHashMap.newKeySet();
        pendingServers.addAll(serverNames);

        // Check health every 2 seconds for up to 120 seconds
        final int maxAttempts = 60;
        final int[] attempt = {0};

        server.getScheduler()
                .buildTask(
                        plugin,
                        new Runnable() {
                            @Override
                            public void run() {
                                attempt[0]++;

                                for (String serverName : new ArrayList<>(pendingServers)) {
                                    String containerId = serverContainers.get(serverName);
                                    if (containerId == null) {
                                        logger.warn("No container ID for server {}", serverName);
                                        pendingServers.remove(serverName);
                                        continue;
                                    }

                                    try {
                                        var inspect =
                                                dockerClient
                                                        .inspectContainerCmd(containerId)
                                                        .exec();
                                        var state = inspect.getState();
                                        var health = state.getHealth();

                                        if (health != null
                                                && "healthy".equals(health.getStatus())) {
                                            logger.info("Server {} is healthy", serverName);
                                            pendingServers.remove(serverName);
                                        } else if (state.getRunning() != null
                                                && state.getRunning()) {
                                            String healthStatus =
                                                    health != null
                                                            ? health.getStatus()
                                                            : "no-healthcheck";
                                            logger.debug(
                                                    "Server {} health: {}",
                                                    serverName,
                                                    healthStatus);
                                        } else {
                                            // Container not running - might have crashed
                                            String status = state.getStatus();
                                            Integer exitCode = state.getExitCode();
                                            logger.warn(
                                                    "Server {} container not running: status={},"
                                                            + " exitCode={}",
                                                    serverName,
                                                    status,
                                                    exitCode);
                                            pendingServers.remove(serverName);
                                        }
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to check health of {}: {}",
                                                serverName,
                                                e.getMessage());
                                    }
                                }

                                if (pendingServers.isEmpty()) {
                                    logger.info(
                                            "All {} servers are healthy! Waiting for ping...",
                                            serverNames.size());
                                    // Now verify servers are actually pingable via Velocity
                                    waitForServersPingable(serverNames)
                                            .thenAccept(pingResult -> future.complete(pingResult));
                                } else if (attempt[0] >= maxAttempts) {
                                    logger.error(
                                            "Timeout waiting for servers to become healthy. Still"
                                                    + " pending: {}",
                                            pendingServers);
                                    future.complete(false);
                                } else {
                                    // Schedule next check
                                    server.getScheduler()
                                            .buildTask(plugin, this)
                                            .delay(2, TimeUnit.SECONDS)
                                            .schedule();
                                }
                            }
                        })
                .delay(2, TimeUnit.SECONDS)
                .schedule();

        return future;
    }

    public void stopAllServers() {
        if (!dockerEnabled) return;

        // Discover all managed containers via label so we catch containers that were started by a
        // previous plugin instance (after a proxy restart the in-memory map is empty).
        Map<String, String> toStop = new java.util.LinkedHashMap<>(serverContainers);
        try {
            dockerClient
                    .listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(List.of("mcsrswap.managed=true"))
                    .exec()
                    .forEach(
                            c -> {
                                String name =
                                        c.getNames() != null && c.getNames().length > 0
                                                ? c.getNames()[0].replaceFirst("^/", "")
                                                : c.getId();
                                toStop.putIfAbsent(name, c.getId());
                            });
        } catch (Exception e) {
            logger.warn(
                    "Could not list managed containers via label; relying on tracked map: {}",
                    e.getMessage());
        }

        logger.info("Stopping {} game server(s)...", toStop.size());

        for (Map.Entry<String, String> entry : toStop.entrySet()) {
            String containerRef = entry.getKey();
            String containerId = entry.getValue();
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                // Derive logical server name from container name (strip "mcsrswap-" prefix)
                String serverName =
                        containerRef.startsWith("mcsrswap-")
                                ? containerRef.substring("mcsrswap-".length())
                                : containerRef;
                server.getServer(serverName)
                        .ifPresent(rs -> server.unregisterServer(rs.getServerInfo()));
                logger.info("Stopped and removed container: {}", containerRef);
            } catch (Exception e) {
                logger.error("Failed to stop/remove container {}", containerRef, e);
            }
        }

        serverContainers.clear();
    }

    public void removeAllData() {
        if (!dockerEnabled) return;

        logger.info("Removing all game server volumes...");

        // List all Docker volumes and find ones matching our pattern
        try {
            var volumesResponse = dockerClient.listVolumesCmd().exec();
            var volumes = volumesResponse.getVolumes();

            if (volumes == null) {
                logger.warn("No volumes found");
                return;
            }

            logger.info("Found {} total volumes", volumes.size());
            int removedCount = 0;

            for (var volume : volumes) {
                String volumeName = volume.getName();
                logger.info("Checking volume: {} (looking for prefix: mcsrswap-game)", volumeName);

                if (volumeName.startsWith("mcsrswap-game")) {
                    try {
                        logger.info("Attempting to remove volume: {}", volumeName);
                        dockerClient.removeVolumeCmd(volumeName).exec();
                        logger.info("Successfully removed volume: {}", volumeName);
                        removedCount++;
                    } catch (Exception e) {
                        logger.error(
                                "Failed to remove volume {}: {}", volumeName, e.getMessage(), e);
                    }
                } else {
                    logger.debug("Skipping volume {} (doesn't match prefix)", volumeName);
                }
            }

            if (removedCount == 0) {
                logger.info("No game server volumes found to remove");
            } else {
                logger.info("Removed {} game server volume(s)", removedCount);
            }
        } catch (Exception e) {
            logger.error("Failed to list volumes", e);
        }
    }

    public List<String> getRunningServers() {
        if (!dockerEnabled) {
            return new ArrayList<>(plugin.gameServers);
        }
        return new ArrayList<>(serverContainers.keySet());
    }

    public Map<String, String> getServerContainers() {
        return new HashMap<>(serverContainers);
    }

    public void cleanup() {
        if (dockerEnabled && dockerClient != null) {
            try {
                // Stop containers first
                stopAllServers();

                // Remove server data directories
                removeAllData();

                // Clear tracked containers and close client
                serverContainers.clear();

                dockerClient.close();
            } catch (Exception e) {
                logger.error("Error during Docker cleanup", e);
            }
        }
    }

    public void shutdown() {
        cleanup();
    }

    /**
     * Wait for servers to be pingable via Velocity's ping API. This ensures the Minecraft server is
     * actually accepting connections.
     */
    private java.util.concurrent.CompletableFuture<Boolean> waitForServersPingable(
            List<String> serverNames) {
        logger.info("Verifying {} servers are pingable...", serverNames.size());

        java.util.concurrent.CompletableFuture<Boolean> future =
                new java.util.concurrent.CompletableFuture<>();
        Set<String> pendingServers = ConcurrentHashMap.newKeySet();
        pendingServers.addAll(serverNames);

        final int maxAttempts = 30; // 30 seconds timeout
        final int[] attempt = {0};

        server.getScheduler()
                .buildTask(
                        plugin,
                        new Runnable() {
                            @Override
                            public void run() {
                                attempt[0]++;

                                for (String serverName : new ArrayList<>(pendingServers)) {
                                    server.getServer(serverName)
                                            .ifPresent(
                                                    registeredServer -> {
                                                        registeredServer
                                                                .ping()
                                                                .whenComplete(
                                                                        (ping, error) -> {
                                                                            if (error == null) {
                                                                                logger.info(
                                                                                        "Server {}"
                                                                                            + " is pingable",
                                                                                        serverName);
                                                                                pendingServers
                                                                                        .remove(
                                                                                                serverName);
                                                                            } else {
                                                                                logger.debug(
                                                                                        "Server {}"
                                                                                            + " ping"
                                                                                            + " failed:"
                                                                                            + " {}",
                                                                                        serverName,
                                                                                        error
                                                                                                .getMessage());
                                                                            }
                                                                        });
                                                    });
                                }

                                // Check after a short delay to allow ping responses
                                server.getScheduler()
                                        .buildTask(
                                                plugin,
                                                () -> {
                                                    if (pendingServers.isEmpty()) {
                                                        logger.info(
                                                                "All {} servers are pingable and"
                                                                        + " ready!",
                                                                serverNames.size());
                                                        future.complete(true);
                                                    } else if (attempt[0] >= maxAttempts) {
                                                        logger.error(
                                                                "Timeout waiting for servers to"
                                                                        + " become pingable. Still"
                                                                        + " pending: {}",
                                                                pendingServers);
                                                        future.complete(false);
                                                    } else {
                                                        // Schedule next attempt
                                                        server.getScheduler()
                                                                .buildTask(plugin, this)
                                                                .delay(1, TimeUnit.SECONDS)
                                                                .schedule();
                                                    }
                                                })
                                        .delay(500, TimeUnit.MILLISECONDS)
                                        .schedule();
                            }
                        })
                .delay(1, TimeUnit.SECONDS)
                .schedule();

        return future;
    }
}
