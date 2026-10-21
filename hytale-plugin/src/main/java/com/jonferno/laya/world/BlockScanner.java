package com.jonferno.laya.world;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkGrid;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.function.IntPredicate;

/**
 * Block scanning over the engine's chunk sections. WORLD-THREAD ONLY — every
 * caller runs inside {@code world.execute(...)}.
 *
 * Reference implementation mirrored (javap'd from the local 0.7.2 HytaleServer.jar):
 * {@code com.hypixel.hytale.server.core.universe.world.commands.block.bulk.BulkSectionScan}
 * <ul>
 *   <li>{@code forEachSection(...)} walks section coordinates with
 *       {@code SphereSpiralIterator(SphereOffsets)} and skips y outside
 *       {@code [0, HEIGHT_SECTIONS)} when the world does not support cubic sections.</li>
 *   <li>the per-section read is {@code lambda$forEachSection$0}:
 *       <pre>
 *       Ref&lt;ChunkStore&gt; ref = grid.getChunkSectionReferenceAtBlock(x, y, z);
 *       if (ref == null || !ref.isValid()) -&gt; null
 *       else chunkStore.getStore().getComponent(ref, BlockSection.getComponentType())
 *       </pre>
 *       BulkSectionScan uses the ASYNC reference getter because bulk commands run
 *       off-thread; this scanner already runs on the world thread, so the synchronous
 *       {@code getChunkSectionReferenceAtBlock} is used — same ChunkGrid API, no join.</li>
 *   <li>per-block reads go through {@code BlockSection.get(x, y, z)}, which is
 *       {@code get(ChunkUtil.indexBlock(x, y, z))} — LOCAL section coordinates 0..31.</li>
 * </ul>
 *
 * The scanner returns raw engine block ids; NAMES come from config/blocks.json (the
 * single name source) and are resolved by the caller ({@link #assetId(int)} is the
 * id -&gt; engine asset-key bridge used for both hint matching and display lookup).
 */
public final class BlockScanner {

    private BlockScanner() {
    }

    /** Local section size / mask (ChunkUtil.SIZE == 32, ChunkUtil.SIZE_MASK == 31). */
    private static final int SECTION_BITS = ChunkUtil.BITS;
    private static final int SECTION_MASK = ChunkUtil.SIZE_MASK;

    /** Air. BlockType.EMPTY_ID == 0 (BlockType.EMPTY_KEY == "Empty"). */
    public static final int EMPTY_ID = BlockType.EMPTY_ID;

    /** identify ray: 16 steps of 0.4 blocks (brief). */
    private static final double RAY_STEP = 0.4;
    private static final int RAY_STEPS = 16;

    /** M1 view-cone pre-rank threshold: dot(playerForward, dirToBlock) > 0.3. */
    private static final double VIEW_CONE_DOT = 0.3;

    /** identify fallback: nearest non-air within 6 blocks (brief). */
    private static final double IDENTIFY_FALLBACK_RADIUS = 6.0;

    /** Result of a section scan: the chosen block plus the scan counters. */
    public static final class ScanResult {
        public final int x;
        public final int y;
        public final int z;
        public final int blockId;
        /** non-air blocks inspected inside the radius */
        public final int checked;
        /** blocks inside the radius whose id matched the predicate */
        public final int found;

        ScanResult(int x, int y, int z, int blockId, int checked, int found) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
            this.checked = checked;
            this.found = found;
        }

        public boolean found() {
            return found > 0;
        }
    }

    /**
     * Briefed public entry point: nearest block whose id matches {@code idMatch}
     * within {@code radius} of {@code center}, or null. Pure distance ranking.
     */
    public static int[] findNearest(Store<EntityStore> store, Vector3d center, double radius, IntPredicate idMatch) {
        ScanResult r = scanNearest(store, center, radius, idMatch, null);
        if (r == null || !r.found()) return null;
        return new int[]{r.x, r.y, r.z, r.blockId};
    }

    /**
     * Nearest matching block plus scan counters (checked/found) and plan-M1
     * view-cone pre-rank: candidates with {@code dot(viewForward, dirToBlock) > 0.3}
     * rank before purely nearer candidates. {@code viewForward} may be null -> pure distance.
     */
    public static ScanResult scanNearest(Store<EntityStore> store, Vector3d center, double radius,
                                         IntPredicate idMatch, Vector3d viewForward) {
        return scanNearest(store, center, radius, idMatch, viewForward, 0.0);
    }

    /**
     * Same as {@link #scanNearest(Store, Vector3d, double, IntPredicate, Vector3d)} with an
     * optional vertical band: candidates farther than {@code maxDy} blocks above/below the
     * centre are ignored ({@code maxDy <= 0} disables the band). Task scanning uses it so
     * Laya does not dive into caves 25 blocks under the player.
     */
    public static ScanResult scanNearest(Store<EntityStore> store, Vector3d center, double radius,
                                         IntPredicate idMatch, Vector3d viewForward, double maxDy) {
        ChunkGrid grid = gridOf(store);
        if (grid == null || center == null || idMatch == null || radius <= 0.0) return null;
        Store<ChunkStore> sectionStore = grid.getStore();
        if (sectionStore == null) return null;

        Vector3d fwd = null;
        if (viewForward != null && viewForward.lengthSquared() > 1.0e-8) {
            fwd = new Vector3d(viewForward).normalize();
        }

        double r2 = radius * radius;
        int ri = (int) Math.ceil(radius);
        int minX = (int) Math.floor(center.x) - ri;
        int maxX = (int) Math.floor(center.x) + ri;
        int minY = (int) Math.floor(center.y) - ri;
        int maxY = (int) Math.floor(center.y) + ri;
        int minZ = (int) Math.floor(center.z) - ri;
        int maxZ = (int) Math.floor(center.z) + ri;

        int checked = 0;
        int found = 0;
        // best-so-far
        int bx = 0, by = 0, bz = 0, bid = 0;
        boolean bestExposed = false;
        boolean bestCone = false;
        double bestD2 = Double.MAX_VALUE;

        for (int sx = minX >> SECTION_BITS; sx <= (maxX >> SECTION_BITS); sx++) {
            int sbx = sx << SECTION_BITS;
            int lx0 = Math.max(0, minX - sbx);
            int lx1 = Math.min(SECTION_MASK, maxX - sbx);
            if (lx0 > lx1) continue;
            for (int sy = minY >> SECTION_BITS; sy <= (maxY >> SECTION_BITS); sy++) {
                int sby = sy << SECTION_BITS;
                int ly0 = Math.max(0, minY - sby);
                int ly1 = Math.min(SECTION_MASK, maxY - sby);
                if (ly0 > ly1) continue;
                for (int sz = minZ >> SECTION_BITS; sz <= (maxZ >> SECTION_BITS); sz++) {
                    int sbz = sz << SECTION_BITS;
                    int lz0 = Math.max(0, minZ - sbz);
                    int lz1 = Math.min(SECTION_MASK, maxZ - sbz);
                    if (lz0 > lz1) continue;

                    // --- BulkSectionScan.lambda$forEachSection$0, synchronous variant ---
                    Ref<ChunkStore> ref = grid.getChunkSectionReferenceAtBlock(sbx, sby, sbz);
                    if (ref == null || !ref.isValid()) continue;   // null/missing refs are skipped
                    BlockSection section = sectionStore.getComponent(ref, BlockSection.getComponentType());
                    if (section == null) continue;
                    // ------------------------------------------------------------------

                    for (int lx = lx0; lx <= lx1; lx++) {
                        double dx = (sbx + lx) - center.x;
                        double dx2 = dx * dx;
                        for (int lz = lz0; lz <= lz1; lz++) {
                            double dz = (sbz + lz) - center.z;
                            double dxz2 = dx2 + dz * dz;
                            if (dxz2 > r2) continue;
                            for (int ly = ly0; ly <= ly1; ly++) {
                                double dy = (sby + ly) - center.y;
                                double d2 = dxz2 + dy * dy;
                                if (d2 > r2) continue;
                                int id = section.get(lx, ly, lz);
                                if (id == EMPTY_ID) continue;
                                checked++;
                                if (!idMatch.test(id)) continue;
                                if (maxDy > 0.0 && Math.abs(dy) > maxDy) continue;
                                // Cycle 7: rank reachable/visible ore first. The nearest
                                // matching block is often a buried vein the player cannot
                                // see; she then walks off and swings at the ground. Buried
                                // candidates are only considered within 8 blocks.
                                boolean exp = isExposed(grid, sbx + lx, sby + ly, sbz + lz);
                                if (!exp && d2 > 64.0) continue;
                                found++;
                                boolean cone = false;
                                if (fwd != null) {
                                    double len = Math.sqrt(d2);
                                    if (len > 1.0e-6) {
                                        cone = (dx * fwd.x + dy * fwd.y + dz * fwd.z) / len > VIEW_CONE_DOT;
                                    }
                                }
                                if (found == 1
                                    || (exp && !bestExposed)
                                    || (exp == bestExposed && cone && !bestCone)
                                    || (exp == bestExposed && cone == bestCone && d2 < bestD2)) {
                                    bx = sbx + lx;
                                    by = sby + ly;
                                    bz = sbz + lz;
                                    bid = id;
                                    bestExposed = exp;
                                    bestCone = cone;
                                    bestD2 = d2;
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ScanResult(bx, by, bz, bid, checked, found);
    }

    /**
     * Ray-march the player's view: 16 steps of 0.4 blocks from the player's
     * transform position along {@code Transform.getDirection()} (javap-pinned:
     * pitch/yaw based), returning {x,y,z,blockId} of the first non-air block.
     * Fallback when the ray misses: nearest non-air block within 6 blocks with the
     * plan-M1 view-cone pre-rank (aimed blocks win over merely closer ones).
     */
    public static int[] identifyBlock(PlayerRef player, double maxDist) {
        if (player == null) return null;
        Transform transform = player.getTransform();
        if (transform == null) return null;
        Ref<EntityStore> playerEntityRef = player.getReference();
        if (playerEntityRef == null) return null;
        Store<EntityStore> store = playerEntityRef.getStore();
        ChunkGrid grid = gridOf(store);
        if (grid == null) return null;

        Vector3d pos = new Vector3d(transform.getPosition());
        Vector3d fwd = new Vector3d(transform.getDirection());
        if (fwd.lengthSquared() < 1.0e-8) return null;
        fwd.normalize();

        int steps = (int) Math.max(1L, Math.min((long) RAY_STEPS, Math.round(maxDist / RAY_STEP)));
        for (int i = 1; i <= steps; i++) {
            double d = i * RAY_STEP;
            int bx = (int) Math.floor(pos.x + fwd.x * d);
            int by = (int) Math.floor(pos.y + fwd.y * d);
            int bz = (int) Math.floor(pos.z + fwd.z * d);
            int id = blockAt(grid, bx, by, bz);
            if (id != EMPTY_ID) {
                return new int[]{bx, by, bz, id};
            }
        }

        // Ray missed (looking at sky/void): nearest non-air within 6, view-cone first.
        ScanResult near = scanNearest(store, pos, IDENTIFY_FALLBACK_RADIUS, id -> id != EMPTY_ID, fwd);
        if (near == null || !near.found()) return null;
        return new int[]{near.x, near.y, near.z, near.blockId};
    }

    /**
     * Engine asset key (e.g. "Ore_Copper") for a raw block id, or null when the id
     * is out of the asset array. This is the id -&gt; blocks.json key bridge: the
     * blocks.json "blocks" keys ARE engine asset keys.
     */
    public static String assetId(int blockId) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) return null;
        BlockType type = map.getAsset(blockId);
        return type == null ? null : type.getId();
    }

    /**
     * Task 5 read helper: raw engine block id at (x,y,z) for the break driver's
     * before/after verification. Same section-read sequence as {@link #identifyBlock};
     * returns {@link #EMPTY_ID} for unloaded/air positions.
     *
     * <p>Package-visible on purpose (only {@link BlockBreaker} uses it) — this is the
     * single minimal addition made to BlockScanner for Task 5.</p>
     */
    static int readBlock(Store<EntityStore> store, int x, int y, int z) {
        ChunkGrid grid = gridOf(store);
        if (grid == null) return EMPTY_ID;
        return blockAt(grid, x, y, z);
    }

    /** True when at least one of the six face neighbours is air (surface / cave wall). */
    private static boolean isExposed(ChunkGrid grid, int x, int y, int z) {
        return blockAt(grid, x + 1, y, z) == EMPTY_ID
            || blockAt(grid, x - 1, y, z) == EMPTY_ID
            || blockAt(grid, x, y + 1, z) == EMPTY_ID
            || blockAt(grid, x, y - 1, z) == EMPTY_ID
            || blockAt(grid, x, y, z + 1) == EMPTY_ID
            || blockAt(grid, x, y, z - 1) == EMPTY_ID;
    }

    /** Single block read: exact BulkSectionScan section-read sequence, null-safe. */
    private static int blockAt(ChunkGrid grid, int x, int y, int z) {        Ref<ChunkStore> ref = grid.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null || !ref.isValid()) return EMPTY_ID;
        Store<ChunkStore> sectionStore = grid.getStore();
        if (sectionStore == null) return EMPTY_ID;
        BlockSection section = sectionStore.getComponent(ref, BlockSection.getComponentType());
        if (section == null) return EMPTY_ID;
        return section.get(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK);
    }

    /** Store<EntityStore> -> the world's ChunkGrid (ChunkStore extends ChunkGrid). */
    private static ChunkGrid gridOf(Store<EntityStore> store) {
        if (store == null) return null;
        EntityStore external = store.getExternalData();
        if (external == null) return null;
        World world = external.getWorld();
        if (world == null) return null;
        return world.getChunkStore();
    }
}
