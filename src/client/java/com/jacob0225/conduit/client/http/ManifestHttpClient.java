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
 * <p>The client calls {@link #fetch} when the player clicks Join, <b>before</b>
 * any game connection is opened. If the server runs Conduit, it answers with
 * the manifest JSON; if it doesn't, the connection is refused and we treat the
 * server as vanilla (the caller proceeds with a normal connect).
 *
 * <p>The manifest travels over plain HTTP and is fully attacker-controlled, so
 * {@link ManifestJson#fromJson} re-validates every field through the same
 * {@code InputValidator} gate used for network packets.
 *
 * <p>Port convention: the HTTP endpoint defaults to a fixed port (25566), which
 * the client probes first. If that refuses, we fall back to (game port + 1) for
 * legacy servers, then to the game port itself for single-port setups.
 */
public class ManifestHttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HTTP-Client");

    /** Fixed default port that the server listens on unless overridden. */
    private static final int DEFAULT_HTTP_PORT = 25566;

    /** How long to wait for the manifest endpoint before giving up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    // Reusable client — HttpClient is thread-safe and meant to be shared.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private ManifestHttpClient() {}

    /**
     * Fetch the manifest from the given host and game port.
     *
     * @param host           server host (already SRV-resolved by the caller)
     * @param gamePort       the Minecraft game port; HTTP defaults to gamePort+1
     * @return the parsed, validated manifest; empty if the server doesn't run
     *         Conduit (endpoint unreachable) — never throws on connection failure
     */
    public static CompletableFuture<Optional<ManifestPayload>> fetch(String host, int gamePort) {
        // Candidate ports, in order:
        //   1. The fixed default (25566) — where a current server listens.
        //   2. gamePort + 1            — legacy convention.
        //   3. gamePort                — single-port setups (only MC port forwarded).
        // Skip a candidate if it duplicates one already tried.
        int primary   = DEFAULT_HTTP_PORT;
        int secondary = gamePort + 1;
        int tertiary  = gamePort;

        LOGGER.info("=== CONDUIT MANIFEST FETCH ===");
        LOGGER.info("Target host='{}', gamePort={}", host, gamePort);
        LOGGER.info("Will try ports: {} (default), {} (gamePort+1), {} (gamePort)",
                primary, secondary, tertiary);

        CompletableFuture<Optional<ManifestPayload>> chain =
                tryFetch(host, primary, "default")
                        .thenCompose(opt -> {
                            if (opt.isPresent()) {
                                LOGGER.info("✓ Got manifest from default port {} — skipping fallbacks", primary);
                                return CompletableFuture.completedFuture(opt);
                            }
                            LOGGER.info("Default port {} had no manifest — trying gamePort+1 ({})", primary, secondary);
                            return tryFetch(host, secondary, "gamePort+1");
                        });

        // Append the gamePort fallback only if it differs from the candidates
        // already attempted.
        if (tertiary != primary && tertiary != secondary) {
            chain = chain.thenCompose(opt -> {
                if (opt.isPresent()) {
                    LOGGER.info("✓ Got manifest from port {} — skipping final fallback", secondary);
                    return CompletableFuture.completedFuture(opt);
                }
                LOGGER.info("Port {} had no manifest — trying gamePort ({})", secondary, tertiary);
                return tryFetch(host, tertiary, "gamePort");
            });
        }

        return chain;
    }

    private static CompletableFuture<Optional<ManifestPayload>> tryFetch(String host, int port, String label) {
        URI uri = URI.create("http://" + host + ":" + port + "/manifest.json");
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
