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
import java.util.Locale;

/**
 * Client-side Conduit settings stored at config/conduit/client.json.
 */
public final class ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile ClientConfig instance;

    private String theme = "conduit";
    private boolean compactDownloadUi = false;
    private boolean autoStartDownloads = false;
    private boolean showDownloadDetails = false;
    private boolean dimBackground = true;
    private boolean showOptionalMods = true;

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
                config.theme = string(root, "theme", config.theme);
                config.compactDownloadUi = bool(root, "compactDownloadUi", config.compactDownloadUi);
                config.autoStartDownloads = bool(root, "autoStartDownloads", config.autoStartDownloads);
                config.showDownloadDetails = bool(root, "showDownloadDetails", config.showDownloadDetails);
                config.dimBackground = bool(root, "dimBackground", config.dimBackground);
                config.showOptionalMods = bool(root, "showOptionalMods", config.showOptionalMods);
                config.normalize();
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

    public void cycleTheme() {
        Theme[] themes = Theme.values();
        Theme current = theme();
        int next = (current.ordinal() + 1) % themes.length;
        theme = themes[next].id;
    }

    public Theme theme() {
        return Theme.byName(theme);
    }

    public boolean compactDownloadUi() {
        return compactDownloadUi;
    }

    public void setCompactDownloadUi(boolean compactDownloadUi) {
        this.compactDownloadUi = compactDownloadUi;
    }

    public boolean autoStartDownloads() {
        return autoStartDownloads;
    }

    public void setAutoStartDownloads(boolean autoStartDownloads) {
        this.autoStartDownloads = autoStartDownloads;
    }

    public boolean showDownloadDetails() {
        return showDownloadDetails;
    }

    public void setShowDownloadDetails(boolean showDownloadDetails) {
        this.showDownloadDetails = showDownloadDetails;
    }

    public boolean dimBackground() {
        return dimBackground;
    }

    public void setDimBackground(boolean dimBackground) {
        this.dimBackground = dimBackground;
    }

    public boolean showOptionalMods() {
        return showOptionalMods;
    }

    public void setShowOptionalMods(boolean showOptionalMods) {
        this.showOptionalMods = showOptionalMods;
    }

    private void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "theme: conduit, emerald, amethyst, amber, monochrome");
        root.addProperty("theme", theme);
        root.addProperty("compactDownloadUi", compactDownloadUi);
        root.addProperty("autoStartDownloads", autoStartDownloads);
        root.addProperty("showDownloadDetails", showDownloadDetails);
        root.addProperty("dimBackground", dimBackground);
        root.addProperty("showOptionalMods", showOptionalMods);

        try (Writer writer = Files.newBufferedWriter(path)) {
            writer.write(GSON.toJson(root));
        }
    }

    private void normalize() {
        theme = theme == null ? "conduit" : theme.toLowerCase(Locale.ROOT).trim();
        if (Theme.byName(theme) == Theme.CONDUIT && !"conduit".equals(theme)) {
            LOGGER.warn("Unknown Conduit theme '{}', using conduit.", theme);
            theme = "conduit";
        }
    }

    private static String string(JsonObject root, String key, String fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        return root.get(key).getAsString();
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        return root.get(key).getAsBoolean();
    }

    public enum Theme {
        CONDUIT("conduit", 0xFF41C7F2, 0xFF75E4B3, 0xFF101722, 0xEE17202D),
        EMERALD("emerald", 0xFF38D97B, 0xFFA5F3C7, 0xFF0F1D17, 0xEE13251D),
        AMETHYST("amethyst", 0xFFC084FC, 0xFFF0ABFC, 0xFF1A1428, 0xEE211834),
        AMBER("amber", 0xFFFFC857, 0xFFFFE29A, 0xFF211A0E, 0xEE2B2111),
        MONOCHROME("monochrome", 0xFFE6E6E6, 0xFFFFFFFF, 0xFF121212, 0xEE1D1D1D);

        private final String id;
        private final int accent;
        private final int accentSoft;
        private final int background;
        private final int panel;

        Theme(String id, int accent, int accentSoft, int background, int panel) {
            this.id = id;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.background = background;
            this.panel = panel;
        }

        public int accent() {
            return accent;
        }

        public int accentSoft() {
            return accentSoft;
        }

        public int background() {
            return background;
        }

        public int panel() {
            return panel;
        }

        private static Theme byName(String raw) {
            String wanted = raw == null ? "conduit" : raw.toLowerCase(Locale.ROOT).trim();
            for (Theme theme : values()) {
                if (theme.id.equals(wanted)) return theme;
            }
            return CONDUIT;
        }
    }
}
