package de.mcsrswap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed representation of the plugin config (minimal, immutable). Add fields here as needed and use
 * PluginConfig.fromMap(map) to construct from the parsed YAML/Map used today. This class also reads
 * a small set of environment variables to allow runtime overrides when running in Docker.
 */
public final class PluginConfig {
    // Default values
    private static final int DEFAULT_ROTATION_TIME = 120;
    private static final double DEFAULT_REQUIRED_PERCENTAGE = 1.0;
    private static final boolean DEFAULT_VERSUS = false;
    private static final String DEFAULT_LANGUAGE = "en_US";
    private static final String DEFAULT_GAME_SERVER_PREFIX = "game";
    private static final String DEFAULT_LOBBY_SERVER_NAME = "lobby";
    private static final String DEFAULT_LIMBO_SERVER_NAME = "limbo";
    private static final boolean DEFAULT_SPECTATE_AFTER_WIN = true;
    private static final String DEFAULT_SPECTATE_TARGET = "next";
    private static final boolean DEFAULT_SAVE_HOTBAR = true;
    private static final int DEFAULT_EYE_HOVER_TICKS = 80;

    // Docker defaults
    private static final boolean DEFAULT_DOCKER_ENABLED = false;
    private static final String DEFAULT_DOCKER_IMAGE = "mcsrswap-gameserver:latest";
    private static final String DEFAULT_DOCKER_NETWORK = "mcsrswap-network";
    private static final String DEFAULT_DOCKER_HOST = null;
    private static final String DEFAULT_DOCKER_GAME_DATA_DIR = null;

    public final int rotationTime;
    public final double requiredPercentage;
    public final boolean versus;

    // general settings
    public final String language; // normalized form like en_US
    public final String gameServerPrefix;
    public final String lobbyServerName;
    public final String limboServerName;
    public final boolean spectateAfterWin;
    public final String spectateTarget;
    public final boolean saveHotbar;
    public final int eyeHoverTicks;

    public final List<String> admins;
    /** Players pre-configured to be ignored at game start (names or UUIDs). */
    public final List<String> ignorePlayers;
    public final List<Long> worldSeeds;
    public final Docker docker;

    public static final class Docker {
        public final boolean enabled;
        public final String image;
        public final String network;
        public final String host;
        /** Host-side absolute path for game server data directories (bind-mounted into game containers). */
        public final String gameDataDir;
        /**
         * When {@code true}, the lobby container is stopped once a game starts and restarted when the
         * game ends. Controlled by {@code docker.autoStopLobby: true} or the env variable
         * {@code MCSRSWAP_AUTO_STOP_LOBBY=true}. Default: {@code false}.
         */
        public final boolean autoStopLobby;
        /**
         * Externally-hosted game servers (on other machines) included in the game rotation alongside
         * Docker-managed containers. These are registered with Velocity at startup and included in
         * the readiness ping-check. Configured via {@code docker.externalServers} in config.yml or
         * {@code MCSRSWAP_EXTERNAL_SERVERS=name:host:port,...} in the environment.
         */
        public final List<ExternalServer> externalServers;

        /** A game server that runs outside Docker (e.g. on another physical host). */
        public static final class ExternalServer {
            public final String name;
            public final String host;
            public final int port;

            public ExternalServer(String name, String host, int port) {
                this.name = name;
                this.host = host;
                this.port = port;
            }

            @Override
            public String toString() {
                return name + ":" + host + ":" + port;
            }
        }

        public Docker(
                boolean enabled,
                String image,
                String network,
                String host,
                String gameDataDir,
                boolean autoStopLobby,
                List<ExternalServer> externalServers) {
            this.enabled = enabled;
            this.image = image;
            this.network = network;
            this.host = host;
            this.gameDataDir = gameDataDir;
            this.autoStopLobby = autoStopLobby;
            this.externalServers =
                    externalServers == null ? List.of() : List.copyOf(externalServers);
        }

        @Override
        public String toString() {
            return "Docker{enabled="
                    + enabled
                    + ", image='"
                    + image
                    + "', network='"
                    + network
                    + "', host='"
                    + host
                    + "', gameDataDir='"
                    + gameDataDir
                    + "', autoStopLobby="
                    + autoStopLobby
                    + ", externalServers="
                    + externalServers
                    + "'}";
        }
    }

    public PluginConfig(
            int rotationTime,
            double requiredPercentage,
            boolean versus,
            String language,
            String gameServerPrefix,
            String lobbyServerName,
            String limboServerName,
            boolean spectateAfterWin,
            String spectateTarget,
            boolean saveHotbar,
            int eyeHoverTicks,
            List<String> admins,
            List<String> ignorePlayers,
            List<Long> worldSeeds,
            Docker docker) {
        this.rotationTime = rotationTime;
        this.requiredPercentage = requiredPercentage;
        this.versus = versus;
        this.language = language;
        this.gameServerPrefix = gameServerPrefix;
        this.lobbyServerName = lobbyServerName;
        this.limboServerName = limboServerName;
        this.spectateAfterWin = spectateAfterWin;
        this.spectateTarget = spectateTarget;
        this.saveHotbar = saveHotbar;
        this.eyeHoverTicks = eyeHoverTicks;
        this.admins = admins == null ? List.of() : List.copyOf(admins);
        this.ignorePlayers = ignorePlayers == null ? List.of() : List.copyOf(ignorePlayers);
        this.worldSeeds = worldSeeds == null ? List.of() : List.copyOf(worldSeeds);
        this.docker = docker;
    }

    @SuppressWarnings("unchecked")
    public static PluginConfig fromMap(Map<String, Object> map) {
        if (map == null) map = Map.of();

        int rotation =
                toInt(
                        map.getOrDefault(
                                "rotationTime",
                                map.getOrDefault("rotation", DEFAULT_ROTATION_TIME)));
        Object req =
                map.getOrDefault(
                        "requiredPercentage",
                        map.getOrDefault(
                                "required",
                                map.getOrDefault(
                                        "requiredPercentage", DEFAULT_REQUIRED_PERCENTAGE)));
        double requiredPercentage = toDouble(req, DEFAULT_REQUIRED_PERCENTAGE);
        boolean versus = toBoolean(map.getOrDefault("versus", DEFAULT_VERSUS));

        Object langObj = map.getOrDefault("language", DEFAULT_LANGUAGE);
        String language = normalizeLanguage(Objects.toString(langObj, DEFAULT_LANGUAGE));

        String gameServerPrefix =
                Objects.toString(map.getOrDefault("gameServerPrefix", DEFAULT_GAME_SERVER_PREFIX));
        String lobbyServerName =
                Objects.toString(map.getOrDefault("lobbyServerName", DEFAULT_LOBBY_SERVER_NAME));
        String limboServerName =
                Objects.toString(map.getOrDefault("limboServerName", DEFAULT_LIMBO_SERVER_NAME));
        boolean spectateAfterWin =
                toBoolean(map.getOrDefault("spectateAfterWin", DEFAULT_SPECTATE_AFTER_WIN));
        String spectateTarget =
                Objects.toString(map.getOrDefault("spectateTarget", DEFAULT_SPECTATE_TARGET));
        boolean saveHotbar = toBoolean(map.getOrDefault("saveHotbar", DEFAULT_SAVE_HOTBAR));
        int eyeHoverTicks = toInt(map.getOrDefault("eyeHoverTicks", DEFAULT_EYE_HOVER_TICKS));

        List<String> admins = new ArrayList<>();
        Object adminsObj = map.get("admins");
        if (adminsObj instanceof List) {
            for (Object o : (List<Object>) adminsObj) {
                admins.add(Objects.toString(o, ""));
            }
        }

        List<String> ignorePlayers = new ArrayList<>();
        Object ignoreObj = map.get("ignorePlayers");
        if (ignoreObj instanceof List) {
            for (Object o : (List<Object>) ignoreObj) {
                String entry = Objects.toString(o, "").trim();
                if (!entry.isEmpty()) ignorePlayers.add(entry);
            }
        }

        List<Long> worldSeeds = new ArrayList<>();
        Object seedsObj = map.get("worldSeeds");
        if (seedsObj instanceof List) {
            for (Object o : (List<Object>) seedsObj) {
                try {
                    if (o instanceof Number) {
                        worldSeeds.add(((Number) o).longValue());
                    } else {
                        worldSeeds.add(Long.parseLong(Objects.toString(o).trim()));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // docker subsection (may be nested under 'docker' or 'dockerConfig')
        Map<String, Object> dockerMap = null;
        Object maybeDocker = map.get("docker");
        if (maybeDocker instanceof Map) dockerMap = (Map<String, Object>) maybeDocker;
        else if (map.get("dockerConfig") instanceof Map)
            dockerMap = (Map<String, Object>) map.get("dockerConfig");

        // Collect docker config into mutable locals so env overrides can be applied cleanly.
        boolean dockerEnabled = DEFAULT_DOCKER_ENABLED;
        String dockerImage = DEFAULT_DOCKER_IMAGE;
        String dockerNetwork = DEFAULT_DOCKER_NETWORK;
        String dockerHost = DEFAULT_DOCKER_HOST;
        String dockerGameDataDir = DEFAULT_DOCKER_GAME_DATA_DIR;
        boolean dockerAutoStopLobby = false;
        List<Docker.ExternalServer> dockerExternalServers = new ArrayList<>();

        if (dockerMap != null) {
            dockerEnabled = toBoolean(dockerMap.getOrDefault("enabled", DEFAULT_DOCKER_ENABLED));
            dockerImage =
                    Objects.toString(dockerMap.getOrDefault("image", DEFAULT_DOCKER_IMAGE));
            dockerNetwork =
                    Objects.toString(dockerMap.getOrDefault("network", DEFAULT_DOCKER_NETWORK));
            if (dockerMap.containsKey("host"))
                dockerHost = Objects.toString(dockerMap.get("host"));
            if (dockerMap.containsKey("gameDataDir"))
                dockerGameDataDir = Objects.toString(dockerMap.get("gameDataDir"));
            dockerAutoStopLobby =
                    toBoolean(dockerMap.getOrDefault("autoStopLobby", false));
            Object extObj = dockerMap.get("externalServers");
            if (extObj instanceof List) {
                for (Object entry : (List<?>) extObj) {
                    if (entry instanceof Map) {
                        Map<?, ?> em = (Map<?, ?>) entry;
                        String extName = Objects.toString(em.get("name"), "").trim();
                        String extHost = Objects.toString(em.get("host"), "").trim();
                        int extPort = 25565;
                        Object portObj = em.get("port");
                        if (portObj != null) {
                            extPort = toInt(portObj, 25565);
                        }
                        if (!extName.isEmpty() && !extHost.isEmpty()) {
                            dockerExternalServers.add(
                                    new Docker.ExternalServer(extName, extHost, extPort));
                        }
                    }
                }
            }
        }

        // Environment overrides (useful when running in Docker compose)
        String envLobby = System.getenv("MCSRSWAP_LOBBY_ADDRESS");
        if (envLobby != null && !envLobby.isBlank()) {
            lobbyServerName = envLobby;
        }
        String envLimbo = System.getenv("MCSRSWAP_LIMBO_ADDRESS");
        if (envLimbo != null && !envLimbo.isBlank()) {
            limboServerName = envLimbo;
        }
        String envImage = System.getenv("MCSRSWAP_GAMESERVER_IMAGE");
        if (envImage != null && !envImage.isBlank()) dockerImage = envImage;
        String envNetwork = System.getenv("MCSRSWAP_DOCKER_NETWORK");
        if (envNetwork != null && !envNetwork.isBlank()) dockerNetwork = envNetwork;
        String envHost = System.getenv("MCSRSWAP_DOCKER_HOST");
        if (envHost == null || envHost.isBlank()) envHost = System.getenv("DOCKER_HOST");
        if (envHost != null && !envHost.isBlank()) dockerHost = envHost;
        String envDockerMode = System.getenv("MCSRSWAP_DOCKER_MODE");
        if (envDockerMode != null
                && (envDockerMode.equalsIgnoreCase("true") || envDockerMode.equals("1"))) {
            dockerEnabled = true;
        }
        String envGameDataDir = System.getenv("GAME_DATA_DIR");
        if (envGameDataDir != null && !envGameDataDir.isBlank()) dockerGameDataDir = envGameDataDir;
        // MCSRSWAP_AUTO_STOP_LOBBY=true — stop lobby container during game, restart on end
        String envAutoStopLobby = System.getenv("MCSRSWAP_AUTO_STOP_LOBBY");
        if (envAutoStopLobby != null && !envAutoStopLobby.isBlank()) {
            dockerAutoStopLobby = toBoolean(envAutoStopLobby);
        }
        // MCSRSWAP_EXTERNAL_SERVERS=name:host:port,name2:host2:port2
        String envExternal = System.getenv("MCSRSWAP_EXTERNAL_SERVERS");
        if (envExternal != null && !envExternal.isBlank()) {
            for (String entry : envExternal.split(",")) {
                String[] parts = entry.trim().split(":");
                if (parts.length >= 3) {
                    String extName = parts[0].trim();
                    String extHost = parts[1].trim();
                    int extPort = toInt(parts[2].trim(), 25565);
                    if (!extName.isEmpty() && !extHost.isEmpty()) {
                        dockerExternalServers.add(
                                new Docker.ExternalServer(extName, extHost, extPort));
                    }
                }
            }
        }

        Docker docker =
                new Docker(
                        dockerEnabled,
                        dockerImage,
                        dockerNetwork,
                        dockerHost,
                        dockerGameDataDir,
                        dockerAutoStopLobby,
                        dockerExternalServers);

        String envWorldSeeds = System.getenv("MCSRSWAP_WORLD_SEEDS");
        if (envWorldSeeds != null && !envWorldSeeds.isBlank()) {
            worldSeeds.clear();
            for (String part : envWorldSeeds.split(",")) {
                try {
                    worldSeeds.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String envAdmins = System.getenv("MCSRSWAP_ADMINS");
        if (envAdmins != null && !envAdmins.isBlank()) {
            for (String part : envAdmins.split(",")) {
                String entry = part.trim();
                if (!entry.isEmpty()) admins.add(entry);
            }
        }

        String envIgnore = System.getenv("MCSRSWAP_IGNORE_PLAYERS");
        if (envIgnore != null && !envIgnore.isBlank()) {
            for (String part : envIgnore.split(",")) {
                String entry = part.trim();
                if (!entry.isEmpty()) ignorePlayers.add(entry);
            }
        }

        return new PluginConfig(
                rotation,
                requiredPercentage,
                versus,
                language,
                gameServerPrefix,
                lobbyServerName,
                limboServerName,
                spectateAfterWin,
                spectateTarget,
                saveHotbar,
                eyeHoverTicks,
                admins,
                ignorePlayers,
                worldSeeds,
                docker);
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(Objects.toString(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(Objects.toString(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(Objects.toString(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean toBoolean(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        String s = Objects.toString(o, "false").toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static String normalizeLanguage(String in) {
        if (in == null) return "en_US";
        String s = in.trim();
        if (s.endsWith(".yml")) s = s.substring(0, s.length() - 4);
        s = s.replace('-', '_');
        String[] parts = s.split("_");
        if (parts.length == 2) {
            return parts[0].toLowerCase() + "_" + parts[1].toUpperCase();
        }
        // fallback: return as-is trimmed
        return s;
    }

    @Override
    public String toString() {
        return "PluginConfig{rotationTime="
                + rotationTime
                + ", requiredPercentage="
                + requiredPercentage
                + ", versus="
                + versus
                + ", language='"
                + language
                + "', admins="
                + admins
                + ", docker="
                + docker
                + "}";
    }
}
