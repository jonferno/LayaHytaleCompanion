package com.jonferno.laya.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.hud.LayaHudController;
import com.jonferno.laya.npc.NPCController;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /laya hud on|off} — show or hide the companion card, and remember the choice.
 *
 * <p>The choice is persisted (see {@link NPCController#setHudEnabled(boolean)}) so it
 * survives a relaunch: {@code LayaPlugin}'s player-ready gate reads it before queueing
 * the card. The card itself is only ever touched on the world thread.
 */
public class HudCommand extends AbstractCommand {

    /** The two states of the card. */
    public enum HudState { on, off }

    private final NPCController controller;

    private final RequiredArg<HudState> stateArg =
        withRequiredArg("state", "on to show the HUD, off to hide it",
            ArgTypes.forEnum("state", HudState.class));

    public HudCommand(NPCController controller) {
        super("hud", "Show or hide the companion HUD");
        // Player command: no permission node (see LayaCommand.requireNoPermission).
        // Each subcommand runs its own setOwner gate, so it must opt out explicitly.
        requireNoPermission();
        this.controller = controller;
    }

    /** Player-facing name, with the same "Laya" floor LayaCommand uses (never "null: "). */
    private String companionName() {
        String name = "Laya";
        if (controller != null) {
            String configured = controller.getCompanionName();
            if (configured != null && !configured.isBlank()) {
                name = configured;
            }
        }
        return name;
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        LayaPlugin.log("Command /Laya hud invoked by " + context.sender().getUsername());

        HudState state = context.get(stateArg);
        if (state == null) {
            context.sendMessage(Message.raw("Usage: /laya hud on|off"));
            return CompletableFuture.completedFuture(null);
        }

        // No console: the card belongs to a player entity, so we need a sender ref.
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Run this in game."));
            return CompletableFuture.completedFuture(null);
        }
        Ref<EntityStore> senderRef = context.senderAsPlayerRef();
        if (senderRef == null || controller == null) {
            context.sendMessage(Message.raw(companionName()
                + ": Couldn't reach the assistant - the HUD was not changed."));
            return CompletableFuture.completedFuture(null);
        }

        LayaPlugin plugin = LayaPlugin.get();
        LayaHudController hud = plugin == null ? null : plugin.getHudController();
        if (hud == null) {
            context.sendMessage(Message.raw(companionName()
                + ": Couldn't reach the assistant - the HUD was not changed."));
            return CompletableFuture.completedFuture(null);
        }

        // Store reads assert the world thread (Store.getComponent -> assertThread), and
        // command execute runs on a ForkJoinPool worker - so BOTH the component reads
        // and the HUD mutation must happen inside runOnWorld. Not doing so produced
        // "IllegalStateException: Assert not in thread!" and the command failed before
        // printing anything.
        boolean on = state == HudState.on;
        String name = companionName();
        controller.runOnWorld(() -> {
            try {
                Store<EntityStore> store = senderRef.getStore();
                PlayerRef playerRef = store.getComponent(senderRef, PlayerRef.getComponentType());
                Player player = store.getComponent(senderRef, Player.getComponentType());
                if (playerRef == null || player == null) {
                    context.sendMessage(Message.raw(name
                        + ": Couldn't reach the assistant - the HUD was not changed."));
                    return;
                }
                if (on) {
                    hud.show(playerRef, player);
                } else {
                    hud.hide(playerRef);
                }
            } catch (Throwable t) {
                LayaPlugin.log("Command /Laya hud apply failed: " + t);
                try {
                    context.sendMessage(Message.raw(name
                        + ": Couldn't reach the assistant - the HUD was not changed."));
                } catch (Throwable ignored) {
                }
            }
        });
        // Persist outside the HUD's own guard: the flag is the player's choice, not
        // the state change, so a repeat off still persists.
        controller.setHudEnabled(on);

        context.sendMessage(Message.raw(name + (on ? ": HUD shown." : ": HUD hidden.")));
        return CompletableFuture.completedFuture(null);
    }
}
