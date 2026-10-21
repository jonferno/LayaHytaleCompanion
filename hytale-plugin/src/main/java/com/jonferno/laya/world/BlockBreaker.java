package com.jonferno.laya.world;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jonferno.laya.LayaPlugin;

/**
 * S1 break driver: mirrors the inner chain of
 * {@code com.hypixel.hytale.server.npc.corecomponents.combat.ActionBlockHitInteraction.execute}
 * as verified by {@code javap -c -p} against the local 0.7.2 HytaleServer.jar.
 *
 * <p>The engine action, once per collision hit, does (bytecode offsets 39..209):</p>
 * <pre>
 *   manager = store.getComponent(npcRef, InteractionModule.get().getInteractionManagerComponent())
 *   root    = RootInteraction.getRootInteractionOrUnknown(interactionId)
 *   chunkStore = store.getExternalData().getWorld().getChunkStore()
 *   raw     = new BlockPosition(hit.blockX, hit.blockY, hit.blockZ)
 *   ctx     = InteractionContext.forInteraction(manager, npcRef, InteractionType.Collision, store)
 *   ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK_RAW, raw)
 *   ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK,
 *                                    BlockOperations.resolveBaseBlockPosition(chunkStore, raw))
 *   chain   = manager.initChain(InteractionType.Collision, ctx, root, -1, raw, false)
 *   manager.queueExecuteChain(chain)
 * </pre>
 *
 * <p>What we synthesize: the engine gets {@code BlockHit.blockX/Y/Z} from a
 * {@code BlockCollisionProvider} (the motion controller's collision hits). We have no
 * collision provider, so the target coordinates are supplied directly and the
 * remaining members (manager, context, meta keys, base-block resolution, chain) are
 * built exactly as the bytecode does. Nothing is shortcut: the interaction still runs
 * through the engine's chain machinery.</p>
 *
 * <p>{@link #hitBlock} returns whether a change was OBSERVED by a section read taken
 * immediately after queueing. {@code queueExecuteChain} only appends to the manager's
 * {@code chainStartQueue} (verified: the method body is a single {@code ObjectList.add}),
 * so the chain is ticked later by the entity's interaction tick — the immediate read can
 * legitimately report {@code false}. Callers that need positive evidence should
 * re-read after a short delay ({@link #verify(World, int, int, int)}).</p>
 *
 * <p>WORLD-THREAD ONLY — every engine call above touches world/chunk state.</p>
 */
public final class BlockBreaker {

    /** Root interaction key for plain block damage (Server/Item/RootInteractions/Block/Block_Damage.json). */
    public static final String DEFAULT_INTERACTION = "Block_Damage";

    /**
     * Probe candidates, most specific first. {@code Test_Charge_Destroy_Block} is the
     * exact interaction the engine's own NPC charge test roles use for BlockHitInteraction
     * (Server/NPC/Roles/_Core/Tests_Development/*.json) and resolves to the
     * {@code DestroyBlock} interaction; {@code Block_Damage} is the plain damage key
     * (Condition "Adventure" -> ApplyEffect Physical 5, cooldown 1s).
     */
    public static final String[] PROBE_INTERACTION_IDS = {
        "Test_Charge_Destroy_Block", DEFAULT_INTERACTION
    };

    /**
     * Fix cycle 1 (mine): in 0.7.2 every {@code Block_Damage} hit reported
     * {@code damaged=false} (12/12, final test 2026-10-18), so the plain
     * {@code ApplyEffect Physical 5} path does not register on world blocks.
     * {@code mine} now drives the same proven root interaction as {@code gather}
     * (one-hit destroy, "block gone (1 hits)" evidence). The pickaxe requirement
     * is still enforced by the plugin's refusal gate, not by the interaction.
     */
    public static final String MINE_INTERACTION = "Test_Charge_Destroy_Block";

    /**
     * R-T6-1: the interaction the {@code gather} task drives. {@code Test_Charge_Destroy_Block}
     * resolves to the {@code DestroyBlock} interaction (instant destroy, no tool needed) and
     * is the exact id PROBE SESSION 1 proved breaks a block (Script: "block gone (1 hits)").
     */
    public static final String GATHER_INTERACTION = "Test_Charge_Destroy_Block";

    /**
     * Break driver selected by PROBE SESSION 1 (S1 chain = VALID, recorded in the plan
     * ledger/progress). Compile-time constant so the shipped artifact names the driver.
     */
    public static final String CURRENT_DRIVER = "S1";

    /**
     * The acting entity. The exact required 5-argument signature has no Ref parameter,
     * but the engine chain is entity-scoped (manager component lookup + InteractionContext
     * owner), so the plugin binds its single NPC here right after spawn (world thread).
     */
    private static volatile Ref<EntityStore> boundActor;

    private BlockBreaker() {
    }

    /** Bind the acting NPC (single-actor plugin, world thread, called once after spawn). */
    public static void bindActor(Ref<EntityStore> actor) {
        boundActor = actor;
    }

    /**
     * Briefed entry point. Uses the actor bound with {@link #bindActor(Ref)}; returns
     * false (and logs once per call) when no actor is bound yet.
     */
    public static boolean hitBlock(World world, Store<EntityStore> store,
                                   int x, int y, int z, String interactionId) {
        Ref<EntityStore> actor = boundActor;
        if (actor == null) {
            log("hitBlock: no actor bound (call bindActor after spawn) — block hit skipped");
            return false;
        }
        return hitBlock(world, store, actor, x, y, z, interactionId);
    }

    /**
     * Explicit-actor overload: full engine chain for one block hit.
     *
     * @return true when a block-id change was observed by the immediate after-read
     *         (live chains are queued, so a false return is not proof of failure)
     */
    public static boolean hitBlock(World world, Store<EntityStore> store, Ref<EntityStore> actor,
                                   int x, int y, int z, String interactionId) {
        if (world == null || store == null || actor == null) return false;
        String id = (interactionId == null || interactionId.isEmpty())
            ? DEFAULT_INTERACTION : interactionId;

        int before = BlockScanner.readBlock(store, x, y, z);
        if (before == BlockScanner.EMPTY_ID) {
            // Nothing to hit (already air) — cheaper than building a doomed chain.
            return false;
        }

        try {
            InteractionManager manager = store.getComponent(
                actor, InteractionModule.get().getInteractionManagerComponent());
            if (manager == null) {
                log("hitBlock: no InteractionManager on actor — block hit skipped (" + id + ")");
                return false;
            }

            RootInteraction root = RootInteraction.getRootInteractionOrUnknown(id);
            if (root == null) {
                log("hitBlock: root interaction '" + id + "' unavailable");
                return false;
            }

            ChunkStore chunkStore = world.getChunkStore();
            BlockPosition raw = new BlockPosition(x, y, z);

            InteractionContext ctx = InteractionContext.forInteraction(
                manager, actor, InteractionType.Collision, store);
            ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK_RAW, raw);
            ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK,
                BlockOperations.resolveBaseBlockPosition(chunkStore, raw));

            InteractionChain chain = manager.initChain(
                InteractionType.Collision, ctx, root, -1, raw, false);
            manager.queueExecuteChain(chain);
        } catch (Throwable t) {
            log("hitBlock @" + x + "," + y + "," + z + " (" + id + ") failed: " + t);
            return false;
        }

        int after = BlockScanner.readBlock(store, x, y, z);
        return after != before;
    }

    /**
     * Read-back used as probe evidence: true while a non-air block still occupies
     * (x,y,z). The section read is the same BulkSectionScan sequence BlockScanner uses.
     */
    public static boolean verify(World world, int x, int y, int z) {
        return blockId(world, x, y, z) != BlockScanner.EMPTY_ID;
    }

    /** Raw engine block id at (x,y,z); {@link BlockScanner#EMPTY_ID} when unreadable/air. */
    public static int blockId(World world, int x, int y, int z) {
        if (world == null) return BlockScanner.EMPTY_ID;
        EntityStore entityStore = world.getEntityStore();
        Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
        return blockId(store, x, y, z);
    }

    /** Raw engine block id using an already-captured entity store. */
    static int blockId(Store<EntityStore> store, int x, int y, int z) {
        return BlockScanner.readBlock(store, x, y, z);
    }

    // --- S2 fallback (signature reserved only — activates ONLY if the probe kills S1) ---
    // Synthesized BlockCollisionProvider (fills BlockHit.blockX/Y/Z, hitCount=1) fed to a
    // private ActionBlockHitInteraction instance. Not implemented on purpose: the probe
    // must decide first.

    /** S2 placeholder — do not implement unless the probe shows S1 cannot damage blocks. */
    public static boolean hitBlockS2(World world, Store<EntityStore> store, Ref<EntityStore> actor,
                                     int x, int y, int z, String interactionId) {
        throw new UnsupportedOperationException("S2 reserved: activates only if the S1 probe fails");
    }

    // --- S3 fallback (signature reserved only — direct block write) ---
    // Block write primitive discovered by javap: BlockOperations.setBlock(ChunkStore,
    // Ref<ChunkStore>, x, y, z, blockId, BlockType, rotation, ...). Not implemented on
    // purpose: it bypasses drops/effects, so it is a last resort.

    /** S3 placeholder — do not implement unless both S1 and S2 fail in the probe. */
    public static boolean removeBlock(World world, Store<EntityStore> store, int x, int y, int z) {
        throw new UnsupportedOperationException("S3 reserved: activates only if S1/S2 fail the probe");
    }

    private static void log(String message) {
        LayaPlugin.log(message);
    }
}
