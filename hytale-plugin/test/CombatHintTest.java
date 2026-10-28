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

        System.out.println(passed + " PASS, " + failed + " FAIL");
        if (failed > 0) System.exit(1);
    }

    private static void match(String role, String token, boolean expected) {
        boolean got = CombatController.hintTokenMatches(role, token);
        check("hintTokenMatches(" + role + ", " + token + ") == " + expected,
            got == expected, String.valueOf(got));
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
