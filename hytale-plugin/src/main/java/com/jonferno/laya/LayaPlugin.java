package com.jonferno.laya;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.jonferno.laya.bridge.Bridge;
import com.jonferno.laya.commands.LayaCommand;
import com.jonferno.laya.events.ChatListener;
import com.jonferno.laya.hud.LayaHudController;
import com.jonferno.laya.npc.NPCController;

public class LayaPlugin extends JavaPlugin {

    private static LayaPlugin instance;

    private final Bridge bridge;
    private final NPCController npcController;
    private final LayaHudController hudController;

    public LayaPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
        this.bridge = new Bridge();
        this.npcController = new NPCController(this);
        this.hudController = new LayaHudController(this);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new LayaCommand("Laya", "Talk to Laya, your AI companion"));

        getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent.class,
            event -> ChatListener.handle(event, this)
        );

        // Cycle 5: default interact key on Laya -> her live inventory window.
        // PlayerInteractEvent is deprecated in 0.7.2 but still fired for entity
        // Use; the companion reference plugin uses this same hook.
        getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent.class,
            event -> npcController.handlePlayerInteract(event)
        );

        // Cycle 6: the entity interact prompt/Use is not reliable for a
        // dynamically spawned NPC, so also listen to the raw right-click and
        // open on proximity+facing (same fallback the reference plugin uses).
        getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent.class,
            event -> npcController.handlePlayerMouseButton(event)
        );

        // M1: capture world on join + auto-spawn Laya as a real NPC_Elf
        getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent.class,
            event -> npcController.captureWorld(event.getWorld())
        );
        getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
            event -> {
                var ref = event.getPlayerRef();
                if (ref != null) {
                    var store = ref.getStore();
                    var playerRef = store.getComponent(
                        ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (playerRef != null) {
                        npcController.ensureSpawned(playerRef);
                        // Task 2: show the companion card for the owning player.
                        var player = store.getComponent(
                            ref, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                        if (player != null) {
                            hudController.queueShow(playerRef, player);
                        }
                    }
                }
            }
        );

        getEventRegistry().registerGlobal(
            ShutdownEvent.class,
            event -> stopBridge()
        );

        // Task 5: the single entry point combat goes through. An entity damage filter in
        // DamageModule's filter group, so it runs after the engine's gather/validation
        // passes and can still cancel the event (friendly fire, environment immunity,
        // lethal -> downed) or stamp a reactive target. Registered here in setup(),
        // after the controllers exist and before the world can tick.
        getEntityStoreRegistry().registerSystem(
            new com.jonferno.laya.npc.LayaDamageFilter(
                com.hypixel.hytale.server.core.modules.entity.damage.DamageModule.get().getFilterDamageGroup(),
                npcController.getCombat()));

        bridge.start();
        npcController.start();

        log("Laya plugin loaded. Nervous system bridge pending.");
    }

    public static void stopBridge() {
        if (instance != null) {
            instance.bridge.stop();
            instance.npcController.stop();
            instance.hudController.hide();
        }
    }

    public static void log(String message) {
        String time = java.time.LocalTime.now().toString();
        String line = "[" + time + "] [Laya] " + message;
        System.out.println(line);
        try {
            java.util.Map<String, String> env = System.getenv();
            String logPath = env.getOrDefault("LAYA_LOG", "D:/Games/Hytale/UserData/Logs/laya-plugin.log");
            java.nio.file.Path path = java.nio.file.Paths.get(logPath); java.nio.file.Files.createDirectories(path.getParent());
            String entry = line + System.lineSeparator();
            if (java.nio.file.Files.exists(path)) {
                java.nio.file.Files.write(path, entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            } else {
                java.nio.file.Files.write(path, entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE);
            }
        } catch (Exception ignored) {
        }
    }

    public static LayaPlugin get() {
        return instance;
    }

    public Bridge getBridge() {
        return bridge;
    }

    public NPCController getNPCController() {
        return npcController;
    }

    public void sendPlayerMessage(String text) {
        if (npcController != null) {
            npcController.sayToPlayer(text);
        } else {
            log("Laya says: " + text);
        }
    }
}


