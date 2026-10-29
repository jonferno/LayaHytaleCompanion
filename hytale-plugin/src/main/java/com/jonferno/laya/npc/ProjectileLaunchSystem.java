package com.jonferno.laya.npc;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.jonferno.laya.LayaPlugin;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 0.4.4: visible arrows and magic orbs.
 *
 * <p>{@link ProjectileModule#spawnProjectile} needs a {@link CommandBuffer}, which only
 * exists inside an ECS system (the constructor is protected and the consume method is
 * package-private, so the follow tick cannot make one). The combat code enqueues a shot
 * here; this system drains the queue with the buffer the engine hands it.
 *
 * <p>The projectile is cosmetic: the engine's impact damage path is written for the
 * player who fired, so ranged and magic damage is still applied by
 * {@link CombatController} directly. The asset ids are resolved from whatever the
 * installed packs actually ship rather than hard-coded.
 */
public final class ProjectileLaunchSystem extends EntityTickingSystem<EntityStore> {

    private record Shot(Ref<EntityStore> creator, boolean magic, Vector3d from, Vector3d dir) {
    }

    private static final ConcurrentLinkedQueue<Shot> QUEUE = new ConcurrentLinkedQueue<>();
    private static String arrowId = null;
    private static String magicId = null;
    private static boolean idsResolved = false;
    private static boolean firstSpawnLogged = false;
    private static boolean firstErrorLogged = false;

    /** Queue one shot; safe from the world thread. */
    public static void enqueue(Ref<EntityStore> creator, boolean magic, Vector3d from, Vector3d dir) {
        if (creator == null || from == null || dir == null) return;
        QUEUE.add(new Shot(creator, magic, new Vector3d(from), new Vector3d(dir)));
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        if (QUEUE.isEmpty()) return;
        resolveIdsOnce();
        Shot shot;
        while ((shot = QUEUE.poll()) != null) {
            try {
                String id = shot.magic() ? magicId : arrowId;
                if (id == null) continue;
                ProjectileConfig cfg = ProjectileConfig.getAssetMap().getAsset(id);
                if (cfg == null) continue;
                ProjectileModule.get().spawnProjectile(
                    shot.creator(), buffer, cfg, new Vector3d(shot.from()), new Vector3d(shot.dir()));
                if (!firstSpawnLogged) {
                    firstSpawnLogged = true;
                    LayaPlugin.log("Combat: projectile spawned (" + id + ")");
                }
            } catch (Throwable t) {
                if (!firstErrorLogged) {
                    firstErrorLogged = true;
                    LayaPlugin.log("Combat: projectile spawn failed: " + t);
                }
            }
        }
    }

    /** Pick the first shipped projectile config that looks like an arrow / a spell bolt. */
    private static void resolveIdsOnce() {
        if (idsResolved) return;
        idsResolved = true;
        try {
            Map<String, ProjectileConfig> map = ProjectileConfig.getAssetMap().getAssetMap();
            arrowId = firstMatch(map, "arrow");
            magicId = firstMatch(map, "magic_orb", "orb", "magic", "bolt", "fire_bolt", "ice_bolt");
            LayaPlugin.log("Combat: projectiles arrow=" + arrowId + " magic=" + magicId
                + " (" + (map == null ? 0 : map.size()) + " configs)");
        } catch (Throwable t) {
            LayaPlugin.log("Combat: projectile config scan failed: " + t);
        }
    }

    private static String firstMatch(Map<String, ProjectileConfig> map, String... tokens) {
        if (map == null) return null;
        for (String token : tokens) {
            for (String id : map.keySet()) {
                if (id != null && id.toLowerCase().contains(token)) return id;
            }
        }
        return null;
    }
}
