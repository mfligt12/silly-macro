package com.iipxp.beemacro;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Background music: one player for the whole app process (the app, the floating panel and the macro share it, so
 * it can never play twice). Tracks come from {@link Playlists}. Preferences live in the profile (music*).
 *
 * Audio focus: asks for media focus before playing. A phone call or another app's short sound (transient loss)
 * pauses and resumes after; "duck" lowers the volume; a permanent loss (another music app) stops, except while a
 * macro run is going and "Keep playing during runs" is on (Bee Swarm's own sounds then mix with it).
 * The app's process stays alive during runs because of the accessibility service, so no extra service is needed.
 */
final class Music implements AudioManager.OnAudioFocusChangeListener {
    private static Music inst;
    static synchronized Music get(Context c) { if (inst == null) inst = new Music(c.getApplicationContext()); return inst; }

    private final Context app; private final Profile p; private final AudioManager am; private final Handler ui = new Handler(Looper.getMainLooper());
    final Playlists lists; private final File listFile;
    private MediaPlayer mp; private AudioFocusRequest focus;
    private boolean playing, preparing, pausedByFocus, ducked;
    private final Random rnd = new Random();
    String error = "";
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Music(Context c) {
        app = c; p = App.profile(c); am = c.getSystemService(AudioManager.class);
        listFile = new File(c.getFilesDir(), "music.txt");
        lists = Playlists.load(listFile);
        if (p.musicPlaylist >= lists.lists.size()) p.musicPlaylist = 0;
        java.util.List<Playlists.Track> ts = list().tracks;            // each app start begins at your default track
        for (int i = 0; i < ts.size(); i++) if (ts.get(i).uri.equals(p.musicDefault)) p.musicIndex = i;
    }

    void listen(Runnable r) { listeners.add(r); }
    void unlisten(Runnable r) { listeners.remove(r); }
    private void changed() { ui.post(() -> { for (Runnable r : listeners) r.run(); }); }
    void saveLists() { lists.save(listFile); changed(); }

    Playlists.Playlist list() { return lists.lists.get(Math.min(p.musicPlaylist, lists.lists.size() - 1)); }
    Playlists.Track track() { Playlists.Playlist l = list(); return l.tracks.isEmpty() ? null : l.tracks.get(Math.max(0, Math.min(p.musicIndex, l.tracks.size() - 1))); }
    boolean playing() { return playing; }
    String title() { Playlists.Track t = track(); return t == null ? "No tracks" : t.title; }
    int position() { try { return mp != null && !preparing ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    int duration() { try { return mp != null && !preparing ? mp.getDuration() : 0; } catch (Exception e) { return 0; } }

    // ------------------------------------------------------------------ controls
    void toggle() { if (playing) pause(); else play(); }
    void play() {
        if (!p.musicOn) { p.musicOn = true; App.save(app); }
        if (mp != null && !preparing && !playing) { if (!requestFocus()) return; mp.start(); playing = true; changed(); return; }
        if (mp == null) start(false);
    }
    void pause() { pausedByFocus = false; if (mp != null && playing) { try { mp.pause(); } catch (Exception ignored) { } } playing = false; changed(); }
    void stop() { release(); abandonFocus(); changed(); }
    void next(boolean user) {
        int n = list().tracks.size(), j = Playlists.next(p.musicIndex, n, p.musicRepeat, p.musicShuffle, rnd, user);
        if (j < 0) { release(); changed(); return; }     // repeat off: end of the playlist
        p.musicIndex = j; App.save(app); start(true);
    }
    void prev() {
        if (position() > 3000) { try { mp.seekTo(0); } catch (Exception ignored) { } return; }   // like any player: restart first
        p.musicIndex = Playlists.prev(p.musicIndex, list().tracks.size()); App.save(app); start(true);
    }
    void playAt(int playlist, int index) { p.musicPlaylist = playlist; p.musicIndex = index; App.save(app); start(true); }
    void seek(int ms) { if (mp != null && !preparing) try { mp.seekTo(ms); } catch (Exception ignored) { } }
    void setVolume(int v) { p.musicVolume = Math.max(0, Math.min(100, v)); applyVolume(); }
    void setMute(boolean m) { p.musicMute = m; App.save(app); applyVolume(); changed(); }
    void setOn(boolean on) { p.musicOn = on; App.save(app); if (on) play(); else stop(); changed(); }
    private void applyVolume() {
        if (mp == null) return;
        float v = p.musicMute ? 0 : (float) (Math.pow(p.musicVolume / 100.0, 2) * (ducked ? 0.3 : 1));   // squared: feels even to the ear
        try { mp.setVolume(v, v); } catch (Exception ignored) { }
    }

    /** Is Silly Macro's own screen showing? Outside the app, music only plays during a run (if allowed). */
    static volatile boolean appVisible;
    /** The app went to the background (Home, Roblox, another app): pause unless a run is going and may keep music. */
    static void appHidden(Context c) {
        appVisible = false;
        if (inst == null) return;
        // checked 8 s later: Start opens Roblox a few seconds before the run begins, and a theme switch rebuilds the screen
        inst.ui.postDelayed(() -> {
            if (!appVisible && inst.playing && !(runActive() && inst.p.musicDuringRun)) { inst.pause(); Log.add("music: paused (left the app, no run)"); }
        }, 8000);
    }
    /** A run ended: outside the app the music stops with it. */
    static void runEnded() {
        if (inst != null && inst.playing && !appVisible) inst.ui.post(() -> { inst.pause(); Log.add("music: paused (run ended)"); });
    }
    /** A run started: pause unless music may keep playing during runs. */
    static void runStarted(Context c) {
        if (inst == null) return;
        if (!inst.p.musicDuringRun && inst.playing) inst.ui.post(inst::pause);
    }
    private static boolean runActive() { Engine e = Engine.inst; return e != null && !e.finished(); }

    // ------------------------------------------------------------------ playback
    private void start(boolean force) {
        Playlists.Track t = track();
        if (t == null) { error = "This playlist is empty"; changed(); return; }
        if (!requestFocus()) { error = "Another app is using the audio"; changed(); return; }
        release();
        preparing = true; error = ""; changed();
        new Thread(() -> {
            try {
                MediaPlayer m = new MediaPlayer();
                m.setAudioAttributes(attrs());
                if (t.builtin()) m.setDataSource(builtinFile(Integer.parseInt(t.uri.substring(8))).getPath());
                else m.setDataSource(app, Uri.parse(t.uri));
                m.setLooping("one".equals(p.musicRepeat));
                m.setOnCompletionListener(x -> ui.post(() -> next(false)));
                m.setOnErrorListener((x, what, extra) -> { ui.post(() -> { error = "Couldn't play " + t.title; Log.add("music: error " + what + "/" + extra + " on " + t.title); release(); changed(); }); return true; });
                m.prepare();
                ui.post(() -> {
                    if (!preparing) { m.release(); return; }   // stopped while loading
                    mp = m; preparing = false; applyVolume(); mp.start(); playing = true; changed();
                    Log.add("music: playing " + t.title);
                });
            } catch (Exception e) {
                ui.post(() -> { preparing = false; playing = false; error = "Couldn't open " + t.title + (t.builtin() ? "" : " (file moved or deleted?)"); Log.add("music: " + e); changed(); });
            }
        }, "music").start();
    }
    private void release() { preparing = false; playing = false; if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; } }
    /** Built-in track as a WAV in files/music, generated on first use (~1 s, ~2 MB each). */
    private File builtinFile(int k) throws Exception {
        File dir = new File(app.getFilesDir(), "music"); dir.mkdirs();
        File f = new File(dir, "builtin_" + k + "_v1.wav");
        if (!f.exists() || f.length() < 1000) {
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(Playlists.wav(Playlists.synth(k, Playlists.RATE, Playlists.SECONDS), Playlists.RATE)); }
            if (!tmp.renameTo(f)) throw new Exception("couldn't save the built-in track");
        }
        return f;
    }
    void setRepeat(String r) { p.musicRepeat = r; App.save(app); if (mp != null) try { mp.setLooping("one".equals(r)); } catch (Exception ignored) { } changed(); }

    // ------------------------------------------------------------------ audio focus
    private AudioAttributes attrs() { return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(); }
    private boolean requestFocus() {
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs())
                .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(this, ui).build();
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void abandonFocus() { if (focus != null) am.abandonAudioFocusRequest(focus); }
    @Override public void onAudioFocusChange(int change) {
        Log.add("music: audio focus " + change);
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (runActive() && p.musicDuringRun) return;          // allowed to keep playing over the run
                pause(); abandonFocus(); break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) { pause(); pausedByFocus = true; } break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true; applyVolume(); break;
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false; applyVolume();
                if (pausedByFocus && mp != null) { pausedByFocus = false; mp.start(); playing = true; changed(); }
                break;
        }
    }

    /** Imported audio file (Storage Access Framework URI, permission already taken) added to the current playlist. */
    void add(String uri, String title) { list().tracks.add(new Playlists.Track(uri, Playlists.clean(title).isEmpty() ? "Imported track" : title)); saveLists(); }
}
