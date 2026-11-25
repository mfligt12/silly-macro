package com.iipxp.beemacro;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Background music data, pure Java (desktop self-test): playlists of tracks (built-in or imported audio files),
 * the next/previous rule for repeat + shuffle, and the built-in tracks themselves.
 *
 * Built-in tracks are generated on the device by {@link #synth} from a few lines of maths (chords, plucks, a soft
 * beat): original, no samples or recordings, so there is nothing copyrighted in the APK. Generated once into
 * files/music/ as WAV, then played like any imported file.
 *
 * File format (files/music.txt): "#Playlist name" starts a playlist, each following "uri<TAB>title" line is a track.
 */
public final class Playlists {
    public static final class Track {
        public String uri, title;
        public Track(String uri, String title) { this.uri = uri; this.title = title; }
        public boolean builtin() { return uri.startsWith("builtin:"); }
    }
    public static final class Playlist {
        public String name; public final List<Track> tracks = new ArrayList<>();
        public Playlist(String name) { this.name = name; }
    }

    public static final String[] BUILTIN = {"Midnight Drift", "Soft Hive", "Glass Pulse"};
    public final List<Playlist> lists = new ArrayList<>();

    public static Track builtin(int i) { return new Track("builtin:" + i, BUILTIN[i]); }
    public static String clean(String s) { s = s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(); return s; }

    // ------------------------------------------------------------------ editing
    public Playlist create(String name) { Playlist p = new Playlist(clean(name).isEmpty() ? "Playlist " + (lists.size() + 1) : clean(name)); lists.add(p); return p; }
    public void rename(int i, String name) { if (i >= 0 && i < lists.size() && !clean(name).isEmpty()) lists.get(i).name = clean(name); }
    /** The last playlist can't be removed: there is always one to play from. */
    public boolean remove(int i) { if (lists.size() <= 1 || i < 0 || i >= lists.size()) return false; lists.remove(i); return true; }
    public static boolean move(List<?> l, int from, int to) {
        if (from < 0 || from >= l.size() || to < 0 || to >= l.size() || from == to) return false;
        @SuppressWarnings("unchecked") List<Object> o = (List<Object>) l;
        o.add(to, o.remove(from)); return true;
    }
    /** First start: one playlist with the built-in tracks. */
    public void ensureDefault() {
        if (!lists.isEmpty()) return;
        Playlist p = create("Silly Macro mix");
        for (int i = 0; i < BUILTIN.length; i++) p.tracks.add(builtin(i));
    }

    // ------------------------------------------------------------------ next / previous
    /** Index after `i` in a list of n: repeat "one" stays, "all" wraps, "off" ends (-1). Shuffle picks another track
     *  at random (never the same one twice in a row when there is a choice). userSkip = the Next button: repeat-one
     *  doesn't trap it. */
    public static int next(int i, int n, String repeat, boolean shuffle, Random r, boolean userSkip) {
        if (n <= 0) return -1;
        if ("one".equals(repeat) && !userSkip) return Math.max(0, Math.min(i, n - 1));
        if (shuffle && n > 1) { int j; do j = r.nextInt(n); while (j == i); return j; }
        if (i + 1 < n) return i + 1;
        return "off".equals(repeat) && !userSkip ? -1 : 0;
    }
    public static int prev(int i, int n) { return n <= 0 ? -1 : (i - 1 + n) % n; }

    // ------------------------------------------------------------------ load / save
    public static Playlists load(File f) {
        Playlists p = new Playlists();
        if (f.exists()) try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            Playlist cur = null;
            for (String s; (s = r.readLine()) != null; ) {
                if (s.startsWith("#")) { cur = new Playlist(s.substring(1)); p.lists.add(cur); }
                else if (cur != null) { int t = s.indexOf('\t'); if (t > 0) cur.tracks.add(new Track(s.substring(0, t), s.substring(t + 1))); }
            }
        } catch (Exception ignored) { }
        p.ensureDefault();
        return p;
    }
    public synchronized boolean save(File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Playlist l : lists) {
                w.write("#" + clean(l.name) + "\n");
                for (Track t : l.tracks) w.write(clean(t.uri) + "\t" + clean(t.title) + "\n");
            }
        } catch (Exception e) { return false; }
        return tmp.renameTo(f) || (f.delete() && tmp.renameTo(f));
    }

    // ------------------------------------------------------------------ built-in tracks (original, generated)
    public static final int RATE = 22050, SECONDS = 48;
    /** Mono 16-bit PCM of built-in track `k`: a chord pad (all), plus soft beat (1) or plucked arpeggio (2).
     *  48 s = an exact number of bars, so each loops without a seam. Deterministic: same samples every time. */
    public static short[] synth(int k, int rate, int seconds) {
        int n = rate * seconds; float[] mix = new float[n];
        double bpm = k == 0 ? 60 : k == 1 ? 80 : 100, beat = 60 / bpm;
        // chord roots in semitones from A3 (220 Hz); each chord = root, third, fifth, octave
        int[][] prog = k == 0 ? new int[][]{{0, 3, 7, 12}, {-4, 0, 3, 8}, {-9, -5, -2, 3}, {-2, 2, 5, 10}}      // Am F C G (slow)
                : k == 1 ? new int[][]{{-4, 0, 3, 7}, {-7, -3, 0, 5}, {0, 3, 7, 10}, {-2, 2, 5, 9}}               // Fmaj7 Dm7-ish Am7 G6
                : new int[][]{{3, 7, 10, 15}, {-2, 2, 5, 10}, {0, 3, 7, 12}, {-4, 0, 3, 8}};                      // C G Am F
        double bar = beat * 4, chordLen = k == 0 ? bar * 2 : bar;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate, ct = t % chordLen;
            int[] ch = prog[(int) (t / chordLen) % prog.length];
            double env = Math.min(1, ct / 0.6) * Math.min(1, (chordLen - ct) / 0.4);   // soft attack and release
            double v = 0;
            for (int s : ch) { double f = 220 * Math.pow(2, s / 12.0); v += Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 1.003 * t) + 0.18 * Math.sin(4 * Math.PI * f * t); }
            mix[i] += (float) (v * env * 0.055);
        }
        Random r = new Random(11 + k);
        if (k == 1) {   // soft beat: kick on 1 and 3, hat on the off-beats, a little swing
            for (double t0 = 0; t0 < seconds; t0 += beat) {
                int beatNo = (int) Math.round(t0 / beat) % 4;
                if (beatNo % 2 == 0) for (int j = 0; j < rate * 0.35 && (int) (t0 * rate) + j < n; j++) {
                    double t = j / (double) rate, f = 50 + 70 * Math.exp(-t * 30);
                    mix[(int) (t0 * rate) + j] += (float) (Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 9) * 0.45);
                }
                int hs = (int) ((t0 + beat * 0.56) * rate); double lp = 0;
                for (int j = 0; j < rate * 0.06 && hs + j < n; j++) { double x = r.nextDouble() * 2 - 1; double hp = x - lp; lp = x; mix[hs + j] += (float) (hp * Math.exp(-j / (rate * 0.012)) * 0.08); }
            }
        }
        if (k == 2 || k == 0) {   // plucked arpeggio (track 2) / sparse bell notes (track 0) from the chord tones
            double step = k == 2 ? beat / 2 : beat * 2;
            int note = 0;
            for (double t0 = 0; t0 < seconds - 0.01; t0 += step, note++) {
                if (k == 0 && r.nextInt(3) == 0) continue;
                int[] ch = prog[(int) (t0 / chordLen) % prog.length];
                double f = 440 * Math.pow(2, ch[k == 2 ? note % 4 : r.nextInt(4)] / 12.0);
                int s0 = (int) (t0 * rate);
                for (int j = 0; j < rate * 1.2 && s0 + j < n; j++) {
                    double t = j / (double) rate;
                    mix[s0 + j] += (float) ((Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t)) * Math.exp(-t * (k == 2 ? 5 : 2.5)) * Math.min(1, t / 0.004) * (k == 2 ? 0.12 : 0.09));
                }
            }
        }
        short[] out = new short[n]; double lp = 0;
        for (int i = 0; i < n; i++) { lp += 0.35 * (mix[i] - lp); out[i] = (short) Math.round(Math.tanh(lp) * 26000); }   // gentle low-pass + soft clip
        return out;
    }
    /** 16-bit mono WAV bytes. */
    public static byte[] wav(short[] pcm, int rate) {
        int data = pcm.length * 2; byte[] b = new byte[44 + data];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII))
          .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
          .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        for (short s : pcm) bb.putShort(s);
        return b;
    }
}
