package com.jonferno.laya.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jonferno.laya.LayaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cycle 12: Laya's appearance, built on the engine's own character-creator
 * cosmetic registry.
 *
 * <p>Every skin field is a {@code assetId.textureId[.variantId]} string
 * ({@link com.hypixel.hytale.server.core.cosmetics.PlayerSkin.PlayerSkinPartId#fromString}).
 * The editor page gets the valid parts/colours from {@link #optionsJson}, applies
 * one skin at a time, and the plugin persists it in {@code config/laya-skin.json}.
 *
 * <p>Live rendering: the client receives a {@code PlayerSkinUpdate} for any entity
 * carrying {@link PlayerSkinComponent} (EntityTrackerSystems$EntitySkin), and a
 * rebuilt {@link ModelComponent} from {@link CosmeticsModule#createModel(PlayerSkin)}
 * keeps the server-side model in step.
 */
public final class Appearance {

    /** The 20 protocol fields, in character-creator order. */
    public static final String[] FIELDS = {
        "bodyCharacteristic", "underwear", "face", "eyes", "ears", "mouth",
        "facialHair", "haircut", "eyebrows", "pants", "overpants", "undertop",
        "overtop", "shoes", "headAccessory", "faceAccessory", "earAccessory",
        "skinFeature", "gloves", "cape"
    };

    private Appearance() {
    }

    /** Config file; env override keeps parity with the other plugin config paths. */
    public static Path skinPath() {
        return Paths.get(System.getenv().getOrDefault(
            "LAYA_SKIN_JSON", "D:/VibeCoding/LayaHytale/config/laya-skin.json"));
    }

    // --- build / validate -------------------------------------------------

    public static PlayerSkin fromValues(Map<String, String> values) {
        PlayerSkin skin = new PlayerSkin();
        for (String f : FIELDS) {
            String v = values == null ? null : values.get(f);
            set(skin, f, (v == null || v.isBlank()) ? null : v.trim());
        }
        return skin;
    }

    public static PlayerSkin fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            Map<String, String> values = new LinkedHashMap<>();
            for (String f : FIELDS) {
                JsonElement e = o.get(f);
                if (e != null && !e.isJsonNull()) values.put(f, e.getAsString());
            }
            return fromValues(values);
        } catch (Throwable t) {
            LayaPlugin.log("Appearance: could not parse saved skin: " + t.getMessage());
            return null;
        }
    }

    /** Validate against the registry; null when the skin is usable. */
    public static String validate(PlayerSkin skin) {
        try {
            CosmeticsModule.get().validateSkin(skin);
            return null;
        } catch (Throwable t) {
            String msg = t.getMessage();
            return (msg == null || msg.isBlank()) ? t.toString() : msg;
        }
    }

    // --- apply ------------------------------------------------------------

    /**
     * World thread only. Rebuilds the model from the skin (server side + network)
     * and pushes the skin component so the client re-renders her live.
     */
    public static void apply(Store<EntityStore> store, Ref<EntityStore> ref, PlayerSkin skin) {
        if (store == null || ref == null || skin == null) return;
        try {
            Model model = CosmeticsModule.get().createModel(skin);
            if (model != null) {
                store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(model));
            }
        } catch (Throwable t) {
            LayaPlugin.log("Appearance: model rebuild failed: " + t.getMessage());
        }
        try {
            PlayerSkinComponent comp = new PlayerSkinComponent(skin);
            store.putComponent(ref, PlayerSkinComponent.getComponentType(), comp);
            comp.setNetworkOutdated();
        } catch (Throwable t) {
            LayaPlugin.log("Appearance: skin component failed: " + t.getMessage());
        }
    }

    // --- persistence ------------------------------------------------------

    public static void save(PlayerSkin skin) {
        try {
            Path p = skinPath();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, toJson(skin).toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LayaPlugin.log("Appearance: could not save skin: " + t.getMessage());
        }
    }

    public static PlayerSkin load() {
        try {
            Path p = skinPath();
            if (!Files.exists(p)) return null;
            return fromJson(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LayaPlugin.log("Appearance: could not load skin: " + t.getMessage());
            return null;
        }
    }

    public static JsonObject toJson(PlayerSkin skin) {
        JsonObject o = new JsonObject();
        for (String f : FIELDS) {
            String v = get(skin, f);
            if (v == null) o.add(f, com.google.gson.JsonNull.INSTANCE);
            else o.addProperty(f, v);
        }
        return o;
    }

    // --- editor data ------------------------------------------------------

    /**
     * Everything the appearance page needs: the current skin plus, for every
     * category, the valid parts and their colour/variant choices.
     */
    public static String optionsJson(PlayerSkin current) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("current", current == null ? new JsonObject() : toJson(current));
        JsonArray categories = new JsonArray();
        try {
            CosmeticRegistry r = CosmeticsModule.get().getRegistry();
            addCategory(categories, "bodyCharacteristic", "Body", false, r.getBodyCharacteristics(), r);
            addCategory(categories, "underwear", "Underwear", false, r.getUnderwear(), r);
            addCategory(categories, "face", "Face", false, r.getFaces(), r, false);
            addCategory(categories, "eyes", "Eyes", false, r.getEyes(), r);
            addCategory(categories, "ears", "Ears", false, r.getEars(), r, false);
            addCategory(categories, "mouth", "Mouth", false, r.getMouths(), r, false);
            addCategory(categories, "facialHair", "Facial hair", true, r.getFacialHairs(), r);
            addCategory(categories, "haircut", "Haircut", false, r.getHaircuts(), r);
            addCategory(categories, "eyebrows", "Eyebrows", true, r.getEyebrows(), r);
            addCategory(categories, "pants", "Pants", true, r.getPants(), r);
            addCategory(categories, "overpants", "Overpants", true, r.getOverpants(), r);
            addCategory(categories, "undertop", "Undertop", true, r.getUndertops(), r);
            addCategory(categories, "overtop", "Overtop", true, r.getOvertops(), r);
            addCategory(categories, "shoes", "Shoes", true, r.getShoes(), r);
            addCategory(categories, "headAccessory", "Head accessory", true, r.getHeadAccessories(), r);
            addCategory(categories, "faceAccessory", "Face accessory", true, r.getFaceAccessories(), r);
            addCategory(categories, "earAccessory", "Ear accessory", true, r.getEarAccessories(), r);
            addCategory(categories, "skinFeature", "Skin feature", true, r.getSkinFeatures(), r);
            addCategory(categories, "gloves", "Gloves", true, r.getGloves(), r);
            addCategory(categories, "cape", "Cape", true, r.getCapes(), r);
            root.add("categories", categories);
        } catch (Throwable t) {
            root.addProperty("ok", false);
            root.addProperty("error", "Cosmetics registry unavailable: " + t.getMessage());
            LayaPlugin.log("Appearance: options failed: " + t);
        }
        return root.toString();
    }

    private static void addCategory(JsonArray out, String key, String label, boolean optional,
                                    Map<String, PlayerSkinPart> parts, CosmeticRegistry registry) {
        addCategory(out, key, label, optional, parts, registry, true);
    }

    /**
     * @param withTexture false for face/ears/mouth: their field is validated as a whole
     *                    registry key, not a dot-separated attachment. Their greyscale
     *                    textures are tinted by the body skin tone instead.
     */
    private static void addCategory(JsonArray out, String key, String label, boolean optional,
                                    Map<String, PlayerSkinPart> parts, CosmeticRegistry registry,
                                    boolean withTexture) {
        if (parts == null || parts.isEmpty()) return;
        JsonObject cat = new JsonObject();
        cat.addProperty("key", key);
        cat.addProperty("label", label);
        cat.addProperty("optional", optional);
        JsonArray list = new JsonArray();
        List<String> ids = new ArrayList<>(parts.keySet());
        ids.sort(String::compareToIgnoreCase);
        for (String id : ids) {
            PlayerSkinPart part = parts.get(id);
            if (part == null) continue;
            JsonObject p = new JsonObject();
            p.addProperty("id", part.getId());
            // getName() is a LOCALIZATION KEY ("avatarCustomization.haircuts.Afro.name"),
            // never the label. Resolve it to a display name; the page always has `id`
            // as a fallback, so a raw key must never reach the dropdown.
            p.addProperty("name", displayName(part));

            JsonArray gradients = new JsonArray();
            String gradientSet = withTexture ? part.getGradientSet() : null;
            if (gradientSet != null) {
                try {
                    var gs = registry.getGradientSets().get(gradientSet);
                    if (gs != null && gs.getGradients() != null) {
                        for (String g : gs.getGradients().keySet()) gradients.add(g);
                    }
                } catch (Throwable ignored) {
                }
            }
            p.add("gradients", gradients);

            // Gradient-tinted parts (hair, face, eyes, ears, mouth, body) carry no
            // named textures - only a single greyscale texture tinted by the chosen
            // gradient. Emit it so the page can show a real preview for them too.
            if (withTexture) {
                if (gradientSet != null) p.addProperty("gradientSet", gradientSet);
                String grey = safe(() -> part.getGreyscaleTexture());
                if (grey != null && !grey.isBlank()) {
                    p.addProperty("greyscale", toCommonAssetPath(grey));
                }
            }

            JsonArray textures = new JsonArray();
            if (withTexture && part.getTextures() != null) {
                for (var e : part.getTextures().entrySet()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("id", e.getKey());
                    t.addProperty("name", prettify(e.getKey()));
                    PlayerSkinPartTexture tex = e.getValue();
                    if (tex != null) {
                        String tp = tex.getTexture();
                        if (tp != null && !tp.isBlank()) t.addProperty("path", toCommonAssetPath(tp));
                        String[] bc = tex.getBaseColor();
                        if (bc != null && bc.length > 0 && bc[0] != null) t.addProperty("color", bc[0]);
                    }
                    textures.add(t);
                }
            }
            p.add("textures", textures);

            JsonObject variants = new JsonObject();
            if (withTexture && part.getVariants() != null) {
                for (var e : part.getVariants().entrySet()) {
                    JsonArray vt = new JsonArray();
                    if (e.getValue() != null && e.getValue().getTextures() != null) {
                        for (String t : e.getValue().getTextures().keySet()) vt.add(t);
                    }
                    variants.add(e.getKey(), vt);
                }
            }
            p.add("variants", variants);
            list.add(p);
        }
        cat.add("parts", list);
        out.add(cat);
    }

    /** GenericLong -> "Generic Long"; Default_48 -> "Default 48". */
    private static String prettify(String id) {
        if (id == null) return "";
        String s = id.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return s.trim();
    }

    /**
     * A display name for a part. {@link PlayerSkinPart#getName()} is a localization
     * KEY (e.g. "avatarCustomization.haircuts.Afro.name"), so a raw key must never be
     * used as a label. Try the game's i18n module by reflection (no hard dependency on
     * Hytale:I18nModule), then fall back to the last dotted segment prettified.
     */
    private static String displayName(PlayerSkinPart part) {
        String raw = safe(() -> part.getName());
        if (raw != null && !raw.isBlank()
                && !raw.startsWith("avatarCustomization") && !raw.contains(".")) {
            return raw;   // already a plain label
        }
        if (raw != null && raw.contains(".")) {
            String localized = resolveI18n(raw);
            if (localized != null && !localized.isBlank() && !localized.equals(raw)
                    && !localized.startsWith("avatarCustomization")) {
                return localized;
            }
            // Fall back to the key's own trailing id segment: ...haircuts.Afro.name -> Afro
            String key = raw.endsWith(".name") ? raw.substring(0, raw.length() - 5) : raw;
            int dot = key.lastIndexOf('.');
            String leaf = (dot >= 0 && dot + 1 < key.length()) ? key.substring(dot + 1) : key;
            return prettify(leaf);
        }
        return prettify(safe(() -> part.getId()));
    }

    /**
     * Resolve a localization key via the i18n module, by reflection so the plugin does
     * not need Hytale:I18nModule in its manifest Dependencies. Best effort: any failure
     * returns null and the caller falls back to the prettified id.
     */
    private static String resolveI18n(String key) {
        try {
            Class<?> cls = Class.forName("com.hypixel.hytale.server.core.modules.i18n.I18nModule");
            Object module = cls.getMethod("get").invoke(null);
            if (module == null) return null;
            String lang = (String) cls.getField("DEFAULT_LANGUAGE").get(null);
            Object msg = cls.getMethod("getMessage", String.class, String.class)
                .invoke(module, lang, key);
            return msg instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The registry returns model/texture paths relative to the asset root
     * ("Characters/Haircuts/Afro.png", "Cosmetics/Shoes/..."); the packed assets live
     * under "Common/". Normalise to a Common/... path the /texture endpoint can serve.
     */
    private static String toCommonAssetPath(String p) {
        if (p == null || p.isBlank()) return p;
        String s = p.replace('\\', '/').trim();
        if (s.startsWith("Common/")) return s;
        return "Common/" + s;
    }

    /** Run a supplier that may throw, returning null on any failure. */
    private static <T> T safe(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String summary(PlayerSkin s) {
        if (s == null) return "(none)";
        return "body=" + s.bodyCharacteristic
            + " haircut=" + s.haircut
            + " overtop=" + s.overtop
            + " pants=" + s.pants
            + " shoes=" + s.shoes;
    }

    // --- field plumbing ---------------------------------------------------

    private static String get(PlayerSkin s, String f) {
        if (s == null) return null;
        return switch (f) {
            case "bodyCharacteristic" -> s.bodyCharacteristic;
            case "underwear" -> s.underwear;
            case "face" -> s.face;
            case "eyes" -> s.eyes;
            case "ears" -> s.ears;
            case "mouth" -> s.mouth;
            case "facialHair" -> s.facialHair;
            case "haircut" -> s.haircut;
            case "eyebrows" -> s.eyebrows;
            case "pants" -> s.pants;
            case "overpants" -> s.overpants;
            case "undertop" -> s.undertop;
            case "overtop" -> s.overtop;
            case "shoes" -> s.shoes;
            case "headAccessory" -> s.headAccessory;
            case "faceAccessory" -> s.faceAccessory;
            case "earAccessory" -> s.earAccessory;
            case "skinFeature" -> s.skinFeature;
            case "gloves" -> s.gloves;
            case "cape" -> s.cape;
            default -> null;
        };
    }

    private static void set(PlayerSkin s, String f, String v) {
        switch (f) {
            case "bodyCharacteristic" -> s.bodyCharacteristic = v;
            case "underwear" -> s.underwear = v;
            case "face" -> s.face = v;
            case "eyes" -> s.eyes = v;
            case "ears" -> s.ears = v;
            case "mouth" -> s.mouth = v;
            case "facialHair" -> s.facialHair = v;
            case "haircut" -> s.haircut = v;
            case "eyebrows" -> s.eyebrows = v;
            case "pants" -> s.pants = v;
            case "overpants" -> s.overpants = v;
            case "undertop" -> s.undertop = v;
            case "overtop" -> s.overtop = v;
            case "shoes" -> s.shoes = v;
            case "headAccessory" -> s.headAccessory = v;
            case "faceAccessory" -> s.faceAccessory = v;
            case "earAccessory" -> s.earAccessory = v;
            case "skinFeature" -> s.skinFeature = v;
            case "gloves" -> s.gloves = v;
            case "cape" -> s.cape = v;
            default -> {
            }
        }
    }
}
