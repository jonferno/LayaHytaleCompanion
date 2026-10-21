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
import com.jonferno.laya.npc.NPCController;

/**
 * Laya's custom pack + equipment page.
 *
 * <p>The native container window cannot render arbitrary armor/utility sections
 * beside the pack, and a custom page suppresses it. This page renders both:
 * 36 pack slots on the left and five equipment slots on the right. Click a slot
 * to select it, then click a target slot to move/swap. Auto-equip still runs for
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
        for (int i = 0; i < 45; i++) { // player
            setSlot(b, "#Player" + i + "Icon", controller.uiItemId(100 + i),
                controller.uiQuantity(100 + i));
            bind(events, "#Player" + i, 100 + i);
        }
        for (int i = 0; i < 36; i++) {
            setSlot(b, "#Pack" + i + "Icon", controller.uiItemId(i), controller.uiQuantity(i));
            bind(events, "#Pack" + i, i);
        }
        for (int i = 0; i < 5; i++) {
            setSlot(b, "#Gear" + i + "Icon", controller.uiItemId(36 + i),
                controller.uiQuantity(36 + i));
            bind(events, "#Gear" + i, 36 + i);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SortButton",
            com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "sort"));
        b.set("#Selection.Text", selected < 0
            ? "Click an item, then click a target slot."
            : "Selected slot " + selected + " - now click a target.");
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
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
        if (!((idx >= 0 && idx <= 40) || (idx >= 100 && idx <= 144))) return;
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

    private static void bind(UIEventBuilder events, String element, int slot) {
        events.addEventBinding(CustomUIEventBindingType.Activating, element,
            com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "select")
                .append("Slot", Integer.toString(slot)));
        events.addEventBinding(CustomUIEventBindingType.DoubleClicking, element,
            com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "quick")
                .append("Slot", Integer.toString(slot)));
        events.addEventBinding(CustomUIEventBindingType.RightClicking, element,
            com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "quick")
                .append("Slot", Integer.toString(slot)));
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
            .build();
        public String slot;
        public String action;
    }
}
