package com.jacob0225.conduit.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Client-side Conduit settings stored at config/conduit/client.json.
 */
public final class ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Accent themes, in the order the settings screen cycles through them. */
    public static final List<String> THEMES = List.of("ocean", "prismarine", "amethyst", "sunset");

    private static volatile ClientConfig instance;

    // General
    private boolean compactDownloadUi = false;
    private boolean autoStartDownloads = false;
    private boolean showOptionalMods = true;
    private boolean titleScreenButton = true;
    // Sounds
    private boolean uiSounds = true;
    private boolean hoverSounds = true;
    private int soundVolume = 80;
    // Visuals
    private String theme = "ocean";
    private boolean particles = true;
    private boolean animations = true;

    public static ClientConfig get() {
        ClientConfig current = instance;
        if (current == null) {
            current = load();
            instance = current;
        }
        return current;
    }

    public static ClientConfig load() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                ClientConfig defaults = new ClientConfig();
                defaults.save(path);
                instance = defaults;
                LOGGER.info("Wrote default Conduit client config to {}", path);
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                ClientConfig config = new ClientConfig();
                config.compactDownloadUi = bool(root, "compactDownloadUi", config.compactDownloadUi);
                config.autoStartDownloads = bool(root, "autoStartDownloads", config.autoStartDownloads);
                config.showOptionalMods = bool(root, "showOptionalMods", config.showOptionalMods);
                config.titleScreenButton = bool(root, "titleScreenButton", config.titleScreenButton);
                config.uiSounds = bool(root, "uiSounds", config.uiSounds);
                config.hoverSounds = bool(root, "hoverSounds", config.hoverSounds);
                config.soundVolume = integer(root, "soundVolume", config.soundVolume, 0, 100);
                config.setTheme(string(root, "theme", config.theme));
                config.particles = bool(root, "particles", config.particles);
                config.animations = bool(root, "animations", config.animations);
                instance = config;
                return config;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load Conduit client config, using defaults: {}", e.getMessage());
            ClientConfig defaults = new ClientConfig();
            instance = defaults;
            return defaults;
        }
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("conduit").resolve("client.json");
    }

    public void save() throws IOException {
        save(configPath());
        instance = this;
    }

    /** Puts every setting back to its default (call {@link #save()} afterwards). */
    public void resetToDefaults() {
        ClientConfig d = new ClientConfig();
        compactDownloadUi = d.compactDownloadUi;
        autoStartDownloads = d.autoStartDownloads;
        showOptionalMods = d.showOptionalMods;
        titleScreenButton = d.titleScreenButton;
        uiSounds = d.uiSounds;
        hoverSounds = d.hoverSounds;
        soundVolume = d.soundVolume;
        theme = d.theme;
        particles = d.particles;
        animations = d.animations;
    }

    public boolean compactDownloadUi() { return compactDownloadUi; }
    public void setCompactDownloadUi(boolean value) { compactDownloadUi = value; }

    public boolean autoStartDownloads() { return autoStartDownloads; }
    public void setAutoStartDownloads(boolean value) { autoStartDownloads = value; }

    public boolean showOptionalMods() { return showOptionalMods; }
    public void setShowOptionalMods(boolean value) { showOptionalMods = value; }

    public boolean titleScreenButton() { return titleScreenButton; }
    public void setTitleScreenButton(boolean value) { titleScreenButton = value; }

    public boolean uiSounds() { return uiSounds; }
    public void setUiSounds(boolean value) { uiSounds = value; }

    public boolean hoverSounds() { return hoverSounds; }
    public void setHoverSounds(boolean value) { hoverSounds = value; }

    /** 0–100, applied on top of Minecraft's own master volume. */
    public int soundVolume() { return soundVolume; }
    public void setSoundVolume(int value) { soundVolume = Math.max(0, Math.min(100, value)); }

    public String theme() { return theme; }
    public void setTheme(String value) {
        String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
        theme = THEMES.contains(v) ? v : "ocean";
    }

    public boolean particles() { return particles; }
    public void setParticles(boolean value) { particles = value; }

    public boolean animations() { return animations; }
    public void setAnimations(boolean value) { animations = value; }

    private void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("compactDownloadUi", compactDownloadUi);
        root.addProperty("autoStartDownloads", autoStartDownloads);
        root.addProperty("showOptionalMods", showOptionalMods);
        root.addProperty("titleScreenButton", titleScreenButton);
        root.addProperty("uiSounds", uiSounds);
        root.addProperty("hoverSounds", hoverSounds);
        root.addProperty("soundVolume", soundVolume);
        root.addProperty("theme", theme);
        root.addProperty("particles", particles);
        root.addProperty("animations", animations);

        try (Writer writer = Files.newBufferedWriter(path)) {
            writer.write(GSON.toJson(root));
        }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isBoolean()) return fallback;
        return root.get(key).getAsBoolean();
    }

    private static int integer(JsonObject root, String key, int fallback, int min, int max) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isNumber()) return fallback;
        return Math.max(min, Math.min(max, root.get(key).getAsInt()));
    }

    private static String string(JsonObject root, String key, String fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        return root.get(key).getAsString();
    }
}
