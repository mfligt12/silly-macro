package com.iipxp.beemacro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The frame sender's wire constants and the screencap parser (pure Java, desktop-tested in test/AiSelfTest). */
public final class FrameWire {
    public static final int MAGIC = 0x534C4652;   // "SLFR"
    public static final int OK = 0, NO_FRAME = 1, DENIED = 2, UNKNOWN = 3;

    /** {width, height, headerBytes} of raw `screencap` output, or null. The header is 3 ints (old) or 4 ints (Android
     *  9+, adds the dataspace); it is worked out from the total size so both work. Format must be RGBA_8888 (1). */
    public static int[] screencapLayout(byte[] all) {
        if (all == null || all.length < 16) return null;
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int w = b.getInt(0), h = b.getInt(4), f = b.getInt(8);
        if (w <= 0 || h <= 0 || w > 10000 || h > 10000 || f != 1) return null;
        long px = (long) w * h * 4, header = all.length - px;
        if (header != 12 && header != 16) return null;
        return new int[]{w, h, (int) header};
    }
}
