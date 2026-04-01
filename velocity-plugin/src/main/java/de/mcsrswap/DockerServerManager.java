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
    private int minPort = 25600;
    private int maxPort = 25650;
    private String dataPath = "./server-data";
    
    private final Map<String, String> serverContainers = new ConcurrentHashMap<>();
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

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
        
        gameServerImage = dockerConfig.getOrDefault("gameServerImage", 
                System.getenv("MCSRSWAP_GAMESERVER_IMAGE") != null 
                    ? System.getenv("MCSRSWAP_GAMESERVER_IMAGE") 
                    : "ghcr.io/" + System.getenv("GITHUB_REPOSITORY") + "-gameserver:latest"
        ).toString();
        networkName = dockerConfig.getOrDefault("network", "mcsrswap-network").toString();
        minPort = (int) dockerConfig.getOrDefault("minPort", 25600);
        maxPort = (int) dockerConfig.getOrDefault("maxPort", 25650);
        
        // Support for XDG Base Directory and relative paths
        String configuredPath = dockerConfig.getOrDefault("dataPath", "").toString();
        if (configuredPath.isEmpty()) {
            // Try XDG_DATA_HOME first, then fallback to ./server-data
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null && !xdgDataHome.isEmpty()) {
                dataPath = xdgDataHome + "/mcsrswap/servers";
            } else {
                String home = System.getenv("HOME");
                if (home != null && !home.isEmpty()) {
                    dataPath = home + "/.local/share/mcsrswap/servers";
                } else {
                    dataPath = "./server-data";
                }
            }
        } else {
            dataPath = configuredPath;
        }
        
        // Make path absolute
        java.nio.file.Path absolutePath = java.nio.file.Paths.get(dataPath).toAbsolutePath();
        dataPath = absolutePath.toString();
        
        logger.info("Using data path: {}", dataPath);
        
        try {
            DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();
            
            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            
            dockerClient.pingCmd().exec();
            logger.info("Docker integration enabled: image={}, network={}, ports={}-{}", 
                    gameServerImage, networkName, minPort, maxPort);
        } catch (Exception e) {
            logger.error("Failed to connect to Docker daemon. Disabling Docker integration.", e);
            dockerEnabled = false;
        }
    }

    public boolean isDockerEnabled() {
        return dockerEnabled;
    }

    public List<String> startServers(int count) {
        if (!dockerEnabled) {
            logger.warn("Docker is disabled, cannot start servers");
            return Collections.emptyList();
        }
        
        List<String> serverNames = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            String serverName = plugin.gameServerPrefix + (i + 1);
            try {
                int port = allocatePort();
                if (port == -1) {
                    logger.error("No available ports for server {}", serverName);
                    continue;
                }
                
                String containerId = createGameServer(serverName, port);
                serverContainers.put(serverName, containerId);
                
                InetSocketAddress address = new InetSocketAddress("localhost", port);
                ServerInfo serverInfo = new ServerInfo(serverName, address);
                server.registerServer(serverInfo);
                
                serverNames.add(serverName);
                logger.info("Started game server: {} (container: {}, port: {})", serverName, containerId.substring(0, 12), port);
                
            } catch (Exception e) {
                logger.error("Failed to start server {}", serverName, e);
            }
        }
        
        if (!serverNames.isEmpty()) {
            waitForServersReady(serverNames);
        }
        
        return serverNames;
    }

    private String createGameServer(String serverName, int port) {
        // Create host directory for server data
        String hostDataPath = dataPath + "/" + serverName;
        java.nio.file.Path path = java.nio.file.Paths.get(hostDataPath);
        try {
            java.nio.file.Files.createDirectories(path);
            logger.info("Created data directory: {}", hostDataPath);
        } catch (Exception e) {
            logger.warn("Could not create data directory {}: {}", hostDataPath, e.getMessage());
        }
        
        CreateContainerResponse container = dockerClient.createContainerCmd(gameServerImage)
                .withName("mcsrswap-" + serverName)
                .withEnv(
                        "EULA=TRUE",
                        "ONLINE_MODE=FALSE",
                        "SERVER_PORT=25565",
                        "MEMORY=2G",
                        "TYPE=FABRIC",
                        "VERSION=1.16.1"
                )
                .withHostConfig(
                        HostConfig.newHostConfig()
                                .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), ExposedPort.tcp(25565)))
                                .withNetworkMode(networkName)
                                .withMemory(2147483648L)
                                .withBinds(new Bind(hostDataPath, new Volume("/data")))
                )
                .withLabels(Collections.singletonMap("mcsrswap.server", serverName))
                .exec();
        
        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    private int allocatePort() {
        for (int port = minPort; port <= maxPort; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        return -1;
    }

    private void waitForServersReady(List<String> serverNames) {
        logger.info("Waiting for {} servers to become ready...", serverNames.size());
        
        server.getScheduler().buildTask(plugin, () -> {
            for (String name : serverNames) {
                server.getServer(name).ifPresent(rs -> {
                    rs.ping().thenAccept(ping -> {
                        logger.info("Server {} is ready (players: {}/{})", 
                                name, ping.getPlayers().orElse(null));
                    }).exceptionally(ex -> {
                        logger.warn("Server {} not yet ready", name);
                        return null;
                    });
                });
            }
        }).delay(10, TimeUnit.SECONDS).schedule();
    }

    public void stopAllServers() {
        if (!dockerEnabled) return;
        
        logger.info("Stopping {} game servers...", serverContainers.size());
        
        for (Map.Entry<String, String> entry : serverContainers.entrySet()) {
            String serverName = entry.getKey();
            String containerId = entry.getValue();
            
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                
                server.getServer(serverName).ifPresent(rs -> server.unregisterServer(rs.getServerInfo()));
                
                logger.info("Stopped server: {}", serverName);
            } catch (Exception e) {
                logger.error("Failed to stop server {}", serverName, e);
            }
        }
        
        serverContainers.clear();
        usedPorts.clear();
    }

    public List<String> getRunningServers() {
        if (!dockerEnabled) {
            return new ArrayList<>(plugin.gameServers);
        }
        return new ArrayList<>(serverContainers.keySet());
    }

    public void cleanup() {
        if (dockerEnabled && dockerClient != null) {
            try {
                stopAllServers();
                dockerClient.close();
            } catch (Exception e) {
                logger.error("Error during Docker cleanup", e);
            }
        }
    }
}
