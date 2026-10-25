package com.jonferno.laya.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.jonferno.laya.LayaPlugin;
import com.jonferno.laya.npc.NPCController;

/**
 * Owns the lifetime of Laya's companion card for the one owning player.
 *
 * <p>Failures never escape: Hytale UI is client-facing and a missing asset or a
 * hostile API change must not take down the NPC or the bridge. The first
 * failure is logged and then latched so a broken HUD cannot spam the log.
 *
 * <p>Task 3 adds the live values: {@link #frame(PlayerRef, Player)} is called from
 * the 4 Hz follow tick (world thread) and is internally throttled to one push per
 * {@link #REFRESH_MS}; {@link #poke()} is the immediate path used by the combat
 * events (downed, revive) and the appearance apply.
 */
public final class LayaHudController {

    /** Must match the key passed to {@link LayaHud}'s constructor. */
    private static final String HUD_KEY = "laya_companion";

    /** Live refresh interval. The follow tick runs faster than this on purpose. */
    private static final long REFRESH_MS = 500;

    @SuppressWarnings("unused")
    private final LayaPlugin plugin;

    private boolean shown;
    private boolean failureLogged;

    /** Kept so shutdown can hide the card without re-resolving the component. */
    private PlayerRef shownFor;
    private Player shownPlayer;

    /** World-thread millis of the last pushed refresh; 0 = never ticked yet. */
    private long lastFrameMs = 0;

    /** Deferred first show: the CustomHud packet is dropped while the client is still loading. */
    private boolean pendingShow = false;
    private long pendingSinceMs = 0;
    private PlayerRef pendingRef;
    private Player pendingPlayer;

    /** Set by {@link #poke()}, consumed by the next frame regardless of the throttle. */
    private volatile boolean refreshRequested = false;

    public LayaHudController(LayaPlugin plugin) {
        this.plugin = plugin;
        // Task 3: the NPC controller owns the follow tick and the combat events, so it
        // relays the tick shots and the event-driven pokes back to this object. It is
        // created before this controller in LayaPlugin, so the reference is safe here.
        NPCController npc = plugin.getNPCController();
        if (npc != null) {
            npc.setHudRefresher(this);
        }
    }

    /**
     * Adds the card for {@code playerRef}. Safe to call more than once: a HUD
     * already present under the same key is replaced, not duplicated.
     */
    public void show(PlayerRef playerRef, Player player) {
        if (playerRef == null || player == null) {
            return;
        }
        try {
            LayaHud hud = new LayaHud(playerRef);
            // addCustomHud also calls hud.show() (it builds + sends the packet).
            player.getHudManager().addCustomHud(playerRef, hud);
            shown = true;
            shownFor = playerRef;
            shownPlayer = player;
            LayaPlugin.log("HUD: shown");

        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LayaPlugin.log("HUD: add failed: " + t);
            }
            shown = false;
        }
    }

    /** The card is shown this long after the player is ready, once the client has finished loading. */
    private static final long SHOW_DELAY_MS = 3000;

    /**
     * Defers the first show until the world has finished loading: the client drops a
     * CustomHud packet sent during the fade-in (observed: sent 88 ms after ClientReady,
     * never rendered). The first frame after the delay performs the real show.
     */
    public void queueShow(PlayerRef playerRef, Player player) {
        pendingRef = playerRef;
        pendingPlayer = player;
        pendingSinceMs = System.currentTimeMillis();
        pendingShow = true;
    }

    /** Hides the card for the player it was last shown for, if any. */
    public void hide() {
        hide(shownFor);
    }

    /** Removes the card by key. Logs once on the first successful removal. */
    public void hide(PlayerRef playerRef) {
        // A show queued at join must not fire after an explicit /laya hud off:
        // during the SHOW_DELAY_MS window `shown` is still false while `pendingShow`
        // is true, so without clearing it here frame() would resurrect the card.
        pendingShow = false;
        pendingRef = null;
        pendingPlayer = null;
        if (!shown || playerRef == null) {
            return;
        }
        try {
            Player player = shownPlayer;
            if (player == null) {
                // Fallback: resolve the Player component through the ref.
                var entityRef = playerRef.getReference();
                if (entityRef == null) {
                    shown = false;
                    return;
                }
                player = entityRef.getStore().getComponent(
                    entityRef, Player.getComponentType());
            }
            if (player == null) {
                shown = false;
                return;
            }
            player.getHudManager().removeCustomHud(playerRef, HUD_KEY);
            shown = false;
            shownFor = null;
            shownPlayer = null;
            LayaPlugin.log("HUD: hidden");
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LayaPlugin.log("HUD: remove failed: " + t);
            }
            shown = false;
        }
    }

    public boolean isShown() {
        return shown;
    }

    /**
     * Per-tick hook called from the follow tick (world thread). Throttled to
     * {@link #REFRESH_MS}; a {@link #poke()} makes the next call push immediately.
     *
     * <p>Silent no-op when the card is not shown or Laya is not spawned — the world
     * thread calls this unconditionally, so it must never be the reason a tick
     * fails. The first failure logs once and is then latched.
     */
    public void frame(PlayerRef playerRef, Player player) {
        long now = System.currentTimeMillis();
        if (!shown) {
            if (pendingShow && now - pendingSinceMs >= SHOW_DELAY_MS) {
                pendingShow = false;
                show(pendingRef, pendingPlayer);
            }
            if (!shown) {
                return;
            }
        }
        boolean forced = refreshRequested;
        if (!forced && lastFrameMs != 0 && now - lastFrameMs < REFRESH_MS) {
            return;
        }
        lastFrameMs = now;
        refreshRequested = false;
        push(playerRef, player);
    }

    /**
     * Immediate refresh for event-driven changes (downed entry, revive completion,
     * appearance apply). Safe from any thread and a no-op when the card is not
     * shown: it only sets a flag, the world-thread frame does the work.
     */
    public void poke() {
        refreshRequested = true;
    }

    /** {@link #poke()} under the name the brief also offered, for call-site clarity. */
    public void refreshNow() {
        poke();
    }

    /** Reads the live values and hands them to the card. World-thread only. */
    private void push(PlayerRef playerRef, Player player) {
        try {
            if (playerRef == null || player == null) {
                return;
            }
            NPCController npc = LayaPlugin.get() == null ? null : LayaPlugin.get().getNPCController();
            if (npc == null || !npc.isSpawned()) {
                return;   // Laya not spawned yet: keep the last values on screen
            }
            boolean downed = npc.hudDowned();
            float hp = npc.hudHp();
            if (hp < 0f) {
                hp = 0f;   // unreadable health (component gone): draw an empty bar
            }
            // Downed owns the state text in LayaHud; otherwise "attacking" only while a
            // target is actually engaged (attack stance idle should read "following").
            String state;
            String task = npc.hudTask();
            if (downed) {
                state = null;
            } else if (npc.hudEngaged()) {
                state = "attacking";
            } else if (task != null) {
                state = task;
            } else {
                String st = npc.hudStance();
                state = mapStance("attack".equals(st) ? "follow" : st);
            }
            LayaHud hud = hudFor(playerRef, player);
            if (hud != null) {
                hud.setName(npc.getCompanionName());
                hud.setValues(hp, npc.hudMaxHp(), state, downed);
                String revive = "";
                if (downed) {
                    if (npc.hudChanneling()) {
                        long ms = npc.hudReviveRemainingMs();
                        long secs = (ms + 999L) / 1000L;
                        revive = "Picking up... " + secs + "s";
                    } else {
                        revive = LayaHud.DOWNED_TEXT;
                    }
                }
                hud.setRevive(revive);
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LayaPlugin.log("HUD: update failed: " + t);
            }
        }
    }

    /** The card registered under {@link #HUD_KEY}, or null when it is not there. */
    private LayaHud hudFor(PlayerRef playerRef, Player player) {
        try {
            var manager = player.getHudManager();
            if (manager == null) {
                return null;
            }
            // getCustomHud(String) is keyed, not per-player: one owner, one card.
            return manager.getCustomHud(HUD_KEY) instanceof LayaHud hud ? hud : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Combat stance word -> the card's state word (brief Task 3 mapping). */
    private static String mapStance(String stance) {
        if (stance == null) {
            return "following";
        }
        switch (stance) {
            case "attack": return "attacking";
            case "defend": return "defending";
            case "stay":   return "holding";
            case "follow": return "following";
            default:       return stance;
        }
    }
}
