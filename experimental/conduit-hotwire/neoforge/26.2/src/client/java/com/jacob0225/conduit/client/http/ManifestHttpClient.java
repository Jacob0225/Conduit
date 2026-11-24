package com.jacob0225.conduit.client.http;

import com.google.gson.JsonSyntaxException;
import com.jacob0225.conduit.http.ManifestJson;
import com.jacob0225.conduit.network.ManifestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the Conduit manifest from a server's HTTP endpoint.
 *
 * <p>The client calls {@link #fetch} after the server advertises its configured
 * port through the Minecraft login connection. If the server runs Conduit, it answers with
 * the manifest JSON. An unreachable advertised endpoint is reported to the
 * server so login stops before registry synchronization.
 *
 * <p>The manifest travels over plain HTTP and is fully attacker-controlled, so
 * {@link ManifestJson#fromJson} re-validates every field through the same
 * {@code InputValidator} gate used for network packets.
 *
 * <p>No ports are guessed. The exact server-configured port is used.
 */
public class ManifestHttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HTTP-Client");

    /** How long to wait for the manifest endpoint before giving up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    // Reusable client — HttpClient is thread-safe and meant to be shared.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private ManifestHttpClient() {}

    /**
     * Fetch the manifest from the exact host and port advertised during login.
     *
     * @param host           server host (already SRV-resolved by the caller)
     * @param httpPort       server-advertised Conduit HTTP port
     * @return the parsed, validated manifest; empty if the server doesn't run
     *         Conduit (endpoint unreachable) — never throws on connection failure
     */
    public static CompletableFuture<Optional<ManifestPayload>> fetch(
            String host, int httpPort, String publicBaseUrl) {
        LOGGER.info("=== CONDUIT MANIFEST FETCH ===");
        URI uri = publicBaseUrl.isBlank()
                ? URI.create("http://" + bracketIpv6(host) + ":" + httpPort + "/manifest.json")
                : URI.create(publicBaseUrl + "/manifest.json");
        LOGGER.info("Using advertised manifest URL {}", uri);
        return tryFetch(uri, httpPort, "advertised");
    }

    private static String bracketIpv6(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static CompletableFuture<Optional<ManifestPayload>> tryFetch(URI uri, int port, String label) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        LOGGER.info("[{}] → GET {}", label, uri);
        long start = System.currentTimeMillis();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((response, ex) -> {
                    long elapsed = System.currentTimeMillis() - start;
                    if (ex != null) {
                        Throwable root = ex;
                        while (root.getCause() != null) root = root.getCause();
                        String type = root.getClass().getSimpleName();
                        LOGGER.info("[{}] ✗ FAILED after {}ms: {} ({})",
                                label, elapsed, root.getMessage(), type);
                        // The most common failure — connection refused — means
                        // nothing is listening on that port. Very useful to see
                        // explicitly when diagnosing why a server "has no manifest".
                        if (root instanceof ConnectException) {
                            LOGGER.info("[{}]   → nothing listening on port {} (ConnectException)", label, port);
                        }
                        return Optional.<ManifestPayload>empty();
                    }
                    int code = response.statusCode();
                    if (code != 200) {
                        LOGGER.info("[{}] ✗ HTTP {} after {}ms (body={} bytes)", label, code, elapsed,
                                response.body().length());
                        return Optional.<ManifestPayload>empty();
                    }
                    LOGGER.info("[{}] ✓ HTTP 200 after {}ms (body={} bytes)", label, elapsed,
                            response.body().length());
                    try {
                        ManifestPayload payload = ManifestJson.fromJson(response.body());
                        LOGGER.info("[{}] Parsed OK: schemaVersion={}, manifestVersion={}, serverName='{}', {} mod(s)",
                                label, payload.schemaVersion(), payload.manifestVersion(),
                                payload.serverName(), payload.mods().size());
                        if (!payload.mods().isEmpty()) {
                            LOGGER.info("[{}] First mod: id='{}', version='{}', required={}, modrinthId='{}'",
                                    label,
                                    payload.mods().get(0).modId(),
                                    payload.mods().get(0).requiredVersion(),
                                    payload.mods().get(0).required(),
                                    payload.mods().get(0).modrinthProjectId());
                        }
                        return Optional.of(payload);
                    } catch (IllegalArgumentException | JsonSyntaxException e) {
                        LOGGER.warn("[{}] ✗ Response failed validation: {}", label, e.getMessage());
                        LOGGER.warn("[{}]   raw body: {}", label, response.body());
                        return Optional.<ManifestPayload>empty();
                    }
                });
    }
}
