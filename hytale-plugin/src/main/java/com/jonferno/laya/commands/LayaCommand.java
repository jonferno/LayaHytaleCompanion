package com.jonferno.laya.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.jonferno.laya.LayaPlugin;

import java.util.concurrent.CompletableFuture;

public class LayaCommand extends AbstractCommand {

    public LayaCommand(String name, String description) {
        super(name, description);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        LayaPlugin.log("Command /Laya invoked by " + context.sender().getUsername());
        context.sendMessage(Message.raw("Laya: appearance editor -> http://127.0.0.1:8788/appearance"));
        context.sendMessage(Message.raw("Say \"stay here\" to hold her, \"follow me\" to bring her along."));
        return CompletableFuture.completedFuture(null);
    }
}


