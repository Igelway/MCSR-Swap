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
    public final Docker docker;

    public static final class Docker {
        public final boolean enabled;
        public final String image;
        public final String network;

        public Docker(boolean enabled, String image, String network) {
            this.enabled = enabled;
            this.image = image;
            this.network = network;
        }

        @Override
        public String toString() {
            return "Docker{enabled="
                    + enabled
                    + ", image='"
                    + image
                    + "', network='"
                    + network
                    + "}";
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
        this.docker = docker;
    }

    @SuppressWarnings("unchecked")
    public static PluginConfig fromMap(Map<String, Object> map) {
        if (map == null) map = Map.of();

        int rotation = toInt(map.getOrDefault("rotationTime", map.getOrDefault("rotation", 120)));
        Object req =
                map.getOrDefault(
                        "requiredPercentage",
                        map.getOrDefault("required", map.getOrDefault("requiredPercentage", 1.0)));
        double requiredPercentage = toDouble(req, 1.0);
        boolean versus = toBoolean(map.getOrDefault("versus", false));

        Object langObj = map.getOrDefault("language", "en_US");
        String language = normalizeLanguage(Objects.toString(langObj, "en_US"));

        String gameServerPrefix = Objects.toString(map.getOrDefault("gameServerPrefix", "game"));
        String lobbyServerName = Objects.toString(map.getOrDefault("lobbyServerName", "lobby"));
        boolean spectateAfterWin = toBoolean(map.getOrDefault("spectateAfterWin", false));
        String spectateTarget = Objects.toString(map.getOrDefault("spectateTarget", "next"));
        int spectateMinTime = toInt(map.getOrDefault("spectateMinTime", 15));
        boolean saveHotbar = toBoolean(map.getOrDefault("saveHotbar", true));
        int eyeHoverTicks = toInt(map.getOrDefault("eyeHoverTicks", 80));

        List<String> admins = new ArrayList<>();
        Object adminsObj = map.get("admins");
        if (adminsObj instanceof List) {
            for (Object o : (List<Object>) adminsObj) {
                admins.add(Objects.toString(o, ""));
            }
        }

        // docker subsection (may be nested under 'docker' or 'dockerConfig')
        Map<String, Object> dockerMap = null;
        Object maybeDocker = map.get("docker");
        if (maybeDocker instanceof Map) dockerMap = (Map<String, Object>) maybeDocker;
        else if (map.get("dockerConfig") instanceof Map)
            dockerMap = (Map<String, Object>) map.get("dockerConfig");

        Docker docker = new Docker(false, "mcsrswap-gameserver:latest", "mcsrswap-network");
        if (dockerMap != null) {
            boolean enabled = toBoolean(dockerMap.getOrDefault("enabled", docker.enabled));
            String image = Objects.toString(dockerMap.getOrDefault("image", docker.image));
            String network = Objects.toString(dockerMap.getOrDefault("network", docker.network));
            docker = new Docker(enabled, image, network);
        }

        // Environment overrides (useful when running in Docker compose)
        String envLobby = System.getenv("MCSRSWAP_LOBBY_ADDRESS");
        if (envLobby != null && !envLobby.isBlank()) {
            lobbyServerName = envLobby;
        }
        String envImage = System.getenv("MCSRSWAP_GAMESERVER_IMAGE");
        if (envImage != null && !envImage.isBlank()) {
            docker = new Docker(docker.enabled, envImage, docker.network);
        }
        String envDockerMode = System.getenv("MCSRSWAP_DOCKER_MODE");
        if (envDockerMode != null
                && (envDockerMode.equalsIgnoreCase("true") || envDockerMode.equals("1"))) {
            docker = new Docker(true, docker.image, docker.network);
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
