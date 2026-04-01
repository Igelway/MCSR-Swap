package de.mcsrswap;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class VelocityLang {

    private Map<String, String> strings = Collections.emptyMap();

    private static final String[] BUNDLED = {"en_us.yml", "de_de.yml"};

    public void load(Path dataDir, String filename) {
        Path langDir = dataDir.resolve("languages");
        try {
            if (!Files.exists(langDir)) {
                Files.createDirectories(langDir);
            }

            // Extract bundled defaults from JAR if not present on disk
            for (String bundled : BUNDLED) {
                Path target = langDir.resolve(bundled);
                if (!Files.exists(target)) {
                    try (InputStream in = VelocityLang.class.getResourceAsStream("/languages/" + bundled)) {
                        if (in != null) Files.copy(in, target);
                    }
                }
            }

            Path langFile = langDir.resolve(filename);
            if (!Files.exists(langFile)) {
                System.err.println("[SwapPlugin] Language file not found: " + filename + " – falling back to en_us.yml");
                langFile = langDir.resolve("en_us.yml");
            }

            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(langFile)) {
                Map<?, ?> raw = yaml.load(in);
                if (raw != null) {
                    Map<String, String> loaded = new HashMap<>();
                    raw.forEach((k, v) -> loaded.put(String.valueOf(k), String.valueOf(v)));
                    this.strings = loaded;
                }
            }

        } catch (IOException e) {
            System.err.println("[SwapPlugin] Failed to load language file: " + e.getMessage());
        }
    }

    /** Returns the message for the given key. */
    public String get(String key) {
        return strings.getOrDefault(key, "§c[?" + key + "]");
    }

    /**
     * Returns the message for the given key with placeholder substitution.
     * Pass pairs of (placeholderName, value): e.g. get("game_team_loses", "team", "A")
     */
    public String get(String key, String... pairs) {
        String s = get(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return s;
    }
}
