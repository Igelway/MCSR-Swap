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
    private static final boolean DEFAULT_SPECTATE_AFTER_WIN = false;
    private static final String DEFAULT_SPECTATE_TARGET = "next";
    private static final int DEFAULT_SPECTATE_MIN_TIME = 15;
    private static final boolean DEFAULT_SAVE_HOTBAR = true;
    private static final int DEFAULT_EYE_HOVER_TICKS = 80;

    // Docker defaults
    private static final boolean DEFAULT_DOCKER_ENABLED = false;
    private static final String DEFAULT_DOCKER_IMAGE = "mcsrswap-gameserver:latest";
    private static final String DEFAULT_DOCKER_NETWORK = "mcsrswap-network";
    private static final String DEFAULT_DOCKER_HOST = null;

    public final int rotationTime;
    public final double requiredPercentage;
    public final boolean versus;

    // general settings
    public final String language; // normalized form like en_US
    public final String gameServerPrefix;
    public final String lobbyServerName;
    public final boolean spectateAfterWin;
    public final String spectateTarget;
    public final int spectateMinTime;
    public final boolean saveHotbar;
    public final int eyeHoverTicks;

    public final List<String> admins;
    public final List<Long> worldSeeds;
    public final Docker docker;

    public static final class Docker {
        public final boolean enabled;
        public final String image;
        public final String network;
        public final String host;

        public Docker(boolean enabled, String image, String network, String host) {
            this.enabled = enabled;
            this.image = image;
            this.network = network;
            this.host = host;
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
            boolean spectateAfterWin,
            String spectateTarget,
            int spectateMinTime,
            boolean saveHotbar,
            int eyeHoverTicks,
            List<String> admins,
            List<Long> worldSeeds,
            Docker docker) {
        this.rotationTime = rotationTime;
        this.requiredPercentage = requiredPercentage;
        this.versus = versus;
        this.language = language;
        this.gameServerPrefix = gameServerPrefix;
        this.lobbyServerName = lobbyServerName;
        this.spectateAfterWin = spectateAfterWin;
        this.spectateTarget = spectateTarget;
        this.spectateMinTime = spectateMinTime;
        this.saveHotbar = saveHotbar;
        this.eyeHoverTicks = eyeHoverTicks;
        this.admins = admins == null ? List.of() : List.copyOf(admins);
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
        boolean spectateAfterWin =
                toBoolean(map.getOrDefault("spectateAfterWin", DEFAULT_SPECTATE_AFTER_WIN));
        String spectateTarget =
                Objects.toString(map.getOrDefault("spectateTarget", DEFAULT_SPECTATE_TARGET));
        int spectateMinTime = toInt(map.getOrDefault("spectateMinTime", DEFAULT_SPECTATE_MIN_TIME));
        boolean saveHotbar = toBoolean(map.getOrDefault("saveHotbar", DEFAULT_SAVE_HOTBAR));
        int eyeHoverTicks = toInt(map.getOrDefault("eyeHoverTicks", DEFAULT_EYE_HOVER_TICKS));

        List<String> admins = new ArrayList<>();
        Object adminsObj = map.get("admins");
        if (adminsObj instanceof List) {
            for (Object o : (List<Object>) adminsObj) {
                admins.add(Objects.toString(o, ""));
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

        Docker docker =
                new Docker(
                        DEFAULT_DOCKER_ENABLED,
                        DEFAULT_DOCKER_IMAGE,
                        DEFAULT_DOCKER_NETWORK,
                        DEFAULT_DOCKER_HOST);
        if (dockerMap != null) {
            boolean enabled = toBoolean(dockerMap.getOrDefault("enabled", DEFAULT_DOCKER_ENABLED));
            String image = Objects.toString(dockerMap.getOrDefault("image", DEFAULT_DOCKER_IMAGE));
            String network =
                    Objects.toString(dockerMap.getOrDefault("network", DEFAULT_DOCKER_NETWORK));
            String host =
                    dockerMap.containsKey("host")
                            ? Objects.toString(dockerMap.get("host"))
                            : DEFAULT_DOCKER_HOST;
            docker = new Docker(enabled, image, network, host);
        }

        // Environment overrides (useful when running in Docker compose)
        String envLobby = System.getenv("MCSRSWAP_LOBBY_ADDRESS");
        if (envLobby != null && !envLobby.isBlank()) {
            lobbyServerName = envLobby;
        }
        String envImage = System.getenv("MCSRSWAP_GAMESERVER_IMAGE");
        if (envImage != null && !envImage.isBlank()) {
            docker = new Docker(docker.enabled, envImage, docker.network, docker.host);
        }
        String envNetwork = System.getenv("MCSRSWAP_DOCKER_NETWORK");
        if (envNetwork != null && !envNetwork.isBlank()) {
            docker = new Docker(docker.enabled, docker.image, envNetwork, docker.host);
        }
        String envHost = System.getenv("MCSRSWAP_DOCKER_HOST");
        if (envHost == null || envHost.isBlank()) {
            envHost = System.getenv("DOCKER_HOST");
        }
        if (envHost != null && !envHost.isBlank()) {
            docker = new Docker(docker.enabled, docker.image, docker.network, envHost);
        }
        String envDockerMode = System.getenv("MCSRSWAP_DOCKER_MODE");
        if (envDockerMode != null
                && (envDockerMode.equalsIgnoreCase("true") || envDockerMode.equals("1"))) {
            docker = new Docker(true, docker.image, docker.network, docker.host);
        }
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

        return new PluginConfig(
                rotation,
                requiredPercentage,
                versus,
                language,
                gameServerPrefix,
                lobbyServerName,
                spectateAfterWin,
                spectateTarget,
                spectateMinTime,
                saveHotbar,
                eyeHoverTicks,
                admins,
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
