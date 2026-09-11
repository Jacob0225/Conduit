package com.jacob0225.conduit.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jacob0225.conduit.client.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side Conduit settings stored at config/conduit/client.json.
 */
public final class ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile ClientConfig instance;

    private boolean compactDownloadUi = false;
    private boolean autoStartDownloads = false;
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
                config.compactDownloadUi = bool(root, "compactDownloadUi", config.compactDownloadUi);
                config.autoStartDownloads = bool(root, "autoStartDownloads", config.autoStartDownloads);
                config.showOptionalMods = bool(root, "showOptionalMods", config.showOptionalMods);
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
        return PlatformPaths.configDir().resolve("conduit").resolve("client.json");
    }

    public void save() throws IOException {
        save(configPath());
        instance = this;
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

    public boolean showOptionalMods() {
        return showOptionalMods;
    }

    public void setShowOptionalMods(boolean showOptionalMods) {
        this.showOptionalMods = showOptionalMods;
    }

    private void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("compactDownloadUi", compactDownloadUi);
        root.addProperty("autoStartDownloads", autoStartDownloads);
        root.addProperty("showOptionalMods", showOptionalMods);

        try (Writer writer = Files.newBufferedWriter(path)) {
            writer.write(GSON.toJson(root));
        }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        return root.get(key).getAsBoolean();
    }

}
