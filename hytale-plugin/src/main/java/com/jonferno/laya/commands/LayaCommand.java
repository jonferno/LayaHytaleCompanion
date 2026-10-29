package com.jonferno.laya.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.jonferno.laya.LayaPlugin;

import java.util.concurrent.CompletableFuture;

public class LayaCommand extends AbstractCommand {

    public LayaCommand(String name, String description) {
        super(name, description);
        // Player command: no permission node. Without this the engine's setOwner gate
        // has no registered node and no openToEveryone flag, so it silently assigns a
        // "laya" permission nobody holds and the command is rejected ("no permission").
        requireNoPermission();
        // Task 2: /laya reset memory [confirm]. The controller is resolved through the
        // plugin at execution time (as the companion-name work does), so registration
        // here stays independent of plugin construction order.
        addSubCommand(new ResetMemoryCommand(controllerOrNull()));
        // Task 3: /laya hud on|off. Same late resolution as reset.
        addSubCommand(new HudCommand(controllerOrNull()));
        // 0.4.4: /laya combat opens the mode panel.
        addSubCommand(new CombatCommand(controllerOrNull()));
    }

    /** The live controller, or null when the plugin is not up yet (callers must handle null). */
    private static com.jonferno.laya.npc.NPCController controllerOrNull() {
        LayaPlugin plugin = LayaPlugin.get();
        return plugin == null ? null : plugin.getNPCController();
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        LayaPlugin.log("Command /Laya invoked by " + context.sender().getUsername());
        // Player-facing: use the configured companion name (spec: the name everywhere the player sees it).
        // Logs stay literal; only this line is renamed. Fall back to "Laya" so it can never print "null: ".
        String companionName = "Laya";
        LayaPlugin plugin = LayaPlugin.get();
        if (plugin != null && plugin.getNPCController() != null) {
            String configured = plugin.getNPCController().getCompanionName();
            if (configured != null && !configured.isBlank()) {
                companionName = configured;
            }
        }
        context.sendMessage(Message.raw(companionName + ": appearance editor -> http://127.0.0.1:8788/appearance"));
        context.sendMessage(Message.raw("Say \"stay here\" to hold her, \"follow me\" to bring her along."));
        context.sendMessage(Message.raw("Commands: /laya hud on|off  |  /laya combat (companion menu)  |  /laya reset memory confirm"));
        return CompletableFuture.completedFuture(null);
    }
}


