package com.jonferno.laya.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.hud.LayaInventoryPage;
import com.jonferno.laya.npc.NPCController;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /laya combat}: open the all-in-one companion menu on its combat tab.
 *
 * <p>0.4.4: the separate combat window was merged into {@link LayaInventoryPage}. This
 * command is the same page with the tab preselected, so right-click and the command
 * never show two different menus.
 *
 * <p>Same world-thread rules as {@link HudCommand}: the store reads and the page open
 * must run inside {@code runOnWorld}, because command execution is not on the world
 * thread.
 */
public class CombatCommand extends AbstractCommand {

    private final NPCController controller;

    public CombatCommand(NPCController controller) {
        super("combat", "Open the combat mode panel");
        requireNoPermission();
        this.controller = controller;
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        LayaPlugin.log("Command /Laya combat invoked by " + context.sender().getUsername());
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Run this in game."));
            return CompletableFuture.completedFuture(null);
        }
        Ref<EntityStore> senderRef = context.senderAsPlayerRef();
        if (senderRef == null || controller == null) {
            context.sendMessage(Message.raw("Laya: the companion is not ready yet."));
            return CompletableFuture.completedFuture(null);
        }
        controller.runOnWorld(() -> {
            try {
                Store<EntityStore> store = senderRef.getStore();
                PlayerRef playerRef = store.getComponent(senderRef, PlayerRef.getComponentType());
                Player player = store.getComponent(senderRef, Player.getComponentType());
                if (playerRef == null || player == null) {
                    context.sendMessage(Message.raw("Laya: couldn't open the combat panel."));
                    return;
                }
                player.getPageManager().openCustomPage(
                    senderRef, store, new LayaInventoryPage(playerRef, controller));
            } catch (Throwable t) {
                LayaPlugin.log("Command /Laya combat failed: " + t);
                try {
                    context.sendMessage(Message.raw("Laya: couldn't open the combat panel."));
                } catch (Throwable ignored) {
                }
            }
        });
        return CompletableFuture.completedFuture(null);
    }
}
