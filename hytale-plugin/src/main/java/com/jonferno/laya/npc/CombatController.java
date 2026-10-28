package com.jonferno.laya.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.jonferno.laya.LayaPlugin;
import org.joml.Vector3d;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Laya's combat state (spec: docs/COMBAT-DESIGN.md), owned by {@link NPCController}
 * and driven from its 4 Hz follow tick. This class owns:
 *  - the current STANCE ({@code follow | stay | attack | defend}) and its log line;
 *  - MOVEMENT OWNERSHIP: while a fight target is live, {@link #ownsMovement()} returns
 *    true and {@link NPCController}'s follow ring, idle face, leash watchdog and stuck
 *    watchdog all stand down (the {@code motionLocked} gates in {@code startFollowTick});
 *  - the STAY ANCHOR: the spot "stay here" pinned her to, so a fight can hand movement
 *    back to the anchor on release, or leash to the player when that anchor is stale
 *    (player > 32 blocks away — spec section 9);
 *  - Task 4: WEAPONS AND OUTBOUND DAMAGE — the best carried melee weapon is ranked,
 *    equipped (a swap that can neither duplicate nor destroy a stack), swung on a
 *    1100 ms cadence with the item's own animation, and applied through the engine's
 *    damage pipeline; {@link #hpOf(Ref)} is the single HP reader for Tasks 4-6.
 *  - Task 6: TARGET SELECTION — a reactive stamp from the damage filter (15 s memory,
 *    stance-independent), a proactive hunt in the {@code attack} stance (crosshair
 *    first only right after an explicit cue, else the nearest hostile-keyword NPC
 *    within 20 blocks of the player, every 500 ms), leashes of 16 (auto-aggro) / 8
 *    ({@code defend}), a 30 s ignore set for unreachable roles, target-death handling,
 *    and the per-stance movement release. {@link #isDowned()} stays false until Task 7.
 *  - Task 7: the DOWNED STATE and REVIVE CHANNEL (spec section 6) — a lethal hit is
 *    cancelled and replaced by {@link #enterDowned}, which pins HP to 1, clears target,
 *    task and Action slot, plays the Death pose and persists; nothing revives her until
 *    a completed 10 s pickup. The channel is a LITERAL HOLD (Cycle A probe: holding the
 *    interact key repeats Use every ~300-340 ms), cancelled by a &gt;600 ms Use gap
 *    ({@code release}), leaving 3 blocks ({@code range}), player damage
 *    ({@code player damaged}), an {@code attack} cue ({@code attack}) or the player
 *    leaving the world ({@code world left}); completion restores 50 HP and {@code follow}
 *    without auto-engaging. {@link #loadSavedState()}/{@link #saveState()} keep
 *    {@code config/laya-combat.json} in step, and {@link #enterDownedRestored()} re-poses
 *    her on boot when the save says she was down.
 *
 * Task 3 shipped the skeleton: stance state, the movement-ownership gate, the anchor
 * store, and the engagement RELEASE path that Task 6 calls. Task 4 added weapons,
 * animation selection and damage. Task 5 added the damage filter, HP read/write and the
 * reactive stamp entries. Task 6 added target selection. Task 7 added the downed state,
 * the revive channel and combat persistence.
 *
 * Access: this class uses only the {@link NPCController} members the plan widens to
 * package-private for the combat tasks — the fields {@code layaRef}, {@code worldStore},
 * {@code npcEntity}, {@code playerRef}, {@code plugin}, the methods
 * {@link NPCController#writeFollowSlot(Vector3d)}, {@link NPCController#refreshPath()},
 * {@link NPCController#stopSwing()} and {@link NPCController#cancelTaskForCombat()},
 * and the constant {@link NPCController#CLEARED_POSITION}.
 *
 * Threading: {@link #tick(long, Vector3d, Vector3d, double)} and the release path run
 * on the WORLD thread (called from {@code startFollowTick}'s {@code world.execute}).
 * {@link #setStance(String, String)} is called from the bridge HTTP thread, so the
 * stance field is volatile. The Task 4 weapon/damage methods run on the world thread
 * only (called from {@link #tick(long, Vector3d, Vector3d, double)} and, from Task 6,
 * the engagement path), so their state is plain fields.
 */
class CombatController {

    /** A stay anchor farther than this from the player earns one log line, nothing more. */
    private static final double STAY_ANCHOR_RELEASE_DISTANCE = 32.0;
    /** Within this of the anchor the hold is re-armed (engine holds her in place). */
    private static final double STAY_ANCHOR_ARRIVED_DISTANCE = 0.5;
    /** Same offset the leash / stuck watchdogs use for "teleport to the player's side". */
    private static final double PLAYER_SIDE_OFFSET = 1.5;

    // --- Task 4: weapon / swing tuning (spec section 4) ---
    /** Swing cadence, spec section 4: one swing (and one damage application) per 1.1 s. */
    private static final long SWING_INTERVAL_MS = 1100;
    /** A better weapon handed to her mid-fight is picked up within this window. */
    private static final long WEAPON_RECHECK_MS = 5000;
    /** Damage lands only if the target is still this close to Laya (spec section 4). */
    private static final double SWING_RANGE = 3.0;    /** Unarmed fallback (spec section 4 damage table). */
    private static final float UNARMED_DAMAGE = 3f;
    /** Spec section 4: damage is capped, whatever the type/level formula returns. */
    private static final float DAMAGE_CAP = 30f;
    /** Melee weapon classes, best-first for the ranking tie-break (spec section 4). */
    private static final String[] MELEE_CLASS_ORDER = {"axe", "sword", "mace", "spear", "dagger"};

    // --- Task 6: target selection tuning (spec section 3) ---
    /** Reactive memory: 15 s since the last hit by that attacker (spec section 3). */
    private static final long REACTIVE_MEMORY_MS = 15_000;
    /** Auto-aggro leash: 16 blocks from the player in every stance but {@code defend}. */
    private static final double AUTO_AGGRO_LEASH = 16.0;
    /** {@code defend} leash: 8 blocks from the player (spec section 2, approved default #4). */
    private static final double DEFEND_LEASH = 8.0;
    /** Proactive hunt: one sphere scan this often (spec section 3). */
    private static final long SCAN_INTERVAL_MS = 500;
    /** A named attack target is retried for this long before giving up honestly. */
    private static final long ATTACK_HINT_WINDOW_MS = 5_000;
    /** Proactive hunt radius around the PLAYER (spec section 3). */
    private static final double HUNT_RADIUS = 20.0;
    /** Crosshair reach for an explicit "attack that ..." command (spec section 3). */
    private static final float CROSSHAIR_RANGE = 12f;
    /** No position progress for this long -> drop the target as unreachable. */
    private static final long NO_PROGRESS_MS = 8_000;
    /** A target this far above/below Laya is unreachable (spec section 3). */
    private static final double MAX_VERTICAL_GAP = 6.0;
    /** A dropped (unreachable) role is ignored for this long (spec section 3). */
    private static final long IGNORE_MS = 30_000;
    /** Movement counts as "progress" past this much horizontal travel. */
    private static final double PROGRESS_EPSILON = 0.25;
    /** Ignore a repath when the target moved less than this (mirrors the ring's own). */
    private static final double TARGET_REPATH_MOVE = 0.25;

    /**
     * Hostile role keywords (spec section 3's parenthetical, extended with Hytale's
     * own role names). Matching is a case-insensitive SUBSTRING on
     * {@link NPCEntity#getRoleName()}. Written fresh for this plugin — the reference
     * pack's list is not copied (MODLOG "Do not").
     */
    private static final String[] HOSTILE_ROLE_KEYWORDS = {
        // spec section 3 seed
        "skeleton", "zombie", "wolf", "bear", "trork", "scarak", "goblin",
        // our own entries, from the shipped role names (predators, undead, void, raiders)
        "wraith", "ghoul", "hound", "werewolf", "outlander",
        "kweebec_razorleaf", "bramblekin", "golem", "yeti", "void",
        "risen", "shadow_knight", "emberwulf", "fen_stalker", "spirit",
        "snapdragon", "tiger", "leopard", "hyena", "scorpion",
        "crawler", "cactee", "piranha", "shark", "crocodile", "snake",
        "rex", "raptor", "trillodon", "hedera", "spider"
    };

    /**
     * Protected passive roles: never a proactive target, whatever else matches.
     * Checked BEFORE the hostile list, so "protected wins over hostile".
     */
    private static final String[] PROTECTED_PASSIVE_ROLE_KEYWORDS = {
        "merchant", "villager", "civilian", "temple", "quest_master",
        "cow", "calf", "sheep", "lamb", "pig", "piglet", "chicken", "chick",
        "deer", "doe", "stag", "horse", "foal", "goat", "kid", "rabbit",
        "bunny", "duck", "turkey", "pigeon", "cat", "dog", "fox", "frog",
        "tamed_", "prisoner", "sapling", "seedling", "sproutling", "rootling",
        "elder", "kweebec_merchant", "klops_gentleman", "klops_miner",
        "tuluk_fisherman", "npc.laya", "laya"
    };

    /** Spawner/debug/test roles: never a target at all (spec section 3, our own list). */
    private static final String[] INVALID_COMBAT_ROLE_KEYWORDS = {
        "spawner", "debug", "test", "_marker", "marker_", "template_", "cinematic",
        "stationary", "static", "component_", "empty_role", "placeholder"
    };

    // --- Task 7: downed state, revive channel and combat persistence (spec section 6) ---
    /**
     * Task 2's Cycle A Probe decision, verbatim. Evidence: {@code laya-plugin.log}
     * 17:59:27-18:00:27 - ONE {@code Probe: Use t=+<ms>} line every ~300-340 ms for the
     * whole 60 s hold (first at +18256 ms, last at +59963 ms), i.e. holding the interact
     * key REPEATS Use. The channel is therefore a literal hold: each Use refreshes the
     * hold and a gap beyond {@link #REVIVE_HOLD_GAP_MS} cancels with reason
     * {@code release}. Recorded in the plan's "Probe decision" box (2026-10-18).
     */
    private static final String REVIVE_USE_MODE = "literal-hold";
    /** Spec section 6: hold interact beside her for this long to pick her up. */
    private static final long REVIVE_CHANNEL_MS = 10_000;
    /** Literal-hold: a Use gap longer than this means the key was let go. */
    private static final long REVIVE_HOLD_GAP_MS = 600;
    /** Spec section 6: the pickup must be started/maintained within this of Laya. */
    private static final double REVIVE_RANGE = 3.0;
    /** Spec section 6 completion: she stands back up at half health. */
    private static final float REVIVE_HP = 50f;
    /** Spec section 6 downed entry: HP is pinned here each second while downed. */
    private static final float DOWNED_HP = 1f;
    /** An out-of-range press is logged once per press, not once per tick. */
    private static final long REVIVE_OUT_OF_RANGE_LOG_MS = 500;
    /** Exact chat lines use the configured companion name; these are the fallbacks. */
    private String companionName() {
        String n = owner.getCompanionName();
        return n == null || n.isBlank() ? "Laya" : n;
    }
    private String downedWarning() {
        return companionName()
            + " is down! Hold your interact key beside her for 10 seconds to pick her up.";
    }
    private String reviveStartChat() {
        return "Picking " + companionName() + " up... (10s)";
    }
    private String reviveDoneChat() {
        return companionName() + " is back on her feet.";
    }
    private static final String REVIVE_CANCEL_CHAT = "Pickup interrupted.";
    /**
     * Task 7 persistence (spec section 6): {"hp","downed","saved_at"}. An absent file
     * means hp 100, not downed. Boot restore happens in ensureSpawned.
     */
    private static final String COMBAT_JSON = System.getenv().getOrDefault(
        "LAYA_COMBAT_JSON", "config/laya-combat.json");
    /** The Status-slot id that poses her (spec section 6: Death, last frame held). */
    private static final String STATUS_DEATH_ANIM = "Sleep";
    /**
     * The Status-slot clear id. Unproven: the Action slot takes {@code null} (see
     * {@link NPCController#stopSwing()}); cycle 6 verifies the Death pose clears here and,
     * if it does not, {@code "Idle"} on the Status slot is the documented fallback.
     */
    private static final String STATUS_CLEAR_ANIM = null;
    /** Boot default when no combat file exists (spec section 6). */
    private static final float DEFAULT_HP = 100f;

    /**
     * The role maximum health, i.e. the denominator of every "x / y" line and the
     * HUD bar. Matches {@code Server/NPC/Roles/Laya.json} (100). Read by the HUD
     * (Task 3); a future per-role lookup only has to change this one value.
     */
    public static final float MAX_HP = 100f;

    private final NPCController owner;

    /**
     * Task 4 loot state. {@code lastWeaponSlot} is the slot the current weapon lives in
     * and {@code lastWeaponSwapSlot} is the slot its equip swapped with, or {@code -1}.
     * Both are needed to guarantee a later better-weapon swap cannot duplicate or
     * destroy a stack (see {@link #equipWeaponInto}): the swap is always source <->
     * active slot, so a stale remembered slot must first be chased down to where the
     * item actually is now. World-thread only.
     */
    private short lastWeaponSlot = -1;
    private short lastWeaponSwapSlot = -1;
    /** Spec section 4: the unarmed line is logged once per engagement, not per swing. */
    private boolean unarmedLogged = false;
    /** Weapon id last handed to the animation helper (see {@link #currentWeaponId()}). */
    private String lastWieldedId = null;
    /** Action-slot id that the engine refused (client logged "No animation with id X"). */
    private String noAnimationId = null;
    private long nextSwingMs = 0;
    /** Bridge-supplied target words for an explicit attack ("kill those pigs"). */
    private volatile String attackHint = null;
    /** Retry deadline for a named target; 0 while none is pending. */
    private volatile long attackHintUntilMs = 0;
    /** Bridge-thread request to draw the best carried weapon for attack/defend. */
    private volatile boolean drawWeaponPending = false;

    /** Bridge-thread "stop/follow/stay/defend/move_to" request, consumed on the world tick. */
    private volatile boolean cancelPending = false;
    /** One out-of-range/dead skip line per engagement, not one per 1.1 s cadence. */
    private boolean swingSkipLogged = false;
    private int swingCount = 0;
    /** Next weapon re-check due (spec section 4: on acquire, then every 5 s). */
    private long nextWeaponCheckMs = 0;

    // --- Task 5: low-HP notices (spec section 5, one per threshold per fight) ---
    /** Spec section 5: a chat notice once at <= 50% and once at <= 25% per fight. */
    private boolean warnedAt50 = false;
    private boolean warnedAt25 = false;

    // --- Task 6: target lifecycle (world-thread only) ---
    /**
     * The reactive stamp: the attacker that most recently hurt the player or Laya,
     * valid until {@link #reactiveUntilMs}. Stamped from the damage filter (which runs
     * on the world thread, same as this controller).
     */
    private Ref<EntityStore> reactiveTarget = null;
    private long reactiveUntilMs = 0;
    /** The entity combat is currently fighting, or null. Drives {@link #ownsMovement()}. */
    private Ref<EntityStore> activeTarget = null;
    /** Provenance of the current target, for the acquire log: crosshair|nearest|reactive. */
    private String targetSource = null;
    /**
     * The target's role name, captured at acquire time. A killed mob's entity/ref can be
     * retired in the same tick, so {@link #roleOf(Ref)} would read null and the
     * {@code Combat: target down <Role>} line (cycle 5's assertion) would be lost; the
     * captured name keeps the log exact.
     */
    private String targetRole = null;
    /** Last observed horizontal position of the target; progress is measured against it. */
    private double targetLastX = 0, targetLastZ = 0;
    /** Laya's own last sampled horizontal position, for the progress watchdog. */
    private double layaLastX = -Double.MAX_VALUE, layaLastZ = -Double.MAX_VALUE;
    private long targetProgressMs = 0;
    /** Next proactive scan (spec section 3: every 500 ms). */
    private long nextScanMs = 0;
    /** One "no target in sight" line per scan episode, not per 500 ms. */
    private boolean noTargetLogged = false;
    /** One "leash" drop line per reactive episode. */
    private boolean leashDropLogged = false;
    /**
     * Set by {@link #setStance} the moment an EXPLICIT {@code attack} cue arrives, so
     * the very next proactive scan checks the crosshair first (spec section 3); cleared
     * once that scan consumes it, so a later scan does not keep favouring the crosshair.
     * Volatile: written on the bridge HTTP thread, read on the world thread.
     */
    private volatile boolean explicitAttackPending = false;
    /**
     * Set by {@link #setStance} when the stance leaves {@code attack}, so the tick drops
     * a proactively-chosen target (a reactive one survives). Volatile, same reason.
     */
    private volatile boolean stanceReleasePending = false;
    /** role name -> ignore-until millis, for dropped (unreachable) targets. */
    private final java.util.Map<String, Long> ignoreUntilByRole = new java.util.HashMap<>();
    /** The last point combat wrote into the follow slot, to throttle repaths. */
    private Vector3d targetLastWrite = null;
    /** One release per episode: the per-fight latches reset only on the first idle tick. */
    private boolean releaseArmed = false;

    /** "follow" | "stay" | "attack" | "defend". Bridge thread writes, world thread reads. */
    private volatile String stance = "follow";

    /**
     * The spot a "stay here" pinned her to. World-thread only. Written by
     * {@link #noteStayAnchor(Vector3d)} when the stay one-shot runs; consumed by the
     * engagement release. Null while no stay anchor exists.
     */
    private Vector3d lastStayAnchor = null;

    /**
     * True once a release has started walking her home, so the "returning to stay
     * anchor" line fires once per stay rather than on every release call. Both latches
     * clear whenever a new anchor is recorded.
     */
    private boolean stayWalkLogged = false;

    /**
     * True once the release has walked her home and re-armed the engine hold, so the
     * "back on the stay anchor" line and the sentinel write happen once per stay
     * rather than on every release call while she is already home. Cleared whenever a
     * new anchor is recorded.
     */
    private boolean stayAnchorHeld = false;

    /** One "stay anchor held, player far away" log line per stay. */
    private boolean stayAnchorFarLogged = false;

    // --- Task 7: downed + revive state (world-thread only, except where noted) ---
    /**
     * True from the lethal hit until a completed pickup. Read from the follow tick's
     * {@code motionLocked} gate ({@link NPCController#startFollowTick}) on the world
     * thread and from {@code pollInteractKey} on the same thread; volatile so a tick that
     * races the entry write never sees a torn value.
     */
    private volatile boolean downed = false;
    /** True while the 10 s revive channel is running (a subset of {@code downed}). */
    private volatile boolean channeling = false;    /** World-thread millis the channel started (progress = now - this). */
    private long channelStartMs = 0;
    /** World-thread millis of the last accepted Use; a gap > 600 ms cancels (literal hold). */
    private long lastUseMs = 0;
    /** Per-second HP-pin latch while downed. */
    private long lastHpPinMs = 0;
    /** Refresh latch for the downed aggro suppression (attitude IGNORE). */
    private long lastAggroSuppressMs = 0;
    /** Once-per-press latch for the out-of-range interact line. */
    private long lastOutOfRangeLogMs = 0;
    /** The persisted HP, so a save always round-trips the last known value. */
    private float savedHp = DEFAULT_HP;

    CombatController(NPCController owner) {
        this.owner = owner;
    }

    /** Current stance string ("follow" | "stay" | "attack" | "defend"). */
    String getStance() {
        return stance;
    }

    /**
     * Task 3: the same value as {@link #getStance()}, under the name the HUD brief
     * pins. The field is private, so this is the package-visible reader the
     * controller's HUD snapshot relays.
     */
    String stance() {
        return stance;
    }

    /**
     * Set the stance and log it. {@code source} is the provenance in the log line:
     * {@code "command"} for a plain follow/stay verbal command, {@code "explicit"}
     * for an attack/defend cue. Called from {@link NPCController#applyCommand}.
     * Log format is fixed by the brief: {@code Combat: stance=<stance> (<source>)}.
     *
     * <p>Ruling #5: the per-fight low-HP notices are re-armed on every stance change
     * (Task 6 will also call {@link #resetHpWarnings()} when an engagement is released),
     * so a new fight always gets its own two notices.
     */
    void setStance(String stance, String source) {
        if (stance == null || stance.isEmpty()) return;
        if (!"attack".equals(stance)) attackHint = null;
        // Task 7 (ruling 3): an `attack` command during the revive channel cancels it
        // with reason `attack` (spec section 6: "swinging/using an item" interrupts the
        // pickup). setStance runs on the bridge HTTP thread; the channel state is
        // world-thread-owned, so the cancel is handed to the world thread.
        boolean cancelChannel = "attack".equals(stance) && channeling;
        this.stance = stance;
        if (cancelChannel) {
            NPCController w = owner;
            w.runOnWorld(() -> cancelRevive("attack"));
        }
        // Task 6: an explicit "attack that ..." cue arms a one-shot crosshair check for
        // the very next proactive scan (spec section 3); any other stance change clears it.
        // Both flags are volatile: setStance runs on the bridge HTTP thread, the tick on
        // the world thread, so the world thread must own all target state (ruling: only
        // the tick mutates activeTarget/target*).
        if ("attack".equals(stance) && "explicit".equals(source)) {
            explicitAttackPending = true;
        } else if (!"attack".equals(stance)) {
            explicitAttackPending = false;
            // Leaving the attack stance must abandon a proactively-chosen target; defer
            // the actual clear to the tick so target state stays single-writer.
            stanceReleasePending = true;
        }
        // A spoken attack/defend order must visibly ready the weapon even when there
        // is no target yet. The tick owns inventory writes, so defer the draw.
        drawWeaponPending = "attack".equals(stance) || "defend".equals(stance);
        resetHpWarnings();
        LayaPlugin.log("Combat: stance=" + stance + " (" + source + ")");
    }

    /**
     * Bridge-thread command hook: an explicit stop/follow/stay/defend/move_to ends the
     * current fight at the next world tick. The tick also drops the reactive stamp, so
     * auto-aggro cannot immediately re-acquire the same attacker the player just told
     * her to stop hitting.
     */
    void requestCancel() {
        cancelPending = true;
    }

    /** Target words from the player's attack command (any NPC role may match). */
    void setAttackHint(String hint) {
        attackHint = hint == null || hint.isBlank() ? null : hint.trim();
        attackHintUntilMs = attackHint == null ? 0 : System.currentTimeMillis() + ATTACK_HINT_WINDOW_MS;
    }

    /** True while a combat target is engaged (drives the HUD state word). */
    boolean hudEngaged() {
        return activeTarget != null;
    }

    /**
     * Movement ownership gate: true while combat is steering her (a live target) or
     * she is downed. Evaluated by {@code startFollowTick} right after
     * {@link #tick(long, Vector3d, Vector3d, double)}; the follow ring, idle face,
     * leash watchdog and stuck watchdog all stand down while it is true.
     */
    boolean ownsMovement() {
        // Task 6: combat steers while a live target is held.
        return activeTarget != null && !isDowned();
    }

    /**
     * True while she is collapsed (spec section 4/9). Kept separate from
     * {@link #ownsMovement()} so a downed Laya never walks — {@code startFollowTick}
     * ORs the two into its {@code motionLocked} gate.
     *
     * Task 7: true for the WHOLE downed state (entry -> pickup), including while the
     * revive channel is running, so nothing in the follow tick writes movement, weapons
     * or swings until the pickup completes.
     */
    boolean isDowned() {
        return downed;
    }

    /**
     * World-thread heartbeat from the follow tick (4 Hz), once per tick.
     *
     * <p>Task 4 keeps the weapon choice fresh: the best carried melee weapon is drawn
     * on engagement and re-evaluated every {@link #WEAPON_RECHECK_MS}, so a better
     * weapon handed to her mid-fight gets used. Task 6 adds the target lifecycle:
     * reactive takeover, target validation (death/leash/progress), the proactive hunt
     * and the swing cadence (via {@link #swingTick(long, Ref)}).
     *
     * @param now           current world-thread milliseconds
     * @param playerPos     the player's position
     * @param layaPos       Laya's position
     * @param distToPlayer  horizontal distance player -> Laya
     */
    void tick(long now, Vector3d playerPos, Vector3d layaPos, double distToPlayer) {
        // Task 7 (spec section 6, ruling 2): while downed NOTHING else in this tick runs
        // — no weapon re-check, no scan, no steering, no swing. The downed branch owns
        // the per-second HP pin, the literal-hold gap check and the channel completion.
        if (downed) {
            downedTick(now);
            return;
        }
        // An explicit command that ends the fight (stop / follow / stay / defend /
        // move_to) wins over auto-aggro: clear the target AND the reactive stamp,
        // otherwise the next tick re-acquires the same attacker. The old behavior
        // only dropped "nearest" targets, so "nevermind stop" left her swinging a
        // target chosen by crosshair until it died.
        if (cancelPending) {
            cancelPending = false;
            clearTarget();
            reactiveTarget = null;
            reactiveUntilMs = 0;
            releaseArmed = false;
            noTargetLogged = false;
        }
        // Task 4: keep the weapon in hand fresh DURING a fight only. Re-checking
        // while idle logged (and re-selected) the weapon every 5 s forever; acquire()
        // draws the weapon on engagement, so idle needs no work here.
        if (activeTarget != null && now >= nextWeaponCheckMs) {
            nextWeaponCheckMs = now + WEAPON_RECHECK_MS;
            ensureWeaponEquipped(now);
        }
        if (owner.layaRef == null || owner.worldStore == null) return;

        // A spoken attack/defend order readies the best carried weapon immediately,
        // even when no enemy is in range yet. The bridge thread only sets the flag;
        // inventory writes stay here on the world thread.
        if (drawWeaponPending) {
            drawWeaponPending = false;
            ensureWeaponEquipped(now);
        }

        // (1) A live reactive stamp becomes the target (any stance; spec section 2).
        maybeTakeReactive(now, playerPos, layaPos);

        // (1b) A stance change away from attack abandons a proactively-chosen target
        //      (a reactive one survives — auto-aggro is stance-independent).
        if (stanceReleasePending) {
            stanceReleasePending = false;
            if (activeTarget != null && "nearest".equals(targetSource)) {
                clearTarget();
            }
            noTargetLogged = false;
        }

        // (2) Validate the live target: died -> down; out of leash -> drop; no progress
        //     or a vertical gap -> drop as unreachable. A drop just clears the target —
        //     step (5) then releases movement exactly like an idle tick would.
        if (activeTarget != null) {
            validateTarget(now, playerPos, layaPos);
        }

        // (3) Proactive hunt: attack stance only, no reactive target, every 500 ms.
        //     The reactiveTarget check matters: a live stamp beyond the leash leaves
        //     activeTarget null (maybeTakeReactive deliberately keeps the stamp), and
        //     without this the scan would grab a "nearest" target and contradict the
        //     brief's "no reactive target" gate.
        if (activeTarget == null && reactiveTarget == null) {
            maybeHunt(now, playerPos, layaPos);
        }

        // (4) Steer her at the target (or stand and swing) while combat owns movement.
        if (activeTarget != null) {
            steerAndSwing(now, layaPos);
        } else {
            // (5) Nothing engaged: release movement back to the stance, once per episode.
            releaseToStance(playerPos, layaPos, distToPlayer);
        }
    }

    // --- Task 6: reactive acquisition ---

    /**
     * Promote a live reactive stamp into the target. Stance-independent (spec section 2:
     * "she immediately retaliates, whatever stance she is in"), bounded by the stance's
     * leash from the player (16 auto-aggro, 8 defend) and by {@link #REACTIVE_MEMORY_MS}.
     * The acquire path (task cancel + weapon draw + log) lives in {@link #acquire}.
     */
    private void maybeTakeReactive(long now, Vector3d playerPos, Vector3d layaPos) {
        if (reactiveTarget == null) return;
        if (now >= reactiveUntilMs || !isValidEntity(reactiveTarget)) {
            reactiveTarget = null;
            leashDropLogged = false;
            return;
        }
        if (activeTarget != null && sameRef(activeTarget, reactiveTarget)) return;   // already on it
        if (isForbiddenTarget(reactiveTarget)) {
            reactiveTarget = null;
            return;
        }
        // Leash from the PLAYER (spec section 3): defend is 8, every other stance 16.
        double leash = "defend".equals(stance) ? DEFEND_LEASH : AUTO_AGGRO_LEASH;
        double distFromPlayer = distanceXZ(playerPos, positionOf(reactiveTarget));
        if (distFromPlayer > leash) {
            if (!leashDropLogged) {
                leashDropLogged = true;
                LayaPlugin.log("Combat: target dropped (leash " + trimDamage((float) leash) + ") "
                    + roleOf(reactiveTarget));
            }
            // Keep the stamp: the 15 s memory survives, so she engages the moment the
            // player closes back inside the leash (the latch keeps the line once).
            return;
        }
        leashDropLogged = false;
        // A reactive attacker is always fair game (even a passive role that hit us).
        acquire(now, reactiveTarget, "reactive", layaPos);
    }

    /**
     * Take {@code ref} as the target and run the acquire flow once (spec section 3): the
     * source log line, then the task cancel, then the weapon draw and engagement arming.
     */
    private void acquire(long now, Ref<EntityStore> ref, String source, Vector3d layaPos) {
        if (ref == null || isForbiddenTarget(ref)) return;
        boolean fresh = activeTarget == null || !sameRef(activeTarget, ref);
        activeTarget = ref;
        targetSource = source;
        targetRole = roleOf(ref);
        leashDropLogged = false;
        if (fresh) {
            Vector3d tp = positionOf(ref);
            double d = layaPos == null || tp == null ? Double.MAX_VALUE : distanceXZ(layaPos, tp);
            LayaPlugin.log("Combat: target " + roleOf(ref) + " dist="
                + (d == Double.MAX_VALUE ? "?" : trimDamage((float) d)) + " source=" + source);
            owner.cancelTaskForCombat();   // a live mine/gather task loses movement
            armEngagement();               // Task 4: reset per-fight state + draw now
            ensureWeaponEquipped(now);
            targetProgressMs = now;
            noTargetLogged = false;
            releaseArmed = false;
            layaLastX = layaPos == null ? -Double.MAX_VALUE : layaPos.x;
            layaLastZ = layaPos == null ? -Double.MAX_VALUE : layaPos.z;
            if (tp != null) {
                targetLastX = tp.x;
                targetLastZ = tp.z;
            }
        }
    }

    // --- Task 6: target validation / drop ---

    /**
     * Keep the target while it is still worth fighting, else clear it. Drops on: death
     * ({@code hpOf <= 0}), or a vanished entity (both logged as {@code target down}); no
     * position progress for {@link #NO_PROGRESS_MS}; a vertical gap beyond
     * {@link #MAX_VERTICAL_GAP} (the last two add the role to the 30 s ignore set, and
     * log {@code target dropped (unreachable)}). Clearing is enough — {@link #tick}
     * releases movement on the same tick.
     */
    private void validateTarget(long now, Vector3d playerPos, Vector3d layaPos) {
        Ref<EntityStore> target = activeTarget;
        if (!isValidEntity(target)) {
            // The entity/ref was retired before the HP read. A vanished target is treated
            // as a kill for the log (cycle 5 asserts `Combat: target down <Role>`); the
            // captured role keeps the line exact when the component is already gone.
            LayaPlugin.log("Combat: target down " + lastKnownRole(target));
            dropReactiveStamp(target);
            clearTarget();
            return;
        }
        float hp = hpOf(target);
        if (hp >= 0f && hp <= 0f) {
            LayaPlugin.log("Combat: target down " + lastKnownRole(target));
            dropReactiveStamp(target);
            clearTarget();
            return;
        }
        Vector3d tp = positionOf(target);
        if (tp == null) {
            clearTarget();
            return;
        }
        // Vertical gap (spec section 3): more than 3 blocks above/below is unreachable.
        if (layaPos != null && Math.abs(tp.y - layaPos.y) > MAX_VERTICAL_GAP) {
            dropUnreachable(now, target,
                "vertical gap " + trimDamage((float) Math.abs(tp.y - layaPos.y)));
            return;
        }
        // The 16/8 leash is a REACTIVE rule only (auto-aggro / defend, spec section 3):
        // a proactive attack-stance target is governed by the 20-block hunt radius it
        // was chosen within and by the no-progress / vertical-gap drops below, NOT by
        // the leash — applying it here dropped (and re-acquired) any 16-20 block target
        // every tick, spamming the drop line.
        if ("reactive".equals(targetSource)) {
            double leash = "defend".equals(stance) ? DEFEND_LEASH : AUTO_AGGRO_LEASH;
            if (distanceXZ(playerPos, tp) > leash) {
                LayaPlugin.log("Combat: target dropped (leash " + trimDamage((float) leash) + ") "
                    + lastKnownRole(target));
                clearTarget();
                return;
            }
        }
        // Progress watchdog: the target OR Laya must have moved since the last sample.
        boolean progressed = now - targetProgressMs <= NO_PROGRESS_MS;
        if (Math.hypot(tp.x - targetLastX, tp.z - targetLastZ) > PROGRESS_EPSILON) {
            progressed = true;
        } else if (layaPos != null && layaLastX >= -1e6
                && Math.hypot(layaPos.x - layaLastX, layaPos.z - layaLastZ) > PROGRESS_EPSILON) {
            progressed = true;
        }
        if (progressed) {
            targetLastX = tp.x;
            targetLastZ = tp.z;
            targetProgressMs = now;
        }
        if (layaPos != null) {
            layaLastX = layaPos.x;
            layaLastZ = layaPos.z;
        } else {
            layaLastX = -Double.MAX_VALUE;
        }
        if (!progressed) {
            dropUnreachable(now, target, "no progress " + ((now - targetProgressMs) / 1000) + "s");
        }
    }

    /** Drop the target as unreachable: 30 s ignore set on its role + clear (spec section 3). */
    private void dropUnreachable(long now, Ref<EntityStore> target, String why) {
        String role = roleOf(target);
        LayaPlugin.log("Combat: target dropped (unreachable) " + role + " (" + why + ")");
        if (role != null) ignoreUntilByRole.put(role, now + IGNORE_MS);
        clearTarget();
    }

    /**
     * Combat Task 1: forget the reactive stamp when it points at {@code target}.
     *
     * A reactive stamp (auto-aggro / defend memory) outlives the target on purpose, but
     * not when the target is dead or gone: {@link #maybeAcquireReactive} only checks
     * {@link #isValidEntity}, so a ref that is still nominally valid (e.g. a corpse with
     * {@code hp <= 0}) would be re-acquired every tick, re-logging {@code target down}.
     * Clearing both the ref and its deadline makes the stamp die with the entity.
     */
    private void dropReactiveStamp(Ref<EntityStore> target) {
        if (reactiveTarget != null && sameRef(reactiveTarget, target)) {
            reactiveTarget = null;
            reactiveUntilMs = 0;
        }
    }

    /** Drop the current target; the stance regains movement on this same tick. */
    private void clearTarget() {
        activeTarget = null;
        targetSource = null;
        targetRole = null;
        targetProgressMs = 0;
        targetLastWrite = null;
        releaseArmed = false;
        layaLastX = -Double.MAX_VALUE;
        layaLastZ = -Double.MAX_VALUE;
    }

    /**
     * The target's role for a drop/death log: the name captured at acquire time (which
     * survives the entity's retirement) when it is known, else a fresh read.
     */
    private String lastKnownRole(Ref<EntityStore> ref) {
        return targetRole != null && !targetRole.isEmpty() ? targetRole : roleOf(ref);
    }

    // --- Task 6: proactive hunt ---

    /**
     * Attack stance only, no reactive target (spec section 3). Every
     * {@link #SCAN_INTERVAL_MS}: the crosshair first, but only on the scan right after an
     * explicit "attack that ..." cue; otherwise the nearest hostile-keyword NPC within
     * {@link #HUNT_RADIUS} of the player. With nothing found, one
     * {@code Combat: no target in sight} line per scan episode.
     */
    private void maybeHunt(long now, Vector3d playerPos, Vector3d layaPos) {
        if (!"attack".equals(stance)) return;
        if (explicitAttackPending) {
            explicitAttackPending = false;
            Ref<EntityStore> crosshair = crosshairTarget();
            // An explicit crosshair target allows ANY NPC, animals included (spec section 3).
            if (crosshair != null && isValidEntity(crosshair) && !isForbiddenTarget(crosshair)) {
                attackHint = null;
                attackHintUntilMs = 0;
                acquire(now, crosshair, "crosshair", layaPos);
                return;
            }
        }
        // A named target is retried on every scan for a few seconds: the first scan can
        // run before the animal is inside HUNT_RADIUS ("attack bear" missed it once and
        // only found it on the next scan). Outside the window it gives up honestly.
        String hint = attackHint;
        if (hint != null) {
            Ref<EntityStore> byHint = nearestByHint(playerPos, hint, now);
            if (byHint != null) {
                attackHint = null;
                attackHintUntilMs = 0;
                acquire(now, byHint, "hint", layaPos);
                return;
            }
            if (now < attackHintUntilMs) {
                nextScanMs = now + 500;
                return;
            }
            attackHint = null;
            attackHintUntilMs = 0;
            LayaPlugin.log("Combat: no target matching \"" + hint + "\"");
            nextScanMs = 0;   // the explicit cue also forces the nearest scan now
        }
        if (now < nextScanMs) return;
        nextScanMs = now + SCAN_INTERVAL_MS;

        Ref<EntityStore> nearest = nearestHostile(playerPos, now);
        if (nearest != null) {
            acquire(now, nearest, "nearest", layaPos);
            return;
        }
        if (!noTargetLogged) {
            noTargetLogged = true;
            LayaPlugin.log("Combat: no target in sight");
        }
    }

    /** The crosshair target within {@link #CROSSHAIR_RANGE} of the player, or null. */
    private Ref<EntityStore> crosshairTarget() {
        try {
            Ref<EntityStore> player = entityRefOfPlayer();
            if (player == null) return null;
            return TargetUtil.getTargetEntity(player, CROSSHAIR_RANGE, owner.worldStore);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The nearest hostile-keyword NPC within {@link #HUNT_RADIUS} of the player, skipping
     * protected passives, invalid/debug roles, the ignored set, Laya and the player.
     */
    private Ref<EntityStore> nearestHostile(Vector3d playerPos, long now) {
        Ref<EntityStore> best = null;
        double bestDist = Double.MAX_VALUE;
        try {
            java.util.List<Ref<EntityStore>> candidates = TargetUtil.getAllEntitiesInSphere(
                playerPos, HUNT_RADIUS, owner.worldStore);
            if (candidates == null) return null;
            for (Ref<EntityStore> candidate : candidates) {
                if (!isValidEntity(candidate)) continue;
                if (isForbiddenTarget(candidate)) continue;
                String role = roleOf(candidate);
                if (role == null || !isHostileRole(role)) continue;
                Long until = ignoreUntilByRole.get(role);
                if (until != null && now < until) continue;
                double d = distanceXZ(playerPos, positionOf(candidate));
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
        } catch (Throwable t) {
            LayaPlugin.log("Combat: scan failed: " + t);
        }
        return best;
    }

    private static final String[] HINT_STOPWORDS = {
        "kill", "attack", "fight", "the", "that", "this", "those", "these", "animal",
        "mob", "monster", "creature", "near", "by", "please", "some", "all", "and",
        "for", "with", "help", "me", "her", "him", "it", "them", "a", "an", "you",
        "can", "could", "would", "will", "go", "get", "take", "out", "deal", "thing",
        "things", "there", "here", "over", "right", "now"
    };

    /**
     * Nearest NPC whose role matches a meaningful word from the attack command. Unlike
     * the proactive hunt this allows protected passives (cows, pigs, ...) because the
     * player explicitly named the animal (spec section 3: explicit attacks allow any NPC).
     *
     * <p>Tokens are matched fuzzily, not only by substring: "attack that squirell" (one
     * wrong letter) must still find role {@code Squirrel}, or the explicit request falls
     * through to a hostile 20 blocks away. Short tokens stay exact so "bear" cannot match
     * a boar.</p>
     */
    private Ref<EntityStore> nearestByHint(Vector3d playerPos, String hint, long now) {
        Ref<EntityStore> best = null;
        double bestDist = Double.MAX_VALUE;
        String lowerHint = hint.toLowerCase();
        try {
            java.util.List<Ref<EntityStore>> candidates = TargetUtil.getAllEntitiesInSphere(
                playerPos, HUNT_RADIUS, owner.worldStore);
            if (candidates == null) return null;
            for (Ref<EntityStore> candidate : candidates) {
                if (!isValidEntity(candidate) || isForbiddenTarget(candidate)) continue;
                String role = roleOf(candidate);
                if (role == null) continue;
                String roleLower = role.toLowerCase();
                boolean match = false;
                for (String raw : lowerHint.split("[^a-z0-9_]+")) {
                    if (raw.length() < 3 || isHintStopword(raw)) continue;
                    String singular = raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
                    if (hintTokenMatches(roleLower, raw) || hintTokenMatches(roleLower, singular)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                Long until = ignoreUntilByRole.get(role);
                if (until != null && now < until) continue;
                double d = distanceXZ(playerPos, positionOf(candidate));
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
        } catch (Throwable t) {
            LayaPlugin.log("Combat: hint scan failed: " + t);
        }
        return best;
    }

    /**
     * One spoken word against one role name: substring, then prefix, then a small edit
     * distance. Fuzzy only from 6 characters up, so bear/boar stay distinct.
     */
    static boolean hintTokenMatches(String roleLower, String token) {
        if (roleLower.contains(token)) return true;
        for (String part : roleLower.split("[^a-z0-9]+")) {
            if (part.length() < 3) continue;
            if (part.startsWith(token) || token.startsWith(part)) return true;
            int max = token.length() >= 8 ? 2 : (token.length() >= 6 ? 1 : 0);
            if (max == 0) continue;
            if (Math.abs(part.length() - token.length()) > max) continue;
            if (levenshtein(part, token, max) <= max) return true;
        }
        return false;
    }

    /** Bounded edit distance (returns {@code max + 1} as soon as it cannot fit). */
    static int levenshtein(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > max) return max + 1;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    private static boolean isHintStopword(String w) {
        for (String s : HINT_STOPWORDS) if (s.equals(w)) return true;
        return false;
    }

    // --- Task 6: role/ref helpers ---

    /** The role name of an entity, or "target" when it cannot be read. */
    private String roleOf(Ref<EntityStore> ref) {
        try {
            if (ref == null || owner.worldStore == null) return "target";
            NPCEntity npc = owner.worldStore.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) return "target";
            String role = npc.getRoleName();
            return role == null || role.isEmpty() ? "target" : role;
        } catch (Throwable t) {
            return "target";
        }
    }

    /** An entity's world position, or null when it cannot be read. */
    private Vector3d positionOf(Ref<EntityStore> ref) {
        try {
            if (ref == null || owner.worldStore == null) return null;
            TransformComponent t = owner.worldStore.getComponent(ref, TransformComponent.getComponentType());
            return t == null ? null : t.getPosition();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the ref still names a live entity in the store. Never throws. */
    private boolean isValidEntity(Ref<EntityStore> ref) {
        try {
            return ref != null && ref.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The player's entity ref, or null. */
    private Ref<EntityStore> entityRefOfPlayer() {
        try {
            return owner.playerRef == null ? null : owner.playerRef.getReference();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ref identity (index + store; {@code Ref} does not override equals). */
    private static boolean sameRef(Ref<EntityStore> a, Ref<EntityStore> b) {
        return a != null && b != null && a.getIndex() == b.getIndex() && a.getStore() == b.getStore();
    }

    /** Horizontal distance between two points, or {@code Double.MAX_VALUE} on a null. */
    private static double distanceXZ(Vector3d a, Vector3d b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /**
     * Never fight the player or Laya herself. Laya's own role never matches the hostile
     * lists anyway, but a reactive stamp could name her clone or the player, so the check
     * is explicit (spec section 3: "Never targets the player or Laya").
     */
    private boolean isForbiddenTarget(Ref<EntityStore> ref) {
        if (ref == null) return true;
        if (owner.layaRef != null && sameRef(owner.layaRef, ref)) return true;
        Ref<EntityStore> player = entityRefOfPlayer();
        if (player != null && sameRef(player, ref)) return true;
        String role = roleOf(ref);
        return "laya".equalsIgnoreCase(role) || role != null && role.toLowerCase().contains("laya");
    }

    /**
     * A proactive candidate: it must be an NPC whose role matches a hostile keyword.
     * Protected passives and invalid roles act as a veto; the match is a case-insensitive
     * substring (spec section 3).
     */
    private boolean isHostileRole(String role) {
        if (role == null || role.isEmpty()) return false;
        String lower = role.toLowerCase();
        for (String kw : INVALID_COMBAT_ROLE_KEYWORDS) {
            if (lower.contains(kw)) return false;
        }
        for (String kw : PROTECTED_PASSIVE_ROLE_KEYWORDS) {
            if (lower.contains(kw)) return false;   // protected wins over hostile
        }
        for (String kw : HOSTILE_ROLE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    // --- Task 6: movement ownership while a target is live ---

    /**
     * Write the target's position into the follow slot and repath on a meaningful move,
     * then run the swing cadence. The follow ring, idle face, leash and stuck watchdogs
     * are all gated off while {@link #ownsMovement()} is true.
     */
    private void steerAndSwing(long now, Vector3d layaPos) {
        Vector3d tp = positionOf(activeTarget);
        if (tp != null) {
            if (owner.writeFollowSlot(tp)) {
                if (targetLastWrite == null || targetLastWrite.distance(tp) > TARGET_REPATH_MOVE) {
                    targetLastWrite = new Vector3d(tp);
                    owner.refreshPath();
                }
            }
            // The role's HeadMotion Watch keeps her head on the PLAYER, so while
            // fighting she looked at the player instead of the mob. Override the head
            // whenever the target is near, and the body at melee range.
            if (layaPos != null) {
                double d = distanceXZ(layaPos, tp);
                if (d <= 12.0) faceHead(layaPos, tp);
                if (d <= SWING_RANGE + 0.5) faceBody(layaPos, tp);
            }
        }
        swingTick(now, activeTarget);
    }

    /**
     * Turn the body toward a point, same step limit as the idle head/body focus in
     * {@code NPCController.facePlayerWhenIdle} (120 deg per 333 ms tick = 360 deg/s).
     * World-thread only.
     */
    private void faceBody(Vector3d layaPos, Vector3d targetPos) {
        if (owner.layaRef == null || owner.worldStore == null) return;
        TransformComponent layaT = owner.worldStore.getComponent(
            owner.layaRef, TransformComponent.getComponentType());
        if (layaT == null) return;
        float targetYaw = PhysicsMath.headingFromDirection(
            targetPos.x - layaPos.x, targetPos.z - layaPos.z);
        Rotation3f rot = layaT.getRotation();
        float cur = rot.yaw();
        float delta = PhysicsMath.normalizeTurnAngle(targetYaw - cur);
        float maxStep = (float) Math.toRadians(120.0);
        if (delta > maxStep) delta = maxStep;
        else if (delta < -maxStep) delta = -maxStep;
        rot.setYaw(PhysicsMath.normalizeTurnAngle(cur + delta));
    }

    /** Override the role's Watch: the head looks at the mob, not the player, while fighting. */
    private void faceHead(Vector3d layaPos, Vector3d targetPos) {
        if (owner.layaRef == null || owner.worldStore == null) return;
        HeadRotation hr = owner.worldStore.getComponent(
            owner.layaRef, HeadRotation.getComponentType());
        if (hr == null) return;
        float targetYaw = PhysicsMath.headingFromDirection(
            targetPos.x - layaPos.x, targetPos.z - layaPos.z);
        Rotation3f rot = hr.getRotation();
        float cur = rot.yaw();
        float delta = PhysicsMath.normalizeTurnAngle(targetYaw - cur);
        float maxStep = (float) Math.toRadians(150.0);
        if (delta > maxStep) delta = maxStep;
        else if (delta < -maxStep) delta = -maxStep;
        rot.setYaw(PhysicsMath.normalizeTurnAngle(cur + delta));
    }

    /**
     * Release movement back to the stance (spec section 9): {@code stay} calls
     * {@link #releaseMovement} (anchor return); {@code follow}/{@code attack} hand the
     * ring back (its own re-anchor logic re-arms it); {@code defend} holds its ring. Also
     * re-arms the per-fight HP notices, once per episode.
     */
    private void releaseToStance(Vector3d playerPos, Vector3d layaPos, double distToPlayer) {
        if (!releaseArmed) {
            releaseArmed = true;
            targetLastWrite = null;
            resetHpWarnings();   // ruling: reset on release
            // Clear the held Action frame and the Status pose: a swing/hit Status
            // animation can remain after the fight and freeze her combat face.
            owner.stopSwing();
            playStatusAnimation(STATUS_CLEAR_ANIM);
        }
        if ("stay".equals(stance)) {
            releaseMovement(playerPos, layaPos, distToPlayer);
        }
    }

    /**
     * Record the spot a "stay here" pinned her to (world thread, from the stay
     * one-shot in {@code startFollowTick}). The engagement release walks her back
     * here after a fight.
     */
    void noteStayAnchor(Vector3d anchor) {
        lastStayAnchor = anchor == null ? null : new Vector3d(anchor);
        stayWalkLogged = false;    // a fresh anchor always needs a fresh return walk
        stayAnchorHeld = false;
        stayAnchorFarLogged = false;
    }

    /** The stored stay anchor, or null when she is not holding a spot. */
    Vector3d getStayAnchor() {
        return lastStayAnchor == null ? null : new Vector3d(lastStayAnchor);
    }

    /**
     * Engagement release (spec section 9): combat has finished with its target and
     * hands movement back to the stance. For {@code stay} that means walking back to
     * the anchor:
     *  - the anchor goes back into the follow slot ({@link NPCController#writeFollowSlot})
     *    plus a repath, so the role's Seek walks her home;
     *  - once she is within 0.5 blocks the slot is sentineled with
     *    {@link NPCController#CLEARED_POSITION} again so the engine holds her there;
     *  - if the player is more than 32 blocks away, one log line records it and the
     *    anchor still holds. Stay is absolute: she does not follow the player, and she
     *    does not get teleported to them (that turned "hold here" into a 32-block timer,
     *    measured 2026-10-26 on a fresh install).
     *
     * Any non-stay stance needs nothing here: the follow-ring anchor re-arms itself
     * (bootstrap / stay-clear / leash / move_to all null it, and the ring recomputes
     * whenever the player has moved past {@code FOLLOW_START_BUFFER}).
     *
     * World-thread only. Task 6 calls this when a target is dropped; Task 3 ships it
     * with no caller yet.
     */
    void releaseMovement(Vector3d playerPos, Vector3d layaPos, double distToPlayer) {
        if (!"stay".equals(stance) || lastStayAnchor == null) return;
        final Vector3d anchor = lastStayAnchor;

        if (playerPos != null && distToPlayer > STAY_ANCHOR_RELEASE_DISTANCE) {
            // Stay is absolute. The old code teleported her to the player past 32 blocks,
            // which is exactly the "hold here, then she appears next to me" bug.
            if (!stayAnchorFarLogged) {
                stayAnchorFarLogged = true;
                LayaPlugin.log("Combat: stay anchor held (player "
                    + String.format("%.1f", distToPlayer) + " blocks away), not leashing");
            }
        }

        final double adx = anchor.x - layaPos.x;
        final double adz = anchor.z - layaPos.z;
        final double anchorDist = Math.sqrt(adx * adx + adz * adz);

        if (anchorDist <= STAY_ANCHOR_ARRIVED_DISTANCE) {
            if (stayAnchorHeld) return;   // already home and holding — nothing to do
            // Home: hand the hold back to the engine (the sentinel the stay one-shot uses).
            if (owner.writeFollowSlot(NPCController.CLEARED_POSITION)) {
                owner.refreshPath();
                stayAnchorHeld = true;
                // Task 6 (ruling 6): re-anchor the follow ring cleanly on the frame that
                // the stay hold is restored — the same bookkeeping the stay one-shot does.
                owner.activeTarget = false;
                owner.followAnchor = null;
                owner.lastWrittenSlot = null;
                LayaPlugin.log("Combat: back on the stay anchor — holding position");
            }
            return;
        }

        // Still walking home: keep the anchor in the slot so the role's Seek carries on.
        if (owner.writeFollowSlot(anchor)) {
            owner.refreshPath();
            if (!stayWalkLogged) {
                stayWalkLogged = true;
                LayaPlugin.log("Combat: returning to stay anchor ("
                    + String.format("%.1f", anchorDist) + " blocks)");
            }
        }
    }

    /**
     * Cancel a live mine/gather task so combat can take movement (world thread).
     * Delegates to {@link NPCController#cancelTaskForCombat()}, the single
     * implementation of the cancel — no extra chat line (spec: no extra events).
     * Called by Task 6 the first time combat takes movement for a target.
     */
    void cancelTask() {
        owner.cancelTaskForCombat();
    }

    /**
     * Task 4 hook for Task 6: a NEW fight has started, so the per-engagement state is
     * re-armed — the unarmed line may fire again, the weapon is drawn immediately
     * (spec section 4: "on target acquire") and the swing cadence starts from now.
     * World-thread only.
     */
    void armEngagement() {
        unarmedLogged = false;
        swingSkipLogged = false;
        nextSwingMs = 0;
        nextWeaponCheckMs = 0;
        swingCount = 0;
    }

    /** World-thread teleport to the player's side, mirroring the leash watchdog. */
    private boolean teleportToPlayerSide(Vector3d playerPos) {
        if (owner.layaRef == null || owner.worldStore == null) return false;
        try {
            TransformComponent layaT = owner.worldStore.getComponent(
                owner.layaRef, TransformComponent.getComponentType());
            if (layaT == null) return false;
            layaT.teleportPosition(new Vector3d(
                playerPos.x + PLAYER_SIDE_OFFSET, playerPos.y, playerPos.z + PLAYER_SIDE_OFFSET));
            owner.refreshPath();
            return true;
        } catch (Throwable t) {
            LayaPlugin.log("Combat: stay-anchor leash teleport failed: " + t);
            return false;
        }
    }

    // --- Task 4: weapon choice, equip, swing cadence and outbound damage ---

    /**
     * The entity carrying a weapon stack: the container it is in, its slot in that
     * container, and a walking copy of the stack. {@code stack} is never mutated, so a
     * caller can assign it into a container without aliasing anything it does not own.
     */
    private static final class WeaponRef {
        final ItemContainer container;
        final short slot;
        final ItemStack stack;
        final String id;
        final int quality;
        final int level;
        /** One of {@link #MELEE_CLASS_ORDER}, or "" for an unrecognised melee id. */
        final String weaponClass;

        WeaponRef(ItemContainer container, short slot, ItemStack stack) {
            this.container = container;
            this.slot = slot;
            this.stack = stack;
            this.id = stack.getItemId() == null ? "" : stack.getItemId();
            Item item = stack.getItem();
            this.quality = item == null ? Integer.MIN_VALUE : item.getQualityIndex();
            this.level = item == null ? Integer.MIN_VALUE : item.getItemLevel();
            this.weaponClass = weaponClassOf(this.id);
        }

        /** Spec section 4: axe > sword > mace > spear > dagger, unknown last. */
        int classRank() {
            for (int i = 0; i < MELEE_CLASS_ORDER.length; i++) {
                if (MELEE_CLASS_ORDER[i].equals(weaponClass)) return i;
            }
            return MELEE_CLASS_ORDER.length;
        }
    }

    /**
     * Spec section 4: rank by quality desc, then item level desc, then the class
     * tie-break axe &gt; sword &gt; mace &gt; spear &gt; dagger; 0 means equal. A stack
     * whose item config is missing ranks last at every level ({@code getItem()==null}),
     * because its quality/level are the {@code MIN_VALUE} sentinels.
     */
    private static int compareWeapons(WeaponRef a, WeaponRef b) {
        if (a.quality != b.quality) return Integer.compare(b.quality, a.quality);
        if (a.level != b.level) return Integer.compare(b.level, a.level);
        return Integer.compare(a.classRank(), b.classRank());
    }

    /**
     * The melee class of a weapon id, or null when the item is not a melee weapon.
     * Ranged weapons (bow, crossbow, staff, gun), shields and tools never qualify
     * (spec section 4: melee-only v1). Daggers/Clubs are pluralised in the assets,
     * so both spellings are accepted.
     */
    private static String weaponClassOf(String itemId) {
        if (itemId == null || !itemId.startsWith("Weapon_")) return null;
        if (itemId.startsWith("Weapon_Daggers")) return "dagger";   // must precede Dagger
        if (itemId.startsWith("Weapon_Dagger")) return "dagger";
        if (itemId.startsWith("Weapon_Longsword")) return "sword";  // must precede Sword
        if (itemId.startsWith("Weapon_Battleaxe")) return "axe";    // must precede Axe
        if (itemId.startsWith("Weapon_Sword")) return "sword";
        if (itemId.startsWith("Weapon_Axe")) return "axe";
        if (itemId.startsWith("Weapon_Mace")) return "mace";
        if (itemId.startsWith("Weapon_Club")) return "mace";
        if (itemId.startsWith("Weapon_Spear")) return "spear";
        return null;   // Bow / Crossbow / Staff / Gun / Shield / anything else
    }

    /**
     * Spec section 4: every melee weapon in her six inventory sections
     * (hotbar, storage, tools, backpack, utility, armor), best first. A non-melee
     * item — including a ranged weapon, a shield or a tool — never appears.
     */
    private java.util.List<WeaponRef> findWeapons() {
        java.util.List<WeaponRef> found = new java.util.ArrayList<>();
        if (owner.npcEntity == null) return found;
        Inventory inv;
        try {
            inv = owner.npcEntity.getInventory();
        } catch (Throwable t) {
            return found;
        }
        if (inv == null) return found;
        ItemContainer[] sections = {
            inv.getHotbar(), inv.getStorage(), inv.getTools(),
            inv.getBackpack(), inv.getUtility(), inv.getArmor()
        };
        for (ItemContainer section : sections) {
            if (section == null) continue;
            for (short s = 0; s < section.getCapacity(); s++) {
                ItemStack st;
                try {
                    st = section.getItemStack(s);
                } catch (Throwable ignored) {
                    continue;
                }
                if (st == null || ItemStack.isEmpty(st)) continue;
                if (weaponClassOf(st.getItemId()) == null) continue;
                found.add(new WeaponRef(section, s, st));
            }
        }
        found.sort(CombatController::compareWeapons);
        return found;
    }

    /**
     * Spec section 4: draw her best carried melee weapon. Called by Task 6 when a
     * target is acquired and every {@link #WEAPON_RECHECK_MS} during a fight, so a
     * better weapon handed to her mid-fight is picked up. With no qualifying weapon
     * she fights unarmed: {@code SwingRight} for {@link #UNARMED_DAMAGE}, logged once
     * per engagement. World-thread only.
     *
     * <p>The {@code -> hotbar N} line is emitted ONLY when the equip really landed: a
     * failed swap logs the failure (from {@link #equipWeaponInto}) and nothing else, so
     * a "weapon at hotbar N" line can never contradict the end state.
     */
    void ensureWeaponEquipped(long now) {
        if (owner.npcEntity == null || owner.layaRef == null || owner.worldStore == null) return;
        java.util.List<WeaponRef> weapons = findWeapons();
        if (weapons.isEmpty()) {
            noAnimationId = null;
            if (!unarmedLogged) {
                unarmedLogged = true;
                LayaPlugin.log("Combat: unarmed (no melee weapon) dmg=" + trimDamage(UNARMED_DAMAGE));
            }
            return;
        }
        WeaponRef best = weapons.get(0);
        if (!equipWeaponInto(best)) return;   // the failure was already logged truthfully
        LayaPlugin.log("Combat: weapon " + best.id + " quality=" + best.quality
            + " level=" + best.level + " -> hotbar " + owner.npcEntity.getInventory().getActiveHotbarSlot());
    }

    /**
     * Move {@code chosen}'s stack into the ACTIVE hotbar slot and select it, swapping
     * the old active item back into the slot the weapon came from (spec section 4).
     *
     * <p>No item is ever duplicated or destroyed. The swap has to name BOTH slots —
     * the gift pattern's {@code moveItemStackFromSlot(slot, qty, dest)} is an
     * <em>add-anywhere</em> call whose destination slot the engine picks, so it can never
     * write into a chosen (and normally occupied) active slot; it would land the weapon
     * somewhere else entirely while we reported success.
     *
     * <p>Both stacks are read into locals UP FRONT and every write is one of:
     *  - a targeted engine move ({@code moveItemStackFromSlotToSlot}); or
     *  - a {@code setItemStackForSlot} write that only ever overwrites a slot already read
     *    into a local — the weapon to {@code activeSlot} first (its source slot is left
     *    holding the same stack until the weapon has landed, so nothing is ever
     *    unreferenced), then the old active item into the now-free source slot.
     *
     * <p>A destination whose slot holds a different stack must never be written over
     * without that stack being held in a local first, and every branch verifies the end
     * state (both slots) by read-back; a failed verify restores BOTH originals exactly.
     *
     * @return true only when the weapon (by id, full quantity) is in {@code activeSlot}
     *         and the old active item is back in the weapon's source slot afterwards
     */
    private boolean equipWeaponInto(WeaponRef chosen) {
        Inventory inv;
        try {
            inv = owner.npcEntity.getInventory();
        } catch (Throwable t) {
            LayaPlugin.log("Combat: weapon equip failed (no inventory): " + t);
            return false;
        }
        if (inv == null) return false;
        ItemContainer hotbar = inv.getHotbar();
        if (hotbar == null) return false;

        int active = inv.getActiveHotbarSlot();
        if (active < 0 || active >= hotbar.getCapacity()) return false;
        short activeSlot = (short) active;
        String weaponId = chosen.id;
        chosen = resolveToCurrentSlot(inv, weaponId, chosen, activeSlot);
        ItemContainer source = chosen.container;
        short sourceSlot = chosen.slot;

        // Already in a hotbar slot: selecting it IS the equip — neither slot is touched.
        if (source == hotbar) {
            if (activeSlot != chosen.slot) {
                inv.setActiveHotbarSlot(owner.layaRef, (byte) chosen.slot, owner.worldStore);
            }
            lastWeaponSlot = chosen.slot;
            lastWeaponSwapSlot = -1;
            noteWielded(weaponId);
            return true;
        }

        // Capture BOTH stacks before touching anything: `previous` is the stack we are
        // about to overwrite in the active slot, `weapon` the stack we are about to move.
        ItemStack previous = hotbar.getItemStack(activeSlot);
        ItemStack weapon = source.getItemStack(sourceSlot);
        if (weapon == null || ItemStack.isEmpty(weapon) || !weaponId.equals(weapon.getItemId())) {
            return false;   // the scan is stale; nothing to swap (nothing written, nothing lost)
        }
        final int weaponQty = weapon.getQuantity();

        // --- Path A: the targeted engine move (also handles a same-id merge) -----------
        boolean swapped = false;
        try {
            swapped = source.moveItemStackFromSlotToSlot(
                sourceSlot, weaponQty, hotbar, activeSlot).succeeded()
                && mergedLanded(source, sourceSlot, hotbar, activeSlot, weaponId, weaponQty);
        } catch (Throwable t) {
            LayaPlugin.log("Combat: weapon move threw (" + weaponId + "): " + t);
        }

        // --- Path B: explicit order — weapon to activeSlot, THEN previous to sourceSlot ---
        // Only reached when Path A wrote nothing (its verify failure is rolled back first,
        // which is safe because both originals are still held in locals).
        if (!swapped) {
            if (weaponId.equals(idAt(hotbar, activeSlot))) {
                restoreOriginals(source, sourceSlot, hotbar, activeSlot, weapon, previous);
            }
            swapped = swapByWrites(source, sourceSlot, hotbar, activeSlot, weapon, previous);
        }
        if (!swapped) {
            restoreOriginals(source, sourceSlot, hotbar, activeSlot, weapon, previous);
            boolean stillThere = weaponId.equals(idAt(source, sourceSlot));
            LayaPlugin.log("Combat: weapon equip FAILED — " + weaponId
                + " could not be swapped into hotbar " + activeSlot
                + (stillThere ? " (left where it was)" : " (MISSING from slot " + sourceSlot + " — check her pack)"));
            return false;
        }

        inv.setActiveHotbarSlot(owner.layaRef, (byte) activeSlot, owner.worldStore);
        lastWeaponSlot = activeSlot;
        lastWeaponSwapSlot = sourceSlot;
        noteWielded(weaponId);
        LayaPlugin.log("Combat: weapon swap " + weaponId + " <-> "
            + (previous == null || previous.getItemId() == null ? "empty" : previous.getItemId())
            + " (slot " + sourceSlot + " <-> hotbar " + activeSlot + ")");
        return true;
    }

    /**
     * Path B write order, with both stacks already captured in locals. The weapon goes
     * into the active slot FIRST, while its source slot still holds the same stack — so
     * if the source slot can never be cleared afterwards nothing is lost, the verify
     * simply fails and both originals are restored. Only once the weapon is confirmed at
     * {@code activeSlot} is the old active item written into the (now free) source slot.
     */
    private boolean swapByWrites(ItemContainer source, short sourceSlot, ItemContainer hotbar,
                                 short activeSlot, ItemStack weapon, ItemStack previous) {
        try {
            if (!hotbar.setItemStackForSlot(activeSlot, weapon).succeeded()) return false;
            if (!stackIdEquals(hotbar.getItemStack(activeSlot), weapon.getItemId())) return false;
            // The weapon is safely at activeSlot; clear the source, then park `previous`.
            if (!source.setItemStackForSlot(sourceSlot, null).succeeded()) return false;
            if (previous != null && previous.getItemId() != null
                    && !source.setItemStackForSlot(sourceSlot, previous).succeeded()) {
                return false;
            }
            return explicitLanded(source, sourceSlot, hotbar, activeSlot, weapon, previous);
        } catch (Throwable t) {
            LayaPlugin.log("Combat: weapon swap threw: " + t);
            return false;
        }
    }

    /**
     * End state of Path A: the weapon (by id, full quantity) at {@code activeSlot} and the
     * weapon's source slot empty. A same-id merge is accepted — the two stacks become one
     * at {@code activeSlot} and the source slot is left cleared — because no item was lost.
     */
    private boolean mergedLanded(ItemContainer source, short sourceSlot, ItemContainer hotbar,
                                 short activeSlot, String weaponId, int weaponQty) {
        if (!weaponId.equals(idAt(hotbar, activeSlot))) return false;
        if (quantityAt(hotbar, activeSlot) < weaponQty) return false;
        return quantityAt(source, sourceSlot) == 0;
    }

    /**
     * End state of Path B, exactly the spec swap: weapon at {@code activeSlot} and the old
     * active item (full quantity) at the weapon's original source slot.
     */
    private boolean explicitLanded(ItemContainer source, short sourceSlot, ItemContainer hotbar,
                                   short activeSlot, ItemStack weapon, ItemStack previous) {
        if (!weaponIdOf(weapon).equals(idAt(hotbar, activeSlot))) return false;
        if (quantityAt(hotbar, activeSlot) < weapon.getQuantity()) return false;
        if (previous == null || previous.getItemId() == null) return quantityAt(source, sourceSlot) == 0;
        return previous.getItemId().equals(idAt(source, sourceSlot))
            && quantityAt(source, sourceSlot) >= previous.getQuantity();
    }

    /**
     * Restore BOTH originals exactly — the weapon to its source slot and the old active
     * item to the active slot — from the locals captured before any write. Used both to
     * undo a partially-applied Path A and as the failure rollback; a stack that has not
     * moved is simply written back to where it was.
     */
    private void restoreOriginals(ItemContainer source, short sourceSlot, ItemContainer hotbar,
                                  short activeSlot, ItemStack weapon, ItemStack previous) {
        try {
            source.setItemStackForSlot(sourceSlot, weapon);
        } catch (Throwable t) {
            LayaPlugin.log("Combat: weapon swap restore (source) failed: " + t);
        }
        try {
            hotbar.setItemStackForSlot(activeSlot, previous);
        } catch (Throwable t) {
            LayaPlugin.log("Combat: weapon swap restore (active) failed: " + t);
        }
    }

    /** An ItemStack whose id is {@code id}, or empty. Never throws. */
    private static boolean stackIdEquals(ItemStack stack, String id) {
        return stack != null && stack.getItemId() != null && stack.getItemId().equals(id);
    }

    /** The item id of a stack, or "" when there is none. Never throws. */
    private static String weaponIdOf(ItemStack stack) {
        return stack == null || stack.getItemId() == null ? "" : stack.getItemId();
    }

    /** Quantity in a container slot, 0 when empty/missing. Never throws. */
    private static int quantityAt(ItemContainer container, short slot) {
        if (container == null || slot < 0) return 0;
        try {
            ItemStack st = container.getItemStack(slot);
            return st == null || st.getItemId() == null ? 0 : st.getQuantity();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * A remembered weapon slot must never be trusted blindly: an earlier swap may have
     * moved the item. Chase the id to where it currently is (preferring the remembered
     * slot, then its remembered swap partner, then any slot at all) so the next swap
     * cannot address an empty slot and duplicate the stack.
     */
    private WeaponRef resolveToCurrentSlot(Inventory inv, String weaponId, WeaponRef chosen, short activeSlot) {
        // Cheap path: the weapon is still exactly where the scan found it.
        if (chosen.slot == activeSlot && chosen.container == inv.getHotbar()) return chosen;
        if (weaponId.equals(idAt(chosen.container, chosen.slot))) return chosen;
        // It moved: chase the id, preferring the slots we remember touching.
        ItemContainer hotbar = inv.getHotbar();
        if (hotbar != null && lastWeaponSlot >= 0 && weaponId.equals(idAt(hotbar, lastWeaponSlot))) {
            return new WeaponRef(hotbar, lastWeaponSlot, hotbar.getItemStack(lastWeaponSlot));
        }
        if (chosen.container == hotbar && lastWeaponSwapSlot >= 0
                && weaponId.equals(idAt(hotbar, lastWeaponSwapSlot))) {
            return new WeaponRef(hotbar, lastWeaponSwapSlot, hotbar.getItemStack(lastWeaponSwapSlot));
        }
        for (ItemContainer section : allSections(inv)) {
            if (section == null) continue;
            for (short s = 0; s < section.getCapacity(); s++) {
                if (section == hotbar && s == activeSlot) continue;   // never the slot we are about to overwrite
                if (weaponId.equals(idAt(section, s))) {
                    return new WeaponRef(section, s, section.getItemStack(s));
                }
            }
        }
        return chosen;   // not found anywhere else: fall through so the swap fails honestly
    }

    private ItemContainer[] allSections(Inventory inv) {
        return new ItemContainer[] {
            inv.getHotbar(), inv.getStorage(), inv.getTools(),
            inv.getBackpack(), inv.getUtility(), inv.getArmor()
        };
    }

    /** Remember the swung weapon's id and clear the one-time animation fallback. */
    private void noteWielded(String weaponId) {
        noAnimationId = null;
        lastWieldedId = weaponId;
    }

    /** The item id in a slot, or "" for empty/missing. Never throws. */
    private static String idAt(ItemContainer container, short slot) {
        if (container == null || slot < 0) return "";
        try {
            ItemStack st = container.getItemStack(slot);
            if (st == null || st.getItemId() == null) return "";
            return st.getItemId();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The item id currently in Laya's active hotbar slot, or "". */
    private String wieldedId() {
        try {
            if (owner.npcEntity == null) return "";
            Inventory inv = owner.npcEntity.getInventory();
            if (inv == null) return "";
            ItemContainer hotbar = inv.getHotbar();
            if (hotbar == null) return "";
            return idAt(hotbar, (short) inv.getActiveHotbarSlot());
        } catch (Throwable t) {
            return "";
        }
    }

    /** The weapon id the swing/animation helpers should reason about. */
    private String currentWeaponId() {
        String id = wieldedId();
        if (weaponClassOf(id) != null) return id;
        return lastWieldedId != null && weaponClassOf(lastWieldedId) != null ? lastWieldedId : id;
    }

    /**
     * Spec section 4 damage table, capped at {@link #DAMAGE_CAP}. {@code itemLevel} is
     * the item's own level (iron sword level 20 -&gt; 6 + 4 = 10). An unknown or
     * unarmed id falls back to {@link #UNARMED_DAMAGE}.
     */
    float weaponDamage(String itemId, int itemLevel) {
        String cls = weaponClassOf(itemId);
        float dmg;
        if (cls == null) {
            dmg = UNARMED_DAMAGE;
        } else {
            int lvl = Math.max(0, itemLevel);
            switch (cls) {
                case "dagger": dmg = (float) (4.0 + 0.10 * lvl); break;
                case "sword":  dmg = (float) (6.0 + 0.20 * lvl); break;
                case "spear":  dmg = (float) (7.0 + 0.25 * lvl); break;
                case "mace":   dmg = (float) (8.0 + 0.30 * lvl); break;
                case "axe":    dmg = (float) (9.0 + 0.40 * lvl); break;
                default:       dmg = UNARMED_DAMAGE; break;
            }
        }
        return Math.min(dmg, DAMAGE_CAP);
    }

    /**
     * Spec section 4 animation table: the Action-slot id that reads on the wielded
     * item's animation set. Anything unknown (or unarmed) is {@code SwingRight} on the
     * Default set, which is also the fallback when the engine refuses an id.
     */
    String weaponAnimation(String itemId) {
        String cls = weaponClassOf(itemId);
        if (cls == null) return "SwingRight";
        switch (cls) {
            case "sword":
            case "axe":
            case "mace":   return "SwingDown";
            case "spear":  return "Stab";
            case "dagger": return itemId.startsWith("Weapon_Daggers") ? "SwingLeftRight" : "Stab";
            default:       return "SwingRight";
        }
    }

    /**
     * The health stat of {@code ref}, or {@code -1} when it cannot be read (no store,
     * no Ref, no EntityStatMap, or a null health stat). Preflight ruling: this is the
     * single HP helper — Task 5 delegates {@code getLayaHp()} to it and Task 6 uses it
     * for death checks.
     */
    float hpOf(Ref<EntityStore> ref) {
        if (ref == null || owner.worldStore == null) return -1f;
        try {
            EntityStatMap stats = owner.worldStore.getComponent(ref, EntityStatMap.getComponentType());
            if (stats == null) return -1f;
            com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue hp =
                stats.get(DefaultEntityStatTypes.getHealth());
            return hp == null ? -1f : hp.get();
        } catch (Throwable t) {
            return -1f;
        }
    }

    /**
     * Laya's current health, or {@code -1} when it cannot be read. Delegates to
     * {@link #hpOf(Ref)} so there is exactly one HP reader (Task 5 brief, preflight
     * ruling #1).
     */
    float getLayaHp() {
        return hpOf(owner.layaRef);
    }

    /**
     * Write Laya's health stat (Task 7 uses it to pin HP to 1 while downed and to hand
     * back 50 on revive). Returns {@code true} only when the stat really was written.
     */
    boolean setLayaHp(float v) {
        if (owner.layaRef == null || owner.worldStore == null) return false;
        try {
            EntityStatMap stats = owner.worldStore.getComponent(
                owner.layaRef, EntityStatMap.getComponentType());
            if (stats == null) return false;
            stats.setStatValue(DefaultEntityStatTypes.getHealth(), v);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Health is shown ONLY in the companion HUD now; the old chat notices
     * ({@code Laya: 49/100}) are gone. Kept as a no-op so the damage filter's call
     * site and the per-fight warning reset stay intact.
     */
    void noteLayaHp(float hpBeforeHit) {
        // HUD-only: nothing is spoken or written to chat about health.
    }

    /**
     * Re-arm the two low-HP notices for the next fight (ruling #5). Called from
     * {@link #setStance} and, from Task 6, on engagement release.
     */
    void resetHpWarnings() {
        warnedAt50 = false;
        warnedAt25 = false;
    }

    /**
     * Re-assert the downed pose after any hit (the engine's hit animation can replace
     * the Status pose and stand her up, even when the damage itself is cancelled).
     */
    void reassertDownedPose() {
        if (!downed) return;
        playStatusAnimation(STATUS_DEATH_ANIM);
        owner.stopSwing();
        try {
            AnimationUtils.stopAnimation(owner.layaRef, AnimationSlot.Face, owner.worldStore);
            AnimationUtils.stopAnimation(owner.layaRef, AnimationSlot.Emote, owner.worldStore);
        } catch (Throwable ignored) {
        }
    }

    /** True while the revive channel is running (HUD countdown). */
    boolean hudChanneling() {
        return channeling;
    }

    /** Milliseconds left in the revive channel, 0 when not channeling. */
    long hudReviveRemainingMs() {
        if (!channeling) return 0L;
        long left = REVIVE_CHANNEL_MS - (System.currentTimeMillis() - channelStartMs);
        return Math.max(0L, left);
    }

    /** Laya's current live ref, for {@link LayaDamageFilter}'s index + store compare. */
    Ref<EntityStore> ownerRef() {
        return owner.layaRef;
    }

    /**
     * The player's ENTITY ref, for {@link LayaDamageFilter}'s target matching.
     * {@code owner.playerRef} is the engine's {@code PlayerRef} component handle, so its
     * entity ref has to be resolved through {@code getReference()}.
     */
    Ref<EntityStore> playerRef() {
        try {
            return owner.playerRef == null ? null : owner.playerRef.getReference();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The entity's nameplate text for the swing log, or "target" when unavailable. */
    private String nameOf(Ref<EntityStore> ref) {
        if (ref == null || owner.worldStore == null) return "target";
        try {
            Nameplate plate = owner.worldStore.getComponent(ref, Nameplate.getComponentType());
            if (plate != null && plate.getText() != null && !plate.getText().isEmpty()) {
                return plate.getText();
            }
        } catch (Throwable ignored) {
        }
        return "target";
    }

    /** Horizontal distance Laya &lt;-&gt; {@code ref}, or {@code Double.MAX_VALUE}. */
    private double distanceToLaya(Ref<EntityStore> ref) {
        try {
            TransformComponent other = owner.worldStore.getComponent(
                ref, TransformComponent.getComponentType());
            TransformComponent laya = owner.worldStore.getComponent(
                owner.layaRef, TransformComponent.getComponentType());
            if (other == null || laya == null) return Double.MAX_VALUE;
            Vector3d o = other.getPosition();
            Vector3d l = laya.getPosition();
            double dx = o.x - l.x;
            double dz = o.z - l.z;
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * Spec section 4: one swing every {@link #SWING_INTERVAL_MS}. The swing animation
     * plays on every cadence tick, but damage lands only when the target is still
     * valid, within {@link #SWING_RANGE} blocks of Laya and alive ({@code hpOf > 0});
     * otherwise the skip is logged and nothing is damaged. World-thread only.
     */
    /** One skip line per engagement: walking to a target is not an event worth logging every 1.1 s. */
    private void logSwingSkipOnce(String line) {
        if (swingSkipLogged) return;
        swingSkipLogged = true;
        LayaPlugin.log(line);
    }

    void swingTick(long now, Ref<EntityStore> target) {
        if (now < nextSwingMs) return;
        if (owner.npcEntity == null || owner.layaRef == null || owner.worldStore == null) return;
        if (target == null) return;
        nextSwingMs = now + SWING_INTERVAL_MS;

        String weaponId = currentWeaponId();

        // Check BEFORE playing the swing: the old order played the animation first,
        // so she visibly swung at empty air while walking to a target out of reach.
        float hp = hpOf(target);
        if (hp <= 0f) {
            logSwingSkipOnce("Combat: swing skipped — target down");
            return;
        }
        double dist = distanceToLaya(target);
        if (dist > SWING_RANGE) {
            logSwingSkipOnce("Combat: swing skipped — target "
                + String.format("%.1f", dist) + " blocks away (reach " + trimDamage((float) SWING_RANGE) + ")");
            return;
        }

        String anim = playSwingAnimation(weaponId);
        float amount = damageForWeapon(weaponId);
        try {
            DamageSystems.executeDamage(target, owner.worldStore,
                new Damage(new Damage.EntitySource(owner.layaRef), DamageCause.PHYSICAL, amount));
        } catch (Throwable t) {
            LayaPlugin.log("Combat: damage failed (" + weaponId + " anim=" + anim + "): " + t);
            return;
        }
        swingCount++;
        LayaPlugin.log("Combat: swing " + swingCount + " at " + nameOf(target)
            + " dmg=" + trimDamage(amount) + " hp=" + trimDamage(Math.max(0f, hpOf(target))));
    }

    /** Damage for the wielded weapon: its own level, or unarmed when it is not melee. */
    private float damageForWeapon(String weaponId) {
        if (weaponClassOf(weaponId) == null) return UNARMED_DAMAGE;
        int level = 0;
        try {
            ItemStack held = wieldedStack();
            Item item = held == null ? null : held.getItem();
            if (item != null) level = item.getItemLevel();
        } catch (Throwable ignored) {
        }
        return weaponDamage(weaponId, level);
    }

    /** The stack in the active hotbar slot, or null. */
    private ItemStack wieldedStack() {
        try {
            if (owner.npcEntity == null) return null;
            Inventory inv = owner.npcEntity.getInventory();
            if (inv == null || inv.getHotbar() == null) return null;
            return inv.getHotbar().getItemStack((short) inv.getActiveHotbarSlot());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Play the weapon's swing animation, falling back to {@code SwingRight} — once,
     * with a log — when the engine refuses an id (client log:
     * "No animation with id X"). Returns the id that was played.
     */
    private String playSwingAnimation(String weaponId) {
        String anim = weaponAnimation(weaponId);
        if (anim.equals(noAnimationId)) anim = "SwingRight";
        try {
            owner.npcEntity.playAnimation(owner.layaRef, AnimationSlot.Action, anim, owner.worldStore);
        } catch (Throwable t) {
            noAnimationId = anim;
            LayaPlugin.log("Combat: animation '" + anim + "' refused — falling back to SwingRight (" + t + ")");
            anim = "SwingRight";
            try {
                owner.npcEntity.playAnimation(owner.layaRef, AnimationSlot.Action, anim, owner.worldStore);
            } catch (Throwable ignored) {
            }
        }
        return anim;
    }

    /** One decimal place, so {@code 10.0} / {@code 42.0} print as the spec's log shows. */
    private static String trimDamage(float value) {
        return String.format("%.1f", value);
    }

    // --- Task 4-7 stubs (replace bodies; signatures stay) ---

    /**
     * Task 5: the player was damaged by {@code attacker} — stamp a reactive target.
     * Task 6 (spec section 3): any stance, 15 s memory, "closest wins" via the stamp
     * simply being the most recent attacker. Log once per new stamp.
     */
    void onPlayerHit(Ref<EntityStore> attackerRef) {
        // Task 7 (ruling 3, spec section 6): damage to the player breaks the pickup. The
        // filter calls this on the world thread, same thread that owns the channel state.
        if (channeling) {
            cancelRevive("player damaged");
        }
        stampReactive(attackerRef);
    }

    /**
     * Task 5: Laya was damaged by {@code attacker} — stamp a reactive target.
     * Task 6 (spec section 3): the same 15 s memory as {@link #onPlayerHit}.
     */
    void onLayaHit(Ref<EntityStore> attackerRef) {
        stampReactive(attackerRef);
    }

    /**
     * Record a reactive attacker for {@link #REACTIVE_MEMORY_MS}. Never the player or
     * Laya. The stamp is consumed by the next tick, which runs the leash and acquire
     * path. Log line is spec section 7's exact
     * {@code Combat: reactive target <Role> (player hit)} (the same line for a hit on
     * Laya, per spec: one reactive event).
     */
    private void stampReactive(Ref<EntityStore> attackerRef) {
        if (!isValidEntity(attackerRef)) return;
        if (isForbiddenTarget(attackerRef)) return;
        reactiveTarget = attackerRef;
        reactiveUntilMs = System.currentTimeMillis() + REACTIVE_MEMORY_MS;
        leashDropLogged = false;
        LayaPlugin.log("Combat: reactive target " + roleOf(attackerRef) + " (player hit)");
    }

    /**
     * Task 5 calls this on every lethal hit; Task 7 owns the body (spec section 6):
     * HP pinned to 1, target + task cleared, Action slot cleared, Death pose on the
     * Status slot, {@code downed=true}, persisted, and exactly ONE chat warning. The
     * matching {@code Combat: downed (<amount> <cause> from <source>)} line is emitted
     * by {@link LayaDamageFilter} step (f) right after this call, so this method must
     * NOT log it again (ruling 1: keep exactly one line total).
     *
     * <p>World-thread only (the damage filter runs on the world thread). Already downed
     * (a second lethal hit while pinned at 1 HP) is a no-op: no duplicate warning, no
     * repeated task cancel.
     */
    void enterDowned(DamageCause cause, float amount, Ref<EntityStore> sourceRef) {
        if (downed) return;
        downed = true;
        channeling = false;
        channelStartMs = 0;
        lastUseMs = 0;
        lastHpPinMs = 0;
        lastAggroSuppressMs = 0;
        // Clear combat + task movement. cancelTaskForCombat logs its own line (acceptable
        // per ruling 1) and posts NO npc_event (spec: no extra events).
        clearTarget();
        // A downed body must stop being a target: hostiles kept swinging at her corpse,
        // which handed the player free kills. Drop the killer's attitude to IGNORE and
        // clear the target slot now; downedTick keeps refreshing it.
        suppressAggro(owner.worldStore, sourceRef);
        suppressAggro(owner.worldStore, null);
        owner.cancelTaskForCombat();
        owner.clearPendingMoveTo();        // a queued move_to must not walk her while down
        owner.stopSwing();                 // clear the Action slot (spec section 6)
        setLayaHp(DOWNED_HP);              // cancelled lethal hit -> she sits at 1
        savedHp = DOWNED_HP;
        playStatusAnimation(STATUS_DEATH_ANIM);
        saveState();
        owner.pokeHud();                   // Task 3: downed shows immediately, not in 500 ms
        owner.sayToPlayer(downedWarning());
    }

    /**
     * Make every nearby NPC ignore Laya while she is downed.
     *
     * <p>Two engine facts made the first attempt fail (in-game 2026-10-28): clearing the
     * target slot alone does nothing, because the retaliation path in
     * {@code Template_Intelligent} runs {@code OverrideAttitude Hostile Duration 60} as
     * soon as Laya damages an NPC, so its sensors re-acquire her body every tick and it
     * keeps swinging (the player farmed free kills). The fix is to replace that override
     * with {@code IGNORE} for a few seconds, refreshed while she is down, which is the
     * same mechanism the engine itself uses.</p>
     *
     * <p>World thread only. {@code only} restricts it to one NPC; {@code null} scans 40
     * blocks around her.</p>
     */
    void suppressAggro(Store<EntityStore> store, Ref<EntityStore> only) {
        try {
            if (store == null || owner.layaRef == null) return;
            if (only != null) {
                suppressOne(store, only);
                return;
            }
            TransformComponent t = store.getComponent(
                owner.layaRef, TransformComponent.getComponentType());
            if (t == null || t.getPosition() == null) return;
            java.util.List<Ref<EntityStore>> near = TargetUtil.getAllEntitiesInSphere(
                new Vector3d(t.getPosition()), 40.0, store);
            if (near == null) return;
            for (Ref<EntityStore> ref : near) {
                if (ref == null || !ref.isValid()) continue;
                if (ref.getIndex() == owner.layaRef.getIndex()
                    && ref.getStore() == owner.layaRef.getStore()) {
                    continue;
                }
                suppressOne(store, ref);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ignore-override + target-slot clear for one NPC. */
    private void suppressOne(Store<EntityStore> store, Ref<EntityStore> npcRef) {
        try {
            if (store.getComponent(npcRef, NPCEntity.getComponentType()) == null) return;
            WorldSupport ws = WorldSupport.get(npcRef, store);
            if (ws != null) {
                ws.overrideAttitude(owner.layaRef, Attitude.IGNORE, 3.0);
            }
            releaseTargetOn(store, npcRef);
        } catch (Throwable ignored) {
        }
    }

    /** Clear one NPC's target slots that hold Laya. */
    private void releaseTargetOn(Store<EntityStore> store, Ref<EntityStore> npcRef) {
        try {
            MarkedEntitySupport marked = MarkedEntitySupport.get(npcRef, store);
            if (marked == null) return;
            int slots = marked.getMarkedEntitySlotCount();
            for (int i = 0; i < slots; i++) {
                Ref<EntityStore> target = marked.getMarkedEntityRef(i);
                if (target == null || owner.layaRef == null) continue;
                if (target.getIndex() == owner.layaRef.getIndex()
                    && target.getStore() == owner.layaRef.getStore()) {
                    marked.clearMarkedEntity(i);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Per-tick downed maintenance (spec section 6). Runs INSTEAD of the normal combat
     * tick while downed:
     *  - HP is pinned back to 1 once per second, so nothing can refill the bar into a
     *    fake recovery;
     *  - the literal-hold channel (Cycle A: {@code literal-hold}) is advanced: a Use gap
     *    beyond {@link #REVIVE_HOLD_GAP_MS} cancels with reason {@code release}; leaving
     *    the 3-block ring cancels with {@code range}; a vanished player cancels with
     *    {@code world left}; and REVIVE_CHANNEL_MS of held Use completes the pickup.
     *  - No movement, weapon or swing write ever happens here.
     */
    private void downedTick(long now) {
        // (0) Keep hostile NPCs off her body: the retaliation override is Hostile for
        // 60 s, so a one-shot clear is re-acquired next tick. Refreshed every 750 ms.
        if (now - lastAggroSuppressMs >= 750L) {
            lastAggroSuppressMs = now;
            suppressAggro(owner.worldStore, null);
        }
        // (1) HP pin, once per second (spec section 6: "pinned to 1 each second").
        if (now - lastHpPinMs >= 1000L) {
            lastHpPinMs = now;
            float hp = getLayaHp();
            // Any deviation from 1 is repaired — including 0 < hp < 1 and hp <= 0 — so
            // nothing can drift the bar into a fake recovery or a fake death.
            if (hp < 0f || hp != DOWNED_HP) {
                setLayaHp(DOWNED_HP);
            }
        }
        if (!channeling) return;

        // (2) The player left the world: cancel and persist (spec section 6).
        Ref<EntityStore> player = entityRefOfPlayer();
        if (player == null || !isValidEntity(player)) {
            cancelRevive("world left");
            return;
        }
        // (3) Literal hold: a Use gap means the key was released.
        if (now - lastUseMs > REVIVE_HOLD_GAP_MS) {
            cancelRevive("release");
            return;
        }
        // (4) Out of the 3-block ring.
        if (distanceToLaya(player) > REVIVE_RANGE) {
            cancelRevive("range");
            return;
        }
        // (5) 10 s of held Use -> she stands up.
        if (now - channelStartMs >= REVIVE_CHANNEL_MS) {
            completeRevive();
        }
    }

    /**
     * Interact Use beside the downed Laya (spec section 6). Inside the 3-block ring it
     * starts the channel (chat {@code reviveStartChat()}, log {@code Combat: revive
     * start}) or, if already channelling, just refreshes the literal-hold stamp. Outside
     * the ring nothing is started and the out-of-range line is logged once per press, so
     * the prompt behaviour near her stays usable.
     *
     * <p>World-thread only. Returns true when the Use was consumed by the pickup route.
     */
    boolean onReviveUse(Ref<EntityStore> playerEntity) {
        if (!downed) return false;
        long now = System.currentTimeMillis();
        double dist = playerEntity == null ? Double.MAX_VALUE : distanceToLaya(playerEntity);
        if (dist > REVIVE_RANGE) {
            if (now - lastOutOfRangeLogMs >= REVIVE_OUT_OF_RANGE_LOG_MS) {
                lastOutOfRangeLogMs = now;
                LayaPlugin.log("Combat: revive ignored (out of range)");
            }
            return false;   // outside the ring: nothing opens, nothing starts
        }
        lastUseMs = now;
        if (!channeling) {
            channeling = true;
            channelStartMs = now;
            LayaPlugin.log("Combat: revive start");
            owner.sayToPlayer(reviveStartChat());
        }
        return true;
    }

    /**
     * Cancel a running revive channel and drop back to DOWNED with the progress reset
     * (spec section 6). {@code reason} is one of {@code range}, {@code player damaged},
     * {@code attack}, {@code world left}, {@code release}. Each cancel says
     * {@link #REVIVE_CANCEL_CHAT} and logs {@code Combat: revive cancelled (<reason>)}.
     */
    private void cancelRevive(String reason) {
        if (!channeling) return;
        channeling = false;
        channelStartMs = 0;
        lastUseMs = 0;
        LayaPlugin.log("Combat: revive cancelled (" + reason + ")");
        owner.sayToPlayer(REVIVE_CANCEL_CHAT);
        saveState();
    }

    /**
     * The channel finished: she stands up (spec section 6). Chat
     * {@code reviveDoneChat()}, HP {@link #REVIVE_HP}, {@code downed=false}, stance
     * {@code follow}, Death pose cleared, persisted, log {@code Combat: revived hp=50}.
     * Combat does NOT auto-engage afterwards — re-issuing attack/defend is the player's
     * choice.
     */
    private void completeRevive() {
        channeling = false;
        channelStartMs = 0;
        lastUseMs = 0;
        downed = false;
        setLayaHp(REVIVE_HP);
        savedHp = REVIVE_HP;
        playStatusAnimation(STATUS_CLEAR_ANIM);
        stance = "follow";          // ruling 4: stance = follow, with no extra stance log
        resetHpWarnings();
        // Ruling 4: no auto-engage after a pickup — drop any attack intent that was armed
        // while she was down so the next tick does not spuriously hunt.
        explicitAttackPending = false;
        stanceReleasePending = false;
        saveState();
        owner.pokeHud();                   // Task 3: the card leaves DOWNED immediately
        owner.sayToPlayer(reviveDoneChat());
        LayaPlugin.log("Combat: revived hp=" + (int) REVIVE_HP);
    }

    /**
     * Boot restore (spec section 6): if the persisted state says downed, put the just
     * spawned Laya back down at 1 HP with the Death pose. NO chat warning on boot — the
     * warning is a live-entry-only line. Called from {@code ensureSpawned} right after
     * {@code loadInventory()}, so {@code loadSavedState()} has already run.
     */
    void enterDownedRestored() {
        downed = true;
        channeling = false;
        channelStartMs = 0;
        lastUseMs = 0;
        lastHpPinMs = 0;
        setLayaHp(DOWNED_HP);
        savedHp = DOWNED_HP;
        playStatusAnimation(STATUS_DEATH_ANIM);
        LayaPlugin.log("Combat: restored downed (hp=1)");
    }

    /** Task 7: restore the persisted combat state (spec section 6). */
    void loadSavedState() {
        try {
            Path p = Paths.get(COMBAT_JSON);
            if (!Files.exists(p)) {
                savedHp = DEFAULT_HP;
                downed = false;
                return;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            savedHp = root.has("hp") ? root.get("hp").getAsFloat() : DEFAULT_HP;
            downed = root.has("downed") && root.get("downed").getAsBoolean();
        } catch (Throwable t) {
            LayaPlugin.log("Combat: state load failed: " + t);
            savedHp = DEFAULT_HP;
            downed = false;
        }
    }

    /**
     * Task 7: persist {@link #savedHp} and the downed flag. Fired on every HP write and
     * on every downed transition, plus from {@code NPCController.stop()}. Writes
     * {@code {"hp","downed","saved_at"}} as UTF-8 with no BOM.
     */
    void saveState() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("hp", currentSavedHp());
            root.addProperty("downed", downed);
            root.addProperty("saved_at", System.currentTimeMillis());
            Path p = Paths.get(COMBAT_JSON);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LayaPlugin.log("Combat: state save failed: " + t);
        }
    }

    /**
     * The HP to persist: the LIVE stat when it can be read (so a non-lethal hit that
     * landed since the last write is not lost on shutdown), else the last written value.
     * While downed the live stat is the 1 HP pin, which is exactly what should be saved.
     */
    private float currentSavedHp() {
        float live = getLayaHp();
        if (live >= 0f) {
            savedHp = live;
        }
        return savedHp;
    }

    /**
     * Play (or clear) the Status-slot pose. {@code anim == null} clears it. Never
     * throws: an engine refusal is logged once per distinct id.
     */
    private void playStatusAnimation(String anim) {
        if (owner.npcEntity == null || owner.layaRef == null || owner.worldStore == null) return;
        try {
            owner.npcEntity.playAnimation(owner.layaRef, AnimationSlot.Status, anim, anim != null, owner.worldStore);
        } catch (Throwable t) {
            if (anim != null && !anim.equals(statusAnimRefused)) {
                statusAnimRefused = anim;
                LayaPlugin.log("Combat: status animation '" + anim + "' refused (" + t + ")");
            }
        }
    }

    /** The Status-slot id the engine last refused (one log line per id). */
    private String statusAnimRefused = null;
}
