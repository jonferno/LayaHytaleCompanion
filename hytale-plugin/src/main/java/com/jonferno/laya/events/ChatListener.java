package com.jonferno.laya.events;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.jonferno.laya.LayaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Listens for player chat messages and forwards them to the AI sidecar,
 * then delivers the reply back to the player in-game.
 *
 * Pipeline: Hytale chat -> POST /chat (AI bridge) -> Groq/Google + Kokoro
 *           -> parse {say, action} -> send "Laya: <say>" to player + apply NPC action
 */
public class ChatListener {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private static final String AI_BRIDGE_URL = System.getenv().getOrDefault(
        "Laya_AI_BRIDGE_URL", "http://127.0.0.1:8788/chat");

    public static void handle(PlayerChatEvent event, LayaPlugin plugin) {
        var sender = event.getSender();
        String playerName = sender != null ? sender.getUsername() : "unknown";
        String message = event.getContent() != null ? event.getContent() : "";

        LayaPlugin.log("Chat from " + playerName + ": " + message);

        // M1 fallback: if Laya isn't spawned yet (events raced), spawn her now
        if (sender != null) {
            plugin.getNPCController().ensureSpawned(sender);
        }

        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String json = buildSnapshot(plugin, playerName);
        String body = "{\"player\":\"" + escapeJson(playerName) +
            "\",\"message\":\"" + escapeJson(message) +
            "\",\"state\":" + json + "}";

        plugin.getNPCController().setThinking(true);
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create(AI_BRIDGE_URL);
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                String responseBody = response.body() != null ? response.body() : "";
                LayaPlugin.log("Bridge status=" + response.statusCode());

                if (response.statusCode() != 200 || responseBody.isEmpty()) {
                    LayaPlugin.log("Bridge error body: " + responseBody.substring(0, Math.min(responseBody.length(), 300)));
                    return;
                }

                // Parse Laya's reply from the response JSON
                String say = extractJsonString(responseBody, "say");
                String action = extractJsonString(responseBody, "action");
                LayaPlugin.log("Laya says: " + say);
                LayaPlugin.log("Laya action: " + action);

                // --- THE FIX: deliver the reply to the player in-game ---
                if (sender != null && say != null && !say.isEmpty()) {
                    sender.sendMessage(Message.raw(
                        plugin.getNPCController().getCompanionName() + ": " + say));
                    LayaPlugin.log("Delivered reply to " + playerName + ": " + say);
                }

                // Apply the NPC action (follow / attack / stay / move_to)
                if (action != null && !action.isEmpty()) {
                    plugin.getNPCController().applyCommand(responseBody);
                }

            } catch (Exception e) {
                LayaPlugin.log("Chat pipeline failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                plugin.getNPCController().setThinking(false);
            }
        });
    }

    /**
     * Extract a top-level string value from a JSON object.
     * Handles escaped quotes inside the value.
     */
    private static String extractJsonString(String json, String field) {
        String pattern = "\"" + field + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String buildSnapshot(LayaPlugin plugin, String playerName) {
        try {
            return plugin.getNPCController().stateSnapshotJson(playerName);
        } catch (Throwable t) {
            LayaPlugin.log("State snapshot failed: " + t);
            return "{\"player\":{\"name\":\"" + escapeJson(playerName)
                + "\"},\"nearby_entities\":[],\"time_of_day\":\"unknown\",\"laya\":{}}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
