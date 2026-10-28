package com.jonferno.laya.npc;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.jonferno.laya.LayaPlugin;

import java.util.UUID;

/**
 * The single entry point through which combat reaches Laya (spec section 5). An ECS
 * {@link DamageEventSystem} in {@code DamageModule.get().getFilterDamageGroup()}, so it
 * runs after the engine's gather/validation passes and can still cancel the event.
 *
 * <p>{@link #handle} implements exactly the ordered contract the Task 5 brief fixes — no
 * knockback/team/armor/shield logic, no downed state machine, no persistence:
 * <ol>
 *   <li>resolve the target ref; null/invalid returns;</li>
 *   <li>anything that is neither Laya's LIVE ref (index + store compare, so clones are
 *       unprotected) nor the owning player returns;</li>
 *   <li>an already-cancelled event returns (a filter below already decided);</li>
 *   <li>Laya + already downed -> cancel (spec section 5: every hit while downed is
 *       ignored, so a sub-1 hit cannot slip through while hp is pinned at 1 and a second
 *       lethal hit cannot emit a second {@code Combat: downed} line);</li>
 *   <li>Laya + {FALL, DROWNING, SUFFOCATION} -> cancel;</li>
 *   <li>Laya + damage from the owning player -> cancel (friendly fire off, no aggro);</li>
 *   <li>Laya + lethal amount -> cancel and hand the hit to
 *       {@link CombatController#enterDowned};</li>
 *   <li>player + damage from an NPC (directly or through a projectile's creator) ->
 *       {@link CombatController#onPlayerHit}, NOT cancelled (reactive stamp only);</li>
 *   <li>Laya + any other hit -> allowed, {@link CombatController#onLayaHit} plus the
 *       once-per-fight low-HP notices.</li>
 * </ol>
 *
 * <p>Threading: ECS systems run on the world thread, which is also the thread
 * {@link CombatController} is ticked from. Nothing here throws: the whole body is one
 * try/catch that logs the first error exactly once as {@code [LayaDamageFilter] Error ...}.
 */
public class LayaDamageFilter extends DamageEventSystem {

    /** Spec section 5: environment damage the companion is immune to. Fire/lava still hurt. */
    private static final DamageCause[] IMMUNE_CAUSES = {
        DamageCause.FALL, DamageCause.DROWNING, DamageCause.SUFFOCATION
    };

    private final SystemGroup<EntityStore> group;
    private final CombatController controller;
    /** First-error latch: one diagnostics line per session, never one per event. */
    private boolean errorLogged = false;

    /**
     * @param group      the system group to register into
     *                   ({@code DamageModule.get().getFilterDamageGroup()})
     * @param controller Laya's combat state, owned by {@link NPCController}
     */
    public LayaDamageFilter(SystemGroup<EntityStore> group, CombatController controller) {
        this.group = group;
        this.controller = controller;
    }

    /** Registered in {@code DamageModule.get().getFilterDamageGroup()}. */
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return group;
    }

    /**
     * Every entity: the filter decides by the damage event's own target ref, and the
     * query must not exclude either Laya or the player.
     */
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> cmd, Damage damage) {
        try {
            // (a) target ref; null/invalid -> return.
            if (chunk == null || damage == null) return;
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            if (target == null || !target.isValid()) return;

            // (b) neither Laya's live ref nor the player -> return. Clones are NOT protected:
            // compare index + store (Ref does not override equals), exactly like removeStaleClones.
            boolean isLaya = isLiveLaya(target);
            boolean isPlayer = isOwningPlayer(target);
            if (!isLaya && !isPlayer) return;

            // (c) a filter below already decided.
            if (damage.isCancelled()) return;

            DamageCause cause = damage.getCause();
            float amount = damage.getAmount();
            Ref<EntityStore> sourceRef = sourceRefOf(damage);

            if (isLaya) {
                // (d0) Task 7 (spec section 5: "Downed immune: every hit while downed is
                // cancelled"). This guard runs BEFORE the lethal branch below, which does
                // two jobs: it stops a sub-1 hit from slipping through step (h) and
                // reaching the health stat at all while hp is pinned at 1, and it stops a
                // second lethal hit from re-entering (f) and emitting a second
                // `Combat: downed (...)` line. isDowned() only reads a volatile flag, so it
                // is safe here on the world thread.
                if (controller.isDowned()) {
                    damage.setCancelled(true);
                    controller.reassertDownedPose();
                    if (sourceRef != null) controller.suppressAggro(store, sourceRef);
                    LayaPlugin.log("Combat: downed hit ignored");
                    return;
                }
                // Diagnostic: every hit that reaches Laya, so a missed lethal is visible.
                LayaPlugin.log("Combat: hurt amount=" + trim(amount)
                    + " hp=" + trim(controller.getLayaHp()) + " cause=" + causeName(cause));
                // (d) environment immunity.
                if (isImmuneCause(cause)) {
                    damage.setCancelled(true);
                    LayaPlugin.log("Combat: env damage cancelled");
                    return;
                }
                // (e) friendly fire off: the owning player is never an attacker. The check
                // covers both a direct hit and the player's own projectile (arrow, bolt,
                // thrown weapon) — spec default #2: only hostile NPC damage may hurt her.
                if (isPlayerRef(sourceRef)) {
                    damage.setCancelled(true);
                    return;
                }
                if (isOwningPlayersProjectile(store, sourceRef)) {
                    damage.setCancelled(true);
                    LayaPlugin.log("Combat: friendly projectile cancelled");
                    return;
                }
                // (f) lethal hit: cancel it and let Task 7 own the downed state machine.
                float hp = controller.getLayaHp();
                if (hp > 0f && amount >= hp) {
                    damage.setCancelled(true);
                    controller.enterDowned(cause, amount, sourceRef);
                    LayaPlugin.log("Combat: downed (" + trim(amount) + " " + causeName(cause)
                        + " from " + describe(sourceRef) + ")");
                    return;
                }
                // (h) the hit lands: stamp the reactive target, then the low-HP notices.
                // The engine's HitAnimation (which runs before this filter) already played
                // Hurt; our mod assets override Hurt with the face keyframes removed.
                controller.onLayaHit(sourceRef);
                controller.noteLayaHp(hp > 0f ? hp : controller.getLayaHp());
                return;
            }

            // (g) the player was hit: if an NPC did it (directly or via a projectile's
            // creator) stamp the reactive target. The hit itself is never cancelled.
            Ref<EntityStore> attacker = resolveNpcSource(store, sourceRef);
            if (attacker != null) {
                controller.onPlayerHit(attacker);
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LayaPlugin.log("[LayaDamageFilter] Error " + t);
            }
        }
    }

    // --- target resolution ---

    /** True when {@code ref} is Laya's current live entity (index + store; clones excluded). */
    private boolean isLiveLaya(Ref<EntityStore> ref) {
        Ref<EntityStore> laya = controller.ownerRef();
        return laya != null && laya.getIndex() == ref.getIndex() && laya.getStore() == ref.getStore();
    }

    /** True when {@code ref} is the entity of the player this controller is bound to. */
    private boolean isOwningPlayer(Ref<EntityStore> ref) {
        Ref<EntityStore> player = controller.playerRef();
        return player != null && player.getIndex() == ref.getIndex() && player.getStore() == ref.getStore();
    }

    private boolean isPlayerRef(Ref<EntityStore> ref) {
        return ref != null && isOwningPlayer(ref);
    }

    /**
     * True when {@code sourceRef} is a projectile created by the owning player, i.e. the
     * player's own arrow/bolt/thrown weapon (spec default #2: only hostile NPC damage may
     * hurt or down her). The creator UUID is compared directly against the player entity's
     * {@code UUIDComponent} — no archetype scan is needed on this path. Never throws.
     */
    private boolean isOwningPlayersProjectile(Store<EntityStore> store, Ref<EntityStore> sourceRef) {
        if (sourceRef == null || store == null) return false;
        try {
            Ref<EntityStore> player = controller.playerRef();
            if (player == null) return false;
            UUIDComponent playerUuid = store.getComponent(player, UUIDComponent.getComponentType());
            if (playerUuid == null || playerUuid.getUuid() == null) return false;
            UUID creator = projectileCreatorUuid(store, sourceRef);
            return creator != null && creator.equals(playerUuid.getUuid());
        } catch (Throwable t) {
            return false;
        }
    }

    /** The creator UUID of {@code sourceRef}'s projectile, or null when it is not one. */
    private static UUID projectileCreatorUuid(Store<EntityStore> store, Ref<EntityStore> sourceRef) {
        try {
            ProjectileComponent projectile = store.getComponent(
                sourceRef, ProjectileComponent.getComponentType());
            return projectile == null ? null : projectile.getCreatorUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    // --- source resolution ---

    /** The source entity of the event, or null for a non-entity source (command, environment). */
    private static Ref<EntityStore> sourceRefOf(Damage damage) {
        Damage.Source source = damage.getSource();
        if (source instanceof Damage.EntitySource es) {
            return es.getRef();
        }
        return null;
    }

    /**
     * The NPC responsible for a hit on the player: the source ref itself when it is an NPC,
     * otherwise the projectile's creator (spec: arrows and thrown weapons still stamp).
     * Returns null when nobody NPC-ish is responsible. Never throws out to the caller.
     */
    private Ref<EntityStore> resolveNpcSource(Store<EntityStore> store, Ref<EntityStore> sourceRef) {
        if (sourceRef == null) return null;
        try {
            if (isNpc(sourceRef)) return sourceRef;
            UUID creator = projectileCreatorUuid(store, sourceRef);
            if (creator == null) return null;
            return findLoadedNpcByUuid(store, creator);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the entity carries the NPC component (any role). Never throws. */
    private boolean isNpc(Ref<EntityStore> ref) {
        try {
            Store<EntityStore> store = ref.getStore();
            return store != null && store.getComponent(ref, NPCEntity.getComponentType()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Match a projectile creator UUID against the loaded NPC entities. Mirrors
     * {@code NPCController.removeStaleClones}'s archetype walk: one chunk pass over
     * every entity carrying {@link NPCEntity}, comparing {@code UUIDComponent.getUuid()}.
     */
    private Ref<EntityStore> findLoadedNpcByUuid(Store<EntityStore> store, UUID creator) {
        if (store == null) return null;
        try {
            Archetype<EntityStore> archetype = Archetype.of(NPCEntity.getComponentType());
            java.util.concurrent.atomic.AtomicReference<Ref<EntityStore>> found =
                new java.util.concurrent.atomic.AtomicReference<>();
            // BiPredicate overload: the walk stops as soon as the creator is found.
            store.forEachChunk(archetype, (chunk, cb) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    UUIDComponent uuid = chunk.getComponent(i, UUIDComponent.getComponentType());
                    if (uuid == null || !creator.equals(uuid.getUuid())) continue;
                    found.set(chunk.getReferenceTo(i));
                    return false;
                }
                return true;
            });
            return found.get();
        } catch (Throwable t) {
            return null;
        }
    }

    // --- small helpers ---

    private static boolean isImmuneCause(DamageCause cause) {
        if (cause == null) return false;
        for (DamageCause immune : IMMUNE_CAUSES) {
            if (immune == cause) return true;
        }
        return false;
    }

    /** The cause's id for the log line, or "unknown" when the engine passes none. */
    private static String causeName(DamageCause cause) {
        if (cause == null) return "unknown";
        String id = cause.getId();
        return id == null || id.isEmpty() ? "unknown" : id;
    }

    /** A short readable description of an attacker for the downed log line. */
    private String describe(Ref<EntityStore> ref) {
        if (ref == null) return "unknown";
        if (isLiveLaya(ref)) return "Laya";
        if (isOwningPlayer(ref)) return "player";
        try {
            if (isNpc(ref)) return "npc";
        } catch (Throwable ignored) {
        }
        return "entity " + ref.getIndex();
    }

    /** Trim a float to one decimal the way the other combat lines print. */
    private static String trim(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
