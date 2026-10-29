package com.jonferno.laya;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.jonferno.laya.bridge.Bridge;
import com.jonferno.laya.commands.LayaCommand;
import com.jonferno.laya.events.ChatListener;
import com.jonferno.laya.hud.LayaHudController;
import com.jonferno.laya.npc.NPCController;
import com.jonferno.laya.world.CompanionMapMarker;

public class LayaPlugin extends JavaPlugin {

    private static LayaPlugin instance;

    private final Bridge bridge;
    private final NPCController npcController;
    private final LayaHudController hudController;
    private final CompanionMapMarker mapMarker;

    public LayaPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
        this.bridge = new Bridge();
        this.npcController = new NPCController(this);
        this.hudController = new LayaHudController(this);
        this.mapMarker = new CompanionMapMarker(this.npcController);
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
            event -> {
                npcController.captureWorld(event.getWorld());
                // 0.4.4: registering again for the same world is a no-op replace;
                // this covers a world created before the plugin started watching.
                addMapMarkerProvider(event.getWorld());
            }
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
                            // Task 3: gate the join show on the saved choice, or /laya hud off
                            // looks broken after a rejoin (the card would reappear).
                            if (npcController.isHudEnabled()) {
                                hudController.queueShow(playerRef, player);
                            }
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

        // 0.4.4: visible projectiles. A tick system is the only place with a
        // CommandBuffer, which ProjectileModule.spawnProjectile requires.
        getEntityStoreRegistry().registerSystem(
            new com.jonferno.laya.npc.ProjectileLaunchSystem());

        bridge.start();
        npcController.start();

        // 0.4.4: Laya on the world map, for every world from plugin start.
        registerMapMarkerProviders();

        log("Config: " + com.jonferno.laya.npc.NPCController.configPathsSummary());
        log("Laya plugin loaded. Nervous system bridge pending.");
    }

    /**
     * 0.4.4: register the map marker provider for every world, and for worlds
     * created later. The 0.4.3 path registered on her first follow tick and read
     * the entity store from the map thread inside a catch-all, so the marker never
     * appeared and nothing was logged. The provider now reads a snapshot instead.
     */
    private void registerMapMarkerProviders() {
        try {
            getEventRegistry().registerGlobal(
                AddWorldEvent.class,
                event -> addMapMarkerProvider(event.getWorld()));
            for (World world : Universe.get().getWorlds().values()) {
                addMapMarkerProvider(world);
            }
        } catch (Throwable t) {
            log("Map: provider registration failed: " + t);
        }
    }

    private void addMapMarkerProvider(World world) {
        if (world == null) return;
        try {
            world.getWorldMapManager().addMarkerProvider(
                CompanionMapMarker.PROVIDER_KEY, mapMarker);
            log("Map: companion marker provider added to world '" + world.getName() + "'");
        } catch (Throwable t) {
            log("Map: could not add marker provider to world '" + world.getName() + "': " + t);
        }
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

    /** The companion-card controller (Task 3: the /laya hud command drives it). */
    public LayaHudController getHudController() {
        return hudController;
    }

    public void sendPlayerMessage(String text) {
        if (npcController != null) {
            npcController.sayToPlayer(text);
        } else {
            log("Laya says: " + text);
        }
    }
}


