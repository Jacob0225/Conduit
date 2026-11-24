package com.jacob0225.conduit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.util.Locale;
import java.util.Properties;

/** Server-owned settings stored alongside the manifest in config/conduit. */
public final class ServerConfig {
    public static final int DEFAULT_HTTP_PORT = 25566;
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Config");
    private final boolean enabled;
    private final String bindAddress;
    private final int httpPort;
    private final String publicBaseUrl;
    private final int workerThreads;
    private final boolean logRequests;
    private final boolean diagnosticsEnabled;

    private ServerConfig(boolean enabled, String bindAddress, int httpPort, String publicBaseUrl, int workerThreads,
                         boolean logRequests, boolean diagnosticsEnabled) {
        this.enabled = enabled;
        this.bindAddress = bindAddress;
        this.httpPort = httpPort;
        this.publicBaseUrl = publicBaseUrl;
        this.workerThreads = workerThreads;
        this.logRequests = logRequests;
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    public static ServerConfig load(Path configDir) {
        Path path = configDir.resolve("conduit").resolve("conduit-server.properties");
        migrateLegacyConfig(configDir, path);
        Properties values = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                values.load(reader);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}: {}. Using defaults.", path, e.getMessage());
            }
            if (!values.containsKey("publicBaseUrl")) appendPublicBaseUrl(path);
        } else {
            writeDefaults(path);
        }
        return new ServerConfig(
                bool(values, "enabled", true),
                address(values.getProperty("bindAddress", "0.0.0.0")),
                integer(values, "httpPort", DEFAULT_HTTP_PORT, 1, 65535),
                publicBaseUrl(values.getProperty("publicBaseUrl", "")),
                integer(values, "workerThreads", 4, 1, 16),
                bool(values, "logRequests", false),
                bool(values, "diagnosticsEnabled", true)
        );
    }

    private static void migrateLegacyConfig(Path configDir, Path destination) {
        if (Files.exists(destination)) return;
        Path legacy = destination.getParent().resolve("server.properties");
        if (!Files.exists(legacy)) legacy = configDir.resolve("conduit.properties");
        if (!Files.exists(legacy)) return;
        try {
            Files.createDirectories(destination.getParent());
            Files.move(legacy, destination);
            LOGGER.info("Moved legacy Conduit config from {} to {}", legacy, destination);
        } catch (IOException e) {
            LOGGER.warn("Could not move legacy config {} to {}: {}", legacy, destination, e.getMessage());
        }
    }

    private static void writeDefaults(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                writer.write("# Conduit server configuration\n");
                writer.write("# All Conduit server files live in config/conduit/.\n\n");
                writer.write("# Set false to disable Conduit's HTTP manifest endpoint.\n");
                writer.write("enabled=true\n\n");
                writer.write("# Interface used by the manifest HTTP server. Use 127.0.0.1 for a local proxy only.\n");
                writer.write("bindAddress=0.0.0.0\n\n");
                writer.write("# TCP port exposed to connecting clients. Forward this port for remote players.\n");
                writer.write("httpPort=" + DEFAULT_HTTP_PORT + "\n\n");
                writer.write("# Optional client-facing HTTP(S) base URL (for a proxy or different public address).\n");
                writer.write("# Leave blank to automatically use the hostname\n");
                writer.write("# the player connected to.\n");
                writer.write("publicBaseUrl=\n\n");
                writer.write("# Dedicated HTTP worker threads (1-16).\n");
                writer.write("workerThreads=4\n\n");
                writer.write("# Emit one log line for each manifest request.\n");
                writer.write("logRequests=false\n\n");
                writer.write("# Expose a small JSON response at / for connection diagnostics.\n");
                writer.write("diagnosticsEnabled=true\n");
            }
            LOGGER.info("Wrote default Conduit server config to {}", path);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", path, e.getMessage());
        }
    }

    private static void appendPublicBaseUrl(Path path) {
        try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.APPEND)) {
            writer.write("\n# Optional client-facing HTTP(S) base URL (for a proxy or different public address).\n");
            writer.write("# Leave blank to automatically use the hostname\n");
            writer.write("# the player connected to.\n");
            writer.write("publicBaseUrl=\n");
            LOGGER.info("Added publicBaseUrl to {}", path);
        } catch (IOException e) {
            LOGGER.warn("Could not add publicBaseUrl to {}: {}", path, e.getMessage());
        }
    }

    private static boolean bool(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        if (raw == null) return fallback;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        LOGGER.warn("{}={} is not true or false; using {}", key, raw, fallback);
        return fallback;
    }

    private static int integer(Properties values, String key, int fallback, int min, int max) {
        String raw = values.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // Warning below gives the operator the useful detail.
        }
        LOGGER.warn("{}={} is outside {}-{}; using {}", key, raw, min, max, fallback);
        return fallback;
    }

    private static String address(String raw) {
        String value = raw == null ? "0.0.0.0" : raw.trim();
        if (value.isEmpty() || value.length() > 255 || value.contains("/") || value.contains("\\")) {
            LOGGER.warn("Invalid bindAddress '{}'; using 0.0.0.0", raw);
            return "0.0.0.0";
        }
        return value;
    }

    private static String publicBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.isAbsolute() && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && value.length() <= 2048) {
                return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            }
        } catch (IllegalArgumentException ignored) {}
        LOGGER.warn("Invalid publicBaseUrl '{}'; automatically using the player's server hostname", raw);
        return "";
    }

    public boolean enabled() { return enabled; }
    public String bindAddress() { return bindAddress; }
    public int httpPort() { return httpPort; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public int workerThreads() { return workerThreads; }
    public boolean logRequests() { return logRequests; }
    public boolean diagnosticsEnabled() { return diagnosticsEnabled; }
}
