package com.jacob0225.conduit.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jacob0225.conduit.manifest.ServerManifestProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server that exposes the Conduit manifest to clients <b>before</b>
 * they connect to the game server.
 *
 * <p>This is the delivery mechanism that avoids the registry-mismatch race:
 * the client fetches {@code http://<host>:<httpPort>/manifest.json} when the
 * player clicks Join, installs any missing mods, and only then opens the real
 * game connection. Because the manifest is exchanged out-of-band over plain
 * HTTP, the client never receives registry packets it can't decode.
 *
 * <p>Uses the JDK's built-in {@link com.sun.net.httpserver.HttpServer} — no
 * external dependencies, ships with the JVM. Plain HTTP is intentional: the
 * manifest is non-sensitive public data (mod slugs + hashes). TLS would be a
 * follow-up if confidentiality ever matters.
 */
public class ConduitHttpServer {

    private static final Logger LOGGER  = LoggerFactory.getLogger("Conduit/HTTP");
    private static final Gson   GSON    = new GsonBuilder().disableHtmlEscaping().create();

    private final ServerManifestProvider manifestProvider;
    private final int httpPort;

    private HttpServer server;

    /**
     * @param manifestProvider source of the current validated manifest
     * @param httpPort         TCP port to listen on. The server side computes
     *                         this as (MC game port + 1) by default so the
     *                         operator doesn't have to configure anything.
     */
    public ConduitHttpServer(ServerManifestProvider manifestProvider, int httpPort) {
        this.manifestProvider = manifestProvider;
        this.httpPort         = httpPort;
    }

    /** Start listening. Safe to call once per server lifecycle. */
    public void start() {
        try {
            // back-log of 50 is plenty for a mod-sync endpoint; bind to 0.0.0.0
            // so clients can reach it the same way they reach the game server.
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 50);
            server.createContext("/manifest.json", new ManifestHandler());
            // A small dedicated pool keeps HTTP off the netty/external-worker
            // threads, so a slow client can't stall the game server.
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Conduit-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            LOGGER.info("Conduit HTTP manifest endpoint on port {} (0.0.0.0)", httpPort);
        } catch (IOException e) {
            // Non-fatal: the server still runs, just without the HTTP endpoint.
            // Clients will treat an unreachable endpoint as "vanilla server".
            LOGGER.error("Failed to start Conduit HTTP server on port {}: {}",
                    httpPort, e.getMessage());
            LOGGER.error("Clients will not be able to auto-sync mods. " +
                    "Check that port {} is free and open.", httpPort);
            server = null;
        }
    }

    /** Stop listening and release the port. Idempotent. */
    public void stop() {
        if (server != null) {
            // 0 = stop immediately (we're shutting down anyway).
            server.stop(0);
            server = null;
            LOGGER.info("Conduit HTTP server stopped.");
        }
    }

    public int getHttpPort() {
        return httpPort;
    }

    // ── Handler ─────────────────────────────────────────────────────────────

    /**
     * Serves the manifest as JSON. Always responds, even on a fresh install
     * with no mods configured (the provider returns an empty payload in that
     * case) so clients can distinguish "Conduit present, no mods" from
     * "Conduit absent" (connection refused).
     */
    private class ManifestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                String json = ManifestJson.toJson(manifestProvider.getPayload());
                sendJson(exchange, 200, json);

            } catch (Exception e) {
                LOGGER.error("Error serving manifest: {}", e.getMessage());
                sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            } finally {
                exchange.close();
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        // No cache — the operator may edit the manifest and restart; clients
        // must always see the current version. Connection: close keeps things
        // simple and avoids keep-alive resource leaks on a low-traffic endpoint.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
