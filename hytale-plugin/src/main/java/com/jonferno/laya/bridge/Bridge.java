package com.jonferno.laya.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.npc.NPCController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP bridge server that the AI sidecar talks to.
 * Listens on 127.0.0.1:8787 (configurable via Laya_BRIDGE_PORT).
 * Endpoints:
 *   GET  /ping    - health check
 *   POST /command - receive AI commands (follow, attack, say, etc.) and apply them in-game
 */
public class Bridge {

    private static final int PORT;
    private static final String SIDECAR_HEALTH;

    private HttpServer server;
    private final HttpClient client;

    static {
        Map<String, String> env = System.getenv();
        PORT = Integer.parseInt(env.getOrDefault("Laya_BRIDGE_PORT", "8787"));
        SIDECAR_HEALTH = env.getOrDefault("Laya_SIDECAR_HEALTH", "http://127.0.0.1:8788/health");
    }

    public Bridge() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));

            server.createContext("/ping", exchange -> handlePing(exchange));
            server.createContext("/command", exchange -> handleCommand(exchange));
            server.createContext("/tp", exchange -> handleTp(exchange));
            server.createContext("/cosmetics", exchange -> handleCosmetics(exchange));
            server.createContext("/texture", exchange -> handleTexture(exchange));
            server.start();

            LayaPlugin.log("Bridge listening on port " + PORT);

            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(this::selfTest);
        } catch (IOException e) {
            LayaPlugin.log("Bridge failed to start: " + e.getMessage() + " (port " + PORT + " may be in use)");
            throw new RuntimeException("Bridge failed to start on port " + PORT, e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LayaPlugin.log("Bridge stopped.");
        }
    }

    private void handlePing(HttpExchange exchange) {
        try {
            String body = "{\"ok\":true,\"plugin\":\"LayaPlugin\",\"bridge\":\"up\"}";
            respond(exchange, 200, body);
        } catch (IOException e) {
            LayaPlugin.log("Ping response error: " + e.getMessage());
        }
    }

    private void handleCommand(HttpExchange exchange) {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                respond(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }

            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            LayaPlugin.log("Command received: " + body);

            NPCController controller = LayaPlugin.get().getNPCController();
            // true: this path (event reactions, voice replies) has not shown the
            // line to the player yet, so the controller delivers it to chat.
            controller.applyCommand(body, true);

            String response = "{\"status\":\"ok\",\"command_length\":" + body.trim().length() + "}";
            respond(exchange, 200, response);
        } catch (IOException e) {
            try {
                respond(exchange, 400, "{\"error\":\"unreadable body\"}");
            } catch (IOException ignored) {
            }
        }
    }

    private void handleCosmetics(HttpExchange exchange) {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                respond(exchange, 405, "{\"ok\":false,\"error\":\"GET required\"}");
                return;
            }
            String json = LayaPlugin.get().getNPCController().appearanceOptionsJson();
            respond(exchange, 200, json);
        } catch (Throwable t) {
            LayaPlugin.log("Cosmetics response error: " + t);
            try {
                respond(exchange, 500, "{\"ok\":false,\"error\":\"cosmetics failed\"}");
            } catch (IOException ignored) {
            }
        }
    }

    private void handleTp(HttpExchange exchange) {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                respond(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            exchange.getRequestBody().readAllBytes(); // drain any body
            LayaPlugin.log("POST /tp received — teleporting Laya to player");
            com.jonferno.laya.LayaPlugin.get().getNPCController().teleportLayaToPlayer();
            respond(exchange, 200, "{\"status\":\"ok\"}");
        } catch (IOException e) {
            try {
                respond(exchange, 500, "{\"error\":\"tp failed\"}");
            } catch (IOException ignored) {
            }
        }
    }

    private void handleTexture(HttpExchange exchange) {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                respond(exchange, 405, "{}");
                return;
            }
            String p = queryParam(exchange.getRequestURI().getRawQuery(), "p");
            if (p == null) { respond(exchange, 404, "{}"); return; }
            p = java.net.URLDecoder.decode(p, java.nio.charset.StandardCharsets.UTF_8);
            // Only character art: cosmetic parts and the character models/textures the
            // registry references live under Common/Cosmetics/ and Common/Characters/.
            // No traversal, no absolute, no backslash, must be a .png.
            boolean allowedRoot = p.startsWith("Common/Cosmetics/") || p.startsWith("Common/Characters/");
            if (p.contains("..") || p.contains("\\") || p.startsWith("/") || p.contains(":")
                    || !allowedRoot || !p.toLowerCase().endsWith(".png")) {
                respond(exchange, 404, "{}");
                return;
            }
            var pack = com.hypixel.hytale.server.core.asset.AssetModule.get().getBaseAssetPack();
            if (pack == null) { respond(exchange, 404, "{}"); return; }
            java.nio.file.Path file = pack.resolve(java.nio.file.Path.of(p));
            if (file == null || !java.nio.file.Files.isRegularFile(file)) {
                respond(exchange, 404, "{}");
                return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (Throwable t) {
            LayaPlugin.log("Texture serve failed: " + t);   // log, never throw out of the handler
            try { respond(exchange, 404, "{}"); } catch (IOException ignored) {}
        }
    }

    /** Value of a query key in a raw query string, or null. */
    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return pair.substring(eq + 1);
        }
        return null;
    }

    private void selfTest() {
        try {
            URI uri = URI.create(SIDECAR_HEALTH);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            LayaPlugin.log("Sidecar health: " + response.statusCode() + " " + response.body());
        } catch (Exception e) {
            LayaPlugin.log("Sidecar health check failed: " + e.getMessage());
        }
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } catch (Throwable t) {
            try {
                exchange.getResponseBody().close();
            } catch (Throwable suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }
}


