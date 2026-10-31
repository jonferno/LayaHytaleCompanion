package com.jonferno.laya.hud;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jonferno.laya.npc.CombatController;
import com.jonferno.laya.npc.NPCController;

/**
 * Laya's all-in-one interact menu: pack and equipment plus the command bar.
 *
 * <p>0.4.4: no tabs. Everything is on the one page; the bottom row is a strip of
 * dark square icon buttons (Follow, Stay, Attack, Defend, Ranged, Magic) that each
 * act on click, with hover and pressed states. Future commands (farm, mine, chop,
 * gather) become more boxes in the same strip.
 *
 * <p>Inventory: 36 pack slots on the left, five equipment slots on the right. Click a
 * slot to select it, then click a target slot to move/swap. Auto-equip still runs for
 * gifted gear.
 */
public final class LayaInventoryPage extends InteractiveCustomUIPage<LayaInventoryPage.PageEventData> {

    private final NPCController controller;
    private int selected = -1;

    public LayaInventoryPage(PlayerRef playerRef, NPCController controller) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageEventData.CODEC);
        this.controller = controller;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder b, UIEventBuilder events,
                      Store<EntityStore> store) {
        b.append("LayaInventoryPage.ui");
        // Player-facing: use the configured companion name on the page title and
        // her pack column (spec: the name everywhere the player sees it).
        String cname = controller.getCompanionName();
        if (cname == null || cname.isBlank()) cname = "Laya";
        b.set("#Title.Text", cname + "  |  Companion");
        b.set("#LayaLabel.Text", cname + "'s Pack");

        for (int i = 0; i < 45; i++) { // player
            setSlot(b, "#Player" + i + "Icon", controller.uiItemId(100 + i),
                controller.uiQuantity(100 + i));
            bind(events, "#Player" + i, 100 + i);
        }
        for (int i = 0; i < 43; i++) {
            // 0.4.4: ui index 0 is the dedicated weapon slot in the equipped column,
            // so the pack grid (36 storage + 7 hotbar) starts at 1.
            setSlot(b, "#Pack" + i + "Icon", controller.uiItemId(i + 1),
                controller.uiQuantity(i + 1));
            bind(events, "#Pack" + i, i + 1);
        }
        for (int i = 0; i < 5; i++) {
            setSlot(b, "#Gear" + i + "Icon", controller.uiItemId(44 + i),
                controller.uiQuantity(44 + i));
            bind(events, "#Gear" + i, 44 + i);
        }
        setSlot(b, "#GearWeaponIcon", controller.uiItemId(0), controller.uiQuantity(0));
        bind(events, "#GearWeapon", 0);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SortButton",
            EventData.of("Action", "sort"));
        b.set("#Selection.Text", selected < 0
            ? "Click an item, then click a target slot."
            : "Selected slot " + selected + " - now click a target.");

        // Command bar: stance and weapon-family buttons.
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnFollow",
            EventData.of("Action", "stance").append("Value", "follow"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnStay",
            EventData.of("Action", "stance").append("Value", "stay"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnAttack",
            EventData.of("Action", "stance").append("Value", "attack"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnDefend",
            EventData.of("Action", "stance").append("Value", "defend"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnRanged",
            EventData.of("Action", "mode").append("Value", "ranged"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BtnMagic",
            EventData.of("Action", "mode").append("Value", "magic"));

        b.set("#CommandStatus.Text", statusLine());
    }

    /** The one-line readout under the buttons. */
    private String statusLine() {
        CombatController c = controller.getCombat();
        return "Stance: " + c.getStance()
            + "     Mode: " + c.getCombatMode();
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        if (data == null) return;
        if ("stance".equals(data.action)) {
            controller.applyStanceButton(data.value);
            rebuild();
            return;
        }
        if ("mode".equals(data.action)) {
            controller.applyModeButton(data.value);
            rebuild();
            return;
        }
        if ("sort".equals(data.action)) {
            controller.stackAndSortLaya();
            selected = -1;
            rebuild();
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(data.slot);
        } catch (Exception e) {
            return;
        }
        if (!((idx >= 0 && idx <= 48) || (idx >= 100 && idx <= 144))) return;
        if ("quick".equals(data.action)) {
            controller.quickTransfer(idx);
            selected = -1;
            rebuild();
            return;
        }
        if (selected < 0) {
            selected = idx;
        } else {
            if (selected != idx) controller.moveUiItem(selected, idx);
            selected = -1;
        }
        rebuild();
    }

    /** What the current stance does in each mode, for the status tooltip later. */
    static String abilityFor(String mode, String stance) {
        if ("ranged".equals(mode)) {
            return "Volley, every 1.8 s up to 25 blocks (bow, crossbow, gun)";
        }
        if ("magic".equals(mode)) {
            return "Magic Orb, 25 mana, every 2.4 s up to 20 blocks (staff, wand)";
        }
        if ("attack".equals(stance)) return "Power Strike, x1.9 damage, 4.5 s cooldown";
        if ("defend".equals(stance)) return "Shield Bash, x1.35 damage, 4.5 s cooldown";
        if ("stay".equals(stance)) return "Guard Counter, x1.6 on the first swing after a hit";
        return "none while following";
    }

    private static void bind(UIEventBuilder events, String element, int slot) {
        events.addEventBinding(CustomUIEventBindingType.Activating, element,
            EventData.of("Action", "select").append("Slot", Integer.toString(slot)));
        events.addEventBinding(CustomUIEventBindingType.DoubleClicking, element,
            EventData.of("Action", "quick").append("Slot", Integer.toString(slot)));
        events.addEventBinding(CustomUIEventBindingType.RightClicking, element,
            EventData.of("Action", "quick").append("Slot", Integer.toString(slot)));
    }

    private static void setSlot(UICommandBuilder b, String path, String id, int quantity) {
        boolean present = id != null && !id.isEmpty() && !"null".equals(id) && quantity > 0;
        b.set(path + ".Visible", present);
        b.set(path + ".ItemId", present ? id : "");
        b.set(path + ".Quantity", present ? quantity : 0);
    }

    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .append(new KeyedCodec<>("Slot", Codec.STRING),
                (d, v) -> d.slot = v, d -> d.slot).add()
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Value", Codec.STRING),
                (d, v) -> d.value = v, d -> d.value).add()
            .build();
        public String slot;
        public String action;
        public String value;
    }
}

