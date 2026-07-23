package com.jacob0225.conduit.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class VelocityConfig {
    private static final int DEFAULT_HTTP_PORT = 25566;

    private final boolean enabled;
    private final int httpPort;
    private final String publicBaseUrl;
    private final Map<String, String> hostUrls;

    private VelocityConfig(boolean enabled, int httpPort, String publicBaseUrl,
                           Map<String, String> hostUrls) {
        this.enabled = enabled;
        this.httpPort = httpPort;
        this.publicBaseUrl = publicBaseUrl;
        this.hostUrls = Map.copyOf(hostUrls);
    }

    static VelocityConfig load(Path dataDirectory, Logger logger) {
        Path path = dataDirectory.resolve("conduit-velocity.properties");
        Properties values = new Properties();
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(path)) writeDefaults(path);
            try (Reader reader = Files.newBufferedReader(path)) {
                values.load(reader);
            }
        } catch (IOException e) {
            logger.error("Could not load {}; using safe defaults", path, e);
        }

        boolean enabled = booleanValue(values.getProperty("enabled"), true);
        int port = portValue(values.getProperty("httpPort"), logger);
        String baseUrl = baseUrl(values.getProperty("publicBaseUrl", ""), "publicBaseUrl", logger);
        Map<String, String> hostUrls = new HashMap<>();
        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith("host.")) continue;
            String host = normalizeHost(key.substring("host.".length()));
            String url = baseUrl(values.getProperty(key), key, logger);
            if (!host.isEmpty() && !url.isEmpty()) hostUrls.put(host, url);
        }
        return new VelocityConfig(enabled, port, baseUrl, hostUrls);
    }

    private static void writeDefaults(Path path) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            writer.write("# Conduit Velocity companion configuration\n");
            writer.write("# Clients without Conduit are always allowed to continue.\n\n");
            writer.write("enabled=true\n\n");
            writer.write("# Used when publicBaseUrl is blank.\n");
            writer.write("httpPort=" + DEFAULT_HTTP_PORT + "\n\n");
            writer.write("# Leave blank to use http://<hostname-the-player-joined>:<httpPort>.\n");
            writer.write("# This is the client-facing address, not the backend server address.\n");
            writer.write("publicBaseUrl=\n\n");
            writer.write("# Optional per-host overrides. Add one line for each public Minecraft hostname.\n");
            writer.write("# host.play.example.com=https://mods.example.com/conduit\n");
        }
    }

    boolean enabled() {
        return enabled;
    }

    Endpoint endpointFor(String joinedHost) {
        String host = normalizeHost(joinedHost);
        return new Endpoint(httpPort, hostUrls.getOrDefault(host, publicBaseUrl));
    }

    private static boolean booleanValue(String raw, boolean fallback) {
        if (raw == null) return fallback;
        if ("true".equalsIgnoreCase(raw.trim())) return true;
        if ("false".equalsIgnoreCase(raw.trim())) return false;
        return fallback;
    }

    private static int portValue(String raw, Logger logger) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            if (value >= 1 && value <= 65535) return value;
        } catch (NumberFormatException ignored) {}
        logger.warn("Invalid httpPort '{}'; using {}", raw, DEFAULT_HTTP_PORT);
        return DEFAULT_HTTP_PORT;
    }

    private static String baseUrl(String raw, String key, Logger logger) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && value.getBytes(StandardCharsets.UTF_8).length <= 2048) {
                while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
                return value;
            }
        } catch (IllegalArgumentException ignored) {}
        logger.warn("Ignoring invalid {}='{}'; expected an HTTP(S) base URL", key, raw);
        return "";
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    record Endpoint(int port, String publicBaseUrl) {}
}
