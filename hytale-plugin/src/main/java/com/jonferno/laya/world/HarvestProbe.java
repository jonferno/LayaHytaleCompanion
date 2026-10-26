package com.jonferno.laya.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.jonferno.laya.LayaPlugin;

import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Harvesting a ripe crop: the engine's own primitive, one call, no interaction chain.
 *
 * <p>Measured 2026-10-26 from {@code HytaleServer.jar}: the Eternal crops' {@code Use}
 * interaction is {@code {"Type": "HarvestCrop"}}, and
 * {@code HarvestCropInteraction.interactWithBlock} is nothing but
 * {@code FarmingUtil.harvest(chunkStore, entityStore, actorRef, pos)}. So the whole job
 * is one static call on the world thread - no client state to fake, no chain to queue,
 * and none of the despawn risk that killed the {@code Seed_Condition -> Seed_Place}
 * planting chain.</p>
 *
 * <p>What that primitive does (bytecode, same day):</p>
 * <ul>
 *   <li>requires {@code WorldConfig.isBlockGatheringAllowed()};</li>
 *   <li>requires {@code blockType.getGathering().getHarvest() != null};</li>
 *   <li>gives the drops to the harvester through {@code ItemUtils.interactivelyPickupItem},
 *       whose non-player path adds them to her inventory and drops the remainder at her
 *       feet (no player-shaped validation);</li>
 *   <li>plain crops ({@code StageSetAfterHarvest == null}) get {@code setBlock EMPTY}:
 *       consumed;</li>
 *   <li>Eternal crops reset the block entity to {@code "Harvested"} with progress 0:
 *       the block stays and regrows by itself.</li>
 * </ul>
 *
 * <p>It does NOT check reach or facing (the interaction layer does that for players), so
 * callers must gate on distance themselves - see {@code HARVEST_REACH} in
 * {@link com.jonferno.laya.npc.NPCController}. Without that gate she would vacuum the
 * whole field from where she stands.</p>
 *
 * <p>WORLD-THREAD ONLY: the chunk store, the store and the block map all assert it.</p>
 */
public final class HarvestProbe {

    /** Only the crop family is harvestable for this feature (not flowers/grass). */
    public static final String CROP_BLOCK_PREFIX = "Plant_Crop_";

    /** Local-coordinate mask inside a chunk section (ChunkUtil.SIZE_MASK). */
    private static final int SECTION_MASK = 31;

    private HarvestProbe() {
    }

    /** The BlockType the engine would read at this cell, or null for air/unloaded. */
    private static BlockType typeAt(World world, int x, int y, int z) {
        if (world == null) return null;
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) return null;
        int id = BlockBreaker.blockId(world, x, y, z);
        if (id == BlockScanner.EMPTY_ID) return null;
        return map.getAsset(id);
    }

    /** Asset key at the cell (state-specific when the engine registers states that way). */
    public static String keyAt(World world, int x, int y, int z) {
        return BlockScanner.assetId(BlockBreaker.blockId(world, x, y, z));
    }

    /**
     * Canonical form of an asset key.
     *
     * <p>Hytale registers a generated block type per growth stage and marks those with a
     * leading {@code *} in {@code BlockType.getId()}. Measured 2026-10-26: a ripe carrot read
     * as {@code *Plant_Crop_Carrot_Block_State_Definitions_StageFinal}, so every
     * {@code startsWith("Plant_Crop_")} test in this class and the crop-word parser silently
     * rejected it - the sweep reported {@code cropBlocks=0} next to a two-block-away carrot.</p>
     */
    static String normalizeKey(String key) {
        if (key == null) return "";
        String k = key.trim();
        while (k.startsWith("*")) k = k.substring(1);
        return k;
    }

    /** Case-insensitive prefix test (asset keys are mixed case, callers may lower-case). */
    private static boolean hasPrefix(String key, String prefix) {
        return key.length() >= prefix.length()
            && key.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    static boolean isCropKey(String key) {
        String k = normalizeKey(key);
        return !k.isEmpty() && hasPrefix(k, CROP_BLOCK_PREFIX);
    }

    /**
     * Candidate test: the planted crop family ONLY.
     *
     * <p>An earlier build also accepted any {@code Plant_*} block with
     * {@code Gathering.Harvest}, and she spent a whole task picking wild flowers and tall
     * grass while the carrots she was asked for stayed put (measured 2026-10-26). "Harvest"
     * means the farm, not the meadow.</p>
     */
    private static boolean isCandidate(String key, BlockType type) {
        return isCropKey(key);
    }

    /**
     * Direct target test for the block the PLAYER IS LOOKING AT: a crop of any ripeness, or
     * any plant the engine itself calls harvestable. Used only as the "she found nothing"
     * fallback, so pointing at one ripe thing always works even if the sweep missed it.
     */
    public static boolean isHarvestableDirect(World world, int x, int y, int z) {
        try {
            BlockType type = typeAt(world, x, y, z);
            if (type == null) return false;
            if (isCropKey(type.getId())) return true;
            BlockGathering gathering = type.getGathering();
            return gathering != null && gathering.getHarvest() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when this cell is a ripe crop.
     *
     * <p>Signal 1 is the engine's own predicate - {@code Gathering.Harvest != null} - which
     * is exactly what {@code harvest0} checks. Signal 2 is the fallback for a build where
     * the state variant's gathering is not exposed on the BlockType read path (measured
     * in game 2026-10-26: signal 1 alone found nothing while a ripe carrot stood next to
     * the player): the crop's {@code FarmingBlock} has advanced to the last stage of its
     * current stage set. A block in the {@code "Harvested"} set is regrowing, not ripe.</p>
     */
    public static boolean isRipe(World world, int x, int y, int z) {
        try {
            BlockType type = typeAt(world, x, y, z);
            return type != null && isRipe(type, world, x, y, z);
        } catch (Throwable t) {
            log("Harvest: ripeness check @" + x + "," + y + "," + z + " failed: " + t);
            return false;
        }
    }

    private static boolean isRipe(BlockType type, World world, int x, int y, int z) {
        BlockGathering gathering = type.getGathering();
        if (gathering != null && gathering.getHarvest() != null) return true;

        String stageSet = stageSetAt(world, x, y, z);
        if (stageSet == null || "Harvested".equals(stageSet)) return false;
        FarmingData farming = type.getFarming();
        if (farming == null) return false;
        Map<String, FarmingStageData[]> all = farming.getStages();
        if (all == null) return false;
        FarmingStageData[] stages = all.get(stageSet);
        if (stages == null || stages.length == 0) return false;
        FarmingBlock farm = farmAt(world, x, y, z);
        if (farm == null) return false;
        // Crops advance one entry per completed duration, so the last index is ripe.
        return farm.getExecutions() >= stages.length - 1;
    }

    /**
     * True when the crop regrows after harvest instead of being consumed
     * ({@code Farming.getStageSetAfterHarvest()} is set - only Eternal crops do).
     */
    public static boolean isEternal(World world, int x, int y, int z) {
        try {
            BlockType type = typeAt(world, x, y, z);
            if (type == null || type.getFarming() == null) return false;
            return type.getFarming().getStageSetAfterHarvest() != null;
        } catch (Throwable t) {
            log("Harvest: eternal check @" + x + "," + y + "," + z + " failed: " + t);
            return false;
        }
    }

    /** Crop word for the cell ("carrot"), or "" when it is not a crop block. */
    public static String cropWord(World world, int x, int y, int z) {
        return cropWord(keyAt(world, x, y, z));
    }

    /**
     * Canonical asset key: the base block a cell's type derives from.
     *
     * <p>{@code *Plant_Crop_Wheat_Block_State_Definitions_StageFinal} and
     * {@code *Plant_Crop_Wheat_Block_State_Definitions_Stage2} both canonicalise to
     * {@code Plant_Crop_Wheat_Block}. Hytale registers a generated BlockType per growth
     * stage, and {@code config/blocks.json} only lists the base keys, so any hint matcher
     * that compares raw asset keys misses every planted/ripe crop (measured 2026-10-26:
     * "gather all that wheat" scanned 9,195 cells and found 0).</p>
     */
    public static String canonicalKey(String key) {
        String k = normalizeKey(key);
        return k.isEmpty() ? k : stripStateSuffix(k);
    }

    /**
     * {@code Plant_Crop_Carrot_Block} / {@code ..._Block_Eternal} / any state variant
     * ({@code ..._Block_StageFinal}, {@code ..._Block.Stage1}, and the generated spelling
     * {@code *..._Block_State_Definitions_StageFinal}) -> {@code carrot}.
     */
    static String cropWord(String blockKey) {
        String k = canonicalKey(blockKey);
        if (k.isEmpty()) return "";
        if (hasPrefix(k, CROP_BLOCK_PREFIX)) k = k.substring(CROP_BLOCK_PREFIX.length());
        // State suffix first: the state variants are their own asset keys.
        k = stripStateSuffix(k);
        if (k.endsWith("_Block_Eternal")) {
            k = k.substring(0, k.length() - "_Block_Eternal".length());
        } else if (k.endsWith("_Block")) {
            k = k.substring(0, k.length() - "_Block".length());
        }
        k = stripStateSuffix(k);
        return k.toLowerCase(Locale.ROOT);
    }

    private static String stripStateSuffix(String k) {
        String s = k;
        if (s.endsWith("_StageFinal")) s = s.substring(0, s.length() - "_StageFinal".length());
        else if (s.endsWith("_default") || s.endsWith("_Default")) {
            s = s.substring(0, s.length() - "_default".length());
        } else {
            for (int i = 1; i <= 9; i++) {
                String suffix = "_Stage" + i;
                if (s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    break;
                }
            }
        }
        // Belt and braces: any surviving generated marker goes too.
        s = s.replace("_State_Definitions", "").replace(".State_Definitions", "");
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);   // "Block.StageFinal" spelling
        return s;
    }

    private static FarmingBlock farmAt(World world, int x, int y, int z) {
        try {
            return BlockModule.getComponent(FarmingBlock.getComponentType(), world, x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The crop's {@code FarmingBlock} stage set ("Default", "Harvested", ...) or null. */
    public static String stageSetAt(World world, int x, int y, int z) {
        FarmingBlock farm = farmAt(world, x, y, z);
        return farm == null ? null : farm.getCurrentStageSet();
    }

    /**
     * Harvest one cell with the engine primitive.
     *
     * @return the engine's own answer: true when it harvested and dropped, false when the
     *         cell was not harvestable or block gathering is disabled. A true return is
     *         still not proof of effect - the caller verifies by block read-back.
     */
    public static boolean harvest(World world, Store<EntityStore> store,
                                  Ref<EntityStore> actor, int x, int y, int z) {
        if (world == null || store == null || actor == null) return false;
        try {
            boolean ok = FarmingUtil.harvest(
                world.getChunkStore().getStore(), store, actor, new Vector3i(x, y, z));
            log("Harvest: engine harvest @" + x + "," + y + "," + z + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            log("Harvest: engine harvest @" + x + "," + y + "," + z + " failed: " + t);
            return false;
        }
    }

    /**
     * Every ripe crop cell in a box around {@code center} (nearest first), filtered to one
     * crop word when {@code crop} is non-empty. One pass per request, never per tick.
     *
     * <p>The section reference is looked up once per chunk section rather than once per
     * cell: a 256-block radius is 263k columns, and a per-cell {@code ChunkStore} lookup
     * on the world thread would stall the server.</p>
     */
    public static List<int[]> sweep(World world, Vector3d center, int radius, int yRadius,
                                    String crop) {
        List<int[]> cells = new ArrayList<>();
        if (world == null || center == null || radius <= 0) return cells;
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) return cells;
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> sectionStore = chunkStore == null ? null : chunkStore.getStore();

        String want = crop == null ? "" : crop.trim().toLowerCase(Locale.ROOT);
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int r = Math.max(1, radius);
        final double ox = center.x, oy = center.y, oz = center.z;
        int scanned = 0, crops = 0;
        double nearest = -1;
        String nearestKey = "";
        double nearestAny = -1;
        String nearestAnyKey = "";
        List<String> samples = new ArrayList<>();
        // Which crops ARE there when the named one is not: "harvest the carrots" next to a
        // wheat field should read as a crop-word mismatch, not as "no crops".
        List<String> words = new ArrayList<>();

        int lastSx = Integer.MIN_VALUE, lastSy = Integer.MIN_VALUE, lastSz = Integer.MIN_VALUE;
        BlockSection section = null;
        for (int z = cz - r; z <= cz + r; z++) {
            for (int y = cy - yRadius; y <= cy + yRadius; y++) {
                int sy = y >> 5;
                for (int x = cx - r; x <= cx + r; x++) {
                    int sx = x >> 5, sz = z >> 5;
                    if (section == null || sx != lastSx || sy != lastSy || sz != lastSz) {
                        Ref<ChunkStore> ref = chunkStore == null ? null
                            : chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
                        section = (ref == null || !ref.isValid() || sectionStore == null) ? null
                            : sectionStore.getComponent(ref, BlockSection.getComponentType());
                        lastSx = sx;
                        lastSy = sy;
                        lastSz = sz;
                    }
                    if (section == null) continue;
                    scanned++;
                    int id = section.get(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK);
                    if (id == BlockScanner.EMPTY_ID) continue;
                    BlockType type = map.getAsset(id);
                    if (type == null) {
                        if (samples.size() < 3) samples.add("@" + x + "," + y + "," + z
                            + " id=" + id + " NO-ASSET");
                        continue;
                    }
                    String key = type.getId();
                    if (!isCandidate(key, type)) {
                        // Keep the nearest solid block: when nothing harvestable is found,
                        // this line proves the read works and says what she is standing on.
                        double anyD = Math.sqrt(sqDist(x, y, z, ox, oy, oz));
                        if (nearestAny < 0 || anyD < nearestAny) {
                            nearestAny = anyD;
                            nearestAnyKey = key;
                        }
                        continue;
                    }
                    crops++;
                    double d = Math.sqrt(sqDist(x, y, z, ox, oy, oz));
                    if (nearest < 0 || d < nearest) {
                        nearest = d;
                        nearestKey = key;
                    }
                    String word = cropWord(key);
                    if (!want.isEmpty() && !want.equals(word)) {
                        if (words.size() < 3 && !words.contains(word)) words.add(word);
                        continue;
                    }
                    if (!isRipe(type, world, x, y, z)) {
                        if (samples.size() < 3) samples.add(sample(world, x, y, z, type, word));
                        continue;
                    }
                    cells.add(new int[]{x, y, z});
                }
            }
        }
        cells.sort((a, b) -> Double.compare(sqDist(a, ox, oy, oz), sqDist(b, ox, oy, oz)));
        log("Harvest: sweep r=" + r + " y=+-" + yRadius + " crop="
            + (want.isEmpty() ? "any" : want) + " at=" + cx + "," + cy + "," + cz
            + " scanned=" + scanned + " cropBlocks=" + crops + " ripe=" + cells.size()
            + (crops == 0
                ? " nearestSolid=" + (nearestAny < 0 ? "none"
                    : String.format(Locale.ROOT, "%.1f", nearestAny) + "blk(" + nearestAnyKey + ")")
                : " nearest=" + String.format(Locale.ROOT, "%.1f", nearest)
                    + "blk(" + nearestKey + ")")
            + (words.isEmpty() ? "" : " otherCrops=" + words));
        // A zero result next to a visible crop is the bug report that matters: one line per
        // crop block says which signal is missing instead of "there's nothing ripe".
        for (String s : samples) log("Harvest: not-ripe " + s);
        return cells;
    }

    /** Public form of {@link #sample}: one line describing any cell, for diagnostics. */
    public static String describe(World world, int x, int y, int z) {
        BlockType type = typeAt(world, x, y, z);
        if (type == null) return "@" + x + "," + y + "," + z + " empty/unloaded";
        return sample(world, x, y, z, type, cropWord(type.getId()));
    }

    /** One diagnostic line: what the cell looks like to every ripeness signal. */
    private static String sample(World world, int x, int y, int z, BlockType type, String word) {
        BlockGathering gathering = type.getGathering();
        FarmingBlock farm = farmAt(world, x, y, z);
        return "@" + x + "," + y + "," + z + " key=" + type.getId()
            + " crop=" + (word.isEmpty() ? "?" : word)
            + " gathering=" + (gathering != null)
            + " harvest=" + (gathering != null && gathering.getHarvest() != null)
            + " farming=" + (type.getFarming() != null)
            + " stageSetAfterHarvest=" + (type.getFarming() == null ? "?"
                : String.valueOf(type.getFarming().getStageSetAfterHarvest()))
            + " stageSet=" + (farm == null ? "none" : farm.getCurrentStageSet())
            + " executions=" + (farm == null ? "?" : String.valueOf(farm.getExecutions()))
            + " growth=" + (farm == null ? "?" : String.format(Locale.ROOT, "%.2f",
                farm.getGrowthProgress()));
    }

    private static double sqDist(int x, int y, int z, double ox, double oy, double oz) {
        double dx = (x + 0.5) - ox;
        double dy = (y + 0.5) - oy;
        double dz = (z + 0.5) - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double sqDist(int[] cell, double x, double y, double z) {
        double dx = (cell[0] + 0.5) - x;
        double dy = (cell[1] + 0.5) - y;
        double dz = (cell[2] + 0.5) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void log(String message) {
        LayaPlugin.log(message);
    }
}
