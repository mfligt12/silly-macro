package com.iipxp.beemacro;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** App side of {@link FrameServer}: one screen frame per call from the key sender's shell process (no MediaProjection). */
final class ShellFrames {
    static volatile String status = "not connected";
    private static Socket sock; private static DataInputStream in; private static OutputStream out;
    private static byte[] buf;

    static synchronized boolean connect(Context c) {
        if (sock != null && sock.isConnected() && !sock.isClosed()) return true;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", FrameServer.PORT), 500);
            s.setTcpNoDelay(true); s.setSoTimeout(4000); s.setReceiveBufferSize(1 << 20);
            OutputStream o = s.getOutputStream();
            o.write((Keys.token(c) + "\n").getBytes("UTF-8")); o.flush();
            sock = s; out = o; in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            status = "connected";
            return true;
        } catch (Exception e) { status = "frame sender not running"; close(); return false; }
    }

    static synchronized void close() { try { if (sock != null) sock.close(); } catch (Exception ignored) { } sock = null; in = null; out = null; }

    /** Fills f with the newest frame (ARGB). maxW 0 = native size. False if no frame (caller retries / reconnects). */
    static synchronized boolean grab(CaptureService.Frame f, int maxW) {
        if (out == null) return false;
        try {
            out.write(("f " + maxW + "\n").getBytes("UTF-8")); out.flush();
            int magic = in.readInt(), st = in.readInt(), w = in.readInt(), h = in.readInt();
            if (magic != FrameWire.MAGIC) { status = "frame sender spoke another protocol"; close(); return false; }
            if (st == 2) { status = "frame sender refused the connection (token)"; close(); return false; }
            if (st != 0) { status = "frame sender has no frame (" + st + ")"; return false; }
            int n = w * h * 4;
            if (buf == null || buf.length < n) buf = new byte[n];
            in.readFully(buf, 0, n);
            if (f.px == null || f.px.length != w * h) f.px = new int[w * h];
            IntBuffer ib = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] o = f.px;
            ib.get(o, 0, w * h);
            for (int i = 0; i < o.length; i++) { int c = o[i]; o[i] = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF); }   // RGBA bytes -> ARGB
            f.w = w; f.h = h; f.time = android.os.SystemClock.uptimeMillis();
            status = "connected";
            return true;
        } catch (Exception e) { status = "lost connection to the frame sender"; close(); return false; }
    }
}
