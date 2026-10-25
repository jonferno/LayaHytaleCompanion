package com.jonferno.laya.world;

import java.util.HashMap;
import java.util.Map;

/**
 * SEED ITEM ID -> CROP BLOCK ID.
 *
 * <p>GENERATED FILE - do not hand-edit. Every {@code Plant_Seeds_*} asset under
 * {@code Server/Item/Items/Plant/Crop/} carries its own
 * {@code InteractionVars.SeedId[0].BlockTypeToPlace}, and that is the block the seed
 * plants. Regenerate with {@code python build_crops.py} after a game update.</p>
 *
 * <p>Seeds with no entry (e.g. {@code Plant_Seeds_Mushroom}, which inherits the
 * template placeholder) are not soil-plantable and must be refused out loud.</p>
 */
public final class FarmCrops {

    private static final Map<String, String> SEED_TO_BLOCK = new HashMap<>();

    static {
        SEED_TO_BLOCK.put("Plant_Seeds_Aubergine", "Plant_Crop_Aubergine_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Aubergine_Eternal", "Plant_Crop_Aubergine_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Carrot", "Plant_Crop_Carrot_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Carrot_Eternal", "Plant_Crop_Carrot_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Cauliflower", "Plant_Crop_Cauliflower_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Cauliflower_Eternal", "Plant_Crop_Cauliflower_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Chilli", "Plant_Crop_Chilli_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Chilli_Eternal", "Plant_Crop_Chilli_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Corn", "Plant_Crop_Corn_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Corn_Eternal", "Plant_Crop_Corn_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Cotton", "Plant_Crop_Cotton_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Cotton_Eternal", "Plant_Crop_Cotton_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Health1", "Plant_Crop_Health1_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Health2", "Plant_Crop_Health2_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Health3", "Plant_Crop_Health3_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Lettuce", "Plant_Crop_Lettuce_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Lettuce_Eternal", "Plant_Crop_Lettuce_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Mana1", "Plant_Crop_Mana1_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Mana2", "Plant_Crop_Mana2_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Mana3", "Plant_Crop_Mana3_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Onion", "Plant_Crop_Onion_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Onion_Eternal", "Plant_Crop_Onion_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Potato", "Plant_Crop_Potato_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Potato_Eternal", "Plant_Crop_Potato_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Pumpkin", "Plant_Crop_Pumpkin_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Pumpkin_Eternal", "Plant_Crop_Pumpkin_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Rice", "Plant_Crop_Rice_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Rice_Eternal", "Plant_Crop_Rice_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Stamina1", "Plant_Crop_Stamina1_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Stamina2", "Plant_Crop_Stamina2_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Stamina3", "Plant_Crop_Stamina3_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Test_Tree", "Plant_Test_Tree_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Tomato", "Plant_Crop_Tomato_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Tomato_Eternal", "Plant_Crop_Tomato_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Turnip", "Plant_Crop_Turnip_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Turnip_Eternal", "Plant_Crop_Turnip_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Wheat", "Plant_Crop_Wheat_Block");
        SEED_TO_BLOCK.put("Plant_Seeds_Wheat_Eternal", "Plant_Crop_Wheat_Block_Eternal");
        SEED_TO_BLOCK.put("Plant_Seeds_Wild", "Plant_Crop_Wild_Grass_Block");
    }

    private FarmCrops() {
    }

    /** Crop block id for a seed item, or null when the seed has none (not plantable). */
    public static String blockFor(String seedId) {
        return seedId == null ? null : SEED_TO_BLOCK.get(seedId);
    }
}
