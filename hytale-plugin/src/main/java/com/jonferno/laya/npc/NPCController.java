package com.jonferno.laya.npc;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerCreativeSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.corecomponents.world.SensorReadPosition;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.instructions.Instruction;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.hud.LayaInventoryPage;
import com.jonferno.laya.world.BlockBreaker;
import com.jonferno.laya.world.BlockScanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.Pair;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Controls the Laya NPC entity in the Hytale world:
 *  - spawns a real NPC via NPCPlugin.spawnNPC (custom role Laya.json)
 *  - movement is ENGINE-DRIVEN (Phase 1, pattern after ForestHues study pack):
 *    Java computes only the follow POINT and writes it into the role's stored
 *    position slot ("FollowTargetPosition"); the Laya.json instruction
 *    ReadPosition -> Seek walks her there through the Walk motion controller,
 *    which is what produces the walk animation.
 *  - Java teleports are watchdogs ONLY: leash (>25 blocks), stuck recovery
 *    (~3 s no progress), F8/manual unstick, and spawn bootstrap.
 *  - Every engine API used here was verified against the local 0.7.2
 *    HytaleServer.jar (MarkedEntitySupport.get/WorldSupport.get are compile-time
 *    typed; only the protected storedPositions array write is reflective).
 *  - applies AI commands (follow / attack / stay / move_to) as stances
 *  - Task 7: "gift_hand" moves what the PLAYER currently holds into Laya's
 *    inventory (the drop-gift path already ships via the role's PickUpItem block)
 */
public class NPCController {

    // "Laya" = our custom role (Server/NPC/Roles/Laya.json, Player appearance, watches player, no wander)
    private static final String[] MODEL_FALLBACKS = {"Laya", "Kweebec_Sapling", "Feran_Civilian", "Klops_Gentleman"};
    private static final String PRIMARY_MODEL = System.getenv().getOrDefault("LAYA_MODEL", MODEL_FALLBACKS[0]);

    // --- Follow tuning (FOLLOW-TUNING.md adoption: start/stop buffers, leash, stuck) ---
    private static final String FOLLOW_SLOT = "FollowTargetPosition";  // must match Laya.json ReadPosition Slot
    private static final double FOLLOW_DISTANCE = 1.2;     // ring point sits on Laya's side of the player (effective stand-off ~2.7 = 1.2 + Seek stop 1.5)
    private static final double FOLLOW_START_BUFFER = 1.5; // recompute when player is this far beyond stand-off
    private static final double FOLLOW_STOP_BUFFER = 0.5;  // hysteresis so the written slot doesn't jitter
    private static final double LEASH_TELEPORT_DISTANCE = 25.0; // watchdog: teleport catch-up beyond this
    private static final long TICK_MS = 333;               // 3 slot writes/sec — Java decides *where*
    private static final long STUCK_MS = 3000;             // ~60 engine ticks with no progress -> watchdog
    private static final double STUCK_EPSILON = 0.1;       // movement below this counts as "no progress"
    private static final double STUCK_MIN_SLOT_DISTANCE = 2.0; // intent only beyond StopDistance(1.5)+margin — otherwise she's "at position"
    private static final double SLOT_REWRITE_MOVE = 0.25;  // ignore path refresh when slot moves less than this
    // --- Task (mine/gather) tuning ---
    private static final double TASK_ADJACENT = 1.6;   // matches the role's act-block ReadPosition Range
    private static final long TASK_IDLE_MS = 600_000;  // 10 min with no re-target AND no adjacency => abandoned
    private static final double RANGE_EPSILON = 1e-6;  // R-T4-1: tolerance when matching compiled sensor Range
    // Sentinel slot value equals the engine's "cleared" convention: sensor Range filter
    // can never match ±1e308, so ReadPosition goes idle and the role falls back to Nothing.
    static final Vector3d CLEARED_POSITION =
        new Vector3d(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);

    // --- Task actions (mine | gather | identify) ---
    private static final String BLOCKS_JSON = System.getenv().getOrDefault(
        "LAYA_BLOCKS_JSON", "config/blocks.json");
    // Cycle 6: her inventory survives sessions (the NPC entity itself does not).
    private static final String STATE_JSON = System.getenv().getOrDefault(
        "LAYA_STATE_JSON", "config/laya-inventory.json");
    private static final double IDENTIFY_MAX_DIST = 6.4;   // 16 ray steps x 0.4 blocks
    private static final double SCAN_RADIUS = 16.0;        // mine/gather search radius (near the player)

    // --- Task 5: break probe (dev verb "probe_break") ---
    // World-thread timed chain: immediate hit, then up to 6 hits 1500 ms apart.
    private static final double PROBE_SCAN_RADIUS = 3.0;    // "first scanned match within 3 blocks of HER"
    private static final int PROBE_MAX_HITS = 6;
    private static final long PROBE_HIT_INTERVAL_MS = 1500;
    private static final long PROBE_VERIFY_DELAY_MS = 300;  // queued chains tick after the call returns

    // --- Task 6: task executor (mine/gather) state machine ---
    // taskPhase: IDLE -> WALKING (task slot written) -> WORKING (adjacent, timed break
    // chain) -> DONE (block gone; settle so the role's pickup can run) -> IDLE (cleared).
    private static final long TASK_HIT_MS = 1_500;            // one break hit per 1.5 s (brief)
    private static final long TASK_REACH_TIMEOUT_MS = 60_000; // WALKING with no adjacency -> give up
    private static final long TASK_UNSTICK_MS = 8_000;       // WALKING no progress -> place beside block
    // Cycle 13: one placement was not enough when the trunk sits below a ledge - the
    // first placement could land in a canopy. Two placements, then a short grace
    // period, bounds the worst case to ~22 s instead of the 60 s reach timeout.
    private static final int TASK_MAX_UNSTICKS = 2;
    private static final long TASK_GIVE_UP_MS = 6_000;       // after the last placement -> fail
    // Cycle 13: wood is walked to, so the matched block must be near the player's
    // feet, and the block she swings at must be within a reach band of those feet -
    // farther than that the straight-line Seek wedges her and the unstick teleports
    // her out of the player's view. The whole connected tree still falls from
    // whichever trunk block she can actually reach.
    private static final double WOOD_SCAN_BAND = 4.0;
    private static final double MAX_WOOD_BASE_DY = 2.0;
    private static final long TASK_SETTLE_MS = 1_000;         // DONE: let the DroppedItem pickup grab drops
    private static final long TASK_SWING_MS = 450;            // wind-up: play "Mine" once, THEN break the block
    private static final int TASK_MAX_HITS = 12;              // bounded hit budget; honest failure after this
    private static final int TASK_MAX_DIGS = 6;               // Cycle 6: how far she may dig down toward a buried ore
    private static final String PICKAXE_ID_TOKEN = "Tool_Pickaxe"; // item-id substring proving a pickaxe
    // Cycle 8: chopping needs an axe — Hytale's hatchet, or a weapon axe.
    private static final String[] AXE_ID_TOKENS =
        {"Tool_Hatchet", "Tool_Axe", "Weapon_Axe", "Weapon_Battleaxe"};
    private static final Set<String> AXE_HINTS =
        Set.of("wood", "oak", "birch", "spruce", "palm", "ash", "aspen", "maple",
               "willow", "amber", "apple", "azure", "beech", "cedar", "elm",
               "pine", "redwood", "mahogany", "teak", "walnut", "cherry");
    private static final Set<String> WOOD_SPECIES = Set.of(
        "ash", "aspen", "maple", "willow", "amber", "apple", "azure", "birch",
        "oak", "spruce", "palm", "beech", "cedar", "elm", "pine", "redwood",
        "mahogany", "teak", "walnut", "cherry", "crimson", "frost", "jade");

    // blocks.json is loaded ONCE (lazily, first task) into these static maps:
    //   BLOCK_NAMES: engine asset key -> display name   ("Ore_Copper" -> "Copper ore")
    //   HINT_PATTERNS: hint word -> compiled pattern matched against engine asset keys
    //   HINT_LABELS: hint word -> human label ("Copper ore")
    private static final Map<String, String> BLOCK_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> HINT_PATTERNS = new ConcurrentHashMap<>();
    private static final Map<String, String> HINT_LABELS = new ConcurrentHashMap<>();
    private static volatile boolean blocksLoaded = false;

    // Plugin -> ai-bridge event channel. Same HttpClient rules as ChatListener:
    // HTTP/1.1 forced, Content-Type JSON, and we NEVER set Content-Length ourselves.
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();
    private static final String NPC_EVENT_URL = System.getenv().getOrDefault(
        "Laya_NPC_EVENT_URL", "http://127.0.0.1:8788/npc_event");

    final LayaPlugin plugin;
    private boolean running = false;

    // --- Task 3: combat (stance state, movement ownership, stay anchor) ---
    // Package-private so CombatController (same package) can drive the tick and the
    // engagement release; created with the controller, before the follow tick starts.
    final CombatController combat = new CombatController(this);

    // Legacy state map (kept for bridge command compatibility)
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    // M1: real NPC state
    private World world;
    Store<EntityStore> worldStore;
    PlayerRef playerRef;
    private boolean thinking = false;
    private boolean talking = false;
    private long nextFaceClearMs = 0;
    private String savedNameplate = null;
    private float savedHeadPitch = Float.NaN;
    Ref<EntityStore> layaRef;
    NPCEntity npcEntity;

    // Engine-follow binding (resolved once on the world thread right after spawn)
    private MarkedEntitySupport markedSupport;
    private WorldSupport worldSupport;
    private Field storedPositionsField;
    private Field instructionListField;
    private Field sensorSlotField;
    private Field sensorRangeField;   // R-T4-1: SensorReadPosition.range (double) — the slot discriminator
    private int followSlot = -1;
    private int taskSlot = -1;        // second position usage (mine/gather walking); -1 = unbound
    private int taskUnsticks = 0;     // cycle 10: watchdog placement used for this WALKING leg
    // Cycle 10b: a wood task is anchored to the first block it chopped. "Finish it"
    // then means "all wood within WOOD_ANCHOR_RADIUS of that tree", not every birch
    // the player happens to walk past.
    private boolean taskAnchorSet = false;
    private double taskAnchorX, taskAnchorY, taskAnchorZ;
    private static final double WOOD_ANCHOR_RADIUS = 4.5;
    private static final double WOOD_ANCHOR_LEAVE = 12.0;
    // Cycle 10c: "get all birch around" = every wood block within this circle of
    // the player (still re-centred each leg), as opposed to one anchored tree.
    private static final double WOOD_AREA_RADIUS = 12.0;
    private volatile String taskScope = "tree";   // "tree" | "area"
    private volatile int taskRadius = 0;          // requested clear radius (0 = default)
    private volatile boolean taskAreaSet = false; // area anchor captured on first scan
    private volatile double taskAreaX, taskAreaY, taskAreaZ;
    // Cycle 11: a tree is felled, not block-by-block. She takes TASK_SWING_COUNT
    // animation swings at the base, then the connected wood above is removed in
    // one go (real engine drops for the role's pickup).
    private static final int TASK_SWING_COUNT = 3;
    private static final long TASK_FELL_SWING_MS = 700;
    private static final int TREE_MAX_BLOCKS = 96;
    private static final int TREE_MAX_HEIGHT = 28;
    private static final int TREE_MAX_RADIUS = 6;
    private int taskSwingsLeft = 0;
    private long nextSwingMs = 0;
    private boolean taskFelled = false;
    private boolean slotReadbackLogged = false;
    private boolean taskReadbackLogged = false;   // distinct latch for "Task slot readback"
    // World-thread: one "Task active — leash deferred" line per defer episode
    // (re-armed by the follow-ring path / clearTask, never mid-episode).
    private boolean leashDeferredLogged = false;

    // Active-target bookkeeping (world-thread only): the stuck watchdog must
    // fire only when a real follow point is written — gating on the stance
    // flag alone caused the Phase-1 teleport loop when binding failed.
    boolean activeTarget = false;
    private int stuckResets = 0;
    private boolean stuckGaveUp = false;
    // Task interface: millis of the last task-driven slot write (0 = no task has run) — gates the idle shuffle.
    // volatile: written by applyCommand (bridge HTTP thread), read on the world thread.
    private volatile long lastTaskWriteMs = 0;

    // R-T3-1: task target ("x,y,z") stashed by the mine/gather task branch and
    // consumed on the world thread by Task 4's tick block. Nothing reads it yet.
    // volatile: written inside world.execute (world thread), read by Task 4's tick.
    private volatile String pendingTaskTarget = null;

    // Task 5 probe latch: true while a timed probe_break chain is scheduled/running.
    // compareAndSet from the bridge thread prevents two overlapping chains; reset by the
    // chain's terminal callback (block gone / budget exhausted / setup failure).
    private final AtomicBoolean probeChainActive = new AtomicBoolean(false);

    // Task ownership (Task 4): true while Laya stands within TASK_ADJACENT of the live
    // task point. Task 7 consumes it (isTaskAdjacent); world-thread writes, so volatile.
    private volatile boolean taskAdjacent = false;

    // --- Task 6: task executor state (fields only; WORLD-THREAD ONLY mutations) ---
    // taskPhase is one of "IDLE"|"WALKING"|"WORKING"|"DONE" (enum-free strings per brief).
    private String taskPhase = "IDLE";
    private long phaseSinceMs = 0;            // when the current phase was entered
    // Dedicated latch for the timed break chain — SEPARATE from the probe's
    // probeChainActive so a probe and a task can never wedge each other. One hit/verify
    // at a time; released by every terminal path (block gone / budget exhausted / abort).
    private final AtomicBoolean taskWorking = new AtomicBoolean(false);
    // Monotonic chain generation (I-1): bumped at EVERY WALKING entry. A chain captures
    // its generation at arm time; every callback drops unless the generation still
    // matches, so a command that arrives mid-chain can never let an OLD chain act at its
    // coordinates or mislabel a NEW task's task_done.
    private volatile long taskChainGen = 0;
    // PENDING task metadata handoff runScan -> tick consumption (world thread only).
    private String taskKind = null;           // "mine" | "gather" (chosen interaction + verb)
    private String taskLabel = null;          // display name from blocks.json (fallback: asset key)
    private String taskBlockKey = null;       // engine asset key of the target
    private int taskBlockId = 0;              // scanned block id (before-id seed)
    // CHAIN SNAPSHOT (I-2): frozen ONCE at WALKING entry, the same place taskChainGen is
    // bumped. The break chain reads ONLY these fields — never the pending fields above — so
    // a runScan that overwrites taskKind/taskLabel/taskBlockId mid-chain cannot
    // cross-contaminate an in-flight chain's interaction id / label / block id.
    private String taskSnapIid = null;        // interaction id (mine = Block_Damage, gather = destroy)
    private String taskSnapVerb = null;       // "mined" | "gathered"
    private String taskSnapLabel = null;      // display label for the task_done detail
    private String taskSnapKey = null;        // engine asset key for the task_done detail
    private int taskSnapBlockId = 0;          // before-id captured at WALKING entry
    private int taskSnapX, taskSnapY, taskSnapZ;   // target coordinates
    private int taskHitCount = 0;
    private int taskDigCount = 0;   // Cycle 6: downward digs spent on a buried target
    private boolean interactPollErrorLogged = false;
    private boolean interactForceLogged = false;
    private long lastCloneSweepMs = 0;
    // Cycle 9: live fingerprint of her pack; a change means the bridge's view is stale.
    private String lastInventoryFingerprint = null;
    private boolean creativeDetectionFixed = false;

    // --- Task 2 (Phase 4): 60-second held-interact Use probe ("probe_use") ---
    // Window start/end stamps, in world-thread milliseconds. probeUntilMs == 0 means
    // "no window armed"; both fields are cleared together when the window ends, so
    // the "Probe: window ended" line fires exactly once per arm. Additive: outside a
    // live window every Use path behaves exactly as it did before the probe.
    private volatile long probeStartMs = 0;
    private volatile long probeUntilMs = 0;

    // --- Cycle 5: repeat/count tasks ("mine 3 copper", "get all the copper") ---
    // taskTargetCount: 0 = keep going for every matching block in scan range,
    // N = stop after exactly N blocks. taskDoneCount counts SUCCESSFUL blocks for
    // the current task. taskHint is the hint word reused for the between-block
    // rescan. Set from the bridge thread at task start, read on the world thread.
    private volatile int taskTargetCount = 1;
    private volatile int taskDoneCount = 0;
    private volatile String taskHint = "";

    // --- Cycle 5: interact-to-open-inventory ---
    // Debounce repeated Use packets for the same player (450 ms) so one key press
    // cannot open + immediately toggle the page. Key = player name.
    private final Map<String, Long> lastInvOpenMs = new ConcurrentHashMap<>();

    // Stance state. NOTE: the scheduler tick itself is never gated by these —
    // a "stay" must stop WRITING the follow point, never kill the loop
    // (the pre-Phase-1 freeze bug came exactly from killing the loop).
    private volatile boolean following = false;
    private volatile boolean staying = false;      // explicit "stay here" (watching, no walk)
    private volatile String pendingMoveTo = null;   // "x,z" one-shot consumed on the world thread
    private volatile boolean pendingStayClear = false;

    // Cycle 12: the skin currently applied to her (read by the appearance page).
    private volatile PlayerSkin currentSkin = null;

    // Stuck watchdog bookkeeping (world-thread only)
    private double lastX, lastZ;
    private long lastProgressMs;
    Vector3d lastWrittenSlot = null;

    // Follow-anchor state (world-thread only): the stand-off ring point is
    // recomputed only when the PLAYER has moved past the start buffer away from
    // this anchor — deriving the trigger from Laya's own distance made her
    // re-target whenever SHE moved, i.e. circle the player.
    Vector3d followAnchor = null;
    private long nextIdleShuffleMs = 0;
    private long lastFollowDiagMs = 0;   // fix cycle 2: 5 s stand-off diagnostic (evidence for head focus)

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LayaFollowLoop");
        t.setDaemon(true);
        return t;
    });

    public NPCController(LayaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Task 5: Laya's combat state, so {@code LayaPlugin.setup()} can register the damage
     * filter against it. Safe to hand out from construction time — the controller is
     * created as a field initialiser above, before any system can run.
     */
    public CombatController getCombat() {
        return combat;
    }

    // --- Task 3: HUD snapshot + immediate-refresh plumbing ---
    /**
     * The companion card's controller, registered from its constructor (it is
     * created right after this controller in {@code LayaPlugin}). Null until then,
     * and the hooks below stay safe no-ops while it is unset.
     */
    private volatile com.jonferno.laya.hud.LayaHudController hudRefresher;

    /** Task 3: let the HUD controller register itself for tick + event refreshes. */
    public void setHudRefresher(com.jonferno.laya.hud.LayaHudController controller) {
        this.hudRefresher = controller;
    }

    /**
     * Ask the HUD for an immediate refresh (downed entry, revive completion,
     * appearance apply). Safe on any thread and a no-op while no HUD is attached:
     * the call only sets a flag that the next world-thread frame consumes.
     */
    public void pokeHud() {
        com.jonferno.laya.hud.LayaHudController c = hudRefresher;
        if (c == null) return;
        try {
            c.poke();
        } catch (Throwable ignored) {
        }
    }

    /** Task 3: Laya's live health, or {@code -1} when it cannot be read. */
    public float hudHp() {
        return combat.getLayaHp();
    }

    /** Task 3: true while the downed state owns her. */
    public boolean hudDowned() {
        return combat.isDowned();
    }

    /** Task 3: the raw combat stance word ("follow" | "stay" | "attack" | "defend"). */
    public String hudStance() {
        return combat.stance();
    }

    /**
     * Live JSON state for one chat turn. The old ChatListener sent a hard-coded
     * placeholder, so Laya could not know what she was holding or wearing.
     * Keep this JSON small and factual; the AI bridge renders the important
     * parts into the prompt.
     */
    public String stateSnapshotJson(String playerName) {
        JsonObject root = new JsonObject();

        JsonObject player = new JsonObject();
        player.addProperty("name",
            (playerName == null || playerName.isBlank()) ? "player" : playerName);
        try {
            Inventory pinv = playerInventory();
            if (pinv != null && pinv.getHotbar() != null) {
                int active = pinv.getActiveHotbarSlot();
                if (active >= 0 && active < pinv.getHotbar().getCapacity()) {
                    ItemStack held = pinv.getHotbar().getItemStack((short) active);
                    player.addProperty("held_item",
                        held == null ? "" : held.getItemId());
                    player.addProperty("held_slot", active + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        root.add("player", player);

        JsonObject laya = new JsonObject();
        laya.addProperty("alive", !combat.isDowned());
        laya.addProperty("health", combat.getLayaHp());
        try {
            if (npcEntity != null) {
                Inventory inv = npcEntity.getInventory();
                if (inv != null && inv.getHotbar() != null) {
                    int active = inv.getActiveHotbarSlot();
                    if (active >= 0 && active < inv.getHotbar().getCapacity()) {
                        ItemStack held = inv.getHotbar().getItemStack((short) active);
                        laya.addProperty("held_item",
                            held == null ? "" : held.getItemId());
                        laya.addProperty("held_slot", active + 1);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        JsonObject armor = new JsonObject();
        String[] armorKeys = {"head", "chest", "hands", "legs"};
        for (int i = 0; i < armorKeys.length; i++) {
            String id = hudArmorId(i);
            armor.addProperty(armorKeys[i], id == null ? "" : id);
        }
        laya.add("armor", armor);
        String offhand = hudShieldId();
        laya.addProperty("offhand", offhand == null ? "" : offhand);
        laya.addProperty("appearance",
            currentSkin == null ? "" : Appearance.summary(currentSkin));
        String task = hudTask();
        if (task != null) laya.addProperty("task", task);
        root.add("laya", laya);

        root.add("nearby_entities", new JsonArray());
        root.addProperty("time_of_day", "unknown");
        return root.toString();
    }

    /** UI index: 0..7 hotbar, 8..35 storage, 36..39 armor, 40 utility[0]. */
    private Inventory playerInventory() {
        try {
            if (playerRef == null || worldStore == null) return null;
            Ref<EntityStore> pe = playerRef.getReference();
            Player p = worldStore.getComponent(pe, Player.getComponentType());
            return p == null ? null : p.getInventory();
        } catch (Throwable t) {
            return null;
        }
    }

    private ItemContainer uiContainer(int idx) {
        if (idx >= 100) {
            Inventory pinv = playerInventory();
            if (pinv == null) return null;
            int j = idx - 100;
            if (j < 9) return pinv.getHotbar();
            if (j < 45) return pinv.getStorage();
            return null;
        }
        if (npcEntity == null) return null;
        Inventory inv = npcEntity.getInventory();
        if (inv == null) return null;
        if (idx < 8) return inv.getHotbar();
        if (idx < 36) return inv.getStorage();
        if (idx < 40) return inv.getArmor();
        if (idx == 40) return inv.getUtility();
        return null;
    }

    private static short uiSlot(int idx) {
        if (idx >= 100) {
            int j = idx - 100;
            return (short) (j < 9 ? j : j - 9);
        }
        if (idx < 8) return (short) idx;
        if (idx < 36) return (short) (idx - 8);
        if (idx < 40) return (short) (idx - 36);
        return 0;
    }

    public String uiItemId(int idx) {
        try {
            ItemContainer c = uiContainer(idx);
            if (c == null) return null;
            short slot = uiSlot(idx);
            if (slot < 0 || slot >= c.getCapacity()) return null;
            ItemStack st = c.getItemStack(slot);
            return st == null ? null : st.getItemId();
        } catch (Throwable t) {
            return null;
        }
    }

    public int uiQuantity(int idx) {
        try {
            ItemContainer c = uiContainer(idx);
            if (c == null) return 0;
            short slot = uiSlot(idx);
            if (slot < 0 || slot >= c.getCapacity()) return 0;
            ItemStack st = c.getItemStack(slot);
            return st == null ? 0 : st.getQuantity();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Stack identical items across Laya's hotbar+storage, then sort by item id. */
    public void stackAndSortLaya() {
        try {
            if (npcEntity == null) return;
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return;
            ItemContainer hotbar = inv.getHotbar(), storage = inv.getStorage();
            if (hotbar == null || storage == null) return;
            java.util.List<ItemStack> all = new java.util.ArrayList<>();
            for (ItemContainer c : new ItemContainer[]{hotbar, storage}) {
                for (short s = 0; s < c.getCapacity(); s++) {
                    ItemStack st = c.getItemStack(s);
                    if (st != null && st.getItemId() != null && !st.getItemId().isEmpty()) all.add(st);
                }
            }
            for (ItemContainer c : new ItemContainer[]{hotbar, storage}) {
                for (short s = 0; s < c.getCapacity(); s++) c.setItemStackForSlot(s, null);
            }
            java.util.List<ItemStack> merged = new java.util.ArrayList<>();
            for (ItemStack st : all) {
                ItemStack found = null;
                for (ItemStack m : merged) {
                    if (st.isStackableWith(m)) { found = m; break; }
                }
                if (found == null) merged.add(st);
                else merged.set(merged.indexOf(found), found.withQuantity(
                    Math.min(9999, found.getQuantity() + st.getQuantity())));
            }
            merged.sort((a, b) -> {
                int c = String.valueOf(a.getItemId()).compareTo(String.valueOf(b.getItemId()));
                return c != 0 ? c : Integer.compare(b.getQuantity(), a.getQuantity());
            });
            int n = 0;
            for (ItemStack st : merged) {
                if (n < hotbar.getCapacity()) hotbar.setItemStackForSlot((short) n++, st);
                else if (n - hotbar.getCapacity() < storage.getCapacity())
                    storage.setItemStackForSlot((short) (n++ - hotbar.getCapacity()), st);
                else break;
            }
            plugin.log("Inventory UI: stacked/sorted " + merged.size() + " stacks");
        } catch (Throwable t) {
            plugin.log("Inventory UI sort failed: " + t);
        }
    }

    /** Move/swap a UI slot stack. Armor slots only accept their matching piece. */
    public boolean moveUiItem(int from, int to) {
        boolean fromOk = (from >= 0 && from <= 40) || (from >= 100 && from <= 144);
        boolean toOk = (to >= 0 && to <= 40) || (to >= 100 && to <= 144);
        if (from == to || !fromOk || !toOk) return false;
        try {
            ItemContainer sc = uiContainer(from);
            ItemContainer dc = uiContainer(to);
            if (sc == null || dc == null) return false;
            short ss = uiSlot(from), ds = uiSlot(to);
            if (ss < 0 || ss >= sc.getCapacity() || ds < 0 || ds >= dc.getCapacity()) return false;
            ItemStack stack = sc.getItemStack(ss);
            if (stack == null) return false;
            if (!canAccept(dc, ds, stack, to)) return false;
            ItemStack old = dc.getItemStack(ds);
            if (old != null && stack.isStackableWith(old)) {
                dc.setItemStackForSlot(ds, old.withQuantity(
                    Math.min(9999, old.getQuantity() + stack.getQuantity())));
                sc.removeItemStackFromSlot(ss);
                plugin.log("Inventory UI: stacked " + stack.getItemId() + " " + from + " -> " + to);
                return true;
            }
            sc.removeItemStackFromSlot(ss);
            dc.setItemStackForSlot(ds, stack);
            if (old != null) {
                if (canAccept(sc, ss, old, from)) {
                    sc.setItemStackForSlot(ss, old);
                } else if (!addToPack(old)) {
                    plugin.log("Inventory UI: could not return " + old.getItemId() + " to pack");
                }
            }
            plugin.log("Inventory UI: moved " + stack.getItemId() + " " + from + " -> " + to);
            return true;
        } catch (Throwable t) {
            plugin.log("Inventory UI move failed: " + t);
            return false;
        }
    }

    private boolean canAccept(ItemContainer container, short slot, ItemStack stack, int idx) {
        if (stack == null) return false;
        if (idx >= 36 && idx <= 39) {
            com.hypixel.hytale.server.core.asset.type.item.config.Item item = stack.getItem();
            return item != null && item.getArmor() != null
                && item.getArmor().getArmorSlot() != null
                && item.getArmor().getArmorSlot().getValue() == idx - 36;
        }
        return true;   // pack + utility accept anything
    }

    /** Double-click: send one stack to the other side (player <-> Laya pack). */
    public boolean quickTransfer(int idx) {
        try {
            ItemContainer source = uiContainer(idx);
            if (source == null) return false;
            short slot = uiSlot(idx);
            if (slot < 0 || slot >= source.getCapacity()) return false;
            ItemStack stack = source.getItemStack(slot);
            if (stack == null) return false;
            boolean playerSource = idx >= 100;
            ItemContainer[] dests;
            if (playerSource) {
                dests = new ItemContainer[]{layaHotbar(), layaStorage()};
            } else {
                Inventory pinv = playerInventory();
                if (pinv == null) return false;
                dests = new ItemContainer[]{pinv.getHotbar(), pinv.getStorage()};
            }
            for (ItemContainer dest : dests) {
                if (dest != null && dest.addItemStack(stack).succeeded()) {
                    source.removeItemStackFromSlot(slot);
                    plugin.log("Inventory UI: quick transfer " + stack.getItemId() + " " + idx);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            plugin.log("Inventory UI quick transfer failed: " + t);
            return false;
        }
    }

    private ItemContainer layaHotbar() {
        Inventory inv = npcEntity == null ? null : npcEntity.getInventory();
        return inv == null ? null : inv.getHotbar();
    }

    private ItemContainer layaStorage() {
        Inventory inv = npcEntity == null ? null : npcEntity.getInventory();
        return inv == null ? null : inv.getStorage();
    }

    private boolean addToPack(ItemStack stack) {
        if (stack == null || npcEntity == null) return false;
        Inventory inv = npcEntity.getInventory();
        if (inv == null) return false;
        for (ItemContainer c : new ItemContainer[]{
            inv.getHotbar(), inv.getStorage(), inv.getBackpack(), inv.getTools()}) {
            if (c != null && c.addItemStack(stack).succeeded()) return true;
        }
        return false;
    }

    /** HUD: item id equipped in armor slot 0=Head,1=Chest,2=Hands,3=Legs. */
    public String hudArmorId(int slot) {
        try {
            if (npcEntity == null) return null;
            Inventory inv = npcEntity.getInventory();
            ItemContainer armor = inv == null ? null : inv.getArmor();
            ItemStack st = armor == null ? null : armor.getItemStack((short) slot);
            return st == null ? null : st.getItemId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** HUD: item id in utility slot 0 (shield/offhand), or null. */
    public String hudShieldId() {
        try {
            if (npcEntity == null) return null;
            Inventory inv = npcEntity.getInventory();
            ItemContainer utility = inv == null ? null : inv.getUtility();
            ItemStack st = utility == null ? null : utility.getItemStack((short) 0);
            return st == null ? null : st.getItemId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** HUD icon state for the live task: mining / chopping / gathering, or null. */
    public String hudTask() {
        if (taskKind == null || "IDLE".equals(taskPhase)) return null;
        if ("mine".equals(taskKind)) return "mining";
        String label = taskLabel == null ? "" : taskLabel.toLowerCase();
        if (label.contains("wood") || label.contains("log") || label.contains("oak")
            || label.contains("birch") || label.contains("spruce")
            || label.contains("palm") || label.contains("tree")) {
            return "chopping";
        }
        if (label.contains("stone") || label.contains("rubble")
            || label.contains("rock") || label.contains("ore")) {
            return "mining";
        }
        return "gathering";
    }

    /** True while a target is engaged; the HUD shows "attacking" only then. */
    public boolean hudEngaged() {
        return combat.hudEngaged();
    }

    /** True while the revive channel runs (HUD countdown). */
    public boolean hudChanneling() {
        return combat.hudChanneling();
    }

    /** Milliseconds left in the revive channel. */
    public long hudReviveRemainingMs() {
        return combat.hudReviveRemainingMs();
    }

    /** Task 3: the role maximum health, for the "x / y" line and the bar fraction. */
    public float hudMaxHp() {
        return CombatController.MAX_HP;
    }

    public void start() {
        running = true;
        state.put("mode", "idle");
        plugin.log("NPC controller initialized.");
    }

    /**
     * Task 7: run {@code body} on the world thread, or directly when no world exists yet
     * (nothing to serialize against). {@code CombatController.setStance} calls this from
     * the bridge HTTP thread to hand a channel cancel to the thread that owns it.
     */
    void runOnWorld(Runnable body) {
        if (body == null) return;
        World w = world;
        if (w == null) {
            body.run();
            return;
        }
        try {
            w.execute(body);
        } catch (Throwable t) {
            body.run();
        }
    }

    /**
     * Task 7: drop a stashed move_to target. Called from
     * {@link CombatController#enterDowned} on the world thread so a move_to that was
     * queued just before a lethal hit cannot fire on the next tick and walk her while
     * she is down.
     */
    void clearPendingMoveTo() {
        pendingMoveTo = null;
    }

    public void stop() {
        running = false;
        following = false;
        saveInventory();   // Cycle 6: persist her pack before the world goes away
        combat.saveState();   // Task 7: persist hp/downed before the world goes away
        scheduler.shutdownNow();
        plugin.log("NPC controller stopped.");
    }

    // --- Cycle 8: one Laya, not a clone army ---

    /**
     * NPC entities are saved with the world, so spawning one each boot piles up
     * clones. Remove every loaded NPC whose role is Laya except the one this
     * controller owns; the follow tick re-sweeps once a minute to catch clones in
     * chunks that load later.
     */
    private void removeStaleClones() {
        if (worldStore == null) return;
        try {
            Archetype<EntityStore> archetype = Archetype.of(NPCEntity.getComponentType());
            int[] removed = {0};
            worldStore.forEachChunk(archetype, (chunk, cb) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                    if (npc == null) continue;
                    String role = npc.getRoleName();
                    if (role == null || !role.equalsIgnoreCase("Laya")) continue;
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    // Ref does not override equals: compare index + store.
                    if (layaRef != null && layaRef.getIndex() == ref.getIndex()
                            && layaRef.getStore() == ref.getStore()) {
                        continue;
                    }
                    cb.removeEntity(ref, RemoveReason.REMOVE);
                    removed[0]++;
                }
            });
            if (removed[0] > 0) {
                plugin.log("Laya: removed " + removed[0] + " stale clone(s)");
            }
        } catch (Throwable t) {
            plugin.log("Laya: clone cleanup failed: " + t);
        }
    }

    // --- Cycle 6: inventory persistence (the NPC entity is respawned each session) ---

    /** World-thread (or shutdown): write all six inventory sections to LAYA_STATE_JSON. */
    private void saveInventory() {
        if (npcEntity == null) return;
        try {
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return;
            JsonObject root = new JsonObject();
            root.addProperty("saved_at", System.currentTimeMillis());
            String[] names = {"hotbar", "storage", "tools", "backpack", "utility", "armor"};
            ItemContainer[] sections = {
                inv.getHotbar(), inv.getStorage(), inv.getTools(),
                inv.getBackpack(), inv.getUtility(), inv.getArmor()
            };
            int total = 0;
            for (int i = 0; i < names.length; i++) {
                JsonArray arr = new JsonArray();
                ItemContainer sec = sections[i];
                if (sec != null) {
                    for (short s = 0; s < sec.getCapacity(); s++) {
                        ItemStack st = sec.getItemStack(s);
                        if (st == null || st.getItemId() == null || st.getItemId().isEmpty()) continue;
                        JsonObject o = new JsonObject();
                        o.addProperty("slot", (int) s);
                        o.addProperty("id", st.getItemId());
                        o.addProperty("qty", st.getQuantity());
                        o.addProperty("dur", st.getDurability());
                        o.addProperty("maxdur", st.getMaxDurability());
                        arr.add(o);
                        total++;
                    }
                }
                root.add(names[i], arr);
            }
            java.nio.file.Path p = Paths.get(STATE_JSON);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, root.toString().getBytes(StandardCharsets.UTF_8));
            lastInventoryFingerprint = inventoryFingerprint(inv);
            plugin.log("Laya: inventory saved (" + total + " stacks -> " + STATE_JSON + ")");
        } catch (Throwable t) {
            plugin.log("Laya: inventory save failed: " + t);
        }
    }

    /** World-thread, after spawn: restore the saved sections (items keep their slots). */
    private void loadInventory() {
        try {
            java.nio.file.Path p = Paths.get(STATE_JSON);
            if (!Files.exists(p)) {
                plugin.log("Laya: no saved inventory yet (" + STATE_JSON + ")");
                return;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            Inventory inv = npcEntity == null ? null : npcEntity.getInventory();
            if (inv == null) return;
            String[] names = {"hotbar", "storage", "tools", "backpack", "utility", "armor"};
            ItemContainer[] sections = {
                inv.getHotbar(), inv.getStorage(), inv.getTools(),
                inv.getBackpack(), inv.getUtility(), inv.getArmor()
            };
            int restored = 0;
            for (int i = 0; i < names.length; i++) {
                ItemContainer sec = sections[i];
                JsonArray arr = root.has(names[i]) ? root.getAsJsonArray(names[i]) : null;
                if (sec == null || arr == null) continue;
                for (JsonElement el : arr) {
                    try {
                        JsonObject o = el.getAsJsonObject();
                        short slot = (short) o.get("slot").getAsInt();
                        if (slot < 0 || slot >= sec.getCapacity()) continue;
                        String id = o.get("id").getAsString();
                        int qty = o.has("qty") ? o.get("qty").getAsInt() : 1;
                        double dur = o.has("dur") ? o.get("dur").getAsDouble() : 0.0;
                        double maxdur = o.has("maxdur") ? o.get("maxdur").getAsDouble() : 0.0;
                        ItemStack stack = (dur > 0.0 || maxdur > 0.0)
                            ? new ItemStack(id, qty, dur, maxdur, null)
                            : new ItemStack(id, qty);
                        sec.setItemStackForSlot(slot, stack);
                        restored++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            lastInventoryFingerprint = inventoryFingerprint(inv);
            plugin.log("Laya: inventory restored (" + restored + " stacks from " + STATE_JSON + ")");
        } catch (Throwable t) {
            plugin.log("Laya: inventory restore failed: " + t);
        }
    }

    /**
     * Cycle 12: world-thread, after spawn. Apply the saved skin (or adopt the one the
     * NPC already spawned with; a random player skin is the last resort). The editor
     * page then reads this skin as the current selection.
     */
    private void loadSkin() {
        if (layaRef == null || worldStore == null) return;
        try {
            PlayerSkin skin = Appearance.load();
            boolean saved = skin != null;
            if (skin != null) {
                String error = Appearance.validate(skin);
                if (error != null) {
                    plugin.log("Appearance: saved skin rejected (" + error
                        + ") - adopting the spawned skin");
                    skin = null;
                    saved = false;
                }
            }
            if (skin == null) {
                PlayerSkinComponent existing =
                    worldStore.getComponent(layaRef, PlayerSkinComponent.getComponentType());
                if (existing != null) skin = existing.getPlayerSkin();
            }
            if (skin == null) skin = CosmeticsModule.get().generateRandomSkin(new java.util.Random());
            if (skin == null) {
                plugin.log("Appearance: no skin available (saved/existing/random all null)");
                return;
            }
            Appearance.apply(worldStore, layaRef, skin);
            currentSkin = skin;
            // Persist the adopted look once, so the page has a stable baseline.
            if (!saved) Appearance.save(skin);
            plugin.log("Appearance loaded (" + (saved ? "saved" : "adopted")
                + "): " + Appearance.summary(skin));
        } catch (Throwable t) {
            plugin.log("Appearance: load failed: " + t);
        }
    }

    /**
     * Cycle 12: bridge thread -> world thread. Builds and validates the skin from the
     * {@code skin_*} flat fields the bridge forwards, then applies + persists it.
     */
    /**
     * Rebuild Laya's model from the current skin. The client keeps the hurt facial
     * shape (eyebrows/mouth) after combat even though the server animation slot has
     * cleared; a model rebuild is the reliable way to reset it. World thread only.
     */
    void reappear() {
        PlayerSkin skin = currentSkin;
        if (skin == null || layaRef == null || worldStore == null) return;
        Appearance.apply(worldStore, layaRef, skin);
    }

    private void handleAppearance(String json) {
        if (layaRef == null || world == null || worldStore == null) {
            plugin.log("Appearance: no Laya to dress yet");
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String f : Appearance.FIELDS) {
            String v = extractJsonField(json, "skin_" + f);
            if (v != null && !v.isEmpty()) values.put(f, v);
        }
        PlayerSkin skin = Appearance.fromValues(values);
        String error = Appearance.validate(skin);
        if (error != null) {
            plugin.log("Appearance rejected: " + error);
            return;
        }
        try {
            world.execute(() -> {
                try {
                    Appearance.apply(worldStore, layaRef, skin);
                    currentSkin = skin;
                    Appearance.save(skin);
                    plugin.log("Appearance applied: " + Appearance.summary(skin));
                    // Task 3: the card is a fixed skeleton today, but any future
                    // skin-driven card detail must not wait for the 500 ms tick.
                    pokeHud();
                } catch (Throwable t) {
                    plugin.log("Appearance: apply failed: " + t);
                }
            });
        } catch (Throwable t) {
            plugin.log("Appearance: could not schedule apply: " + t);
        }
    }

    /** Bridge GET /cosmetics: the full option tree for the appearance page. */
    public String appearanceOptionsJson() {
        return Appearance.optionsJson(currentSkin);
    }

    /**
     * Cycle 9: the AI bridge reads config/laya-inventory.json on every turn, so the
     * file must track manual moves too (dragging an axe into her window used to stay
     * unsaved until the next gift/reward/stop). One cheap fingerprint per follow tick.
     */
    /** Candidate gear found in a pack section. */
    private static final class GearPick {
        final ItemContainer from;
        final short slot;
        final ItemStack stack;
        final int target;
        final int rank;
        final boolean shield;
        GearPick(ItemContainer from, short slot, ItemStack stack, int target, int rank, boolean shield) {
            this.from = from; this.slot = slot; this.stack = stack;
            this.target = target; this.rank = rank; this.shield = shield;
        }
    }

    /** ItemLevel first, then quality; armor/shield only. */
    private static int gearRank(ItemStack stack) {
        if (stack == null) return -1;
        com.hypixel.hytale.server.core.asset.type.item.config.Item item = stack.getItem();
        if (item == null) return -1;
        return item.getItemLevel() * 1000 + Math.max(0, stack.getQualityIndex());
    }

    private static boolean isShield(ItemStack stack) {
        if (stack == null) return false;
        String id = stack.getItemId();
        return id != null && id.startsWith("Weapon_Shield");
    }

    /** World-thread: keep the best armor per slot and the best shield equipped. */
    private void autoEquipGear(Inventory inv) {
        if (inv == null) return;
        ItemContainer armor = inv.getArmor();
        ItemContainer utility = inv.getUtility();
        java.util.List<ItemContainer> packs = new java.util.ArrayList<>();
        for (ItemContainer c : new ItemContainer[]{
            inv.getHotbar(), inv.getStorage(), inv.getBackpack(), inv.getTools()}) {
            if (c != null) packs.add(c);
        }
        java.util.List<GearPick> armorPicks = new java.util.ArrayList<>();
        GearPick shieldPick = null;
        for (ItemContainer pack : packs) {
            for (short s = 0; s < pack.getCapacity(); s++) {
                ItemStack st = pack.getItemStack(s);
                if (st == null || st.getItemId() == null) continue;
                com.hypixel.hytale.server.core.asset.type.item.config.Item item = st.getItem();
                if (item == null) continue;
                if (item.getArmor() != null && item.getArmor().getArmorSlot() != null && armor != null) {
                    int target = item.getArmor().getArmorSlot().getValue();
                    if (target >= 0 && target < armor.getCapacity()) {
                        armorPicks.add(new GearPick(pack, s, st, target, gearRank(st), false));
                    }
                } else if (isShield(st) && utility != null && utility.getCapacity() > 0) {
                    if (shieldPick == null || gearRank(st) > shieldPick.rank) {
                        shieldPick = new GearPick(pack, s, st, 0, gearRank(st), true);
                    }
                }
            }
        }
        // Best candidate per armor slot.
        GearPick[] best = new GearPick[armor == null ? 0 : armor.getCapacity()];
        for (GearPick p : armorPicks) {
            if (best[p.target] == null || p.rank > best[p.target].rank) best[p.target] = p;
        }
        for (int t = 0; t < best.length; t++) {
            GearPick pick = best[t];
            if (pick == null) continue;
            ItemStack current = armor.getItemStack((short) t);
            if (pick.rank <= gearRank(current)) continue;
            equipFromPack(armor, (short) t, pick, inv);
        }
        if (shieldPick != null) {
            ItemStack current = utility.getItemStack((short) 0);
            if (shieldPick.rank > gearRank(current) || !isShield(current)) {
                equipFromPack(utility, (short) 0, shieldPick, inv);
            }
        }
        // Nudge the armor system to apply stats/appearance.
        try {
            InventoryComponent.Armor ac = worldStore.getComponent(
                layaRef, InventoryComponent.Armor.getComponentType());
            if (ac != null) ac.setOutdatedEquipment(true);
        } catch (Throwable ignored) {
        }
    }

    private void equipFromPack(ItemContainer target, short targetSlot, GearPick pick, Inventory inv) {
        try {
            ItemStack old = target.getItemStack(targetSlot);
            ItemStack best = pick.stack.getQuantity() > 1
                ? pick.stack.withQuantity(1) : pick.stack;
            target.removeItemStackFromSlot(targetSlot);
            pick.from.removeItemStackFromSlot(pick.slot);
            target.setItemStackForSlot(targetSlot, best);
            if (old != null) {
                boolean placed = false;
                for (ItemContainer pack : new ItemContainer[]{
                    inv.getHotbar(), inv.getStorage(), inv.getBackpack(), inv.getTools()}) {
                    if (pack != null && pack.addItemStack(old).succeeded()) { placed = true; break; }
                }
                if (!placed) plugin.log("Auto-equip: could not return " + old.getItemId() + " to pack");
            }
            plugin.log("Auto-equip: " + best.getItemId() + " -> "
                + (pick.shield ? "utility" : "armor") + " slot " + targetSlot
                + " (rank " + pick.rank + ")");
        } catch (Throwable t) {
            plugin.log("Auto-equip failed: " + t);
        }
    }

    private void syncInventoryIfChanged() {
        if (npcEntity == null) return;
        try {
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return;
            String fp = inventoryFingerprint(inv);
            if (fp.equals(lastInventoryFingerprint)) return;
            saveInventory();   // sets lastInventoryFingerprint
            plugin.log("Laya: inventory changed -> persisted for the AI");
        } catch (Throwable ignored) {
        }
    }

    /** Stable order (hotbar, storage, tools, backpack, utility, armor) for change detection. */
    private String inventoryFingerprint(Inventory inv) {
        StringBuilder sb = new StringBuilder();
        ItemContainer[] sections = {
            inv.getHotbar(), inv.getStorage(), inv.getTools(),
            inv.getBackpack(), inv.getUtility(), inv.getArmor()
        };
        for (ItemContainer sec : sections) {
            if (sec == null) {
                sb.append('|');
                continue;
            }
            for (short s = 0; s < sec.getCapacity(); s++) {
                ItemStack st = sec.getItemStack(s);
                if (st == null || st.getItemId() == null || st.getItemId().isEmpty()) continue;
                sb.append(st.getItemId()).append(':').append(st.getQuantity())
                  .append(':').append(st.getDurability()).append(';');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /** Cache the world reference — captured from AddPlayerToWorldEvent. */
    public void captureWorld(World w) {
        if (world == null && w != null) {
            world = w;
            plugin.log("World captured: " + w);
        }
    }

    /**
     * Spawn Laya near the player if not already spawned. Safe to call
     * repeatedly — idempotent. On success the follow slot is bound immediately
     * and an initial follow point is written (bootstrap).
     */
    public void ensureSpawned(PlayerRef player) {
        if (layaRef != null || player == null) return;
        if (world == null) {
            plugin.log("Cannot spawn Laya yet — world not captured.");
            return;
        }
        playerRef = player;
        worldStore = player.getReference().getStore();

        Vector3d spawnPos = new Vector3d(player.getTransform().getPosition()).add(2.0, 0.0, 1.5);
        // Build attempt list: configured model first, then known-good vanilla roles
        java.util.List<String> attempts = new java.util.ArrayList<>();
        attempts.add(PRIMARY_MODEL);
        for (String m : MODEL_FALLBACKS) {
            if (!m.equals(PRIMARY_MODEL)) attempts.add(m);
        }
        plugin.log("Spawning Laya at " + spawnPos + " (trying " + attempts + ")");

        world.execute(() -> {
            // Cycle 8: Hytale saves NPCs with the world, so every boot used to add
            // another Laya. Clear the stale ones (same role) before spawning the new.
            removeStaleClones();
            for (String model : attempts) {
                try {
                    Pair<Ref<EntityStore>, INonPlayerCharacter> result =
                        NPCPlugin.get().spawnNPC(worldStore, model, null, spawnPos, new Rotation3f());
                    if (result != null && result.first() != null) {
                        layaRef = result.first();
                        // Task 5: the break driver is entity-scoped (InteractionManager +
                        // InteractionContext owner) — bind Laya as the acting entity.
                        BlockBreaker.bindActor(layaRef);
                        layaEntityId = "laya_npc_" + System.currentTimeMillis();
                        if (result.second() instanceof NPCEntity npc) {
                            npcEntity = npc;
                            bindFollowSlot(npc, model);
                            loadInventory();   // Cycle 6: her pack survives sessions
                            loadSkin();        // Cycle 12: her look survives sessions
                            // Task 7: restore hp/downed AFTER the pack, then re-pose her
                            // if the save says she was down (no chat warning on boot).
                            combat.loadSavedState();
                            if (combat.isDowned()) {
                                combat.enterDownedRestored();
                            }
                        }
                        following = true;
                        state.put("mode", "follow");
                        startFollowTick();
                        // Bootstrap write so she has a target from tick 0
                        lastProgressMs = System.currentTimeMillis();
                        plugin.log("Laya spawned as '" + model + "'! NPC type: " + result.second().getNPCTypeId());
                        plugin.log("Movement: engine Seek follow (reads slot '" + FOLLOW_SLOT + "', " + (1000 / TICK_MS) + " writes/sec)");
                        return;
                    }
                    plugin.log("  Model '" + model + "' returned null, trying next...");
                } catch (Throwable t) {
                    plugin.log("  Model '" + model + "' failed: " + t.getMessage());
                }
            }
            plugin.log("All spawn attempts failed! Check LAYA_MODEL env var.");
        });
    }

    /**
     * Resolve the stored-position slot binding. Verified 0.7.2 facts:
     *  - MarkedEntitySupport.get(Ref, ComponentAccessor) is a static typed getter;
     *    the protected Vector3d[] storedPositions array has no public setter,
     *    hence one reflective field write.
     *  - getSlotName() only reverses TARGET-slot names (targetSlotMapper) and
     *    null-PEs when the role declares none — position slots are a separate
     *    build-time namespace. The authoritative index therefore comes from
     *    the engine-compiled ReadPosition sensor node itself.
     * World-thread only.
     */
    private void bindFollowSlot(NPCEntity npc, String model) {
        followSlot = -1;
        try {
            markedSupport = MarkedEntitySupport.get(npc.getReference(), worldStore);
            if (markedSupport == null) {
                plugin.log("bindFollowSlot: MarkedEntitySupport missing — engine seek follow unavailable");
                return;
            }
            worldSupport = WorldSupport.get(npc.getReference(), worldStore);

            Class<?> type = markedSupport.getClass();
            Field field = null;
            while (type != null) {
                try {
                    field = type.getDeclaredField("storedPositions");
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                plugin.log("bindFollowSlot: storedPositions field not found — engine seek follow unavailable");
                return;
            }
            field.setAccessible(true);
            storedPositionsField = field;

            Vector3d[] slots = readSlotArray();
            int count = slots == null ? 0 : slots.length;

            // PRIMARY: read the index the ENGINE itself compiled into our
            // ReadPosition sensor node. Slot-name reverse lookup cannot work
            // for position slots: getSlotName() reverses only TARGET-slot
            // names (targetSlotMapper.getNameMap()); our role declares no
            // named target slots, initEmpty() leaves slotToNameMap null and
            // getSlotName() is missing the null-guard its sibling
            // methods have (the Phase-1 test session NPE).
            resolveInstructionFields();
            Instruction root = npc.getRole() != null ? npc.getRole().getRootInstruction() : null;

            // R-T4-1: collect EVERY ReadPosition node's (range, slot) pair. The task
            // blocks are inserted BEFORE the follow block in Laya.json, so the legacy
            // "first ReadPosition node wins" scan can no longer identify the follow
            // slot — the compiled Range is the discriminator (follow=40, task act=1.6,
            // task walk=120; all three distinct in this role).
            List<double[]> usages = new ArrayList<>();
            collectPositionUsages(root, usages);
            int idx = findSlotByRange(usages, 40.0);
            if (idx < 0) {
                idx = findFollowSlotIndex(root);   // legacy fallback (range reflection unavailable)
            }

            if (idx >= 0 && idx < count) {
                followSlot = idx;
                plugin.log("Follow slot '" + FOLLOW_SLOT + "' bound at index " + followSlot
                    + " of " + count + " (read from compiled sensor node)");
            } else if (idx < 0 && count == 1 && model.equalsIgnoreCase(npc.getRoleName())) {
                // Scan failed (unexpected tree shape) but: single position usage
                // + single-element array + OUR OWN role => index is forced to 0.
                // Never engaged for vanilla fallback roles (they have no
                // ReadPosition node and their sole slot must not be hijacked).
                followSlot = 0;
                plugin.log("Follow slot '" + FOLLOW_SLOT + "' bound at index 0 of 1 (single-slot fallback for role '" + model + "')");
            } else {
                plugin.log("FAILED to resolve follow slot '" + FOLLOW_SLOT + "' (array=" + count
                    + ", sensor idx=" + idx + ") — engine seek follow disabled");
            }

            // R-T4-1: bind the task slot by the SAME range walk. Never throws into the
            // follow binding — on any mismatch the task writes are disabled (taskSlot=-1)
            // and the follow slot keeps working.
            bindTaskSlot(usages, count);
        } catch (Throwable t) {
            followSlot = -1;
            plugin.log("bindFollowSlot failed: " + t);
        }
    }

    private void resolveInstructionFields() throws NoSuchFieldException {
        if (instructionListField == null) {
            instructionListField = Instruction.class.getDeclaredField("instructionList");
            instructionListField.setAccessible(true);
        }
        if (sensorSlotField == null) {
            sensorSlotField = SensorReadPosition.class.getDeclaredField("slot");
            sensorSlotField.setAccessible(true);
        }
        if (sensorRangeField == null) {
            // R-T4-1: protected final double range — the compiled Range from the role JSON.
            sensorRangeField = SensorReadPosition.class.getDeclaredField("range");
            sensorRangeField.setAccessible(true);
        }
    }

    /**
     * R-T4-1: recurse the compiled instruction tree and record every
     * ReadPosition node as { range, slot } (world-thread, bind time).
     */
    private void collectPositionUsages(Instruction node, List<double[]> out) throws IllegalAccessException {
        if (node == null) return;
        Sensor sensor = node.getSensor();
        if (sensor instanceof SensorReadPosition read) {
            out.add(new double[] { sensorRangeField.getDouble(read), sensorSlotField.getInt(read) });
        }
        Instruction[] kids = (Instruction[]) instructionListField.get(node);
        if (kids != null) {
            for (Instruction kid : kids) {
                collectPositionUsages(kid, out);
            }
        }
    }

    /** R-T4-1: the slot index of the usage whose compiled Range equals {@code range} (±1e-6), or -1. */
    private static int findSlotByRange(List<double[]> usages, double range) {
        for (double[] u : usages) {
            if (Math.abs(u[0] - range) < RANGE_EPSILON) {
                return (int) u[1];
            }
        }
        return -1;
    }

    /**
     * R-T4-1 (binding ruling): bind the task position slot from the SAME compiled-range
     * walk. The two task blocks MUST claim the same auto-registered slot
     * ("TaskTargetPosition" declared twice in Laya.json): act Range 1.6 and walk
     * Range 120. Any mismatch disables task writes only — the follow binding above
     * has already been resolved and is never touched.
     */
    private void bindTaskSlot(List<double[]> usages, int count) {
        taskSlot = -1;
        int followIdx = -1;
        int actIdx = -1;
        int walkIdx = -1;
        StringBuilder ranges = new StringBuilder("[");
        try {
            for (double[] u : usages) {
                double r = u[0];
                int s = (int) u[1];
                if (ranges.length() > 1) ranges.append(", ");
                ranges.append(String.format(java.util.Locale.ROOT, "%.3f", r));
                if (Math.abs(r - 40.0) < RANGE_EPSILON) followIdx = s;
                else if (Math.abs(r - TASK_ADJACENT) < RANGE_EPSILON) actIdx = s;
                else if (Math.abs(r - 120.0) < RANGE_EPSILON) walkIdx = s;
            }
            ranges.append("]");

            boolean ok = followIdx >= 0 && actIdx >= 0 && walkIdx >= 0
                && actIdx == walkIdx          // both task blocks share one slot
                && actIdx != followIdx        // task slot is NOT the follow slot
                && actIdx < count && followIdx < count;
            if (ok) {
                taskSlot = actIdx;
                plugin.log("Task slot bound at index " + taskSlot + " (range-derived)");
            } else {
                taskSlot = -1;
                plugin.log("FAILED task slot binding (follow=" + followIdx + " task=" + actIdx
                    + " ranges=" + ranges + ") — task writes disabled");
            }
        } catch (Throwable t) {
            taskSlot = -1;
            plugin.log("FAILED task slot binding (follow=" + followIdx + " task=" + actIdx
                + " ranges=" + ranges + ") — task writes disabled (" + t + ")");
        }
    }

    /** Recurse our role's compiled instruction tree for the ReadPosition node; returns its slot index (world-thread, bind time). */
    private int findFollowSlotIndex(Instruction node) throws IllegalAccessException {
        if (node == null) return -1;
        Sensor sensor = node.getSensor();
        if (sensor instanceof SensorReadPosition read) {
            return sensorSlotField.getInt(read);
        }
        Instruction[] kids = (Instruction[]) instructionListField.get(node);
        if (kids != null) {
            for (Instruction kid : kids) {
                int idx = findFollowSlotIndex(kid);
                if (idx >= 0) return idx;
            }
        }
        return -1;
    }

    private Vector3d[] readSlotArray() throws IllegalAccessException {
        if (markedSupport == null || storedPositionsField == null) return null;
        return (Vector3d[]) storedPositionsField.get(markedSupport);
    }

    /** Write a point into an arbitrary stored position slot (world-thread only). */
    private boolean writeSlot(int slotIndex, Vector3d point) {
        if (slotIndex < 0 || markedSupport == null || storedPositionsField == null) return false;
        try {
            Vector3d[] slots = readSlotArray();
            if (slots == null || slotIndex >= slots.length) return false;
            Vector3d existing = slots[slotIndex];
            if (existing == null) {
                slots[slotIndex] = new Vector3d(point);
            } else {
                existing.set(point);
            }
            return true;
        } catch (Throwable t) {
            plugin.log("writeSlot(" + slotIndex + ") failed: " + t);
            return false;
        }
    }

    /** Write a point into the follow slot (world-thread only). */
    /**
     * Fix cycle 3 (head focus): turn her BODY onto the player while she is idle.
     *
     * <p>The engine's Watch is a head-only motion, and the Player model declares a Camera
     * block without any yaw range, so {@code MotionControllerBase.calculateYaw} clamps the
     * head to the default +/-45 deg. Inside the follow ring's StopDistance the Seek force
     * clears its steering, so nothing claims the body yaw either — she keeps staring along
     * her last heading ("looking forward"). Writing the transform yaw is exactly what
     * {@code MotionControllerBase.moveEntity} does, and {@code readEntityPosition} adopts
     * the transform on the next tick, so the change survives the controller's own write.
     *
     * <p>Only applied when she is close to the player and no task owns her motion; at
     * longer range the Seek already points her at the ring and must not be fought.
     */
    private void facePlayerWhenIdle(Vector3d p, Vector3d l, double dist, double maxDist) {
        if (layaRef == null) return;
        if (dist < 0.35 || dist > maxDist) return;   // ~0.35-2.7 at the ring; farther while staying
        TransformComponent layaT = worldStore.getComponent(layaRef, TransformComponent.getComponentType());
        if (layaT == null) return;
        float targetYaw = PhysicsMath.headingFromDirection(p.x - l.x, p.z - l.z);
        Rotation3f rot = layaT.getRotation();
        float cur = rot.yaw();
        float delta = PhysicsMath.normalizeTurnAngle(targetYaw - cur);
        float maxStep = (float) Math.toRadians(120.0);   // 360 deg/s at the 333 ms tick
        if (delta > maxStep) delta = maxStep;
        else if (delta < -maxStep) delta = -maxStep;
        rot.setYaw(PhysicsMath.normalizeTurnAngle(cur + delta));
    }

    boolean writeFollowSlot(Vector3d point) {
        return writeSlot(followSlot, point);
    }

    /** Read back a stored position slot through the engine's public getter (proof a write landed). */
    private Vector3d readSlot(int slotIndex) {
        if (slotIndex < 0 || markedSupport == null) return null;
        try {
            return markedSupport.getStoredPosition(slotIndex);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read back the follow slot. */
    private Vector3d readFollowSlot() {
        return readSlot(followSlot);
    }

    /** Ask the engine to repath after a slot change (world-thread only). */
    void refreshPath() {
        if (worldSupport != null) {
            try {
                worldSupport.requestNewPath();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * End the current task (world-thread only): sentinel the task slot so the
     * role's two task blocks stop claiming, re-arm the follow ring, drop adjacency,
     * reset the task timestamps, and — ONLY when {@code reason} starts with
     * {@code task_failed} — post a {@code task_failed} event.
     *
     * R-T4-2 (cross-task coupling, binding): Task 1 gates the idle shuffle on
     * {@code lastTaskWriteMs == 0}. Task writes stamp this field non-zero, so if
     * clearTask did NOT zero it the shuffle would stay dead forever after the first
     * task. While a task is live the field stays fresh (shuffle correctly suppressed);
     * the moment the task ends here, the shuffle resumes.
     *
     * The state reset itself lives in {@link #resetTaskState(String)}; this method adds
     * only the failure-POST decision (R-T6 success split).
     */
    private void clearTask(String reason) {
        resetTaskState(reason);
        if (reason != null && reason.startsWith("task_failed")) {
            // Same escaped event channel Task 3 established ({"event","detail"} JSON).
            postEvent("task_failed", reason);
        }
    }

    /**
     * Task 6 (R-T6 success split): clear ALL task state and log, but make NO event
     * decision. Shared by the failure path (via {@link #clearTask}) and the success
     * path ({@link #finishTaskSuccess}), so a {@code task_done} teardown can reuse the
     * reset without the {@code task_failed} POST gate misfiring. World-thread only.
     */
    private void resetTaskState(String reason) {
        if (taskSlot >= 0 && writeSlot(taskSlot, CLEARED_POSITION)) {
            refreshPath();
        }
        followAnchor = null;    // re-arm the ring: player re-anchors after the task
        taskAdjacent = false;
        lastTaskWriteMs = 0;    // R-T4-2: hand the shuffle gate back to Task 1
        leashDeferredLogged = false;   // fix (c): a later defer episode logs again
        taskWorking.set(false);        // Task 6: release the break chain latch
        taskPhase = "IDLE";
        phaseSinceMs = 0;
        taskHitCount = 0;
        taskDigCount = 0;
        taskRadius = 0;
        taskAreaSet = false;
        stopSwing();                   // Cycle 5: clear the Action slot when the task ends
        plugin.log("Task cleared: " + reason);
    }

    /**
     * Engine-follow heartbeat: writes only the follow TARGET; the role's Seek
     * BodyMotion animates the walk. Teleports happen only via the watchdogs.
     */
    private void startFollowTick() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running || layaRef == null || world == null || playerRef == null) return;
            try {
                world.execute(() -> {
                    try {
                        if (layaRef != null && !layaRef.isValid()) {
                            plugin.log("Laya: entity ref invalid — clearing controller ref");
                            layaRef = null;
                            return;
                        }
                        Ref<EntityStore> playerRefNow = playerRef.getReference();
                        if (playerRefNow == null) return;
                        TransformComponent playerT =
                            worldStore.getComponent(playerRefNow, TransformComponent.getComponentType());
                        TransformComponent layaT =
                            worldStore.getComponent(layaRef, TransformComponent.getComponentType());
                        if (playerT == null || layaT == null) return;

                        Vector3d p = new Vector3d(playerT.getPosition());
                        Vector3d l = new Vector3d(layaT.getPosition());
                        long now = System.currentTimeMillis();

                        // Task 2: retire an expired probe window (exactly one log per
                        // arm — both fields are cleared here, and the "live" test in
                        // pollInteractKey requires probeUntilMs != 0).
                        if (probeUntilMs != 0 && now >= probeUntilMs) {
                            probeStartMs = 0;
                            probeUntilMs = 0;
                            plugin.log("Probe: window ended");
                        }

                        // Cycle 7: keep her interactable + consume the engine's
                        // Use (default interact key) — PlayerInteractEvent is never
                        // fired by 0.7.2, so the engine's own interaction queue is
                        // the only reliable signal.
                        pollInteractKey(playerRefNow);

                        // Cycle 9: keep config/laya-inventory.json live (manual moves,
                        // gifts, pickups) so the AI always knows what she carries.
                        syncInventoryIfChanged();

                        // Cycle 8: sweep late-loading chunks for stale Laya clones.
                        if (now - lastCloneSweepMs > 60000L) {
                            lastCloneSweepMs = now;
                            removeStaleClones();
                        }

                        // --- one-shot: stay (clear target -> engine idles her) ---
                        if (pendingStayClear) {
                            pendingStayClear = false;
                            if (writeFollowSlot(CLEARED_POSITION)) {
                                refreshPath();
                                lastWrittenSlot = null;   // force a fresh write when follow re-arms
                                followAnchor = null;      // player re-anchors after the disruption
                                activeTarget = false;
                                stuckResets = 0;
                                stuckGaveUp = false;
                                // Task 3: this is the "stay anchor" — the spot she holds until
                                // a fight takes movement and hands her back here on release.
                                combat.noteStayAnchor(new Vector3d(l));
                                plugin.log("Follow target cleared — engine holds her in place");
                            }
                        }

                        // --- one-shot: task target (mine/gather) -> engine walks her to the block ---
                        // R-T3-1: pendingTaskTarget ("x,y,z") is stashed by runScan on the world
                        // thread; consumed here once (Task 4's block).
                        String taskTarget = pendingTaskTarget;
                        if (taskTarget != null) {
                            pendingTaskTarget = null;
                            if (taskSlot < 0) {
                                plugin.log("Task target ignored — task slot not bound (index " + taskSlot + ")");
                            } else {
                                String[] parts = taskTarget.split(",");
                                double tx = Double.parseDouble(parts[0]);
                                double ty = Double.parseDouble(parts[1]);
                                double tz = Double.parseDouble(parts[2]);
                                boolean ok = writeSlot(taskSlot, new Vector3d(tx, ty, tz));
                                if (ok) {
                                    refreshPath();
                                    lastTaskWriteMs = now;   // starts the 10-min abandonment timer
                                    taskAdjacent = false;
                                    stuckResets = 0;
                                    stuckGaveUp = false;
                                    // Task 6: enter WALKING. Bump the chain GENERATION FIRST
                                    // (I-1), then freeze the CHAIN SNAPSHOT (I-2) from the
                                    // pending runScan metadata — so any in-flight chain from a
                                    // previous task is invalidated before its inputs can change,
                                    // and the new chain reads only frozen values.
                                    taskChainGen++;
                                    taskSnapIid = taskInteractionId();
                                    taskSnapVerb = "mine".equals(taskKind) ? "mined" : "gathered";
                                    taskSnapLabel = taskLabel;
                                    taskSnapKey = taskBlockKey;
                                    taskSnapBlockId = taskBlockId;
                                    taskSnapX = (int) Math.floor(tx);
                                    taskSnapY = (int) Math.floor(ty);
                                    taskSnapZ = (int) Math.floor(tz);
                                    int liveId = BlockBreaker.blockId(world, taskSnapX, taskSnapY, taskSnapZ);
                                    if (liveId != BlockScanner.EMPTY_ID) taskSnapBlockId = liveId;
                                    taskWorking.set(false);   // legitimate new-task release (tick thread)
                                    taskHitCount = 0;
                                    taskDigCount = 0;
                                    taskPhase = "WALKING";
                                    phaseSinceMs = now;
                                    taskUnsticks = 0;
                                    // Cycle 10: hold the right tool (hatchet for wood,
                                    // pickaxe for ore) BEFORE the walk, so the swing reads.
                                    equipTaskTool();
                                    plugin.log("Task phase -> WALKING (target @" + taskSnapX + ","
                                        + taskSnapY + "," + taskSnapZ + " interaction="
                                        + taskSnapIid + " gen=" + taskChainGen + ")");
                                    if (!taskReadbackLogged) {
                                        taskReadbackLogged = true;
                                        Vector3d rb = readSlot(taskSlot);
                                        plugin.log("Task slot readback (index " + taskSlot + "): "
                                            + (rb == null ? "NULL — element missing!" : rb.toString()));
                                    }
                                }
                                plugin.log("Task target set to (" + tx + ", " + tz + ") engine walk: " + ok);
                            }
                        }

                        // --- one-shot: move_to (engine walks her to the point) ---
                        // Task 7: a move_to can never write movement while she is downed,
                        // whatever route stashed it (the command branch is refused too).
                        String move = combat.isDowned() ? null : pendingMoveTo;
                        if (combat.isDowned()) pendingMoveTo = null;
                        if (move != null) {
                            pendingMoveTo = null;
                            String[] parts = move.split(",");
                            double tx = Double.parseDouble(parts[0]);
                            double tz = Double.parseDouble(parts[1]);
                            boolean ok = writeFollowSlot(new Vector3d(tx, l.y, tz));
                            if (ok) {
                                refreshPath();
                                activeTarget = true;
                                lastWrittenSlot = null;  // re-arm band logic after the detour
                                followAnchor = null;      // player re-anchors after the detour
                                stuckResets = 0;
                                stuckGaveUp = false;
                            }
                            plugin.log("Follow target set to (" + tx + ", " + tz + ") engine walk: " + ok);
                        }

                        double dx = p.x - l.x;
                        double dz = p.z - l.z;
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        // Task 3: combat heartbeat runs every tick, immediately after the
                        // distances are known. While combat owns movement (or she is downed)
                        // the follow ring, idle face and the two watchdogs stand down — the
                        // `motionLocked` gates below. Task target/phase and move_to stay
                        // UNGATED: combat cancels a live task at engage, and a move_to
                        // command issued during a fight deliberately wins.
                        combat.tick(now, p, l, dist);
                        clearStuckFace(now);
                        boolean motionLocked = combat.ownsMovement() || combat.isDowned();

                        // Task 3: keep the companion card live. The 500 ms throttle lives
                        // inside the controller, so this call is cheap at the tick rate.
                        // Only when Laya is really spawned, the player component exists and
                        // the HUD controller has registered itself; a failure here must never
                        // take the follow tick down.
                        try {
                            Player hudPlayer = worldStore.getComponent(
                                playerRefNow, Player.getComponentType());
                            if (hudPlayer != null && layaRef != null && hudRefresher != null) {
                                hudRefresher.frame(playerRef, hudPlayer);
                            }
                        } catch (Throwable ignored) {
                        }

                        // R-T4-1: a live task owns movement — the follow ring must not
                        // write (lastTaskWriteMs is stamped on every task write and kept
                        // fresh while she is adjacent to the task point, below).
                        boolean taskLive = lastTaskWriteMs != 0 && now - lastTaskWriteMs < TASK_IDLE_MS;

                        // Fix cycle 3: while idle at the ring, point her BODY at the player.
                        // The Watch only drives the head (clamped +/-45 deg) and the Seek
                        // clears its yaw inside StopDistance, so nothing else faces her.
                        // Cycle 12: a stayed Laya still watches the player, from farther away.
                        if (!taskLive && pendingMoveTo == null && (following || staying) && !motionLocked) {
                            facePlayerWhenIdle(p, l, dist,
                                following ? FOLLOW_DISTANCE + 1.5 : 16.0);
                        }

                        // Diagnostic (fix cycle 2/3): one line per 5 s so the plugin log proves
                        // the settled stand-off and whether the head is actually offset from the
                        // body, instead of guessing from screenshots.
                        if (following && now - lastFollowDiagMs >= 5000) {
                            lastFollowDiagMs = now;
                            double diagStandoff = Math.min(FOLLOW_DISTANCE, Math.max(0.1, dist - 0.15));
                            TransformComponent lt = worldStore.getComponent(layaRef, TransformComponent.getComponentType());
                            HeadRotation hr = worldStore.getComponent(layaRef, HeadRotation.getComponentType());
                            float bodyYaw = lt != null ? lt.getRotation().yaw() : 0f;
                            float headYaw = hr != null ? hr.getRotation().yaw() : bodyYaw;
                            // Cycle 10: coordinates + task distance, so a "can't reach it"
                            // proves whether she moved and how far the block is.
                            String taskDiag = "";
                            Vector3d tPos = null;
                            if (taskLive && taskSlot >= 0) {
                                Vector3d tp = readSlot(taskSlot);
                                if (tp != null && Math.abs(tp.x) < 1e6) {
                                    tPos = tp;
                                    double tdx = tp.x - l.x, tdy = tp.y - l.y, tdz = tp.z - l.z;
                                    taskDiag = String.format(" task=(%.0f,%.0f,%.0f) tdist=%.1f",
                                        tp.x, tp.y, tp.z, Math.sqrt(tdx*tdx + tdy*tdy + tdz*tdz));
                                }
                            }
                            plugin.log(String.format(
                                "Follow diag: dist=%.2f standoff=%.2f taskLive=%s bodyDeg=%.0f headOffsetDeg=%.0f laya=(%.1f,%.1f,%.1f)%s",
                                dist, diagStandoff, taskLive,
                                Math.toDegrees(bodyYaw),
                                Math.toDegrees(PhysicsMath.normalizeTurnAngle(headYaw - bodyYaw)),
                                l.x, l.y, l.z, taskDiag));
                        }

                        // --- follow stance: player-anchored deadband ---
                        // The stand-off ring point is recomputed ONLY when the PLAYER
                        // has moved past FOLLOW_START_BUFFER away from followAnchor
                        // (or when the anchor was reset: bootstrap, stay-clear, leash
                        // teleport, move_to). Triggering off Laya's own distance made
                        // her re-target every tick she moved and circle the player.
                        if (following && pendingMoveTo == null && !taskLive && !motionLocked) {
                            // fix (c): this block is reached exactly when no task is live, i.e.
                            // the leash is NOT deferring — re-arm the once-per-episode defer log.
                            leashDeferredLogged = false;
                            boolean reanchor = followAnchor == null;
                            if (!reanchor) {
                                double adx = p.x - followAnchor.x;
                                double adz = p.z - followAnchor.z;
                                reanchor = Math.sqrt(adx * adx + adz * adz) > FOLLOW_START_BUFFER;
                            }
                            if (reanchor) {
                                // Point on the stand-off ring, on LAYA'S side of the player.
                                // FIX CYCLE 2: this used (player - Laya), the unit vector pointing
                                // AWAY from her, so the ring point landed FOLLOW_DISTANCE BEYOND the
                                // player. Seek's StopDistance 1.5 then left her ~0.3 blocks in front
                                // of him, forever walking into his capsule ("runs into me every time
                                // I stop"); at that range the yaw geometry degenerates (dx,dz -> 0)
                                // and the Watch clamp cannot resolve, so her head never turned.
                                // Point player -> Laya instead.
                                double ux = dist > 0.001 ? -dx / dist : 1.0;
                                double uz = dist > 0.001 ? -dz / dist : 0.0;
                                // Never aim behind her feet when the player is already inside the
                                // ring: cap the stand-off just in front of her. Inside Seek's 1.5
                                // stop distance the pursue force clears its yaw (SteeringForcePursue
                                // computes output.clear()), which frees the Watch to blend her body
                                // and head onto the player instead of her walking backwards.
                                double standoff = Math.min(FOLLOW_DISTANCE, Math.max(0.1, dist - 0.15));
                                Vector3d point = new Vector3d(
                                    p.x + ux * standoff, p.y, p.z + uz * standoff);
                                boolean moved = lastWrittenSlot == null
                                    || lastWrittenSlot.distance(point) > SLOT_REWRITE_MOVE;
                                if (writeFollowSlot(point)) {
                                    lastWrittenSlot = point;
                                    activeTarget = true;
                                    followAnchor = new Vector3d(p);   // anchor on the player's position
                                    if (moved) {
                                        refreshPath();
                                        stuckResets = 0;
                                        stuckGaveUp = false;
                                    }
                                    if (!slotReadbackLogged) {
                                        slotReadbackLogged = true;
                                        Vector3d rb = readFollowSlot();
                                        plugin.log("Slot write readback (index " + followSlot + "): "
                                            + (rb == null ? "NULL — element missing!" : rb.toString()));
                                    }
                                }
                            }

                            // --- idle shuffle: small random step so she is never frozen while idle ---
                            // Only after the timer is armed: the first evaluation of a fresh
                            // session (nextIdleShuffleMs == 0) arms it 20-40 s out instead of
                            // shuffling immediately, so a spawn never counts as an idle shuffle.
                            if (nextIdleShuffleMs == 0) {
                                nextIdleShuffleMs = now + 20_000L + ThreadLocalRandom.current().nextLong(20_000L);
                            } else if (now >= nextIdleShuffleMs && lastTaskWriteMs == 0) {
                                // Fix cycle 1 (head focus): a random-direction shuffle left
                                // her facing away from the player ~2/3 of the time, and the
                                // head Watch can only cover +/-45 deg relative to the body.
                                // Step TOWARD the player (small jitter keeps it alive) so the
                                // walk ends with her body - and eyes - on the player.
                                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                                double step = Math.min(2.0, dist - 1.5);   // never target past the player
                                if (step < 1.6 && dist > 2.2) step = 1.6;  // keep the liveliness tick even at the stand-off
                                Vector3d shufflePoint = null;
                                if (step >= 1.6) {
                                    double theta = Math.atan2(dz, dx)
                                        + (rnd.nextDouble() * 2.0 - 1.0) * Math.toRadians(25.0);
                                    shufflePoint = new Vector3d(
                                        l.x + Math.cos(theta) * step, l.y, l.z + Math.sin(theta) * step);
                                }
                                boolean shuffled = shufflePoint != null && writeFollowSlot(shufflePoint);
                                nextIdleShuffleMs = now + 20_000L + rnd.nextLong(20_000L); // 20-40 s apart
                                if (shuffled) {
                                    lastWrittenSlot = shufflePoint;
                                    activeTarget = true;
                                    refreshPath();
                                    plugin.log("Idle shuffle target (" + String.format("%.1f", shufflePoint.x)
                                        + ", " + String.format("%.1f", shufflePoint.z) + ")");
                                }
                            }
                        }

                        // --- watchdog 1: leash teleport (>25 blocks, follow stance only — a "stay" must never be leashed) ---
                        if (following && dist > LEASH_TELEPORT_DISTANCE && !motionLocked) {
                            if (taskLive) {
                                // Task ownership replaces follow: she may legitimately be far
                                // from the player while walking to the block. Log once per
                                // defer episode (fix c). R-T4-4: defer falls through —
                                // task phases must heartbeat/abandon, so NO return here.
                                if (!leashDeferredLogged) {
                                    leashDeferredLogged = true;
                                    plugin.log("Task active — leash deferred");
                                }
                            } else {
                                leashDeferredLogged = false;   // fix (c): non-defer path ran
                                layaT.teleportPosition(new Vector3d(p.x + 1.5, p.y, p.z + 1.5));
                                plugin.log("Leash watchdog: Laya teleported (" + String.format("%.1f", dist) + " blocks away)");
                                lastProgressMs = now;
                                followAnchor = null;   // player re-anchors after the catch-up teleport
                                stuckResets = 0;
                                stuckGaveUp = false;
                                lastX = l.x;
                                lastZ = l.z;
                                return;
                            }
                        }

                        // --- task ownership: adjacency heartbeat + abandonment (R-T4-1) ---
                        // The task slot holds the block we were sent to. While Laya is
                        // within TASK_ADJACENT we refresh the timer — ONLY then. Mere
                        // liveness must NOT refresh it, otherwise a task abandoned
                        // halfway (she never arrives, never re-targeted) would never
                        // time out. 10 minutes of neither adjacency nor a re-target
                        // => the task is dropped and the bridge is told.
                        if (taskSlot >= 0) {
                            Vector3d taskPoint = readSlot(taskSlot);
                            // Only a LIVE task (a task write has happened and not been
                            // cleared) may heartbeat — an unwritten slot must never look
                            // "adjacent" and suppress the follow ring.
                            boolean taskLiveNow = lastTaskWriteMs != 0;
                            if (taskLiveNow && taskPoint != null && Math.abs(taskPoint.x) < 1e6) {
                                double tdx = taskPoint.x - l.x;
                                double tdz = taskPoint.z - l.z;
                                boolean adjacent = Math.sqrt(tdx * tdx + tdz * tdz) <= TASK_ADJACENT;
                                taskAdjacent = adjacent;
                                if (adjacent) {
                                    lastTaskWriteMs = now;   // heartbeat while at the block
                                }
                                if (now - lastTaskWriteMs > TASK_IDLE_MS) {
                                    clearTask("task_failed: wandered off");
                                }
                            } else {
                                taskAdjacent = false;
                            }
                        }

                        // --- Task 6: task executor state machine (world-thread) ---
                        // Wraps Task 4's adjacency result: she reaches the block -> WORKING.
                        // Reach timeout only applies while WALKING (never arrived). DONE
                        // settles TASK_SETTLE_MS so the role's DroppedItem pickup can run.
                        if ("WALKING".equals(taskPhase)) {
                            if (isTaskAdjacent()) {
                                boolean woodTask = AXE_HINTS.contains(
                                    taskHint == null ? "" : taskHint.toLowerCase());
                                if (woodTask) {
                                    // Cycle 11: 3 animation swings at the stump, then
                                    // the connected tree is removed in one go.
                                    taskPhase = "SWINGING";
                                    taskSwingsLeft = TASK_SWING_COUNT;
                                    nextSwingMs = now;
                                    phaseSinceMs = now;
                                    plugin.log("Task SWINGING: " + TASK_SWING_COUNT
                                        + " swings (" + swingAnimationId() + ") at the stump @"
                                        + taskSnapX + "," + taskSnapY + "," + taskSnapZ);
                                } else {
                                    taskPhase = "WORKING";
                                    phaseSinceMs = now;
                                    taskHitCount = 0;
                                    // Freeze the chain's inputs (gen + interaction + coords) ONCE
                                    // (I-1/I-2): the callbacks carry these finals, never re-reading
                                    // the shared mutable task* fields.
                                    final long gen = taskChainGen;
                                    final String iid = taskSnapIid;
                                    final int sx = taskSnapX, sy = taskSnapY, sz = taskSnapZ;
                                    if (taskWorking.compareAndSet(false, true)) {
                                        plugin.log("Task WORKING started (interaction=" + iid
                                            + " target @" + sx + "," + sy + "," + sz
                                            + " block=" + taskSnapBlockId + " gen=" + gen + ")");
                                        taskHit(gen, iid, 1, sx, sy, sz, false);
                                    }
                                }
                            } else if (taskUnsticks < TASK_MAX_UNSTICKS
                                && now - phaseSinceMs > TASK_UNSTICK_MS) {
                                // Cycle 10: the engine Seek is a straight-line mover; the
                                // player standing between her and the block (or a tree/step)
                                // left her walking in place until the 60s timeout. Watchdog
                                // placement beside the block, then adjacency runs.
                                // Cycle 13: place at the BLOCK's level, not the player's -
                                // p.y dropped her into the canopy of a tree below a ledge and
                                // the engine resolved her onto its top, still out of reach.
                                taskUnsticks++;
                                // Cycle 13: adjacency and the role's task slot both use the
                                // block's integer corner (taskSnapX/Z). A radial offset from
                                // the centre measured 1.75 from that corner (outside
                                // TASK_ADJACENT 1.6), and a radial offset from the corner can
                                // land inside the trunk. Place one axis out instead: 0.4 clear
                                // of the face, ~1.49 from the corner.
                                double cx = taskSnapX + 0.5, cz = taskSnapZ + 0.5;
                                double ox = p.x - cx, oz = p.z - cz;
                                if (Math.abs(ox) < 1e-3 && Math.abs(oz) < 1e-3) {
                                    ox = l.x - cx; oz = l.z - cz;
                                }
                                if (Math.abs(ox) < 1e-3 && Math.abs(oz) < 1e-3) { ox = 1.0; oz = 0.0; }
                                double spotX, spotZ;
                                if (Math.abs(ox) >= Math.abs(oz)) {
                                    spotX = cx + Math.signum(ox) * 0.9;
                                    spotZ = cz;
                                } else {
                                    spotX = cx;
                                    spotZ = cz + Math.signum(oz) * 0.9;
                                }
                                Vector3d spot = new Vector3d(spotX, taskSnapY, spotZ);
                                layaT.teleportPosition(spot);
                                refreshPath();
                                plugin.log("Task unstick " + taskUnsticks + "/" + TASK_MAX_UNSTICKS
                                    + ": teleported beside target @" + taskSnapX + ","
                                    + taskSnapY + "," + taskSnapZ + " after no progress");
                                phaseSinceMs = now;   // fresh window for the adjacency check
                            } else if (taskUnsticks >= TASK_MAX_UNSTICKS
                                && now - phaseSinceMs > TASK_GIVE_UP_MS) {
                                clearTask("task_failed: can't reach it");
                            } else if (now - phaseSinceMs > TASK_REACH_TIMEOUT_MS) {
                                clearTask("task_failed: can't reach it");
                            }
                        } else if ("SWINGING".equals(taskPhase)) {
                            // Cycle 11: the visible "couple of swings" before the fall.
                            if (now >= nextSwingMs) {
                                if (taskSwingsLeft > 0) {
                                    playSwing();
                                    taskSwingsLeft--;
                                    plugin.log("Task swing " + (TASK_SWING_COUNT - taskSwingsLeft)
                                        + "/" + TASK_SWING_COUNT);
                                    nextSwingMs = now + TASK_FELL_SWING_MS;
                                } else {
                                    // Hold one animation length after the last swing so the
                                    // Chop motion finishes before the tree comes down.
                                    fellTree(taskSnapX, taskSnapY, taskSnapZ);
                                    stopSwing();
                                    taskPhase = "DONE";
                                    phaseSinceMs = now;
                                    plugin.log("Task DONE - tree felled @" + taskSnapX + ","
                                        + taskSnapY + "," + taskSnapZ + "; settling "
                                        + TASK_SETTLE_MS + "ms for pickup");
                                }
                            }
                        } else if ("DONE".equals(taskPhase) && now - phaseSinceMs >= TASK_SETTLE_MS) {
                            finishTaskSuccess();
                        }

                        // Live intent: a real (non-sentinel) target farther than StopDistance+margin.
                        // (The Phase-1 test run teleported in a loop because this used to be
                        // gated only on the stance flag while the slot was never written.)
                        double slotDist = -1.0;
                        if (activeTarget && followSlot >= 0) {
                            Vector3d sp = readFollowSlot();
                            if (sp != null && Math.abs(sp.x) < 1e6 && Math.abs(sp.y) < 1e6) {
                                double sdx = sp.x - l.x;
                                double sdz = sp.z - l.z;
                                slotDist = Math.sqrt(sdx * sdx + sdz * sdz);
                            }
                        }

                        // --- watchdog 2: stuck recovery (bounded: <=2 resets, then stay quiet) ---
                        // Follow-ring recovery is follow-phased; task phases own their own
                        // recovery (reach-timeout + the abandonment check above). Gating on
                        // taskLive also stops the stale follow point yanking her off the block.
                        if (!taskLive && slotDist > STUCK_MIN_SLOT_DISTANCE && !motionLocked) {
                            boolean moved = Math.abs(l.x - lastX) > STUCK_EPSILON
                                || Math.abs(l.z - lastZ) > STUCK_EPSILON;
                            if (moved) {
                                lastProgressMs = now;
                                stuckResets = 0;
                                stuckGaveUp = false;
                            } else if (!stuckGaveUp && now - lastProgressMs > STUCK_MS) {
                                layaT.teleportPosition(new Vector3d(p.x + 1.5, p.y, p.z + 1.5));
                                refreshPath();
                                stuckResets++;
                                plugin.log("Stuck watchdog: teleported to player side (no progress >"
                                    + STUCK_MS + "ms, reset " + stuckResets + "/2, slotDist="
                                    + String.format("%.1f", slotDist) + ")");
                                if (stuckResets >= 2) {
                                    stuckGaveUp = true;
                                    plugin.log("Stuck watchdog: 2 resets without movement — suspect engine Seek not driving her"
                                        + " (bind log line above has the slot index); staying quiet, slot writes continue");
                                }
                                lastProgressMs = now;
                            }
                        } else {
                            lastProgressMs = now;
                        }
                        lastX = l.x;
                        lastZ = l.z;
                    } catch (Throwable t) {
                        plugin.log("Follow tick error: " + t);
                    }
                });
            } catch (Throwable t) {
                plugin.log("Follow schedule error: " + t);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public boolean isSpawned() {
        return layaRef != null;
    }

    /** Task 7 hook: true while Laya stands within TASK_ADJACENT of a live task point. */
    public boolean isTaskAdjacent() {
        return taskAdjacent;
    }

    /** Unstick: teleport Laya to the player's side (exposed as POST /tp on the plugin bridge). */
    public void teleportLayaToPlayer() {
        if (world == null || layaRef == null || worldStore == null || playerRef == null) {
            plugin.log("teleportLayaToPlayer: not ready (spawned=" + (layaRef != null) + ", world=" + (world != null) + ")");
            return;
        }
        world.execute(() -> {
            try {
                TransformComponent playerT =
                    worldStore.getComponent(playerRef.getReference(), TransformComponent.getComponentType());
                TransformComponent layaT =
                    worldStore.getComponent(layaRef, TransformComponent.getComponentType());
                if (playerT == null || layaT == null) {
                    plugin.log("teleportLayaToPlayer: transform missing");
                    return;
                }
                Vector3d p = new Vector3d(playerT.getPosition()).add(1.5, 0.0, 1.5);
                layaT.teleportPosition(p);
                plugin.log("Laya teleported to player at " + p);
            } catch (Throwable t) {
                plugin.log("teleportLayaToPlayer failed: " + t);
            }
        });
    }

    public String getLayaEntityId() {
        return layaEntityId;
    }

    // --- Task 7: gifts ("gift_hand") ---

    /**
     * Task 7 gift entry (bridge HTTP thread). Readiness check here, then the whole
     * transfer is handed to the world thread — the same shape as
     * {@link #teleportLayaToPlayer()}. No target fields: the gift is whatever the
     * PLAYER is holding right now.
     *
     * Scope ruling R-T7-1: {@code gift_hand} only. The custom-UI / barter screen
     * (drag-menu) is deferred; the drop-gift path already ships via the role's
     * unconditional PickUpItem block in Laya.json.
     */
    private void handleGiftHand() {
        if (world == null || worldStore == null || playerRef == null || layaRef == null) {
            plugin.log("Gift ignored — not ready (world=" + (world != null)
                + ", store=" + (worldStore != null) + ", player=" + (playerRef != null)
                + ", laya=" + (layaRef != null) + ")");
            return;
        }
        world.execute(this::giftHeldItem);
    }

    /**
     * World-thread: move the player's held item into Laya's inventory.
     *
     * <p>Inventory pin (javap, local 0.7.2 HytaleServer.jar):
     * <ul>
     *   <li>{@code Player extends LivingEntity}; {@code LivingEntity.getInventory(): Inventory}.
     *       Both the player and the NPC legacy {@code Inventory} views are wired to the live
     *       {@code InventoryComponent} sections by the engine itself:
     *       {@code PlayerSystems$PlayerInitSystem.onEntityAdded} and
     *       {@code NPCSystems$OnNPCAdded.onEntityAdded} both call
     *       {@code Inventory.backwardsCompatHook(Holder)}, which copies the section
     *       components into the legacy {@code Inventory} fields — so these containers are
     *       the SAME objects the entity really uses (not a shadow copy).</li>
     *   <li>Held slot: {@code Inventory.getItemInHand()} returns the TOOLS item while
     *       {@code usingToolsItem()} is true, otherwise the active hotbar item. This method
     *       mirrors that exactly: {@code usingToolsItem() ? {getTools(), getActiveToolsSlot()}
     *       : {getHotbar(), getActiveHotbarSlot()}}.</li>
     *   <li>Transfer: {@code ItemContainer.moveItemStackFromSlot(short slot, int qty,
     *       ItemContainer other): MoveTransaction<ItemStackTransaction>} is a SINGLE
     *       writeAction that removes from the source slot and adds to the destination,
     *       returning BOTH sides in one transaction. Its bytecode
     *       ({@code lambda$internal_moveItemStackFromSlot$3}) puts any add-remainder BACK
     *       into the source slot, so a full destination cannot destroy the item — no dupe
     *       (unlike add-first) and no silent loss (unlike remove-first). This is the paired
     *       operation the Task 7 brief prefers.</li>
     * </ul>
     *
     * <p>Truthfulness (RF4): the "gifted" count is never assumed — it is derived from what
     * the source slot actually still holds after the call. Empty hand and a destination
     * that takes nothing both log a refusal and post NO {@code gave} event.
     */
    private void giftHeldItem() {
        final String id;
        final int qty;
        final ItemContainer src;
        final short slot;
        final ItemContainer dest;
        try {
            if (npcEntity == null) {
                plugin.log("Gift: Laya has no NPC entity yet");
                return;
            }
            Ref<EntityStore> playerRefNow = playerRef.getReference();
            if (playerRefNow == null) {
                plugin.log("Gift: player reference missing");
                return;
            }
            Player player = worldStore.getComponent(playerRefNow, Player.getComponentType());
            if (player == null) {
                plugin.log("Gift: player entity component missing");
                return;
            }
            Inventory pinv = player.getInventory();
            if (pinv == null) {
                plugin.log("Gift: nothing in hand (player inventory unavailable)");
                return;
            }

            // Mirror Inventory.getItemInHand(): tools when the tools item is in use,
            // otherwise the active hotbar slot.
            boolean usingTools = false;
            try {
                usingTools = pinv.usingToolsItem();
            } catch (Throwable ignored) {
                // fall through to the hotbar branch
            }
            src = usingTools ? pinv.getTools() : pinv.getHotbar();
            int slotIdx = usingTools ? pinv.getActiveToolsSlot() : pinv.getActiveHotbarSlot();
            if (src == null || slotIdx < 0) {
                plugin.log("Gift: nothing in hand");
                return;
            }
            slot = (short) slotIdx;

            ItemStack held = src.getItemStack(slot);
            if (held == null || ItemStack.isEmpty(held) || held.getItemId() == null) {
                plugin.log("Gift: nothing in hand");
                return;
            }
            id = held.getItemId();
            qty = held.getQuantity();

            Inventory ninv = npcEntity.getInventory();
            if (ninv == null) {
                plugin.log("Gift: nothing moved — Laya has no inventory (" + id + " x" + qty + " stays with the player)");
                return;
            }
            dest = ninv.getCombinedHotbarFirst();
            if (dest == null) {
                plugin.log("Gift: nothing moved — Laya has no inventory (" + id + " x" + qty + " stays with the player)");
                return;
            }
        } catch (Throwable t) {
            plugin.log("Gift failed before transfer: " + t);
            return;
        }

        MoveTransaction<ItemStackTransaction> mv;
        try {
            // The engine's paired remove-from-player + add-to-Laya operation.
            mv = src.moveItemStackFromSlot(slot, qty, dest);
        } catch (Throwable t) {
            // RF4 successor-failure honesty: if the remove already happened, say so
            // loudly instead of implying nothing changed.
            int afterQty = giftSlotQuantity(src, slot);
            if (afterQty < qty) {
                plugin.log("Gift FAILED after removing " + id + " x" + (qty - afterQty)
                    + " — item may be lost (" + t + ")");
            } else {
                plugin.log("Gift failed, item stays in hand: " + t);
            }
            return;
        }

        // Honest accounting: the operation may return part to the source slot, so the
        // truth is what the source slot holds now.
        int moved = qty - giftSlotQuantity(src, slot);
        if (mv != null && mv.succeeded() && moved >= qty) {
            int destSlot = giftDestSlot(mv);
            equipGiftInHand(destSlot, id);
            plugin.log("Gifted " + id + " x" + moved + " to Laya (slot "
                + (destSlot >= 0 ? String.valueOf(destSlot) : "unknown") + ")");
            saveInventory();   // Cycle 6: a gift must survive the next launch
            postEvent("gave", id + " x" + moved);
            return;
        }
        if (moved <= 0) {
            plugin.log("Gift: nothing moved — Laya couldn't take " + id + " x" + qty
                + " (item stays with the player)");
            return;
        }
        // Partial: be explicit about what actually crossed over.
        plugin.log("Gift partial: " + id + " x" + moved + " of x" + qty
            + " to Laya (slot " + (mv != null && mv.succeeded() ? giftDestSlot(mv) : "unknown")
            + "); the rest stays with the player");
        postEvent("gave", id + " x" + moved);
    }

    /** Quantity in a container slot, 0 for empty/missing (never throws). */
    private static int giftSlotQuantity(ItemContainer container, short slot) {
        try {
            ItemStack s = container.getItemStack(slot);
            return (s == null || ItemStack.isEmpty(s)) ? 0 : s.getQuantity();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Destination slot of a completed move transaction, or -1 when unavailable. */
    private static int giftDestSlot(MoveTransaction<ItemStackTransaction> mv) {
        try {
            ItemStackTransaction add = mv.getAddTransaction();
            if (add != null && add.getSlotTransactions() != null && !add.getSlotTransactions().isEmpty()) {
                return add.getSlotTransactions().get(add.getSlotTransactions().size() - 1).getSlot();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return -1;
    }

    /**
     * Fix cycle 1 (visible wield): a gift lands in the hotbar, but an NPC never selects
     * it, so the Player rig renders empty hands (and the pickaxe "Mine" animation has no
     * item to come from). If the destination slot is in the hotbar, select it.
     * Verified API (javap 0.7.2): Inventory.setActiveHotbarSlot(Ref, byte, accessor).
     * Never throws.
     */
    private void equipGiftInHand(int destSlot, String id) {
        try {
            Ref<EntityStore> layaRefNow = layaRef;
            if (layaRefNow == null || destSlot < 0 || npcEntity == null) return;
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return;
            ItemContainer hotbar = inv.getHotbar();
            if (hotbar == null || destSlot >= hotbar.getCapacity()) return; // storage slot: leave it
            inv.setActiveHotbarSlot(layaRefNow, (byte) destSlot, worldStore);
            plugin.log("Wield: active hotbar slot " + destSlot + " selected (" + id + ")");
        } catch (Throwable t) {
            plugin.log("Wield: could not select slot " + destSlot + " (" + t + ")");
        }
    }

    /**
     * Apply an AI command JSON from the bridge (say/action/target_x/target_z).
     * Stance mapping (Java owns the *where*, the engine owns the *how*):
     *   follow  -> arm slot writes (tick writes the stand-off point)
     *   stay    -> one-shot clear of the slot; the loop itself never stops
     *   move_to -> one-shot write of the destination point
     *   attack  -> follow stance (targeting arrives in Phase 3)
     */
    /** Show one of Laya's lines in the player's chat (event reactions used to be log-only). */
    public void sayToPlayer(String text) {
        if (text == null || text.isEmpty()) return;
        plugin.log("Laya says: " + text);
        try {
            // Same delivery ChatListener uses: the PlayerRef component owns the chat feed.
            if (playerRef != null) {
                playerRef.sendMessage(Message.raw("Laya: " + text));
            }
        } catch (Throwable t) {
            plugin.log("Laya: could not deliver chat line: " + t);
        }
    }

    public void applyCommand(String json) {
        applyCommand(json, false);
    }

    /**
     * @param deliverSay true when this command came from the bridge (event reaction,
     *                   voice reply) and nobody has shown it to the player yet. The
     *                   typed-chat path (ChatListener) already sends the line itself,
     *                   so it passes false and nothing is said twice.
     */
    /** Chat request started/ended: show dots above her head and tilt her head up. */
    public void setThinking(boolean on) {
        if (world == null) return;
        try {
            world.execute(() -> applyThinking(on));
        } catch (Throwable ignored) {
        }
    }

    private void applyThinking(boolean on) {
        try {
            if (layaRef == null || worldStore == null) return;
            if (on && !thinking) {
                Nameplate np = worldStore.getComponent(layaRef, Nameplate.getComponentType());
                if (np != null) {
                    savedNameplate = np.getText();
                    np.setText("Laya  ...");
                }
                HeadRotation hr = worldStore.getComponent(layaRef, HeadRotation.getComponentType());
                if (hr != null) {
                    savedHeadPitch = hr.getRotation().pitch();
                    hr.getRotation().setPitch((float) Math.toRadians(-25.0));
                }
            } else if (!on && thinking) {
                Nameplate np = worldStore.getComponent(layaRef, Nameplate.getComponentType());
                if (np != null && savedNameplate != null) np.setText(savedNameplate);
                HeadRotation hr = worldStore.getComponent(layaRef, HeadRotation.getComponentType());
                if (hr != null && !Float.isNaN(savedHeadPitch)) hr.getRotation().setPitch(savedHeadPitch);
                savedNameplate = null;
                savedHeadPitch = Float.NaN;
            }
            thinking = on;
        } catch (Throwable t) {
            plugin.log("Thinking indicator failed: " + t);
        }
    }

    /** Voice helper audio playback started/ended: hold the Talk face. */
    public void setTalking(boolean on) {
        if (world == null) return;
        try {
            world.execute(() -> applyTalking(on));
        } catch (Throwable ignored) {
        }
    }

    private void applyTalking(boolean on) {
        try {
            if (layaRef == null || worldStore == null || playerRef == null) return;
            if (on) {
                // The Player model does not bind Talk on the Face slot. Item
                // animations are resolved globally and can carry ThirdPersonFace.
                AnimationUtils.playAnimation(layaRef, AnimationSlot.Action,
                    "LayaTalk", "Talk", true, worldStore);
            } else {
                AnimationUtils.stopAnimation(layaRef, AnimationSlot.Action, worldStore);
                sendFaceStop();
            }
            talking = on;
        } catch (Throwable t) {
            plugin.log("Talk face failed: " + t);
        }
    }

    /** Direct Face-slot stop; the client accepts null even though it rejects Talk. */
    private void sendFaceStop() {
        try {
            if (layaRef == null || worldStore == null || playerRef == null) return;
            NetworkId nid = worldStore.getComponent(layaRef, NetworkId.getComponentType());
            if (nid == null) return;
            playerRef.getPacketHandler().write(
                new PlayAnimation(nid.getId(), null, null, AnimationSlot.Face));
        } catch (Throwable ignored) {
        }
    }

    /** Once a second while quiet, clear any residual face animation (mouth reset). */
    private void clearStuckFace(long now) {
        if (talking) return;
        if (now < nextFaceClearMs) return;
        nextFaceClearMs = now + 1000L;
        sendFaceStop();
    }

    public void applyCommand(String json, boolean deliverSay) {
        if (!running) {
            return;
        }
        String talk = extractJsonField(json, "talk");
        if (talk != null) {
            if ("start".equalsIgnoreCase(talk)) setTalking(true);
            else if ("stop".equalsIgnoreCase(talk) || "end".equalsIgnoreCase(talk)) setTalking(false);
        }
        String say = extractJsonField(json, "say");
        String action = extractJsonField(json, "action");
        String task = extractJsonField(json, "task");
        String targetHint = extractJsonField(json, "target_hint");
        // Cycle 5: "count" = how many matching blocks to work through.
        // 0 / missing -> the bridge's "all" spelling is 0; missing -> 1.
        Integer countVal = extractJsonInt(json, "count");
        int count = (countVal == null || countVal < 0) ? 1 : countVal;
        String scope = extractJsonField(json, "scope");
        Integer radiusVal = extractJsonInt(json, "radius");
        int radius = (radiusVal == null || radiusVal < 0) ? 0 : radiusVal;

        if (say != null && !say.isEmpty()) {
            if (deliverSay) {
                sayToPlayer(say);
            } else {
                plugin.log("Laya says: " + say);
            }
        }

        // Task action ("task": identify|mine|gather, optional "target_hint"): the scan
        // runs inside world.execute and reports back to the bridge as an event. Tasks
        // are not exclusive — a task never blocks the stance lines below.
        // "null"/"none"/"" are the bridge's "no task" spellings (a JSON null arrives
        // here as the literal string "null" through extractJsonField).
        if (task != null && !task.isEmpty()
            && !"none".equalsIgnoreCase(task) && !"null".equalsIgnoreCase(task)) {
            handleTask(task, targetHint, count, scope, radius);
        }

        if ("stop_task".equalsIgnoreCase(action)) {
            // Player said "stop mining" / "that's enough" / "never mind": cancel the
            // task AND any live fight, but KEEP following (a stop-task is not a stay).
            combat.requestCancel();
            handleStopTask();
        } else if ("follow".equalsIgnoreCase(action)) {
            following = true;
            staying = false;
            state.put("mode", "follow");
            plugin.log("Laya: following player (follow=true)");
            combat.setStance("follow", "command");
            combat.requestCancel();
        } else if ("stay".equalsIgnoreCase(action)) {
            // NOTE: stay does NOT stop the tick loop — it only clears the follow
            // point once. The loop (and watchdogs) keep running; the next
            // "follow" re-arms slot writes instantly. (Killing the loop on stay
            // was the original freeze bug.)
            handleStopTask();
            following = false;
            staying = true;
            pendingStayClear = true;
            state.put("mode", "stay");
            plugin.log("Laya: hold note received (follow target cleared; loop keeps running)");
            combat.setStance("stay", "command");
            combat.requestCancel();
        } else if ("attack".equalsIgnoreCase(action)) {
            following = true;
            staying = false;
            state.put("mode", "attack");
            plugin.log("Laya: attack mode");
            combat.setStance("attack", "explicit");
            combat.setAttackHint(targetHint);
        } else if ("defend".equalsIgnoreCase(action)) {
            // Task 3: guard stance — stays close, fights only reactively (spec section 2).
            following = true;
            staying = false;
            state.put("mode", "defend");
            plugin.log("Laya: defend mode");
            combat.setStance("defend", "explicit");
            combat.requestCancel();
        } else if ("probe_break".equalsIgnoreCase(action)) {
            // Task 5 dev verb: timed S1 break probe at the first scanned block near Laya.
            // LOG-ONLY (no npc_event), no task/stance/movement state is touched.
            handleProbeBreak();
        } else if ("probe_use".equalsIgnoreCase(action)) {
            // Task 2 dev verb: arm the 60 s held-interact window. LOG-ONLY
            // (no npc_event); no task/stance/movement state is touched.
            startUseProbe();
        } else if ("gift_hand".equalsIgnoreCase(action)) {
            // Task 7 gift: hand what the PLAYER currently holds to Laya.
            // R-T7-1: gift_hand only — no barter UI / InteractionInstruction (deferred T13).
            // No target fields: the gift is whatever is in the player's hand right now.
            // Readiness check runs HERE (bridge thread); the transfer runs inside
            // world.execute (world thread), mirroring teleportLayaToPlayer.
            handleGiftHand();
        } else if ("set_appearance".equalsIgnoreCase(action)) {
            handleAppearance(json);
        } else if ("move_to".equalsIgnoreCase(action)) {
            // Task 7 (spec section 6, no movement while downed): refuse a move_to while she
            // is down. No state is touched and nothing is stashed, so no follow-slot write,
            // repath or `Follow target set to` line can fire from this command.
            if (combat.isDowned()) {
                plugin.log("Combat: move_to refused (downed)");
                return;
            }
            Double tx = extractJsonDouble(json, "target_x");
            Double tz = extractJsonDouble(json, "target_z");
            staying = false;
            state.put("mode", "move_to");
            if (tx != null) state.put("target_x", tx);
            if (tz != null) state.put("target_z", tz);
            if (tx != null && tz != null) {
                pendingMoveTo = tx + "," + tz;   // consumed on the world thread in the tick
                following = false;
                plugin.log("Laya: moving to (" + tx + ", " + tz + ") via engine walk");
            } else {
                plugin.log("Laya: move_to missing coordinates");
            }
        }
    }

    // --- Task actions: identify | mine | gather ---

    /**
     * "task" branch entry point (called from the bridge HTTP thread).
     * Validates readiness, loads config/blocks.json once, then schedules the whole
     * world scan on the world thread — the same pattern as teleportLayaToPlayer.
     * The world-thread body logs and posts an event back to the ai-bridge.
     */
    private void handleTask(String task, String hint, int count) {
        handleTask(task, hint, count, null, 0);
    }

    private void handleTask(String task, String hint, int count, String scope) {
        handleTask(task, hint, count, scope, 0);
    }

    private void handleTask(String task, String hint, int count, String scope, int radius) {
        final String t = task.trim().toLowerCase();
        final String rawHint = hint == null ? "" : hint.trim().toLowerCase();
        // Cycle 5: "just get 1" carries no hint word; reuse the previous hint
        // instead of failing with "unknown target hint ''".
        String chosenHint = rawHint.isEmpty()
            ? (taskHint == null ? "" : taskHint)
            : rawHint;
        if (WOOD_SPECIES.contains(chosenHint)) chosenHint = "wood";
        final String h = chosenHint;
        final int requested = count <= 0 ? 0 : Math.min(count, 64); // 0 = all in range
        if (world == null || worldStore == null || playerRef == null) {
            plugin.log("Task '" + t + "' ignored — not ready (world=" + (world != null)
                + ", player=" + (playerRef != null) + ")");
            postEvent("task_failed", t + ": not ready (no world/player yet)");
            return;
        }
        // Task 7 (ruling 2): no new tasks while she is down — the downed state owns her.
        // No task state is touched and NO event is posted (spec: no extra events).
        if (combat.isDowned()) {
            plugin.log("Combat: task refused (downed)");
            return;
        }
        ensureBlocksLoaded();
        // Cycle 5: repeat state is set HERE (bridge thread) and read on the world
        // thread. A new task supersedes the previous counters.
        taskTargetCount = requested;
        taskDoneCount = 0;
        taskHint = h;
        taskScope = "area".equalsIgnoreCase(scope) ? "area" : "tree";
        taskRadius = radius > 0 ? Math.min(radius, 32) : 0;
        taskAreaSet = false;     // a new area task anchors to its own first scan
        taskAnchorSet = false;   // cycle 10b: a new task re-pins to its own first block
        taskFelled = false;      // cycle 11: fresh fell latch per task
        taskSwingsLeft = 0;
        plugin.log("Task received: " + t + (h.isEmpty() ? "" : " hint=" + h)
            + " count=" + (requested == 0 ? "all" : String.valueOf(requested))
            + " scope=" + taskScope
            + (taskRadius > 0 ? " radius=" + taskRadius : ""));
        world.execute(() -> {
            try {
                if ("identify".equals(t)) {
                    runIdentify();
                } else if ("mine".equals(t) || "gather".equals(t)) {
                    // Task 6 refusal gate (world-thread, BEFORE any task-slot write):
                    // mining needs a pickaxe in Laya's inventory; gather uses the
                    // instant-destroy interaction and needs no tool.
                    if ("mine".equals(t) && !hasPickaxe()) {
                        plugin.log("Task mine refused — no pickaxe in Laya's inventory");
                        postEvent("task_failed", "I don't have a pickaxe");
                        return;
                    }
                    if (AXE_HINTS.contains(h == null ? "" : h.toLowerCase()) && !hasAxe()) {
                        plugin.log("Task chop refused — no axe in Laya's inventory");
                        postEvent("task_failed", "I don't have an axe to chop with");
                        return;
                    }
                    runScan(t, h);
                } else {
                    plugin.log("Unknown task: " + t);
                    postEvent("task_failed", t + ": unknown task (expected identify|mine|gather)");
                }
            } catch (Throwable ex) {
                plugin.log("Task '" + t + "' failed: " + ex);
                postEvent("task_failed", t + ": internal error (" + ex.getClass().getSimpleName() + ")");
            }
        });
    }

    /**
     * Task 3: combat is taking movement — cancel a live mine/gather task. Runs on the
     * WORLD thread (CombatController is ticked from {@code startFollowTick}'s
     * {@code world.execute}), so it mutates the task state directly. Unlike
     * {@link #handleStopTask()} this is a combat hand-off, not a player request: no
     * {@code npc_event} is posted (spec: no extra chat lines) — only the cancelling
     * log line the run sheet asserts.
     */
    void cancelTaskForCombat() {
        pendingTaskTarget = null;
        resetTaskState("cancelled by combat");
        plugin.log("Combat: task cancelled for combat");
    }

    /**
     * "stop_task" (bridge thread): cancel a live repeat task but KEEP following.
     * Drops the queued target as well, otherwise the tick would immediately
     * consume a target that was stashed but not yet walked to.
     */
    private void handleStopTask() {
        if (world == null) return;
        world.execute(() -> {
            boolean live = !"IDLE".equals(taskPhase) || pendingTaskTarget != null;
            if (!live) {
                plugin.log("stop_task: no task running");
                return;
            }
            int done = taskDoneCount;
            String label = taskSnapLabel != null ? taskSnapLabel
                : (taskLabel != null ? taskLabel : "block");
            pendingTaskTarget = null;
            resetTaskState("task_cancelled by player");
            plugin.log("Task cancelled by player (" + done + " " + label + " done)");
            postEvent("task_done", "stopped on request; " + done + " " + label + " finished");
        });
    }

    /**
     * Cycle 10: put the task's tool in her hand (hatchet for wood hints, pickaxe for
     * mine). A tool sitting in storage is moved to the first free hotbar slot, then
     * selected as the active hotbar slot - the client renders the held item and the
     * swing with it. Runs on the world thread at WALKING entry.
     */
    private void equipTaskTool() {
        if (npcEntity == null || layaRef == null || worldStore == null) return;
        String hint = taskHint == null ? "" : taskHint.toLowerCase();
        boolean axe = AXE_HINTS.contains(hint);
        boolean pick = "mine".equals(taskKind);
        if (!axe && !pick) return;
        try {
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return;
            ItemContainer hotbar = inv.getHotbar();
            if (hotbar == null) return;
            boolean moved = false;
            int slot = findTaskToolSlot(hotbar, axe);
            if (slot < 0) {
                ItemContainer[] others = {inv.getStorage(), inv.getTools(), inv.getBackpack(), inv.getUtility()};
                outer:
                for (ItemContainer sec : others) {
                    if (sec == null) continue;
                    for (short s = 0; s < sec.getCapacity(); s++) {
                        ItemStack st = sec.getItemStack(s);
                        if (!isTaskTool(st, axe)) continue;
                        int empty = firstEmptySlot(hotbar);
                        if (empty < 0) empty = inv.getActiveHotbarSlot();
                        if (empty < 0) break outer;
                        ItemStack previous = hotbar.getItemStack((short) empty);
                        hotbar.setItemStackForSlot((short) empty, st);
                        sec.setItemStackForSlot(s, previous);   // swap, never drop the old item
                        slot = empty;
                        moved = true;
                        plugin.log("Wield: swapped " + st.getItemId() + " into hotbar " + empty
                            + " for the task" + (previous == null ? "" : " (stored " + previous.getItemId() + ")"));
                        break outer;
                    }
                }
            }
            if (slot < 0) {
                plugin.log("Wield: no " + (axe ? "axe" : "pickaxe") + " in her hotbar/storage to hold");
                return;
            }
            inv.setActiveHotbarSlot(layaRef, (byte) slot, worldStore);
            ItemStack held = hotbar.getItemStack((short) slot);
            plugin.log("Wield: task tool " + (held == null ? "?" : held.getItemId())
                + " (hotbar " + slot + ") for " + (axe ? "chopping" : "mining"));
            if (moved) saveInventory();
        } catch (Throwable t) {
            plugin.log("Wield: task tool failed: " + t);
        }
    }

    private int findTaskToolSlot(ItemContainer hotbar, boolean axe) {
        for (short s = 0; s < hotbar.getCapacity(); s++) {
            if (isTaskTool(hotbar.getItemStack(s), axe)) return s;
        }
        return -1;
    }

    private int firstEmptySlot(ItemContainer container) {
        for (short s = 0; s < container.getCapacity(); s++) {
            ItemStack st = container.getItemStack(s);
            if (st == null || st.getItemId() == null || st.getItemId().isEmpty()) return s;
        }
        return -1;
    }

    private boolean isTaskTool(ItemStack st, boolean axe) {
        if (st == null || st.getItemId() == null) return false;
        String id = st.getItemId();
        if (axe) {
            return id.startsWith("Tool_Hatchet") || id.startsWith("Tool_Axe")
                || id.startsWith("Weapon_Axe") || id.startsWith("Weapon_Battleaxe");
        }
        return id.startsWith("Tool_Pickaxe");
    }

    /**
     * World-thread: play exactly one tool swing (the role no longer restarts it).
     * Cycle 11b: the animation id must come from the HELD ITEM's animation set,
     * not a guess. "Mine" only exists on the pickaxe set and "Chop" only on the
     * hatchet set - playing "Mine" with a hatchet in hand is a silent no-op
     * (client log: "No animation with id Mine on entity with model
     * Characters/Player.blockymodel"). "SwingDown" is the axe set; "Interact" is
     * the Default set, used when her hands hold neither tool.
     */
    private String swingAnimationId() {
        try {
            if (npcEntity != null) {
                Inventory inv = npcEntity.getInventory();
                if (inv != null && inv.getHotbar() != null) {
                    ItemStack held = inv.getHotbar().getItemStack((short) inv.getActiveHotbarSlot());
                    String id = held == null ? null : held.getItemId();
                    if (id != null) {
                        if (id.startsWith("Tool_Pickaxe")) return "Mine";
                        if (id.startsWith("Tool_Hatchet")) return "Chop";
                        if (id.startsWith("Tool_Axe") || id.startsWith("Weapon_Axe")
                            || id.startsWith("Weapon_Battleaxe")) return "SwingDown";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Interact";
    }

    private void playSwing() {
        if (npcEntity == null || layaRef == null || worldStore == null) return;
        String anim = swingAnimationId();
        try {
            npcEntity.playAnimation(layaRef, AnimationSlot.Action, anim, worldStore);
        } catch (Throwable t) {
            plugin.log("Swing animation failed (" + anim + "): " + t);
        }
    }

    /** World-thread: clear the Action animation slot (null = stop). */
    void stopSwing() {
        if (npcEntity == null || layaRef == null || worldStore == null) return;
        try {
            npcEntity.playAnimation(layaRef, AnimationSlot.Action, null, worldStore);
        } catch (Throwable ignored) {
        }
    }

    // --- Cycle 5/6/7: interact (default key / right-click) on Laya -> her inventory ---

    /**
     * Cycle 7: 0.7.2 never fires PlayerInteractEvent (the class exists but has no
     * construction site in the server), so the default interact key is read from
     * the engine's own NPC interaction queue instead. We also keep her marked
     * interactable for this player — the role's InteractionInstruction turned out
     * not to set the component for this NPC, so the client showed no prompt.
     */
    private void pollInteractKey(Ref<EntityStore> playerEntity) {
        if (layaRef == null || worldStore == null || playerEntity == null) return;
        try {
            Player player = worldStore.getComponent(playerEntity, Player.getComponentType());
            ensureCreativeNpcDetection(playerEntity, player);

            StateSupport ss = StateSupport.get(layaRef, worldStore);
            if (ss == null) return;
            ss.setInteractable(layaRef, playerEntity, true,
                "server.interactionHints.generic", true, worldStore);
            if (!interactForceLogged) {
                interactForceLogged = true;
                plugin.log("Laya: interactable forced from plugin (hint=server.interactionHints.generic)");
            }
            if (ss.consumeInteraction(playerEntity)) {
                // --- Task 7: downed route FIRST (ruling: downed, probe, inventory) ---
                // While she is down a Use is the pickup input, never the inventory. When
                // up this returns false and nothing changes.
                if (combat.isDowned()) {
                    combat.onReviveUse(playerEntity);
                    return;
                }
                // --- Task 2: 60 s held-interact Use probe (downed route excluded it) ---
                // While a probe window is live, a Use packet is LOGGED and swallowed:
                // no inventory, so a held key can be observed as a clean event stream.
                long probeNow = System.currentTimeMillis();
                if (probeUntilMs != 0 && probeNow < probeUntilMs) {
                    plugin.log("Probe: Use t=+" + (probeNow - probeStartMs) + "ms");
                    return;
                }
                if (player != null) {
                    plugin.log("Laya: interact key (engine Use) -> opening inventory");
                    tryOpenInventory(playerEntity, player, "interact-key");
                }
            }
        } catch (Throwable t) {
            if (!interactPollErrorLogged) {
                interactPollErrorLogged = true;
                plugin.log("Laya: interact poll failed: " + t);
            }
        }
    }

    /**
     * Cycle 9: StateSupport.update() drops every player from interactablePlayers
     * unless EntityDetectionUtil says the NPC can detect them - and in CREATIVE the
     * default creativeSettings.allowNPCDetection is false. That made our re-add
     * race the NPC tick, so the Use packet was usually refused (needing many presses).
     * Enabling the setting once removes the pruning; survival players are untouched.
     */
    private void ensureCreativeNpcDetection(Ref<EntityStore> playerEntity, Player player) {
        try {
            if (player == null || player.getGameMode() != GameMode.Creative) return;
            PlayerSettings ps = worldStore.getComponent(playerEntity, PlayerSettings.getComponentType());
            if (ps == null || ps.creativeSettings() == null) return;
            if (ps.creativeSettings().allowNPCDetection()) return;
            PlayerCreativeSettings cs = ps.creativeSettings();
            PlayerCreativeSettings fixed = new PlayerCreativeSettings(
                true, cs.respondToHit(), cs.placeMode(), cs.creativeInteractionDistance(),
                cs.showBuilderToolsNotifications(), cs.noPhysics(), cs.eraserEnabled(),
                cs.activateTriggerVolumes());
            PlayerSettings updated = new PlayerSettings(
                ps.showEntityMarkers(),
                ps.armorItemsPreferredPickupLocation(),
                ps.weaponAndToolItemsPreferredPickupLocation(),
                ps.usableItemsItemsPreferredPickupLocation(),
                ps.solidBlockItemsPreferredPickupLocation(),
                ps.miscItemsPreferredPickupLocation(),
                fixed,
                ps.hideHelmet(), ps.hideCuirass(), ps.hideGauntlets(), ps.hidePants(),
                ps.voiceSettings());
            worldStore.putComponent(playerEntity, PlayerSettings.getComponentType(), updated);
            if (!creativeDetectionFixed) {
                creativeDetectionFixed = true;
                plugin.log("Laya: creative NPC detection enabled for the player (single-press interact)");
            }
        } catch (Throwable t) {
            if (!creativeDetectionFixed) {
                creativeDetectionFixed = true;
                plugin.log("Laya: creative detection tweak failed: " + t);
            }
        }
    }

    /**
     * Player pressed the default interact key on Laya. Uses the engine's
     * (deprecated-but-live) PlayerInteractEvent, the same hook the companion
     * reference plugin uses for its NPC menus. Opens a real ContainerWindow on
     * her LIVE inventory (hotbar + storage), so picked-up ore is reachable:
     * the player can drag it out into their own pack.
     */
    @SuppressWarnings("deprecation")
    public void handlePlayerInteract(PlayerInteractEvent event) {
        if (event == null || !running || layaRef == null) return;
        Player player = event.getPlayer();
        Ref<EntityStore> playerEntity = event.getPlayerRef();
        if (player == null || playerEntity == null || world == null || worldStore == null) return;
        Ref<EntityStore> target = event.getTargetRef();
        boolean onHer = target != null && target.equals(layaRef);
        if (!onHer) {
            PlayerRef pref = player.getPlayerRef();
            if (pref == null || !nearAndFacing(pref)) return;
        } else if (!withinInteractRange(playerEntity)) {
            return;
        }
        plugin.log("Laya: interact event (action=" + event.getActionType()
            + " targetRef=" + (target != null) + " onHer=" + onHer + ")");
        if (combat.isDowned()) return;   // Task 7: the pickup owns Use while she is down
        tryOpenInventory(playerEntity, player, "interact-event");
    }

    /**
     * Right-click fallback. In 0.7.2 a dynamically spawned NPC does not
     * reliably surface the client interact prompt / Use (so no
     * PlayerInteractEvent is fired), but the raw mouse-button event always
     * arrives and carries the entity ref. The companion reference plugin
     * listens to this event too, with a proximity+facing fallback.
     */
    public void handlePlayerMouseButton(com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent event) {
        if (event == null || !running || layaRef == null || worldStore == null) return;
        var mb = event.getMouseButton();
        if (mb == null
            || mb.state != com.hypixel.hytale.protocol.MouseButtonState.Pressed
            || mb.mouseButtonType != com.hypixel.hytale.protocol.MouseButtonType.Right) {
            return;
        }
        Player player = event.getPlayer();
        Ref<EntityStore> playerEntity = event.getPlayerRef();
        PlayerRef pref = event.getPlayerRefComponent();
        if (player == null || playerEntity == null || pref == null) return;
        Ref<EntityStore> target = event.getTargetEntityRef();
        boolean onHer = target != null && target.equals(layaRef);
        if (!onHer && !nearAndFacing(pref)) return;
        plugin.log("Laya: right-click (targetEntity=" + (target != null) + " onHer=" + onHer + ")");
        if (combat.isDowned()) {
            // Task 7: never open the inventory while she is down. The right-click is a
            // one-shot press (no repeats), so it is swallowed rather than fed to the
            // literal-hold channel — a single press would otherwise be cancelled as
            // `release` 600 ms later and spam the cancel line. The Use route (which does
            // repeat while held) is the pickup input.
            return;
        }
        tryOpenInventory(playerEntity, player, "mouse-right");
    }

    /** Within 5 blocks and either very close or roughly in the player's view. */
    private boolean nearAndFacing(PlayerRef pref) {
        if (pref == null || worldStore == null || layaRef == null) return false;
        TransformComponent lt = worldStore.getComponent(layaRef, TransformComponent.getComponentType());
        if (lt == null || pref.getTransform() == null) return false;
        Vector3d p = new Vector3d(pref.getTransform().getPosition());
        Vector3d l = new Vector3d(lt.getPosition());
        double d = p.distance(l);
        if (d > 5.0) return false;
        if (d <= 2.5) return true;
        Vector3d dir = new Vector3d(pref.getTransform().getDirection());
        if (dir.lengthSquared() < 1.0e-6) return false;
        return dir.normalize().dot(l.sub(p).normalize()) >= 0.5;
    }

    private boolean withinInteractRange(Ref<EntityStore> playerEntity) {
        TransformComponent pt = worldStore.getComponent(playerEntity, TransformComponent.getComponentType());
        TransformComponent lt = worldStore.getComponent(layaRef, TransformComponent.getComponentType());
        if (pt == null || lt == null) return false;
        return pt.getPosition().distanceSquared(lt.getPosition()) <= 36.0; // 6 blocks
    }

    private void tryOpenInventory(Ref<EntityStore> playerEntity, Player player, String source) {
        PlayerRef pref = player.getPlayerRef();
        String key = pref == null ? "player" : String.valueOf(pref.getUuid());
        long now = System.currentTimeMillis();
        Long last = lastInvOpenMs.get(key);
        if (last != null && now - last < 450) return;   // one key press = one open
        lastInvOpenMs.put(key, now);
        openInventoryFor(playerEntity, player, source);
    }

    private void openInventoryFor(Ref<EntityStore> playerEntity, Player player, String source) {
        world.execute(() -> {
            try {
                if (npcEntity == null || layaRef == null) return;
                Inventory inv = npcEntity.getInventory();
                if (inv == null) return;
                List<ItemContainer> sections = new ArrayList<>();
                if (inv.getHotbar() != null) sections.add(inv.getHotbar());
                if (inv.getStorage() != null) sections.add(inv.getStorage());
                CombinedItemContainer container =
                    new CombinedItemContainer(sections.toArray(new ItemContainer[0]));
                PlayerRef pref = player.getPlayerRef();
                if (pref == null) return;
                player.getPageManager().openCustomPage(playerEntity, worldStore,
                    new LayaInventoryPage(pref, this));
                plugin.log("Laya: inventory page opened via " + source + " (36 pack + 5 gear slots)");
            } catch (Throwable t) {
                plugin.log("Laya: opening her inventory failed: " + t);
            }
        });
    }

    /** World-thread: identify the block the player is looking at. */
    private void runIdentify() {
        int[] hit = BlockScanner.identifyBlock(playerRef, IDENTIFY_MAX_DIST);
        if (hit == null) {
            plugin.log("Identified -1 as none (no block in view)");
            postEvent("task_failed", "identify: no block in view");
            return;
        }
        String name = displayName(hit[3]);
        plugin.log("Identified " + hit[3] + " as " + name);
        postEvent("identified", name + " (" + hit[3] + ") at " + hit[0] + "," + hit[1] + "," + hit[2]);
    }

    /**
     * World-thread: mine/gather — scan {@value #SCAN_RADIUS} blocks around the player
     * for blocks whose engine asset key matches the hint's regex from blocks.json.
     * The chosen target is stashed in {@link #pendingTaskTarget} for Task 4's tick.
     */
    private void runScan(String task, String hint) {
        int r = scanTaskTarget(task, hint);
        if (r == -2) {
            postEvent("task_failed", "that trunk is on a ledge I can't reach from here");
        } else if (r < 0) {
            postEvent("task_failed", task + ": unknown target hint '" + hint + "'");
        } else if (r == 0) {
            postEvent("task_failed", "no " + hintLabel(hint) + " within "
                + (taskRadius > 0 ? taskRadius : (int) SCAN_RADIUS) + " blocks");
        }
    }

    /**
     * World-thread scan + stash for the next block of a task. Returns
     * 1 = target stashed, 0 = nothing matching in range, -1 = unknown hint,
     * -2 = tree base out of vertical reach (cycle 13).
     * Shared by the initial task scan and the repeat rescan between blocks
     * (the repeat path wants a silent "nothing left", not a task_failed).
     */
    private int scanTaskTarget(String task, String hint) {
        Pattern pattern = HINT_PATTERNS.get(hint);
        if (pattern == null) pattern = resolveHintPattern(hint);
        if (pattern == null) {
            plugin.log("Scan " + hint + " checked=0 found=0 (unknown hint)");
            return -1;
        }
        Vector3d center;
        double radius = SCAN_RADIUS;
        boolean isWood = AXE_HINTS.contains(hint);
        boolean area = "area".equals(taskScope);
        boolean anchoredWood = isWood && !area;
        if (area) {
            // "clear a 25x25 area": pin the circle to where the command was given,
            // then keep re-scanning that same area while she clears it.
            if (!taskAreaSet) {
                Vector3d p0 = new Vector3d(playerRef.getTransform().getPosition());
                taskAreaX = p0.x;
                taskAreaY = p0.y;
                taskAreaZ = p0.z;
                taskAreaSet = true;
            }
            center = new Vector3d(taskAreaX, taskAreaY, taskAreaZ);
            radius = taskRadius > 0 ? taskRadius
                : (isWood ? WOOD_AREA_RADIUS : SCAN_RADIUS);
        } else if (anchoredWood && taskAnchorSet) {
            // Cycle 10b: repeat legs stay on the tree that was started. If the
            // player has walked away, finish honestly instead of chasing the
            // next birch beside them.
            center = new Vector3d(taskAnchorX, taskAnchorY, taskAnchorZ);
            radius = WOOD_ANCHOR_RADIUS;
            double away = new Vector3d(playerRef.getTransform().getPosition()).distance(center);
            if (away > WOOD_ANCHOR_LEAVE) {
                plugin.log("Scan " + hint + ": player left the tree ("
                    + String.format(java.util.Locale.ROOT, "%.1f", away) + " blocks) - finishing the task");
                return 0;
            }
        } else {
            center = new Vector3d(playerRef.getTransform().getPosition());
        }
        // Cycle 10: wood is "this tree here" - nearest wins (a trunk beside the player
        // must beat a tree 10 blocks ahead in the view cone). Ore keeps the
        // visible-first ranking that fixed the cave-diving.
        Vector3d forward = (isWood || area)
            ? null : new Vector3d(playerRef.getTransform().getDirection());
        final Pattern scanPattern = pattern;
        BlockScanner.ScanResult result = BlockScanner.scanNearest(
            worldStore, center, radius, id -> matchesHint(scanPattern, id), forward,
            isWood ? WOOD_SCAN_BAND : 8.0);
        int checked = result == null ? 0 : result.checked;
        int found = result == null ? 0 : result.found;
        plugin.log("Scan " + hint + " checked=" + checked + " found=" + found
            + " radius=" + (int) radius + " scope=" + taskScope);
        if (result == null || !result.found()) {
            return 0;
        }
        int tx = result.x, ty = result.y, tz = result.z;
        int targetId = result.blockId;
        if (isWood) {
            // Cycle 11: swing at the BOTTOM of the tree. Collect the connected
            // wood and retarget the lowest block (the stump), so the animation
            // reads as chopping the trunk and the whole tree can fall from there.
            // Cycle 13: on a slope the absolute base can be several blocks below
            // the player's feet even though the trunk is right there, so swing at
            // the lowest block that is actually within her reach band; fellTree
            // still removes the whole connected tree from that block.
            List<int[]> tree = collectTreeBlocks(tx, ty, tz);
            double feetY = playerRef.getTransform().getPosition().y;
            int[] base = null;      // absolute lowest wood block
            int[] swing = null;     // lowest block within her reach band
            int[] nearest = null;   // fallback: block closest to her feet
            for (int[] b : tree) {
                if (base == null || b[1] < base[1]) base = b;
                double dy = Math.abs(b[1] - feetY);
                if (dy <= MAX_WOOD_BASE_DY && (swing == null || b[1] < swing[1])) swing = b;
                if (nearest == null || dy < Math.abs(nearest[1] - feetY)) nearest = b;
            }
            int[] chosen = swing != null ? swing : nearest;
            if (chosen != null) {
                tx = chosen[0];
                ty = chosen[1];
                tz = chosen[2];
                targetId = BlockBreaker.blockId(world, tx, ty, tz);
            }
            plugin.log("Task tree: " + tree.size() + " wood blocks, swing block @" + tx + "," + ty + "," + tz
                + (base != null ? " (base @" + base[0] + "," + base[1] + "," + base[2] + ")" : ""));
            // Cycle 13: no block of this tree is near her feet -> the straight-line
            // Seek cannot get there; say so before walking instead of wedging.
            if (chosen != null && Math.abs(chosen[1] - feetY) > MAX_WOOD_BASE_DY) {
                plugin.log("Task tree rejected: nearest block @" + tx + "," + ty + "," + tz + " is "
                    + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(chosen[1] - feetY))
                    + " blocks from the player vertically (limit " + MAX_WOOD_BASE_DY + ")");
                return -2;
            }
        }
        // Task 4 consumes this on the world thread (walk Laya over + mine/gather).
        // Task 6 stamps the task kind/label/before-id here so the tick's timed break
        // chain knows which interaction to drive and what it is trying to break.
        taskKind = task;
        taskLabel = displayName(targetId);
        taskBlockKey = BlockScanner.assetId(targetId);
        taskBlockId = targetId;
        if (anchoredWood && !taskAnchorSet) {
            taskAnchorSet = true;
            taskAnchorX = tx;
            taskAnchorY = ty;
            taskAnchorZ = tz;
            plugin.log("Task anchor: wood task pinned to " + tx + "," + ty + "," + tz
                + " (rescan radius " + WOOD_ANCHOR_RADIUS + ")");
        }
        pendingTaskTarget = tx + "," + ty + "," + tz;
        plugin.log("Task " + task + " target stashed for the Task 4 tick: " + pendingTaskTarget);
        return 1;
    }

    // --- Task 6: task executor (mine/gather) ---

    /** The interaction for the ACTIVE pending task (read only at WALKING entry). */
    private String taskInteractionId() {
        return "mine".equals(taskKind) ? BlockBreaker.MINE_INTERACTION : BlockBreaker.GATHER_INTERACTION;
    }

    /**
     * Task 6 chain guard (I-1). A callback may act only when it still belongs to the live
     * chain generation AND that generation is the WORKING one. On a generation mismatch it
     * logs once and returns — it must NEVER touch the latch, the phase or post an event,
     * because the latch/phase/coords may now belong to a different (newer) chain.
     */
    private boolean taskChainCurrent(long gen) {
        if (gen != taskChainGen) {
            plugin.log("Task chain stale (gen=" + gen + " != " + taskChainGen + ") — dropped");
            return false;
        }
        return taskWorking.get() && ("WORKING".equals(taskPhase) || "DIGGING".equals(taskPhase));
    }

    /**
     * Refusal gate (world-thread, called BEFORE any task-slot write): does Laya carry a
     * pickaxe? Pinned inventory read (javap, local 0.7.2 jar):
     * <ul>
     *   <li>{@code LivingEntity.getInventory(): Inventory} — {@code NPCEntity extends LivingEntity};</li>
     *   <li>the six {@code Inventory} sections {@code getHotbar()/getTools()/getStorage()/
     *       getBackpack()/getUtility()/getArmor()} → {@code ItemContainer};</li>
     *   <li>{@code ItemContainer.countItemStacks(Predicate<ItemStack>): int} — bytecode sums
     *       the quantities of all non-empty stacks whose predicate matches (verified in the
     *       {@code lambda$countItemStacks$0} body), so {@code > 0} is a presence check;</li>
     *   <li>the predicate tests {@code ItemStack.getItemId()} (the item asset key, e.g.
     *       {@code Tool_Pickaxe_Copper}) for the substring {@code Tool_Pickaxe}.</li>
     * </ul>
     * Any failure (no NPC entity, no inventory, exception) returns false → the mine task is
     * truthfully refused rather than attempted.
     */
    private boolean hasPickaxe() {
        return hasItemToken(PICKAXE_ID_TOKEN);
    }

    /**
     * Cycle 8: chopping/wood tasks need an axe. Hytale's woodcutting tool is the
     * hatchet; weapon axes are accepted too (both are "an axe" in play).
     */
    private boolean hasAxe() {
        return hasItemToken(AXE_ID_TOKENS);
    }

    /** Present if any of the item-id substrings appears in any inventory section. */
    private boolean hasItemToken(String... tokens) {
        if (npcEntity == null) return false;
        try {
            Inventory inv = npcEntity.getInventory();
            if (inv == null) return false;
            ItemContainer[] sections = {
                inv.getHotbar(), inv.getTools(), inv.getStorage(),
                inv.getBackpack(), inv.getUtility(), inv.getArmor()
            };
            for (ItemContainer section : sections) {
                if (section == null) continue;
                if (section.countItemStacks(s -> {
                        if (s == null || s.getItemId() == null) return false;
                        for (String token : tokens) {
                            if (s.getItemId().contains(token)) return true;
                        }
                        return false;
                    }) > 0) {
                    return true;
                }
            }
        } catch (Throwable t) {
            plugin.log("Tool check failed: " + t);
        }
        return false;
    }

    /**
     * One break hit (world-thread, scheduled): mirrors the probe chain's cadence but owns
     * the SEPARATE {@link #taskWorking} latch and carries its own generation + frozen
     * inputs (I-1/I-2). {@code queueExecuteChain} only enqueues, so the result is verified
     * {@code PROBE_VERIFY_DELAY_MS} later.
     */
    private void taskHit(long gen, String interactionId, int attempt, int x, int y, int z, boolean dig) {
        if (!taskChainCurrent(gen)) return;
        try {
            int before = BlockBreaker.blockId(world, x, y, z);
            if (before == BlockScanner.EMPTY_ID) {
                // Already air before hitting: probe-consistent block-gone evidence.
                taskWorking.set(false);
                if (dig) {
                    plugin.log("Task dig block already air @" + x + "," + y + "," + z);
                    finishDig();
                } else {
                    plugin.log("Task block gone (" + (attempt - 1) + " hits)");
                    enterDone();
                }
                return;
            }
            // Cycle 5: one swing per hit (the role no longer restarts it every
            // tick), then apply the break after the wind-up so the impact reads.
            playSwing();
            final int beforeId = before;   // snapshot for this hit; never a shared field
            world.scheduleAfter(() -> {
                if (!taskChainCurrent(gen)) return;
                try {
                    BlockBreaker.hitBlock(world, worldStore, x, y, z, interactionId);
                    world.scheduleAfter(
                        () -> taskVerify(gen, interactionId, attempt, x, y, z, beforeId, dig),
                        PROBE_VERIFY_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    plugin.log("Task WORKING hit failed: " + t);
                    taskWorking.set(false);
                    clearTask("task_failed: can't break it");
                }
            }, TASK_SWING_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            plugin.log("Task WORKING hit failed: " + t);
            taskWorking.set(false);
            clearTask("task_failed: can't break it");
        }
    }

    /**
     * World-thread (scheduleAfter): the only place success is decided. Success requires
     * block-gone evidence (R-T6-0) — a non-air read, even one whose id changed, is NOT
     * success. On the bounded budget's exhaustion the task fails honestly. {@code beforeId}
     * is the same hit's own before-read (a final parameter, not a shared field).
     *
     * <p>Cycle 6: when the target sits under the ground and the hit does no damage,
     * dig the block under her feet (bounded by {@link #TASK_MAX_DIGS}) and retry —
     * she tunnels down to the vein instead of failing "can't break it".
     */
    private void taskVerify(long gen, String interactionId, int attempt, int x, int y, int z,
                            int beforeId, boolean dig) {
        if (!taskChainCurrent(gen)) return;
        try {
            int after = BlockBreaker.blockId(world, x, y, z);
            boolean gone = after == BlockScanner.EMPTY_ID;
            boolean changed = after != beforeId;
            if (!dig) taskHitCount = attempt;
            plugin.log("Task " + (dig ? "DIG" : "WORKING") + " hit " + attempt
                + " (interaction=" + interactionId + " damaged=" + (changed || gone) + ")");
            if (gone) {
                taskWorking.set(false);
                if (dig) {
                    plugin.log("Task dig block gone (" + attempt + " hits) @" + x + "," + y + "," + z);
                    finishDig();
                } else {
                    plugin.log("Task block gone (" + attempt + " hits)");
                    enterDone();
                }
                return;
            }
            if (!dig && !changed && tryDigDown(gen)) return;
            if (attempt >= TASK_MAX_HITS) {
                taskWorking.set(false);
                plugin.log("Task WORKING no effect after " + TASK_MAX_HITS
                    + " hits (interaction=" + interactionId + ")");
                clearTask(dig ? "task_failed: can't dig down" : "task_failed: can't break it");
                return;
            }
            world.scheduleAfter(
                () -> taskHit(gen, interactionId, attempt + 1, x, y, z, dig),
                TASK_HIT_MS - PROBE_VERIFY_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            plugin.log("Task WORKING verify failed: " + t);
            taskWorking.set(false);
            clearTask("task_failed: can't break it");
        }
    }

    /**
     * Cycle 6: the target is buried and the break cannot reach it. Break the block
     * directly under Laya's feet so she drops a level and tries again. Bounded and
     * only for targets below her (horizontal tunnelling is not attempted).
     */
    private boolean tryDigDown(long gen) {
        if (taskDigCount >= TASK_MAX_DIGS || layaRef == null || worldStore == null) return false;
        TransformComponent lt = worldStore.getComponent(layaRef, TransformComponent.getComponentType());
        if (lt == null) return false;
        Vector3d l = new Vector3d(lt.getPosition());
        if (taskSnapY + 0.5 >= l.y - 0.8) return false;   // target is not below her feet
        int dx = (int) Math.floor(l.x);
        int dy = (int) Math.floor(l.y) - 1;
        int dz = (int) Math.floor(l.z);
        if (BlockBreaker.blockId(world, dx, dy, dz) == BlockScanner.EMPTY_ID) return false;
        taskDigCount++;
        plugin.log("Task dig " + taskDigCount + "/" + TASK_MAX_DIGS + " @" + dx + "," + dy + "," + dz
            + " — target @" + taskSnapX + "," + taskSnapY + "," + taskSnapZ + " out of reach");
        taskPhase = "DIGGING";
        taskWorking.set(true);
        phaseSinceMs = System.currentTimeMillis();
        taskHit(gen, BlockBreaker.MINE_INTERACTION, 1, dx, dy, dz, true);
        return true;
    }

    /** A dig step finished: release the chain and let the tick re-approach / re-hit. */
    private void finishDig() {
        taskWorking.set(false);
        taskPhase = "WALKING";
        phaseSinceMs = System.currentTimeMillis();
        plugin.log("Task dig cleared — resuming target @" + taskSnapX + "," + taskSnapY + "," + taskSnapZ);
    }

    /** Block gone: enter DONE and let the role's always-on pickup run for TASK_SETTLE_MS. */
    private void enterDone() {
        taskPhase = "DONE";
        phaseSinceMs = System.currentTimeMillis();
        plugin.log("Task DONE — block broken @" + taskSnapX + "," + taskSnapY + "," + taskSnapZ
            + "; settling " + TASK_SETTLE_MS + "ms for pickup");
    }

    /**
     * Success teardown (world-thread). Cycle 5: this is also the repeat point.
     * Count the block that just broke; if the task asked for more (N) or for
     * "all" (0), scan again for the same hint and queue the next target. The
     * tick's pending-target branch then walks her over as a NEW chain (new
     * generation). When nothing matching is left — or N is reached — post ONE
     * summary event: progress is reported per task, never per block.
     */
    private void finishTaskSuccess() {
        // Cycle 11: a felled tree already dropped its real loot (role pickup), so
        // the manual single-block reward would double it.
        if (!taskFelled) grantBlockReward();
        int done = ++taskDoneCount;
        final String verb = taskSnapVerb == null ? "finished" : taskSnapVerb;
        final String label = taskSnapLabel == null ? "block" : taskSnapLabel;
        final String key = taskSnapKey == null ? "?" : taskSnapKey;
        final int requested = taskTargetCount;
        final String hint = taskHint == null ? "" : taskHint;

        boolean wantMore = requested == 0 || done < requested;
        if (wantMore && !hint.isEmpty()) {
            int r = scanTaskTarget(taskKind == null ? "gather" : taskKind, hint);
            if (r == 1) {
                taskWorking.set(false);
                taskAdjacent = false;
                taskPhase = "IDLE";
                phaseSinceMs = 0;
                taskHitCount = 0;
                followAnchor = null;   // player re-anchors while she walks to the next one
                plugin.log("Task progress: " + done + "/"
                    + (requested == 0 ? "all" : String.valueOf(requested))
                    + " " + label + " — next target queued");
                return;
            }
        }

        String detail;
        if (wantMore) {
            detail = verb + " " + done + " " + label + " (" + key + "); no more "
                + (hint.isEmpty() ? "matching blocks" : hint)
                + " within " + (taskRadius > 0 ? taskRadius : (int) SCAN_RADIUS) + " blocks";
        } else {
            detail = verb + " " + done + " " + label + " (" + key + "); requested count reached";
        }
        resetTaskState("task_done: " + label + " x" + done);
        postEvent("task_done", detail);
    }

    /**
     * Cycle 7: put the broken block's real drop into her inventory. The destroy
     * interaction does not run the block's gathering/loot pass, so nothing lands
     * in her pack. The block asset still carries the same drop data a survival
     * break uses: either a direct ItemId/Quantity or a DropList (copper ore uses a
     * weighted list: Ore_Copper or Rock_Stone_Cobble).
     */
    /**
     * Cycle 11: connected {@code Wood_*} blocks (6-neighbour flood fill), bounded by
     * {@link #TREE_MAX_BLOCKS}, {@link #TREE_MAX_HEIGHT} and {@link #TREE_MAX_RADIUS}.
     * WORLD-THREAD ONLY (reads chunk sections).
     */
    private List<int[]> collectTreeBlocks(int sx, int sy, int sz) {
        List<int[]> out = new ArrayList<>();
        if (world == null) return out;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{sx, sy, sz});
        seen.add(treeKey(sx, sy, sz));
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty() && out.size() < TREE_MAX_BLOCKS) {
            int[] p = queue.poll();
            int id = BlockBreaker.blockId(world, p[0], p[1], p[2]);
            if (id == BlockScanner.EMPTY_ID) continue;
            String key = BlockScanner.assetId(id);
            if (key == null || !key.startsWith("Wood_")) continue;
            out.add(p);
            for (int[] d : dirs) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (ny < sy - TREE_MAX_HEIGHT || ny > sy + TREE_MAX_HEIGHT) continue;
                if (Math.abs(nx - sx) > TREE_MAX_RADIUS || Math.abs(nz - sz) > TREE_MAX_RADIUS) continue;
                if (seen.add(treeKey(nx, ny, nz))) queue.add(new int[]{nx, ny, nz});
            }
        }
        return out;
    }

    private static long treeKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Cycle 11: remove the whole connected tree in one go through the engine's own
     * physics-removal path (particles + real drops). The role's DroppedItem pickup
     * (range 6) collects them; no manual {@code grantBlockReward} for a felled tree.
     * WORLD-THREAD ONLY.
     */
    private void fellTree(int bx, int by, int bz) {
        if (world == null || layaRef == null) return;
        try {
            List<int[]> wood = collectTreeBlocks(bx, by, bz);
            if (wood.isEmpty()) {
                plugin.log("Task fell: no connected wood at @" + bx + "," + by + "," + bz);
                return;
            }
            // The canopy goes with it: leaves touching any of this tree's wood
            // (one shell layer, so a neighbouring tree's leaves are not eaten).
            List<int[]> leaves = new ArrayList<>();
            Set<Long> leafSeen = new HashSet<>();
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] p : wood) {
                for (int[] d : dirs) {
                    int lx = p[0] + d[0], ly = p[1] + d[1], lz = p[2] + d[2];
                    if (!leafSeen.add(treeKey(lx, ly, lz))) continue;
                    String k = BlockScanner.assetId(BlockBreaker.blockId(world, lx, ly, lz));
                    if (k != null && k.startsWith("Plant_Leaves_") && leaves.size() < 160) {
                        leaves.add(new int[]{lx, ly, lz});
                    }
                }
            }
            List<int[]> blocks = new ArrayList<>(wood);
            blocks.addAll(leaves);
            // Crown first, stump last - the tree visibly comes down toward her.
            blocks.sort((a, b) -> Integer.compare(b[1], a[1]));
            ChunkStore chunkStore = world.getChunkStore();
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
            int removed = 0;
            for (int[] p : blocks) {
                Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(p[0], p[1], p[2]);
                if (section == null) continue;
                int id = BlockBreaker.blockId(world, p[0], p[1], p[2]);
                if (id == BlockScanner.EMPTY_ID) continue;
                BlockType bt = BlockType.getAssetMap().getAsset(id);
                if (bt == null) continue;
                BlockHarvestUtils.naturallyRemoveBlockByPhysics(
                    new Vector3i(p[0], p[1], p[2]), bt, 0, 0, section, entityStore, chunkStoreStore);
                removed++;
            }
            taskFelled = true;
            plugin.log("Task fell: " + removed + " blocks (" + wood.size() + " wood, "
                + leaves.size() + " leaves) @" + bx + "," + by + "," + bz
                + " - drops left for pickup");
        } catch (Throwable t) {
            plugin.log("Task fell failed: " + t);
        }
    }

    private void grantBlockReward() {
        try {
            BlockType bt = BlockType.getAssetMap() == null ? null
                : BlockType.getAssetMap().getAsset(taskSnapBlockId);
            if (bt == null) return;
            BlockGathering gathering = bt.getGathering();
            if (gathering == null) return;
            BlockBreakingDropType breaking = gathering.getBreaking();
            if (breaking == null) return;

            List<ItemStack> drops = new ArrayList<>();
            String itemId = breaking.getItemId();
            if (itemId != null && !itemId.isEmpty()) {
                drops.add(new ItemStack(itemId, Math.max(1, breaking.getQuantity())));
            } else if (breaking.getDropListId() != null && !breaking.getDropListId().isEmpty()) {
                ItemModule itemModule = ItemModule.get();
                if (itemModule != null) {
                    drops.addAll(itemModule.getRandomItemDrops(breaking.getDropListId()));
                }
            }
            if (drops.isEmpty()) return;

            Inventory inv = npcEntity == null ? null : npcEntity.getInventory();
            if (inv == null) return;
            ItemContainer storage = inv.getStorage();
            ItemContainer hotbar = inv.getHotbar();
            StringBuilder granted = new StringBuilder();
            for (ItemStack stack : drops) {
                ItemStackTransaction tx = null;
                if (storage != null) tx = storage.addItemStack(stack);
                if ((tx == null || !tx.succeeded()) && hotbar != null) {
                    tx = hotbar.addItemStack(stack);
                }
                boolean ok = tx != null && tx.succeeded();
                if (granted.length() > 0) granted.append(", ");
                granted.append(ok ? "+" : "FULL ").append(stack.getQuantity())
                    .append(" ").append(stack.getItemId());
            }
            plugin.log("Task reward: " + granted + " -> her inventory");
            saveInventory();
        } catch (Throwable t) {
            plugin.log("Task reward failed: " + t);
        }
    }

    // --- Task 2: Use probe ("probe_use") ---

    /**
     * Arm the 60 s held-interact window (bridge HTTP thread). Log-only: nothing is
     * sent to the bridge and no task/stance/movement state changes. The window is
     * observed in {@link #pollInteractKey} and retired in the follow tick.
     */
    private void startUseProbe() {
        // Task 7 (step 2): the probe is rejected while she is down — a live window would
        // otherwise swallow the pickup's Use events once the downed route stops matching.
        // Silent: no new log string is introduced by Task 7 beyond the ones in the brief.
        if (combat.isDowned()) {
            return;
        }
        long now = System.currentTimeMillis();
        probeStartMs = now;
        probeUntilMs = now + 60000L;
        plugin.log("Probe: armed 60s - hold the interact key beside Laya");
    }

    // --- Task 5: break probe ("probe_break") ---

    /**
     * Probe entry (bridge HTTP thread): latch, then hand the whole chain to the world
     * thread. Target selection is any-hinted first, then any non-air, within 3 blocks
     * of LAYA (brief: "first scanned match within 3 blocks of HER"). No task state,
     * no pendingTaskTarget, no NPC motion.
     */
    private void handleProbeBreak() {
        if (world == null || worldStore == null || layaRef == null) {
            plugin.log("Probe S1 ignored — not ready (world=" + (world != null)
                + ", store=" + (worldStore != null) + ", laya=" + (layaRef != null) + ")");
            return;
        }
        if (!probeChainActive.compareAndSet(false, true)) {
            plugin.log("Probe S1 ignored — a probe chain is already pending");
            return;
        }
        // File I/O happens on this (bridge) thread, not on the world thread.
        ensureBlocksLoaded();
        world.execute(this::startProbeChain);
    }

    /** World-thread: resolve the target block and start the timed hit chain. */
    private void startProbeChain() {
        if (!probeChainActive.get()) return;
        try {
            TransformComponent layaT =
                worldStore.getComponent(layaRef, TransformComponent.getComponentType());
            if (layaT == null) {
                plugin.log("Probe S1: Laya transform missing");
                probeChainActive.set(false);
                return;
            }
            Vector3d center = new Vector3d(layaT.getPosition());

            // Any-hinted-priority, then plain non-air fallback.
            BlockScanner.ScanResult target = BlockScanner.scanNearest(
                worldStore, center, PROBE_SCAN_RADIUS, NPCController::matchesAnyHint, null);
            if (target == null || !target.found()) {
                target = BlockScanner.scanNearest(
                    worldStore, center, PROBE_SCAN_RADIUS,
                    id -> id != BlockScanner.EMPTY_ID, null);
            }
            if (target == null || !target.found()) {
                plugin.log("Probe S1: no target within " + (int) PROBE_SCAN_RADIUS + " blocks of Laya");
                probeChainActive.set(false);
                return;
            }

            String interactionId = BlockBreaker.PROBE_INTERACTION_IDS[0];
            plugin.log("Probe S1: target @" + target.x + "," + target.y + "," + target.z
                + " interaction=" + interactionId);
            probeHit(0, target.x, target.y, target.z);
        } catch (Throwable t) {
            plugin.log("Probe S1 failed: " + t);
            probeChainActive.set(false);
        }
    }

    /**
     * World-thread: one hit. Damage is verified {@code PROBE_VERIFY_DELAY_MS} later
     * because {@code InteractionManager.queueExecuteChain} only enqueues the chain —
     * it runs on the entity's next interaction tick.
     */
    private void probeHit(int hitNumber, int x, int y, int z) {
        if (!probeChainActive.get()) return;
        try {
            if (!BlockBreaker.verify(world, x, y, z)) {
                plugin.log("Probe block gone (" + hitNumber + " hits)");
                probeChainActive.set(false);
                return;
            }
            String interactionId = BlockBreaker.PROBE_INTERACTION_IDS[
                hitNumber % BlockBreaker.PROBE_INTERACTION_IDS.length];
            boolean damaged = BlockBreaker.hitBlock(world, worldStore, x, y, z, interactionId);
            world.scheduleAfter(
                () -> probeVerify(hitNumber, x, y, z, interactionId, damaged),
                PROBE_VERIFY_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            plugin.log("Probe S1 hit failed: " + t);
            probeChainActive.set(false);
        }
    }

    /** World-thread (scheduleAfter): log observed result, then continue or close the chain. */
    private void probeVerify(int hitNumber, int x, int y, int z,
                             String interactionId, boolean damagedImmediate) {
        if (!probeChainActive.get()) return;
        try {
            boolean gone = !BlockBreaker.verify(world, x, y, z);
            boolean damaged = damagedImmediate || gone;
            plugin.log("Probe S1 hit @" + x + "," + y + "," + z
                + " (interaction=" + interactionId + " damaged=" + damaged + ")");

            if (gone) {
                plugin.log("Probe block gone (" + (hitNumber + 1) + " hits)");
                probeChainActive.set(false);
                return;
            }
            if (hitNumber + 1 >= PROBE_MAX_HITS) {
                plugin.log("Probe S1 interactions tried="
                    + String.join(",", BlockBreaker.PROBE_INTERACTION_IDS));
                plugin.log("Probe S1 no effect after " + PROBE_MAX_HITS
                    + " hits (interaction=" + interactionId + ")");
                probeChainActive.set(false);
                return;
            }
            world.scheduleAfter(() -> probeHit(hitNumber + 1, x, y, z),
                PROBE_HIT_INTERVAL_MS - PROBE_VERIFY_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            plugin.log("Probe S1 verify failed: " + t);
            probeChainActive.set(false);
        }
    }

    /**
     * Resolve a spoken hint that is not in blocks.json's hint table against the
     * full block display-name/key table (3,178 entries). This is the "every block
     * I can name" fallback; it only runs for imperative task commands because the
     * bridge produces the hint.
     */
    private static Pattern resolveHintPattern(String hint) {
        ensureBlocksLoaded();
        String key = hint == null ? "" : hint.toLowerCase(java.util.Locale.ROOT).trim();
        if (key.isEmpty()) return null;
        Pattern known = HINT_PATTERNS.get(key);
        if (known != null) return known;
        String[] words = key.replace('_', ' ').split("\\s+");
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : BLOCK_NAMES.entrySet()) {
            String name = e.getValue().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            String asset = e.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            boolean all = true;
            for (String w : words) {
                String singular = w.endsWith("ies") && w.length() > 3
                    ? w.substring(0, w.length() - 3) + "y"
                    : (w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w);
                if (!name.contains(w) && !name.contains(singular)
                    && !asset.contains(w) && !asset.contains(singular)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                keys.add(e.getKey());
                if (keys.size() >= 64) break;
            }
        }
        if (keys.isEmpty()) return null;
        StringBuilder regex = new StringBuilder("^(?:");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) regex.append('|');
            regex.append(Pattern.quote(keys.get(i)));
        }
        regex.append(")$");
        Pattern compiled = Pattern.compile(regex.toString());
        HINT_PATTERNS.put(key, compiled);
        HINT_LABELS.put(key, hint);
        LayaPlugin.log("Hints: resolved '" + hint + "' to " + keys.size() + " block key(s)");
        return compiled;
    }

    /** Any blocks.json hint pattern matches this block's engine asset key. */
    private static boolean matchesAnyHint(int blockId) {
        String key = BlockScanner.assetId(blockId);
        if (key == null) return false;
        for (Pattern pattern : HINT_PATTERNS.values()) {
            if (pattern.matcher(key).find()) return true;
        }
        return false;
    }

    /** Does this block's engine asset key match the hint pattern? */
    private static boolean matchesHint(Pattern pattern, int blockId) {
        String key = BlockScanner.assetId(blockId);
        return key != null && pattern.matcher(key).find();
    }

    /** Display name from blocks.json (single name source), falling back to the asset key. */
    private static String displayName(int blockId) {
        String key = BlockScanner.assetId(blockId);
        if (key == null) return "unknown block #" + blockId;
        String name = BLOCK_NAMES.get(key);
        return name != null ? name : key;
    }

    private static String hintLabel(String hint) {
        return HINT_LABELS.getOrDefault(hint, hint);
    }

    /**
     * Load config/blocks.json ONCE (Task 2 contract:
     * {"hints": {word: {patterns, label}}, "blocks": {assetKey: {name, hint}}}).
     * Graceful: a missing/unreadable file leaves the maps empty and logs once.
     */
    private static synchronized void ensureBlocksLoaded() {
        if (blocksLoaded) return;
        blocksLoaded = true;
        try (Reader reader = Files.newBufferedReader(Paths.get(BLOCKS_JSON), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject hints = root.has("hints") ? root.getAsJsonObject("hints") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : hints.entrySet()) {
                if (entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
                JsonObject hint = entry.getValue().getAsJsonObject();
                List<String> patterns = new ArrayList<>();
                if (hint.has("patterns")) {
                    for (JsonElement p : hint.getAsJsonArray("patterns")) {
                        patterns.add(p.getAsString());
                    }
                }
                if (patterns.isEmpty()) continue;
                String word = entry.getKey().toLowerCase();
                HINT_PATTERNS.put(word, Pattern.compile(String.join("|", patterns)));
                HINT_LABELS.put(word, hint.has("label") ? hint.get("label").getAsString() : entry.getKey());
            }
            JsonObject blocks = root.has("blocks") ? root.getAsJsonObject("blocks") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
                if (entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
                JsonObject block = entry.getValue().getAsJsonObject();
                if (block.has("name")) {
                    BLOCK_NAMES.put(entry.getKey(), block.get("name").getAsString());
                }
            }
            LayaPlugin.log("blocks.json loaded: " + HINT_PATTERNS.size() + " hints, "
                + BLOCK_NAMES.size() + " block names");
        } catch (Exception e) {
            LayaPlugin.log("blocks.json not loaded (" + e.getMessage() + ") — block names/hints unavailable");
        }
    }

    // --- Bridge event channel (plugin -> ai-bridge /npc_event) ---

    private void postEvent(String event, String detail) {
        postEvent(eventJson(event, detail));
    }

    /**
     * Fire-and-forget POST of an event JSON to the ai-bridge, using the same
     * HttpClient rules as ChatListener: HTTP/1.1 forced, JSON content type, and
     * Content-Length is never set by us (the JDK client handles framing).
     */
    private void postEvent(String json) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(NPC_EVENT_URL))
                    .timeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                plugin.log("npc_event " + json + " -> status=" + response.statusCode());
            } catch (Exception e) {
                plugin.log("npc_event failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static String eventJson(String event, String detail) {
        return "{\"event\":\"" + escapeJson(event) + "\",\"detail\":\"" + escapeJson(detail) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // --- Minimal JSON field extraction (no external library) ---

    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) {
                    end += 2;
                    continue;
                }
                if (json.charAt(end) == '"') break;
                end++;
            }
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    private Double extractJsonDouble(String json, String field) {
        String val = extractJsonField(json, field);
        if (val == null) return null;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer extractJsonInt(String json, String field) {
        String val = extractJsonField(json, field);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String layaEntityId = null;
}