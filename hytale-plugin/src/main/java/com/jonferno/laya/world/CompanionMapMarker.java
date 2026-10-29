package com.jonferno.laya.world;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.npc.NPCController;
import org.joml.Vector3d;

/**
 * 0.4.4: Laya on the world map.
 *
 * <p>Registered for every world at plugin start, the same way the reference
 * companion plugin does it, instead of on her first follow tick.
 *
 * <p>The provider runs on the WorldMapManager tick thread, not the world thread.
 * {@code WorldMapTracker} hops to the world executor for its own store read, so
 * this provider must not touch the entity store either: it reads the position
 * snapshot the follow tick publishes. The 0.4.3 version read the store from this
 * thread inside a swallow-all catch, which is why the marker never appeared and
 * nothing was ever logged.
 */
public final class CompanionMapMarker implements WorldMapManager.MarkerProvider {

    public static final String PROVIDER_KEY = "laya_companion";

    private static final String MARKER_ID = "LayaCompanion";
    private static final String MARKER_IMAGE = "UserA.png";
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;

    private final NPCController npc;
    private boolean firstSentLogged = false;
    private long lastErrorMs = 0;

    public CompanionMapMarker(NPCController npc) {
        this.npc = npc;
    }

    @Override
    public void update(World world, Player player, MarkersCollector collector) {
        if (npc == null || world == null || collector == null) return;
        String worldName;
        try {
            worldName = world.getName();
        } catch (Throwable t) {
            return;
        }
        if (!npc.isMapSnapshotLive(worldName)) return;
        Vector3d pos = npc.mapMarkerPosition();
        if (pos == null) return;
        try {
            MapMarker marker = new MapMarkerBuilder(
                MARKER_ID, MARKER_IMAGE, new Transform(pos))
                .withCustomName(npc.getCompanionName())
                .build();
            // addIgnoreViewDistance: her marker shows even when the map view
            // distance does not cover her position yet.
            collector.addIgnoreViewDistance(marker);
            if (!firstSentLogged) {
                firstSentLogged = true;
                LayaPlugin.log(String.format(
                    "Map: marker sent (%s at %.0f,%.0f,%.0f)",
                    npc.getCompanionName(), pos.x, pos.y, pos.z));
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrorMs >= ERROR_LOG_INTERVAL_MS) {
                lastErrorMs = now;
                LayaPlugin.log("Map: marker build failed: " + t);
            }
        }
    }
}
