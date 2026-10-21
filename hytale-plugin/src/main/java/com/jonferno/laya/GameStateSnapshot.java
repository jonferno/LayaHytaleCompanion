package com.jonferno.laya;

/**
 * Represents a snapshot of the current Hytale world state,
 * serialized to JSON and sent to the AI sidecar each turn.
 */
public class GameStateSnapshot {

    private final String playerName;
    private final double playerX;
    private final double playerY;
    private final double playerZ;
    private final int playerHealth;
    private final String heldItem;
    private final String nearbyEntities;
    private final String timeOfDay;
    private final boolean LayaAlive;

    public GameStateSnapshot(String playerName, double playerX, double playerY, double playerZ,
                             int playerHealth, String heldItem, String nearbyEntities,
                             String timeOfDay, boolean LayaAlive) {
        this.playerName = playerName;
        this.playerX = playerX;
        this.playerY = playerY;
        this.playerZ = playerZ;
        this.playerHealth = playerHealth;
        this.heldItem = heldItem;
        this.nearbyEntities = nearbyEntities;
        this.timeOfDay = timeOfDay;
        this.LayaAlive = LayaAlive;
    }

    public String toJson() {
        return "{" +
            "\"player\":{\"name\":\"" + escape(playerName) + "\"," +
            "\"x\":" + playerX + "," +
            "\"y\":" + playerY + "," +
            "\"z\":" + playerZ + "," +
            "\"health\":" + playerHealth + "," +
            "\"held_item\":\"" + escape(heldItem) + "\"}," +
            "\"nearby_entities\":" + nearbyEntities + "," +
            "\"time_of_day\":\"" + escape(timeOfDay) + "\"," +
            "\"Laya\":{\"alive\":" + LayaAlive + "}" +
            "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


