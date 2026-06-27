package com.jacob0225.conduit.client.http;

import com.google.gson.JsonSyntaxException;
import com.jacob0225.conduit.http.ManifestJson;
import com.jacob0225.conduit.network.ManifestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Port convention: the HTTP endpoint defaults to (game port + 1). We try
 * that first; if it refuses we also try the game port itself as a fallback,
 * since some operators may forward only the single MC port.
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
     * Fetch the manifest from the given host and game port.
     *
     * @param host           server host (already SRV-resolved by the caller)
     * @param gamePort       the Minecraft game port; HTTP defaults to gamePort+1
     * @return the parsed, validated manifest; empty if the server doesn't run
     *         Conduit (endpoint unreachable) — never throws on connection failure
     */
    public static CompletableFuture<Optional<ManifestPayload>> fetch(String host, int gamePort) {
        // Candidate ports: the conventional (gamePort+1) first, then gamePort
        // itself as a fallback for single-port setups.
        int primary   = gamePort + 1;
        int secondary = gamePort;

        return tryFetch(host, primary)
                .thenCompose(opt -> opt.isPresent()
                        ? CompletableFuture.completedFuture(opt)
                        : tryFetch(host, secondary));
    }

    private static CompletableFuture<Optional<ManifestPayload>> tryFetch(String host, int port) {
        URI uri = URI.create("http://" + host + ":" + port + "/manifest.json");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        LOGGER.debug("Fetching manifest from {}", uri);
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((response, ex) -> {
                    if (ex != null) {
                        // Connection refused / timeout / DNS — treat as "no Conduit".
                        LOGGER.debug("Manifest endpoint {} unreachable: {}",
                                uri, ex.getMessage());
                        return Optional.<ManifestPayload>empty();
                    }
                    int code = response.statusCode();
                    if (code != 200) {
                        LOGGER.debug("Manifest endpoint {} returned HTTP {}", uri, code);
                        return Optional.<ManifestPayload>empty();
                    }
                    try {
                        ManifestPayload payload = ManifestJson.fromJson(response.body());
                        LOGGER.info("Fetched manifest from {} (v{}, {} mod(s))",
                                uri, payload.manifestVersion(), payload.mods().size());
                        return Optional.of(payload);
                    } catch (IllegalArgumentException | JsonSyntaxException e) {
                        // Tampered or malformed response — refuse to use it.
                        LOGGER.warn("Manifest from {} failed validation: {}", uri, e.getMessage());
                        return Optional.<ManifestPayload>empty();
                    }
                });
    }
}
