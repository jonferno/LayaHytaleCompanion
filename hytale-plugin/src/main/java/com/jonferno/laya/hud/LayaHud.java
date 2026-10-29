package com.jonferno.laya.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Companion card for Laya, shown for the owning player only.
 *
 * <p>Task 3 scope: the card now carries live values. {@link #setValues} is the
 * single entry point; it clamps the health, picks the colour bucket from the
 * fraction, applies the downed override and pushes a patch to the client only
 * when something actually changed.
 *
 * <p>Update-call semantics (read out of the 0.7.2 {@code CustomUIHud} bytecode,
 * not guessed): {@code update(boolean, UICommandBuilder)} builds a
 * {@code CustomHud} packet whose boolean is the packet's {@code clear} field, and
 * writes it through {@code PacketHandler.writeNoCache}. {@code show()} calls
 * {@code build(b)} then {@code update(true, b)} — i.e. "clear and (re)build";
 * {@code setZOrder} calls {@code update(false, emptyBuilder)} — i.e. "keep, just
 * re-apply". A live value refresh is therefore {@code update(false, b)} with a
 * builder that carries only {@code set(...)} commands and NO {@code append}.
 *
 * <p>Element ids are declared in {@code LayaHud.ui}: {@code #Panel},
 * {@code #Portrait}, {@code #Name}, {@code #State}, {@code #BarTrack},
 * {@code #BarGreen}, {@code #BarAmber}, {@code #BarRed}, {@code #Hp}.
 */
public final class LayaHud extends CustomUIHud {

    /** Bare filename; the client resolves it inside {@code Common/UI/Custom/}. */
    private static final String LAYOUT = "LayaCompanionHud.ui";

    private static final String DEFAULT_NAME = "Laya";
    private static final String DEFAULT_STATE = "following";

    /** Downed text (brief): the state word is replaced by this instruction. */
    static final String DOWNED_TEXT = "DOWNED - hold interact";

    // Current values owned by setValues (see the change check there).
    private float hp = 100f;
    private float maxHp = 100f;
    private String state = DEFAULT_STATE;
    private boolean downed = false;
    /** 0.4.4: melee | ranged | magic, so "attacking" draws the right weapon icon. */
    private String mode = "melee";
    /** 0.4.4: staff mana fraction (0..1) for the blue bar under health. */
    private float mana = 1f;
    // Task 3 (name): the companion name shown on the card. Set from the controller;
    // DEFAULT_NAME until then, so the card is never blank.
    private String name = DEFAULT_NAME;

    // Last-sent snapshot, so an unchanged 500 ms frame pushes nothing. The fields
    // start at the built defaults and build() records them as sent, so the first
    // real setValues only pushes when the live values differ from the layout.
    private float lastHp = 100f;
    private float lastMaxHp = 100f;
    private String lastState = null;
    private boolean lastDowned = false;
    private String lastMode = null;
    private float lastMana = -1f;
    private boolean sentOnce = false;
    private String lastRevive = null;
    private String lastGear = null;
    private String lastName = null;

    public LayaHud(PlayerRef playerRef) {
        super(playerRef, "laya_companion", 0);
    }

    @Override
    protected void build(UICommandBuilder b) {
        b.append(LAYOUT);
        // Diagnostic: append-only for one run. If the card renders now, a Java set(...)
        // command is what invalidates the whole update batch.
        lastHp = hp;
        lastMaxHp = maxHp;
        lastState = state;
        lastDowned = downed;
        lastMode = mode;
        lastMana = mana;
        lastName = name;
        sentOnce = true;
    }

    /**
     * Re-states the current values. Called by {@code show()} (with the builder that
     * already carries {@code build}'s append) and by the controller's live refresh,
     * which hands over an empty builder so nothing but the {@code set(...)} patches
     * go out.
     *
     * <p>{@code show} passes {@code clear=true}; keep the parameter out of the
     * decision — the commands are identical either way, and the caller owns which
     * builder it hands over.
     */
    @Override
    public void update(boolean show, UICommandBuilder b) {
        applyCurrent(b);
        // The base implementation is what serialises the CustomHud packet and sends it.
        // Overriding without this call builds the layout and never sends it.
        super.update(show, b);
    }

    /**
     * Task 3 (name): set the companion name shown on the card. A change pushes
     * immediately; the value is re-stated by every later {@code applyCurrent}, so
     * a rename survives the 500 ms refresh too. World-thread only (the card's own
     * builder writes are).
     */
    public void setName(String n) {
        String v = n == null || n.isEmpty() ? DEFAULT_NAME : n;
        if (v.equals(lastName)) return;
        lastName = v;
        this.name = v;
        UICommandBuilder b = new UICommandBuilder();
        b.set("#Name.Text", v);
        update(false, b);
    }

    /** Revive countdown line; empty string hides it. Only Text is set (safe property). */
    public void setRevive(String text) {
        String t = text == null ? "" : text;
        if (t.equals(lastRevive)) return;
        lastRevive = t;
        UICommandBuilder b = new UICommandBuilder();
        b.set("#Revive.Text", t);
        update(false, b);
    }

    /** Equipped gear strip; null/empty hides nothing, empty slots stay visible. */
    public void setGear(String head, String chest, String hands, String legs, String shield) {
        String sig = String.valueOf(head) + "|" + chest + "|" + hands + "|" + legs + "|" + shield;
        if (sig.equals(lastGear)) return;
        lastGear = sig;
        UICommandBuilder b = new UICommandBuilder();
        setGearSlot(b, "#GearHead", head);
        setGearSlot(b, "#GearChest", chest);
        setGearSlot(b, "#GearHands", hands);
        setGearSlot(b, "#GearLegs", legs);
        setGearSlot(b, "#GearShield", shield);
        update(false, b);
    }

    private static void setGearSlot(UICommandBuilder b, String path, String itemId) {
        boolean present = itemId != null && !itemId.isEmpty() && !"null".equals(itemId);
        b.set(path + ".ItemId", present ? itemId : "");
        b.set(path + ".Quantity", present ? 1 : 0);
    }

    /**
     * The Task 3 entry point: clamp, colour, override, then push when changed.
     *
     * @param hp     live health; clamped to {@code 0..maxHp}
     * @param maxHp  the role maximum ({@link com.jonferno.laya.npc.CombatController#MAX_HP});
     *               anything {@code <= 0} falls back to 100 so the fraction is never NaN
     * @param state  the state word chosen by the controller ({@code following},
     *               {@code holding}, {@code attacking}, {@code defending}); ignored while downed
     * @param downed the downed override: empty bar and {@link #DOWNED_TEXT}
     */
    public void setValues(float hp, float maxHp, String state, boolean downed) {
        setValues(hp, maxHp, state, downed, "melee", 1f);
    }

    /**
     * 0.4.4: the same entry point with the combat mode and mana, so an engaged Laya
     * draws a bow or staff on the card instead of the sword, and the blue bar under
     * health tracks her staff mana.
     */
    public void setValues(float hp, float maxHp, String state, boolean downed, String mode,
                          float mana) {
        float max = maxHp > 0f ? maxHp : 100f;
        float clamped = hp < 0f ? 0f : Math.min(hp, max);
        String word = state == null || state.isEmpty() ? DEFAULT_STATE : state;
        String m = mode == null || mode.isEmpty() ? "melee" : mode.toLowerCase();
        float man = Math.max(0f, Math.min(1f, mana));

        boolean changed = !sentOnce
            || downed != lastDowned
            || Math.abs(clamped - lastHp) > 0.01f
            || Math.abs(max - lastMaxHp) > 0.01f
            || Math.abs(man - lastMana) > 0.01f
            || !m.equals(lastMode)
            || (downed ? false : !word.equals(lastState));
        if (!changed) {
            return;
        }

        this.hp = clamped;
        this.maxHp = max;
        this.state = word;
        this.downed = downed;
        this.mode = m;
        this.mana = man;

        UICommandBuilder b = new UICommandBuilder();
        applyCurrent(b);
        // clear=false: patch the existing card rather than re-appending the layout.
        update(false, b);

        lastHp = clamped;
        lastMaxHp = max;
        lastState = word;
        lastDowned = downed;
        lastMode = m;
        lastMana = man;
        sentOnce = true;
    }

    /**
     * Pushes the whole visible state. Exactly one bar is `Visible true`; the other
     * two get {@code Value 0} as well, so if the client ignored {@code Visible} the
     * hidden fills could not paint over the active one (they draw in file order,
     * so red would otherwise sit on top of green).
     */
    private void applyCurrent(UICommandBuilder b) {
        b.set("#Name.Text", name);

        if (downed) {
            b.set("#Hp.Text", "0 / " + (int) maxHp);
            setBars(b, 0f);
            setIcon(b, "downed");
            return;
        }

        // Use the SAME rounded HP that the label shows: 99.6 HP displays as "100 / 100",
        // so the bar must also be full, or it looks permanently one cell short.
        int shownHp = (int) Math.round(hp);
        int shownMax = (int) Math.round(maxHp);
        float fraction = shownMax > 0 ? (float) shownHp / shownMax : 0f;
        b.set("#Hp.Text", shownHp + " / " + shownMax);
        setBars(b, fraction);
        b.set("#ManaFill.Value", Math.max(0f, Math.min(1f, mana)));
        b.set("#Mana.Text", "MP " + Math.round(Math.max(0f, Math.min(1f, mana)) * 100f));
        setIcon(b, state);
    }

    private void setBars(UICommandBuilder b, float fraction) {
        b.set("#BarFill.Value", Math.max(0f, Math.min(1f, fraction)));
    }

    /** Icon/ring ids in the .ui. Exactly one is shown at a time via Value 1. */
    private static final String[] ICONS = {
        "Follow", "Attack", "Defend", "Mine", "Chop", "Gather", "Farm", "Stay", "Downed",
        "Ranged", "Magic"
    };

    /** State word -> icon. Downed is passed explicitly from the caller. */
    private static String iconFor(String state) {
        return iconFor(state, "melee");
    }

    /**
     * 0.4.4: an engaged Laya draws the weapon family she is fighting with. The state
     * word stays "attacking"; the mode picks sword, bow or staff.
     */
    private static String iconFor(String state, String mode) {
        String s = state == null ? "" : state.toLowerCase();
        if ("attacking".equals(s) || "attack".equals(s)) {
            if ("ranged".equals(mode)) return "Ranged";
            if ("magic".equals(mode)) return "Magic";
            return "Attack";
        }
        if ("ranged".equals(s)) return "Ranged";
        if ("magic".equals(s)) return "Magic";
        return switch (s) {
            case "defending", "defend" -> "Defend";
            case "mining", "mine" -> "Mine";
            case "chopping", "chop" -> "Chop";
            case "gathering", "gather" -> "Gather";
            case "farming", "farm", "planting", "tilling" -> "Farm";
            case "holding", "stay" -> "Stay";
            case "downed" -> "Downed";
            default -> "Follow";
        };
    }

    private void setIcon(UICommandBuilder b, String state) {
        String active = iconFor(state, mode);
        for (String name : ICONS) {
            float v = name.equals(active) ? 1f : 0f;
            b.set("#Icon" + name + ".Value", v);
            b.set("#Ring" + name + ".Value", v);
        }
    }

    @Override
    protected void onRemove() {
        // Nothing to release yet; kept explicit so the lifecycle is obvious.
    }
}
