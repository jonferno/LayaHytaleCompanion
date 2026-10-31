package com.jonferno.laya.npc;

/**
 * Attack-hint matching, no world needed.
 *
 * <p>Measured 2026-10-27 in game: "attack that squirell" produced
 * {@code Combat: no target matching "attack that squirell"} because the role matcher only
 * did substrings, and the fallback then picked a bear 23 blocks away. One wrong letter
 * must not lose the animal the player is pointing at.</p>
 *
 * <p>Run: {@code java -cp "build/classes;build/test-classes;<engine jar>"
 * com.jonferno.laya.npc.CombatHintTest}</p>
 */
public final class CombatHintTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // The reported case, and the plain spellings.
        match("squirrel", "squirell", true);
        match("squirrel", "squirrel", true);
        match("temple_squirrel", "squirell", true);
        match("squirrel", "squirrels", true);

        // Substring and prefix still work.
        match("bear_grizzly", "bear", true);
        match("bear_grizzly", "grizzly", true);
        match("boar", "boar", true);

        // Short words stay exact: one edit must not turn a bear into a boar.
        match("boar", "bear", false);
        match("bear", "boar", false);

        // Longer misspellings still resolve; unrelated words do not.
        match("rabbit", "rabbitt", true);
        match("raccoon", "racoon", true);
        match("squirrel", "spider", false);

        // 0.4.4: the hint the bridge sends is the noun, but it can carry filler.
        matchRole("Rabbit", "rabbit", true);
        matchRole("Rabbit_White", "rabbit", true);
        matchRole("Rabbit_Wild", "the rabbit", true);
        matchRole("Rabbit", "kill that rabbit", true);
        matchRole("Chicken", "rabbit", false);
        matchRole("Bunny", "rabbit", false);

        // 0.4.4: combat mode families decide what "ranged" and "magic" equip.
        family("Weapon_Sword_Iron", "melee");
        family("Weapon_Battleaxe_Steel", "melee");
        family("Weapon_Bow_Short", "ranged");
        family("Weapon_Crossbow_Basic", "ranged");
        family("Weapon_Staff_Wood", "magic");
        family("Weapon_Staff_Frost", "magic");
        family("Weapon_Wand_Ember", "magic");
        family("Weapon_Shield_Iron", null);
        family("Tool_Pickaxe_Iron", null);

        // 0.4.4: defend loadout rules.
        check("isShield(Weapon_Shield_Iron)", CombatController.isShield("Weapon_Shield_Iron"), "false");
        check("isShield(Weapon_Sword_Iron)", !CombatController.isShield("Weapon_Sword_Iron"), "true");
        check("twoHanded(Battleaxe)", CombatController.isTwoHanded("Weapon_Battleaxe_Steel"), "false");
        check("twoHanded(Longsword)", CombatController.isTwoHanded("Weapon_Longsword_Iron"), "false");
        check("twoHanded(Sword)", !CombatController.isTwoHanded("Weapon_Sword_Iron"), "true");
        check("twoHanded(Mace)", CombatController.isTwoHanded("Weapon_Mace_Iron"), "false");
        check("twoHanded(Club)", CombatController.isTwoHanded("Weapon_Club_Iron"), "false");

        // 0.4.4: ability burst colour follows the item material.
        color("Weapon_Sword_Cobalt", 0x3F74E0);
        color("Weapon_Daggers_Thorium", 0x57C24A);
        color("Weapon_Staff_Mithril", 0x9B6BE0);
        color("Weapon_Shield_Cobalt", 0x3F74E0);
        color("Armor_Iron_Legs", 0xB8BEC7);
        color("Weapon_Arrow_Crude", 0x9C8A6A);

        System.out.println(passed + " PASS, " + failed + " FAIL");
        if (failed > 0) System.exit(1);
    }

    private static void match(String role, String token, boolean expected) {
        boolean got = CombatController.hintTokenMatches(role, token);
        check("hintTokenMatches(" + role + ", " + token + ") == " + expected,
            got == expected, String.valueOf(got));
    }

    private static void matchRole(String role, String hint, boolean expected) {
        boolean got = CombatController.hintMatchesRole(role.toLowerCase(), hint.toLowerCase());
        check("hintMatchesRole(" + role + ", \"" + hint + "\") == " + expected,
            got == expected, String.valueOf(got));
    }

    private static void family(String itemId, String expected) {
        String got = CombatController.familyClassOf(itemId);
        check("familyClassOf(" + itemId + ") == " + expected,
            expected == null ? got == null : expected.equals(got), String.valueOf(got));
    }

    private static void color(String itemId, int expected) {
        int got = CombatController.materialRgb(itemId);
        check("materialRgb(" + itemId + ") == " + Integer.toHexString(expected),
            got == expected, Integer.toHexString(got));
    }

    private static void check(String what, boolean ok, String got) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what + " (got " + got + ")");
        }
    }
}
