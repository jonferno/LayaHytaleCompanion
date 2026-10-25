package com.jonferno.laya.world;

import java.util.Set;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockFace;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.BlockRotation;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Rotation;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.BlockPlaceUtils;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.jonferno.laya.LayaPlugin;

import org.joml.Vector3i;

/**
 * S1 candidate for farming: drive the engine's own till/plant interaction chain
 * against a specific block, supplying the CLIENT hit face.
 *
 * <p>Why this exists: the mining chain in {@link BlockBreaker} never needed a face.
 * Planting does — {@code Seed_Condition} is a {@code BlockCondition} whose matcher
 * is {@code {"Block":{"Id":"Soil_Dirt_Tilled"},"Face":"Up"}}, and the engine reads
 * that face from {@code InteractionContext.getClientState().blockFace}
 * ({@code BlockConditionInteraction.interactWithBlock} bytecode). An NPC has no
 * client, so the face has to be written into that sync state explicitly, exactly
 * as a client packet would carry it. {@code InteractionSyncData}'s fields are
 * public and mutable, so this is a write, not a hack: the same fields a real
 * right-click fills in.</p>
 *
 * <p>WORLD-THREAD ONLY (manager, context, chunk store and world all assert it).</p>
 *
 * <p>Probe artifact first — the dev verb {@code probe_farm} decides whether it is
 * S1 or falls to the S2/S3 ladder in the farming spec. If it PASSes, the executor
 * reuses {@link #act} and this becomes the farm primitive.</p>
 */
public final class FarmProbe {

    /** Root interaction of the hoe's secondary action (ChangeBlock, 16 soils -> tilled). */
    public static final String TILL_INTERACTION = "Hoe_Till";

    /**
     * Root interaction the seed item's secondary action resolves to. It is a
     * BlockCondition on face Up, then {@code Replace Var "SeedId"} -> {@code Seed_Place}.
     */
    public static final String PLANT_INTERACTION = "Seed_Condition";

    /** The engine's tilled soil block (the plant target sits at y+1 above it). */
    public static final String TILLED_ID = "Soil_Dirt_Tilled";

    /**
     * The exact key set of {@code Hoe_Till}'s {@code Changes} map in
     * Server/Item/Interactions/Weapons/Hoe/Attacks/Till/Hoe_Till.json.
     * COPIED VERBATIM: one wrong character is a silent no-op in the world, and
     * anything outside this set is somebody's build.
     */
    public static final Set<String> TILLABLE = Set.of(
        "Soil_Dirt", "Soil_Dirt_Burnt", "Soil_Dirt_Cold", "Soil_Dirt_Dry",
        "Soil_Grass", "Soil_Grass_Burnt", "Soil_Grass_Cold", "Soil_Grass_Deep",
        "Soil_Grass_Dry", "Soil_Grass_Full", "Soil_Grass_Sunny", "Soil_Leaves",
        "Soil_Mud", "Soil_Mud_Dry", "Soil_Needles", "Soil_Pathway");

    private FarmProbe() {
    }

    public static boolean isTillable(String assetKey) {
        return assetKey != null && TILLABLE.contains(assetKey);
    }

    /**
     * Run one engine interaction chain against (x,y,z).
     *
     * @param face the client hit face to report, or {@code null} to skip the client
     *             state entirely (tilling does not need one; the mining chain never
     *             set one either). A face can only be written AFTER {@code initChain}:
     *             {@code InteractionContext.getClientState()} delegates to the chain
     *             entry, which does not exist before the chain is built — doing it
     *             earlier throws {@code NullPointerException: this.entry is null}.
     * @return true when the chain was queued. A queued chain runs on the entity's
     *         next interaction tick, so the caller MUST verify by block read-back
     *         after a delay — never treat this return as evidence of effect.
     */
    public static boolean act(World world, Store<EntityStore> store, Ref<EntityStore> actor,
                              int x, int y, int z, String rootInteractionId, BlockFace face) {
        if (world == null || store == null || actor == null) return false;
        try {
            InteractionManager manager = store.getComponent(
                actor, InteractionModule.get().getInteractionManagerComponent());
            if (manager == null) {
                log("Farm act: no InteractionManager on the actor");
                return false;
            }
            RootInteraction root = RootInteraction.getRootInteractionOrUnknown(rootInteractionId);
            if (root == null) {
                log("Farm act: root interaction '" + rootInteractionId + "' unavailable");
                return false;
            }
            log("Farm act: root=" + rootInteractionId + " needsRemoteSync=" + root.needsRemoteSync());

            ChunkStore chunkStore = world.getChunkStore();
            BlockPosition raw = new BlockPosition(x, y, z);

            InteractionContext ctx = InteractionContext.forInteraction(
                manager, actor, InteractionType.Collision, store);
            ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK_RAW, raw);
            ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK,
                BlockOperations.resolveBaseBlockPosition(chunkStore, raw));

            InteractionChain chain = manager.initChain(
                InteractionType.Collision, ctx, root, -1, raw, false);
            log("Farm act: chain requiresClient=" + chain.requiresClient());

            if (face != null) {
                // The part mining never needed: give the chain the client-shaped hit
                // so a BlockCondition's "Face" matcher can match. The entry exists now.
                InteractionEntry entry = ctx.getEntry();
                if (entry == null) entry = chain.getOrCreateInteractionEntry(0);
                if (entry == null) {
                    log("Farm act: no chain entry - face cannot be set");
                    return false;
                }
                // InteractionEntry.setClientState REJECTS (returns false, leaves the field
                // null) any state whose operationCounter/rootInteraction differ from the
                // entry's server state. A bare new InteractionSyncData() is all zeros and is
                // therefore always rejected - copy the identity fields first.
                InteractionSyncData client = entry.getClientState();
                if (client == null) {
                    InteractionSyncData server = entry.getServerState();
                    client = new InteractionSyncData();
                    if (server != null) {
                        client.operationCounter = server.operationCounter;
                        client.rootInteraction = server.rootInteraction;
                    }
                }
                client.blockPosition = raw;
                client.blockFace = face;
                client.blockRotation = new BlockRotation(Rotation.None, Rotation.None, Rotation.None);
                boolean accepted = entry.setClientState(client);
                InteractionSyncData stored = entry.getClientState();
                log("Farm act: client face=" + face + " accepted=" + accepted
                    + " storedFace=" + (stored == null ? "no-state" : String.valueOf(stored.blockFace)));
            }

            manager.queueExecuteChain(chain);
            return true;
        } catch (Throwable t) {
            log("Farm act @" + x + "," + y + "," + z + " (" + rootInteractionId + ") failed: " + t);
            return false;
        }
    }

    /**
     * S2: place a crop block with the engine's own placer - the server-side call the
     * {@code PlaceBlockInteraction} makes after a client asks to place
     * ({@code PlaceBlockUtils.placeBlock}, 16 args, mapped from that call site:
     * placer, stack, block key, container, face DIRECTION, clicked block, rotation,
     * held slot, removeItemInHand, chunk section ref, chunk accessor, entity accessor,
     * quickReplace, quickRetype, noPhysics).
     *
     * <p>Why not the chain: running {@code Seed_Condition -> Seed_Place} on the NPC
     * entity despawning her (measured 2026-10-25 - {@code entity ref invalid} 174 ms
     * after the chain was queued). Placement is a client interaction; the server-side
     * equivalent for an actor without a client is this call.</p>
     *
     * <p>WORLD-THREAD ONLY. Consumes one item from the held slot when it succeeds.</p>
     */
    public static boolean plant(World world, Store<EntityStore> store, Ref<EntityStore> actor,
                                int x, int y, int z, String cropBlockId,
                                ItemContainer container, byte slot, ItemStack stack) {
        if (world == null || store == null || actor == null) return false;
        if (cropBlockId == null || cropBlockId.isEmpty()) {
            log("Farm plant S2: no crop block id for that seed - refusing (not soil-plantable)");
            return false;
        }
        if (container == null || stack == null) {
            log("Farm plant S2: no held seed stack - refusing");
            return false;
        }
        try {
            ChunkStore chunkStore = world.getChunkStore();
            Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (section == null || !section.isValid()) {
                log("Farm plant S2: no chunk section at @" + x + "," + y + "," + z);
                return false;
            }
            boolean ok = BlockPlaceUtils.placeBlock(
                actor, stack, cropBlockId, container,
                new Vector3i(0, 1, 0),                 // face direction: place on the Up face
                new Vector3i(x, y, z),                 // the tilled soil block it sits on
                new BlockRotation(Rotation.None, Rotation.None, Rotation.None),
                slot, true,                            // consume one seed from the hand
                section, chunkStore.getStore(), store,
                false, false, false);                  // quickReplace, quickRetype, noPhysics
            log("Farm plant S2 @" + x + "," + (y + 1) + "," + z
                + " block=" + cropBlockId + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            log("Farm plant S2 @" + x + "," + y + "," + z + " failed: " + t);
            return false;
        }
    }

    /**
     * S3: raw crop-block write - the exact primitive the engine's own NPC
     * {@code ActionPlaceBlock} uses, with the 10-arg {@code BlockOperations.setBlock}
     * mapped from that call site: (chunkStore, chunk section ref, x, y, z, block type
     * INDEX, BlockType, 0, 0, 0). The destination cell is passed directly (no face, no
     * clicked block), which is also why {@code ActionPlaceBlock} floors a target
     * position instead of resolving a hit.
     *
     * <p>Chosen over S2 because {@code BlockPlaceUtils.placeBlock} returned false
     * silently for the NPC actor (its validation is player-shaped), and over the
     * interaction chain because that despawned her. What this does NOT do: consume the
     * item, fire {@code PlaceBlockEvent}, play effects or roll drops - the caller
     * consumes the seed and the probe verifies what actually exists afterwards.</p>
     *
     * <p>WORLD-THREAD ONLY.</p>
     *
     * @return a one-line state report, or null when the write could not be attempted.
     */
    public static String plantRaw(World world, int x, int y, int z, String cropBlockId) {
        if (world == null) return null;
        try {
            BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
            if (map == null) {
                log("Farm plant S3: block type map unavailable");
                return null;
            }
            int index = map.getIndex(cropBlockId);
            if (index <= 0) {
                log("Farm plant S3: unknown crop block '" + cropBlockId + "' (index=" + index + ")");
                return null;
            }
            BlockType type = map.getAsset(index);

            ChunkStore chunkStore = world.getChunkStore();
            Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (section == null || !section.isValid()) {
                log("Farm plant S3: no chunk section at @" + x + "," + y + "," + z);
                return null;
            }
            boolean ok = BlockOperations.setBlock(chunkStore, section, x, y, z, index, type, 0, 0, 0);

            // Read-back + the growth prerequisite: a crop only ticks if the block entity
            // carries a FarmingBlock component, so "block placed" is not "crop planted".
            String key = BlockScanner.assetId(BlockBreaker.blockId(world, x, y, z));
            Ref<ChunkStore> entity = BlockModule.getBlockEntity(world, x, y, z);
            FarmingBlock farm = BlockModule.getComponent(
                FarmingBlock.getComponentType(), world, x, y, z);
            String report = "ok=" + ok + " block=" + key
                + " entity=" + (entity == null ? "none" : "yes")
                + " farming=" + (farm == null ? "none" : "yes");
            log("Farm plant S3 @" + x + "," + y + "," + z + " " + report);
            return report;
        } catch (Throwable t) {
            log("Farm plant S3 @" + x + "," + y + "," + z + " failed: " + t);
            return null;
        }
    }

    /** Asset key at (x,y,z), or null for air/unloaded. */
    public static String keyAt(World world, int x, int y, int z) {
        return BlockScanner.assetId(BlockBreaker.blockId(world, x, y, z));
    }

    private static void log(String message) {
        LayaPlugin.log(message);
    }
}
