package de.mcsrswap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Per-player localised strings, loaded from config/worldswap/languages/. Locale is captured via
 * LocaleCaptureMixin when the client sends settings.
 */
public class Lang {

    private static final String[] BUNDLED = {"en_us.properties", "de_de.properties"};

    /** locale code → key → message */
    private static final Map<String, Map<String, String>> tables = new HashMap<>();

    private static final Map<UUID, String> locales = new HashMap<>();

    // ── Init ──────────────────────────────────────────────────────────────

    public static void init() {
        Path langDir =
                FabricLoader.getInstance().getConfigDir().resolve("worldswap").resolve("languages");
        try {
            if (!Files.exists(langDir)) Files.createDirectories(langDir);
            for (String filename : BUNDLED) {
                Path target = langDir.resolve(filename);
                if (!Files.exists(target)) {
                    try (InputStream in =
                            Lang.class.getResourceAsStream("/languages/" + filename)) {
                        if (in != null) Files.copy(in, target);
                    }
                }
                loadFile(filename.replace(".properties", ""), langDir.resolve(filename));
            }
        } catch (IOException e) {
            System.err.println(
                    "[WorldSwap] Failed to initialise language files: " + e.getMessage());
        }
    }

    private static void loadFile(String localeCode, Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file);
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
            Map<String, String> map = new HashMap<>();
            props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
            tables.put(localeCode, map);
        } catch (IOException e) {
            System.err.println(
                    "[WorldSwap] Failed to load language file "
                            + file.getFileName()
                            + ": "
                            + e.getMessage());
        }
    }

    // ── Locale tracking ───────────────────────────────────────────────────

    public static void setLocale(UUID uuid, String locale) {
        locales.put(uuid, locale != null ? locale.toLowerCase() : "en_us");
    }

    public static void removeLocale(UUID uuid) {
        locales.remove(uuid);
    }

    // ── Key lookup ────────────────────────────────────────────────────────

    private static String locale(UUID uuid) {
        String raw = locales.getOrDefault(uuid, "en_us");
        // Try exact match first, then language prefix (e.g. "de_at" → "de_de")
        if (tables.containsKey(raw)) return raw;
        String prefix = raw.substring(0, 2);
        return tables.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .findFirst()
                .orElse("en_us");
    }

    public static String get(UUID uuid, String key) {
        String loc = locale(uuid);
        Map<String, String> table = tables.getOrDefault(loc, Collections.emptyMap());
        if (table.containsKey(key)) return table.get(key);
        // fallback to en_us
        return tables.getOrDefault("en_us", Collections.emptyMap())
                .getOrDefault(key, "§c[?" + key + "]");
    }

    public static String get(UUID uuid, String key, String... pairs) {
        String s = get(uuid, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return s;
    }

    // ── Convenience wrappers ──────────────────────────────────────────────

    public static String gameFinished(UUID uuid) {
        return get(uuid, "game_finished");
    }

    public static String newRound(UUID uuid) {
        return get(uuid, "new_round");
    }

    public static String scoreProgress(UUID uuid, int done, int required) {
        return get(uuid, "scoreboard_progress") + ": §e" + done + "/" + required;
    }

    public static String scoreGoal(UUID uuid) {
        return get(uuid, "scoreboard_goal");
    }

    public static String timer(UUID uuid, int seconds) {
        return get(uuid, "scoreboard_timer", "seconds", String.valueOf(seconds));
    }
}
