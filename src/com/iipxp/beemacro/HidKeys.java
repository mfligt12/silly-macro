package com.iipxp.beemacro;

/**
 * Keys as a real USB keyboard sends them (HID report id 1: modifiers, reserved, up to 6 keys), for the key sender's
 * virtual keyboard. Injected key events carry an input-device id, and Roblox ignored them on phones where Android
 * doesn't list the virtual keyboard as a full keyboard (Discord, Motorola / Android 16: "Roblox doesn't respond to
 * Shizuku, but with my keyboard plugged in it works"). Reports come from the keyboard device itself, like the number
 * keys that fixed the sprinkler. Pure Java (Android key codes as numbers) so the self-test checks the table.
 */
public final class HidKeys {
    public static final int SHIFT_LEFT = 59;   // KeyEvent.KEYCODE_SHIFT_LEFT: a modifier bit, not a key slot

    /** USB HID usage id for an Android key code, 0 = not mapped (the sender then injects it as before). */
    public static int usage(int code) {
        if (code >= 29 && code <= 54) return 0x04 + (code - 29);   // A..Z
        if (code >= 8 && code <= 16) return 0x1E + (code - 8);     // 1..9
        switch (code) {
            case 7: return 0x27;     // 0
            case 66: return 0x28;    // Enter
            case 111: return 0x29;   // Escape
            case 67: return 0x2A;    // Backspace
            case 61: return 0x2B;    // Tab
            case 62: return 0x2C;    // Space
            case 55: return 0x36;    // ,  (camera left)
            case 56: return 0x37;    // .  (camera right)
            case 116: return 0x47;   // Scroll Lock (keep-alive)
            case 92: return 0x4B;    // Page Up (tilt)
            case 93: return 0x4E;    // Page Down
            case 22: return 0x4F;    // Right
            case 21: return 0x50;    // Left
            case 20: return 0x51;    // Down
            case 19: return 0x52;    // Up
            default: return 0;
        }
    }
    public static boolean mapped(int code) { return code == SHIFT_LEFT || usage(code) != 0; }

    /** The report for the keys held now (in pressing order; a real keyboard reports at most 6 at once). */
    public static byte[] report(Iterable<Integer> held) {
        byte[] r = new byte[9]; r[0] = 1;
        int n = 3;
        for (int code : held) {
            if (code == SHIFT_LEFT) { r[1] |= 0x02; continue; }
            int u = usage(code);
            if (u != 0 && n < 9) r[n++] = (byte) u;
        }
        return r;
    }
}
