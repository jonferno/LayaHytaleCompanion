package com.jonferno.laya.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.npc.NPCController;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /laya reset memory [confirm]} — two-step memory reset for the companion.
 *
 * <p>Bare invocation is a DRY RUN: the bridge counts what it would drop and
 * mutates nothing. Only the explicit {@code confirm} argument clears anything.
 * Every failure (bridge down, non-2xx, {@code ok:false}) prints the honest line
 * and changes nothing — a dry run must never report as a clear, and vice versa.
 */
public class ResetMemoryCommand extends AbstractCommand {

    private final NPCController controller;

    private final RequiredArg<String> memoryArg =
        withRequiredArg("memory", "The memory store to reset (memory)", ArgTypes.STRING);

    public ResetMemoryCommand(NPCController controller) {
        super("reset", "Clear the companion's memory");
        // Player command: no permission node (see LayaCommand.requireNoPermission).
        // Each subcommand runs its own setOwner gate, so it must opt out explicitly.
        requireNoPermission();
        // The confirmation is a plain trailing token ("/laya reset memory confirm"),
        // not a named optional arg: this engine's optional args are flags matched by
        // name (--confirm), so a positional "confirm" would fail to parse and the
        // command body would never run. Allow the extra token and read the raw tail.
        setAllowsExtraArguments(true);
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
        LayaPlugin.log("Command /Laya reset invoked by " + context.sender().getUsername());

        String what = context.get(memoryArg);
        if (what == null || !what.equalsIgnoreCase("memory")) {
            context.sendMessage(Message.raw("Usage: /laya reset memory [confirm]"));
            return CompletableFuture.completedFuture(null);
        }

        // Review Focus #5: confirm=true ONLY when the command line carries the explicit
        // trailing "confirm" token. A bare invocation is always a dry run.
        // This engine's optional args are NAME-matched flags (--confirm), so a positional
        // "confirm" is not an arg value; read the raw command input instead.
        String raw = context.getInputString() == null ? "" : context.getInputString();
        LayaPlugin.log("Command /Laya reset raw input: '" + raw + "'");
        boolean confirm = java.util.regex.Pattern.compile("(?i)\\bconfirm\\b").matcher(raw).find();

        if (controller == null) {
            context.sendMessage(Message.raw(companionName()
                + ": Couldn't reach the assistant - memory was not cleared."));
            return CompletableFuture.completedFuture(null);
        }

        // Off-thread HTTP (the controller's future); we only print when it lands.
        return controller.resetMemory(confirm).handle((result, thrown) -> {
            String name = companionName();
            if (thrown != null || result == null || !result.ok) {
                context.sendMessage(Message.raw(name
                    + ": Couldn't reach the assistant - memory was not cleared."));
            } else if (result.dryRun) {
                context.sendMessage(Message.raw("This clears everything " + name + " remembers ("
                    + result.facts + " facts, " + result.turns + " turns). "
                    + "Run /laya reset memory confirm to do it."));
            } else {
                context.sendMessage(Message.raw(name + ": Memory cleared ("
                    + result.facts + " facts, " + result.turns + " turns)."));
            }
            return null;
        });
    }
}
