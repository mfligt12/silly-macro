package com.iipxp.beemacro;

/** Routes recorded from the user's own keyboard on the Galaxy Tab S9 FE (adb getevent of the USB keyboard,
 *  2026-11-17), as {ms from route start, Android key code, 1 down / 0 up}. Replayed key for key by
 *  MacroService.keyScript, so overlapping keys (W held while tapping D) are exact. Start = first key after the
 *  respawn (Esc, R, Enter), shift lock on, camera untouched (mouse only jittered 1 px, never during the walk).
 *  Raw: test/recordings/to_field_keyboard_tablet_getevent.txt, converter test/recordings/kb_export.js.
 *  TO_FIELD_KB: zoom out (O O), walk to the Red Cannon, fire it (E), land, walk to the spot, sprinkler (1). */
public final class RecordedKeys {
    /** TO_FIELD_KB: 42 key events, 27.2 s (keys: KEY_O KEY_W KEY_D KEY_SPACE KEY_E KEY_A KEY_1) */
    static final int[][] TO_FIELD_KB = {{0, 43, 1}, {80, 43, 0}, {144, 43, 1}, {232, 43, 0}, {1200, 51, 1}, {1696, 51, 0}, {2176, 32, 1}, {3352, 32, 0}, {3600, 51, 1}, {5600, 51, 0}, {5864, 62, 1}, {6056, 62, 0}, {6264, 51, 1}, {7416, 51, 0}, {8040, 32, 1}, {8336, 32, 0}, {9240, 33, 1}, {9360, 33, 0}, {11960, 29, 1}, {13424, 29, 0}, {13720, 51, 1}, {14176, 51, 0}, {14376, 32, 1}, {14592, 32, 0}, {14736, 51, 1}, {15128, 51, 0}, {15224, 29, 1}, {15536, 29, 0}, {15952, 51, 1}, {17112, 51, 0}, {17576, 29, 1}, {17856, 29, 0}, {18144, 51, 1}, {19848, 32, 1}, {19896, 32, 0}, {20368, 32, 1}, {20432, 32, 0}, {25800, 51, 0}, {25824, 32, 1}, {26056, 32, 0}, {27088, 8, 1}, {27184, 8, 0}};

    // ---- Timed sequences from Natro Macro (NatroTeam/NatroMacro, GPL-3.0), Android key codes:
    // W 51, A 29, S 47, D 32, SPACE 62, E 33.
    /** paths/gtf-pinetree.ahk, cannon branch, after reaching the Red Cannon: E, hold Right+Back, open the glider
     *  (Space ×2) after 925 ms, release Back after 4.5 s, Right 0.5 s later, close the glider (Space). */
    /** paths/wf-pinetree.ahk (walk branch): jump while walking forward (W stays down through the 200 ms jump). */
    static final int[][] JUMP_W = {{0, 51, 1}, {0, 62, 1}, {200, 62, 0}, {200, 51, 0}};

    public static int[][] get(String name) {
        switch (name) {
            case "TO_FIELD_KB": return TO_FIELD_KB;
            case "JUMP_W": return JUMP_W;
            default: throw new IllegalArgumentException(name);
        }
    }
    public static int durationMs(int[][] ev) { return ev.length == 0 ? 0 : ev[ev.length - 1][0]; }
}
