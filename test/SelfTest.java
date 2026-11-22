import com.iipxp.beemacro.VisualAnchor;
import com.iipxp.beemacro.Buffs;
import com.iipxp.beemacro.Catalog;
import com.iipxp.beemacro.Detector;
import com.iipxp.beemacro.StackOcr;
import com.iipxp.beemacro.FieldMap;
import com.iipxp.beemacro.Moves;
import com.iipxp.beemacro.Patterns;
import com.iipxp.beemacro.NavGuard;
import com.iipxp.beemacro.Profile;
import com.iipxp.beemacro.Remap;
import com.iipxp.beemacro.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Desktop tests: vision on real S23 screenshots, Skillet port vs the original .ahk, profile round-trip. */
public class SelfTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) fails++; }

    static Vision.Result see(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        int w = im.getWidth(), h = im.getHeight();
        Vision.Result r = Vision.analyze(im.getRGB(0, 0, w, h, null, 0, w), w, h);
        System.out.println("      " + f + ": " + r.summary());
        return r;
    }

    public static void main(String[] a) throws Exception {
        // ---- Vision on real screenshots
        Vision.Result s3 = see("s3.png"), s4 = see("s4.png"), s5 = see("s5.png"), s6 = see("s6.png"),
                s7 = see("s7.png"), m1 = see("m1.png"), m2 = see("m2.png"), r1 = see("r1.png");
        check(s3.screen == Vision.Screen.GAME && Math.abs(s3.pollen - 38.9) < 2.5 && s3.pollenConf > 0.8, "backpack 38.9% (39,046,220/100,347,000) at spawn");
        check(s3.prompt == Vision.Prompt.NONE, "no prompt at spawn");
        check(s4.prompt == Vision.Prompt.TAP_CLAIM && s4.promptConf > 0.8, "'Tap Claim Hive' recognized as an unowned hive (text position)");
        check(s5.prompt == Vision.Prompt.TAP_BLUE && s5.promptConf > 0.8, "'Tap Make Honey' prompt");
        check(s6.prompt == Vision.Prompt.TAP_RED && s6.promptConf > 0.8, "'Tap Stop Making Honey' = converting");
        check(Math.abs(s6.pollen - 26.6) < 2.5 && s6.pollenConf > 0.8, "backpack 26.6% mid-conversion");
        check(s7.prompt == Vision.Prompt.BANNER_BLUE && s7.pollen < 1 && s7.pollenConf > 0.8, "conversion complete: banner + 0%");
        check(r1.prompt == Vision.Prompt.BANNER_BLUE, "after respawn: at own hive (banner)");
        check(see("menu_with_panel.png").screen == Vision.Screen.MENU, "Roblox menu recognized with the floating panel docked");
        Vision.Result st = see("menu_settings_tab.png");
        check(st.screen == Vision.Screen.MENU && !st.menuPeople, "menu open on the Settings tab recognized (and known not to be People)");
        check(m1.menuPeople, "menu People tab (Respawn button) recognized");
        check(m1.screen == Vision.Screen.MENU && m1.screenConf > 0.8, "Roblox menu open");
        check(m2.screen == Vision.Screen.RESPAWN_DIALOG && m2.screenConf > 0.8, "respawn confirm dialog");
        Vision.Result bw = see("hud_bright_wall.png");
        check(bw.screen == Vision.Screen.GAME && bw.pollen < 1 && bw.pollenConf > 0.8, "HUD read over bright hive wall (translucent pills)");
        check(see("roblox_settings.png").screen != Vision.Screen.GAME, "Roblox settings page is not gameplay");
        check(see("hud_with_panel.png").screen != Vision.Screen.GAME, "expanded panel covering HUD is not trusted as gameplay");
        check(see("shiftlock_off.png").shiftLock == 0, "shift lock OFF recognized (gray ring)");
        check(see("shiftlock_on.png").shiftLock == 1, "shift lock ON recognized (blue ring)");
        check(see("shiftlock_off_hive.png").shiftLock == 0, "shift lock OFF recognized over the yellow hive floor (translucent ring; live failure 2026-11-17)");
        check(see("field_pinetree.png").field == Vision.Field.PINE_TREE && see("field_pinetree2.png").field == Vision.Field.PINE_TREE, "Pine Tree Forest recognized (blue+white flowers, no red)");
        check(see("field_other.png").field == Vision.Field.OTHER_FIELD, "wrong field (red+blue flowers) recognized as not Pine Tree");
        check(see("r1.png").field == Vision.Field.UNKNOWN, "hive area is not a field");
        check(see("cannon_prompt.png").prompt == Vision.Prompt.TAP_CANNON, "'Tap Fire the Red Cannon' recognized (text width) and not mistaken for Make Honey");
        Vision.Result ds = see("dark_scene_cannon.png");
        check(ds.screen == Vision.Screen.GAME && ds.prompt == Vision.Prompt.TAP_CANNON, "dark game scene is gameplay, not the Roblox menu (live false alarm fixed)");
        check(s5.prompt == Vision.Prompt.TAP_BLUE, "Make Honey stays Make Honey (not cannon)");
        int hOn = 0, hOff = 0;
        for (String f : new String[]{"hasteon1.png", "hasteon2.png"}) if (see(f).haste) hOn++;
        for (String f : new String[]{"hasteoff1.png", "hasteoff2.png", "hasteoff3.png", "hasteoff4.png", "s3.png", "r1.png"}) if (!see(f).haste) hOff++;
        check(hOn == 2 && hOff == 6, "Haste buff: found in 2/2 frames that have it, absent in " + hOff + "/6 that don't (scenery + other icons)");
        String stacks = see("hasteon1.png").hasteStacks + "," + see("hasteon2.png").hasteStacks + "," + see("haste_x4.png").hasteStacks + ","
                + see("haste_x5.png").hasteStacks + "," + see("haste_x7.png").hasteStacks + "," + see("hasteoff1.png").hasteStacks + "," + see("haste_sliding.png").hasteStacks;
        check(stacks.equals("1,1,4,5,7,0,-1"), "Haste stack count read from the icon: no text = x1, x4, x5, x7, none = 0, buff bar mid-slide = unreadable (-1)  [" + stacks + "]");
        Vision.Result dc = see("disconnected.png");
        check(dc.screen != Vision.Screen.GAME, "Disconnected/kicked dialog is not treated as gameplay");
        int[] black = new int[2340 * 1080];
        check(Vision.analyze(black, 2340, 1080).screen == Vision.Screen.DARK, "black/loading frame");
        int[] portrait = new int[1080 * 2340];
        check(Vision.analyze(portrait, 1080, 2340).screen == Vision.Screen.UNKNOWN, "portrait (not in game) = UNKNOWN");

        // ---- Skillet port vs original source, step by step
        String src = new String(Files.readAllBytes(new File("test/fixtures/Skillet.ahk").toPath()), "UTF-8");
        List<String> want = new ArrayList<>();
        double dw = 0.2, hw = 0.3, cr = 0.3, dwa = 1.5;
        Matcher m = Pattern.compile("dy_Walk\\(([^,]+),\\s*(\\w+)(?:,\\s*(\\w+))?\\)|nm_cameraRotation\\(\"(\\w+)\", (\\d)\\)|\\{\" Rot(Up|Down) \" (\\d)\\}").matcher(src);
        while (m.find()) {
            if (m.group(1) != null) {
                String e = m.group(1).replace("dwFDC", "" + dw).replace("hwFDC", "" + hw).replace("crFDC", "" + cr)
                        .replace("dwAlign", "" + dwa).replace("hwAlign", "1.5");
                if (e.equals("t")) continue; // the dy_Walk definition line
                double t = 0; for (String part : e.split("\\+")) t += Double.parseDouble(part.trim());
                String k = key(m.group(2)) + (m.group(3) != null ? "+" + key(m.group(3)) : "");
                want.add(k + " " + Math.round(t * 1000) / 1000.0 + "t");
            } else if (m.group(4) != null) want.add("Rot" + (m.group(4).equals("left") ? "Left " : "Right ") + m.group(5));
            else want.add("Rot" + m.group(6) + " " + m.group(7));
        }
        List<String> got = new ArrayList<>();
        for (Moves.Step s : Moves.skillet(Profile.DW_FDC, Profile.HW_FDC, Profile.CR_FDC, Profile.DW_ALIGN, Profile.HW_ALIGN))
            if (s.type != 'P') got.add(s.type == 'W' ? s.toString().replaceAll("([0-9.]+)t$", "") + Math.round(s.tiles * 1000) / 1000.0 + "t" : s.toString());
        boolean same = want.equals(got);
        if (!same) for (int i = 0; i < Math.max(want.size(), got.size()); i++)
            System.out.println("   " + i + " want=" + (i < want.size() ? want.get(i) : "-") + "  got=" + (i < got.size() ? got.get(i) : "-"));
        check(same && want.size() == 52, "Skillet: " + got.size() + " moves identical to Skillet.ahk (order, keys, tiles, rotations)");
        double loopMs = Moves.totalMs(Moves.skillet(.2, .3, .3, 1.5, 1.5), 33.35, 0);
        System.out.printf("      one Skillet loop at movespeed 33.35 = %.1f s (walk + sleeps, excluding camera swipes)%n", loopMs / 1000);
        check(Moves.tileMs(1, 33.35) == 120, "1 tile = 4000/33.35 ms = 120 ms");
        // Recorded routes (the user's own touches, adb getevent → test/recordings/Export.java)
        List<Moves.Step> rt = Moves.toPineTree(), back = Moves.toHive();
        System.out.println("      to field: " + rt + "\n      to hive:  " + back);
        int[][] tf = Moves.recorded("TO_FIELD"), th = Moves.recorded("TO_HIVE");
        int tfEnd = 0, thEnd = 0; for (int[] s : Moves.recorded("TO_FIELD")) tfEnd = Math.max(tfEnd, s[0] + s[s.length - 1]); for (int[] s : Moves.recorded("TO_HIVE")) thEnd = Math.max(thEnd, s[0] + s[s.length - 1]);
        check(Math.abs(tfEnd / 1000.0 - 35.4) < 0.3 && Math.abs(thEnd - 18697) < 50,
              "recorded walking routes (2nd D-pad take): to field " + tfEnd / 1000.0 + " s, to hive " + thEnd / 1000.0 + " s");
        boolean onPad = true, cannonTap = false;
        for (int[][] r : new int[][][]{tf, th}) for (int[] s : r) {
            if (s[1] > 1250 && s[1] < 1550 && s[2] > 150 && s[2] < 320) cannonTap = true;
            boolean pad = s[1] > 120 && s[1] < 720 && s[2] > 380 && s[2] < 980, hotbar = s[2] > 880 && s[1] >= 700 && s[1] < 1650;
            if (!pad && !hotbar) onPad = false;
        }
        long holdsF = rt.stream().filter(s -> s.type == 'H').count(), holdsH = back.stream().filter(s -> s.type == 'H').count();
        System.out.println("      to field as key holds: " + rt);
        StringBuilder spd = new StringBuilder();
        for (Moves.Step s : back) if (s.type == 'H') spd.append(s.tiles).append(' ');
        check(holdsF >= 12 && holdsH == 7 && rt.stream().noneMatch(s -> s.type == 'Y') && rt.stream().allMatch(s -> s.type != 'H' || s.tiles == 1.0)
                && back.get(0).type == 'H' && back.get(0).tiles > 1.0 && back.get(0).tiles < 1.4 && Moves.recordedSpeed("TO_HIVE", 17000, 500) == 1.7,
              "recordings → D-pad key holds; to-field at base speed, return holds carry the Haste read from the recording (x4→x5→x7): " + spd);
        check(onPad && !cannonTap,
              "both routes are pure D-pad walking (+ hotbar sprinkler), no cannon, no camera swipes");
        check(back.get(back.size() - 1).type == 'U' && back.get(back.size() - 1).label.equals("own hive"),
              "to-hive route ends by finding OUR hive prompt");
        float[] mid = Moves.at(new int[]{0, 100, 200, 0, 300, 400, 100}, 50, 1, 1), end = Moves.at(new int[]{0, 100, 200, 0, 300, 400, 100}, 500, 1, 1);
        check(Math.abs(mid[0] - 200) < 0.01 && Math.abs(mid[1] - 300) < 0.01 && end[0] == 300 && end[1] == 400,
              "replay interpolates a finger's position between recorded samples and holds the last one");

        // ---- Reference catalog, buff row, stack OCR, world detector, tracker
        Catalog cat = Catalog.load(new java.io.FileInputStream("assets/catalog.tsv"), new java.io.FileInputStream("assets/catalog.bin"));
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Catalog.Entry e : cat.entries) kinds.merge(e.kind, 1, Integer::sum);
        check(cat.entries.size() >= 130 && cat.entries.stream().allMatch(e -> !e.url.isEmpty() && (e.status.equals("wiki-reference") || e.status.equals("verified-in-game"))),
              "catalog: " + cat.entries.size() + " entries " + kinds + ", every one with its source, wiki icons marked unverified until seen in-game");
        {   // the black-bear Bear Morph icon cut from your buff row is found there again (box 1 px off, as live slots are)
            BufferedImage br = ImageIO.read(new File("test/fixtures/buffrow_bear_black.png"));
            int rw = br.getWidth(), rh = br.getHeight();
            Catalog.Match bm = cat.match(br.getRGB(0, 0, rw, rh, null, 0, rw), rw, rh, 414, 5, 36, 36, true, e -> e.buffRow(), null);
            check(bm.best != null && bm.best.name.equals("Bear Morph") && bm.score - bm.second >= 0.1,
                  String.format("buff row: Bear Morph (black bear) recognised (%.2f vs next %s %.2f)", bm.score, bm.secondName, bm.second));
        }

        // Buff row vs hand-checked ground truth (test/buff_truth.txt; live frames are in test/report/frames)
        int tp = 0, fp = 0, fn = 0, tn = 0;
        for (String line : Files.readAllLines(new File("test/buff_truth.txt").toPath())) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] pr = line.trim().split("[ ]{2,}"), truth = pr[1].split("[|]");
            String file = pr[0].startsWith("live/") ? "test/report/frames/" + pr[0].substring(5) : "test/" + pr[0];
            BufferedImage im = ImageIO.read(new File(file)); int w = im.getWidth(), h = im.getHeight(); int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            Vision.Result vr = Vision.analyze(px, w, h);
            Buffs bb = new Buffs(cat);
            bb.update(px, w, h, vr, null, 0, null);                       // same frame twice = "held still" (offline only)
            List<Buffs.Effect> seenFx = bb.update(px, w, h, vr, null, 200, null);
            for (int k = 0; k < truth.length; k++) {
                String t = truth[k]; if (t.equals("?")) continue;
                String g = null; for (Buffs.Effect e : seenFx) if (e.slot == k) g = e.name;
                boolean isBuff = !(t.equals("X") || t.equals("-"));
                if (g == null) { if (isBuff) fn++; else tn++; }
                else if (g.equals(t) || g.startsWith(t + " /")) tp++;
                else { fp++; if (isBuff) fn++; System.out.println("      wrong: " + pr[0] + " slot " + k + " truth=" + t + " got=" + g); }
            }
        }
        double prec = tp / (double) Math.max(1, tp + fp), rec = tp / (double) Math.max(1, tp + fn);
        System.out.printf("      buff row (6 frames, single-frame, no motion check): TP %d FP %d FN %d TN %d precision %.2f recall %.2f%n", tp, fp, fn, tn, prec, rec);
        check(prec >= 0.60 && rec >= 0.45, String.format("buff row identification measured: precision %.2f, recall %.2f (regression floor 0.60 / 0.45)", prec, rec));

        // Stack text OCR on frames NOT used to build the glyph templates
        String[][] held = {{"test/report/frames/f1.png", "0", "x31"}, {"test/report/frames/f1.png", "1", "x1000"}, {"test/report/frames/f1.png", "3", "x289"},
                {"test/report/frames/f1.png", "17", "x10"}, {"test/report/frames/f1.png", "20", "x2"}, {"test/report/frames/v2.png", "0", "x31"},
                {"test/report/frames/v2.png", "1", "x1000"}, {"test/report/frames/v2.png", "3", "x289"}, {"test/report/frames/v2.png", "17", "x36"}, {"test/report/frames/v2.png", "19", "11%"}};
        int ocrOk = 0, ocrWrong = 0;
        for (String[] hcase : held) {
            BufferedImage im = ImageIO.read(new File(hcase[0])); int w = im.getWidth(), h = im.getHeight(); int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            double s = w / 2340.0; int k = Integer.parseInt(hcase[1]);
            String g = StackOcr.read(px, w, h, (100 + 60 * k) * s, 179 * h / 1080.0, 58 * s);
            if (hcase[2].equals(g)) ocrOk++; else if (g != null) ocrWrong++;
        }
        check(ocrOk >= 5 && ocrWrong <= 1, "stack text on held-out frames: " + ocrOk + "/10 exact, " + ocrWrong + " wrong (rest unread = reported as unknown, not guessed)");

        // World detector: no names without evidence; verified example recognised; Ignore; duplicates; ignore rects
        BufferedImage fp2 = ImageIO.read(new File("test/fixtures/field_pinetree2.png"));
        int fw = fp2.getWidth(), fh = fp2.getHeight(); int[] fpx = fp2.getRGB(0, 0, fw, fh, null, 0, fw);
        Detector.Library lib = new Detector.Library();
        List<Detector.Det> dets = Detector.detect(fpx, fw, fh, cat, lib, 0.8, null);
        long namedNoLabels = dets.stream().filter(d -> !d.label.equals("Unknown")).count();
        boolean noHud = dets.stream().allMatch(d -> d.y0 >= 0.27 * fh - 8 && !(d.cx() < 0.31 * fw));
        System.out.println("      wiki-only names in this frame: " + dets.stream().filter(d -> !d.label.equals("Unknown")).map(Detector.Det::text).collect(java.util.stream.Collectors.toList()));
        check(dets.size() >= 5 && noHud, "detector: " + dets.size() + " objects in a Pine Tree frame, none in the HUD; " + namedNoLabels + " named from wiki images alone");
        Detector.Det a0 = dets.get(0);
        lib.add("Test Token", Detector.Cat.TOKEN, false, a0.tpl);
        Detector.Det again = Detector.detect(fpx, fw, fh, cat, lib, 0.8, null).stream().filter(d -> d.x0 == a0.x0 && d.y0 == a0.y0).findFirst().orElse(null);
        check(again != null && again.label.equals("Test Token") && again.conf > 0.95, "verified in-game example recognised again (" + (again == null ? "-" : again.text()) + ")");
        Detector.Library ig = new Detector.Library(); ig.add("Ignore", Detector.Cat.IGNORE, false, a0.tpl);
        check(Detector.detect(fpx, fw, fh, cat, ig, 0.8, null).stream().noneMatch(d -> d.x0 == a0.x0 && d.y0 == a0.y0), "marked false detection (Ignore) -> no longer detected");
        List<int[]> ign = new java.util.ArrayList<>(); ign.add(new int[]{a0.x0 - 8, a0.y0 - 8, a0.x1 + 8, a0.y1 + 8});
        check(Detector.detect(fpx, fw, fh, cat, null, 0.8, ign).stream().noneMatch(d -> d.x0 == a0.x0 && d.y0 == a0.y0), "ignore rects (our own drawn boxes) are not detected");
        Detector.Det dA = new Detector.Det(); dA.x0 = 100; dA.y0 = 100; dA.x1 = 160; dA.y1 = 160; dA.conf = 0.9;
        Detector.Det dB = new Detector.Det(); dB.x0 = 110; dB.y0 = 105; dB.x1 = 165; dB.y1 = 158; dB.conf = 0.8;
        check(Detector.suppress(new java.util.ArrayList<>(java.util.Arrays.asList(dA, dB))).size() == 1, "one token found twice (overlapping boxes) -> one detection");
        File lf = Files.createTempFile("labels", ".txt").toFile();
        lib.save(lf); lib.save(lf);
        Detector.Library lib2 = Detector.Library.load(lf);
        check(lib2.ex.size() == 1 && lib2.count("Test Token") == 1 && new File(lf.getPath() + ".bak").exists(), "verified examples save/reload, previous file kept as .bak");
        // Temporal confirmation + tracking
        Detector.Tracker tr = new Detector.Tracker();
        int[][] pos = {{500, 500}, {520, 505}, {540, 510}, {560, 515}};
        String[] names = {"Honey Token", "Honey Token", "Unknown", "Honey Token"};
        String confirmedAt2 = null;
        for (int i = 0; i < pos.length; i++) {
            Detector.Det d = new Detector.Det(); d.x0 = pos[i][0]; d.y0 = pos[i][1]; d.x1 = d.x0 + 60; d.y1 = d.y0 + 60; d.label = names[i]; d.conf = 0.9;
            tr.update(java.util.Collections.singletonList(d), i * 100, null);
            if (i == 1) confirmedAt2 = tr.tracks.get(0).confirmed();
        }
        Detector.Tracker.Track tt = tr.tracks.get(0);
        check(tr.tracks.size() == 1 && confirmedAt2 == null && "Honey Token".equals(tt.confirmed()) && tt.vx > 0,
              "one ID across 4 frames; name confirmed only after 3 of 4 frames agree; velocity estimated");
        // per-object classification: a copied (cached) answer must not count as a new vote
        Detector.Tracker tc = new Detector.Tracker();
        for (int i = 0; i < 4; i++) {
            Detector.Det d = new Detector.Det(); d.x0 = 300 + i * 5; d.y0 = 300; d.x1 = d.x0 + 60; d.y1 = 360;
            final int fi = i;
            tc.update(java.util.Collections.singletonList(d), i * 100, null, (t, x) -> {
                if (fi == 0) { x.label = "Honey Token"; x.conf = 0.9; return true; }          // classified once
                x.label = t.d.label; x.conf = t.d.conf; return false;                         // then carried over
            });
        }
        check(tc.tracks.size() == 1 && tc.tracks.get(0).confirmed() == null && "Honey Token".equals(tc.tracks.get(0).d.label),
              "carried-over classifications keep the box named for the panel but never confirm a name on their own");
        tr.update(new java.util.ArrayList<>(), 700, null);
        check(tr.tracks.size() == 1, "box kept during the grace period");
        tr.update(new java.util.ArrayList<>(), 1000, (x, y) -> Math.abs(x - 590) < 50);
        check(tr.tracks.isEmpty() && tr.history.peek().how.equals("vanished at the player") && tr.history.peek().label.equals("Honey Token"),
              "box removed after grace; history: vanished at the player (counted as collected only if we also reached it)");
        // Natro DetectMovespeed formula: the flat bonuses are relative to the base before Hasty Guard / Gifted Hasty
        check(Math.abs(Moves.speedFactor(33.35, false, false, 1, false, false, false, false, false) - 1) < 1e-9
                && Math.abs(Moves.speedFactor(33.35, false, false, 1.5, false, false, false, false, false) - 1.5) < 1e-9
                && Math.abs(Moves.speedFactor(30, false, false, 1, true, false, false, false, false) - 40 / 30.0) < 1e-9
                && Math.abs(Moves.speedFactor(30 * 1.1 * 1.15, true, true, 1, false, true, false, false, false) - 34 / 30.0) < 1e-9
                && Math.abs(Moves.speedFactor(30, false, false, 1.2, false, false, true, true, true) - 1.2 * 2 * 1.2 * 1.25) < 1e-9,
                "speed factor: Haste, Coconut +10, Bear +4 before Hasty Guard/Gifted, Haste+ ×2, Oil ×1.2, Smoothie ×1.25");
        {   // no screenshot here has Coconut Haste: a 2nd Haste icon must never be seen on any of them
            int coco = 0, n = 0; StringBuilder which = new StringBuilder();
            for (File f : new File("test/fixtures").listFiles()) {
                if (!f.getName().endsWith(".png")) continue;
                n++;
                Vision.Result r = f.getName().startsWith("tab_") ? tab(f.getName()) : see(f.getName());
                if (r.coconutHaste) { coco++; which.append(f.getName()).append(' '); }
            }
            check(coco == 0, "Coconut Haste not seen on any of " + n + " screenshots without it " + which);
        }
        Vision.Result hv = see("haste_x5.png");
        check(hv.haste && hv.hasteSlot >= 0 && hv.hasteConf >= 0.6 && hv.hasteConf <= 1, "Haste box has a real match score (" + String.format("%.2f", hv.hasteConf) + ") and slot " + hv.hasteSlot);

        // ---- Galaxy Tab S9 FE (2304x1440): frames rebuilt into the S23 layout by Remap
        Vision.Result tg = tab("tab_hive_prompt.png"), tm = tab("tab_menu.png"), td = tab("tab_respawn_dialog.png");
        check(tg.screen == Vision.Screen.GAME && Math.abs(tg.pollen - 16.26) < 1.5 && tg.pollenConf > 0.8, "tablet: HUD + backpack " + String.format("%.1f", tg.pollen) + "% (16,320,218/100,347,000 = 16.3%)");
        check(tg.prompt == Vision.Prompt.TAP_BLUE && tg.promptConf > 0.8, "tablet: Tap Make Honey prompt");
        check(tg.shiftLock == 0, "tablet: shift lock OFF read from the remapped button");
        check(tm.screen == Vision.Screen.MENU && tm.menuPeople, "tablet: Roblox dropdown menu (with Respawn) recognised");
        check(tab("tab_menu_keyboard.png").screen == Vision.Screen.MENU, "tablet: the keyboard Esc menu (Leave / Respawn / Resume) recognised");
        check(tab("tab_respawn_keyboard.png").screen == Vision.Screen.RESPAWN_DIALOG, "tablet: respawn confirmation reached from the keyboard menu");
        int falseMenus = 0, gameFrames = 0;
        for (File f : new File("test/fixtures").listFiles((d, n) -> n.startsWith("tab_") && n.endsWith(".png") && !n.contains("menu") && !n.contains("respawn"))) {
            gameFrames++; if (tab(f.getName()).screen == Vision.Screen.MENU) { falseMenus++; System.out.println("      false menu: " + f.getName()); }
        }
        check(falseMenus == 0, "tablet: none of " + gameFrames + " gameplay frames reads as a menu");
        check(td.screen == Vision.Screen.RESPAWN_DIALOG, "tablet: respawn confirmation dialog recognised");
        BufferedImage s3i = ImageIO.read(new File("test/fixtures/s3.png")); int[] s3px = s3i.getRGB(0, 0, s3i.getWidth(), s3i.getHeight(), null, 0, s3i.getWidth());
        check(Remap.apply(s3px, s3i.getWidth(), s3i.getHeight()).px == s3px, "S23 frames pass through Remap untouched");
        int[] tback = Remap.toScreen(Remap.apply(new int[2304 * 1440], 2304, 1440), new int[]{1170, 540, 1170, 540}, false);
        check(Math.abs(tback[0] - 1152) <= 1 && Math.abs(tback[1] - 720) <= 1, "tablet: remapped world centre maps back to the screen centre");

        check(Moves.hotbarSlot(770) == 1 && Moves.hotbarSlot(1030) == 3 && Moves.hotbarSlot(1580) == 7, "recorded hotbar taps map to number keys 1..7 for keyboard mode");

        check(tab("tab_pinetree_zoomed.png").field == Vision.Field.PINE_TREE && tab("tab_other_field.png").field == Vision.Field.OTHER_FIELD, "tablet (zoomed-out keyboard route): Pine Tree recognised, the field before it is not");

        // ---- Natro-style keyboard trip (tablet)
        double ramp = 0, home = 0;
        for (Moves.Step s : Moves.natroToRamp(3, 33.35)) if (s.type == 'H') ramp += s.count;
        for (Moves.Step s : Moves.natroPineHome(3, 33.35)) if (s.type == 'H') home += s.count;
        check(Math.abs(ramp - Moves.tileMs(5 + 23.6, 33.35)) < 3 && Math.abs(home - (Moves.tileMs(31 + 75 + 20 + 3 + 18 + 6 + 10 + 2 + 6 + 108 + 2.7, 33.35) - 200)) < 40,
              "Natro paths ported tile for tile: hive→ramp " + Math.round(ramp) + " ms, Pine Tree→hive pad 3 " + Math.round(home) + " ms walking at movespeed 33.35");

        int[] spr = Moves.natroSprinklerMove("Upper Left", 7, 33.35);
        check(spr[0] == 945 && spr[1] == -661, "Natro sprinkler spot for Pine Tree, Upper Left 7 at movespeed 33.35: forward " + spr[0] + " ms, left " + (-spr[1]) + " ms (2500/1750 × 0.7 × 0.54)");

        BufferedImage dk = ImageIO.read(new File("test/fixtures/tab_dark_game.png"));
        Remap.Out dko = Remap.apply(dk.getRGB(0, 0, dk.getWidth(), dk.getHeight(), null, 0, dk.getWidth()), dk.getWidth(), dk.getHeight());
        check(!dko.menuOpen && Vision.analyze(dko).screen != Vision.Screen.MENU, "tablet: a dark game view (camera in a wall) is not the Roblox menu (menu button must show X)");

        // ---- Screen guard on real frames
        check(guard("tab_pinetree_zoomed.png").state == NavGuard.State.IN_FIELD && guard("field_pinetree.png").state == NavGuard.State.IN_FIELD && guard("field_pinetree4.png").state == NavGuard.State.IN_FIELD, "guard: Pine Tree frames (tablet and S23) read as inside the field");
        check(guard("tab_other_field.png").state != NavGuard.State.IN_FIELD && guard("field_other.png").state != NavGuard.State.IN_FIELD, "guard: the red-flower field is not Pine Tree");
        check(guard("tab_hive_prompt.png").state == NavGuard.State.LOST && guard("s3.png").state == NavGuard.State.LOST, "guard: the hive area (white floor) has no Pine Tree flowers");
        NavGuard.View ed = guard("tab_field_edge.png");
        check(ed.state == NavGuard.State.NEAR_EDGE && ed.toward == NavGuard.Dir.LEFT, "guard: at the field's edge it points back to the flowers (" + ed + ")");
        int[] g1 = new int[NavGuard.GW * NavGuard.GH], g2 = g1.clone();
        for (int i = 0; i < g1.length; i++) { g1[i] = i % 200; g2[i] = (i + 37) % 200; }
        int[] g3 = g1.clone(); for (int i = 0; i < 40; i++) g3[i] += 120;   // bees crossing part of the view
        check(NavGuard.motion(g1, g1.clone()) == 0 && NavGuard.motion(g1, g3) == 0 && NavGuard.motion(g1, g2) > 6, "guard: motion ignores small moving things and sees the world shift");
        check(NavGuard.bagEmptied(60, 0.2) && !NavGuard.bagEmptied(60, 59) && !NavGuard.bagEmptied(1, 0), "guard: a sudden empty bag is detected, normal readings are not");

        // ---- Facing the hive, Shift Lock and the cannon prompt on real tablet frames
        check(facing("tab_facing_hive.png") >= 0.3 && tab("tab_facing_hive.png").shiftLock == 1, "tablet: facing the hive with Shift Lock on");
        check(facing("tab_turned_45.png") < 0.15 && facing("tab_turned_180.png") < 0.15, "tablet: turned away from the hive is not read as facing it");
        check(tab("tab_cannon_keyboard.png").prompt == Vision.Prompt.TAP_CANNON, "tablet: Fire the Red Cannon prompt");

        check(Moves.natroSpawnToHive(3, 33.35).size() == 2 && Moves.natroSpawnToHive(6, 33.35).size() == 3, "route: spawn-to-hive walk follows Natro's table for the slot");
        NavGuard.View fv = guard("tab_pine_high.png");
        check(fv.best() >= NavGuard.IN && fv.red < NavGuard.RED, "guard: raised camera at Pine Tree's corner sees its flowers (" + fv + ")");
        check(facing("tab_spawn_view.png") >= 0.18 && facing("tab_spawn_view.png") < 0.35, "tablet: the central spawn reads as a distant view of the hives");
        fieldMapTests();
        check(!NavGuard.bagEmptied(24, 12) && NavGuard.bagEmptied(24, 0.2), "guard: a capacity change is not a death, an empty bag is");
        double base = 30; base = NavGuard.bagBase(base, 20);
        check(base == 20 && NavGuard.bagRose(base, 20.5) && !NavGuard.bagRose(base, 20.1), "guard: pollen rise still seen after a capacity change lowers the bag %");
        // ---- farming pattern presets (Natro Macro's pattern files, read by Patterns)
        { StringBuilder bad = new StringBuilder(); int ok = 0;
          for (String n : Patterns.NAMES) {
              try {
                  String psrc = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/patterns/" + n + ".ahk")), "UTF-8");
                  java.util.List<Moves.Step> pst = Patterns.steps(psrc, 1, 3, false, false, false, 33.35);
                  double x = 0, y = 0;
                  for (Moves.Step s : pst) if (s.type == 'W') { double dx = 0, dy = 0; for (Moves.Key k : s.keys) { if (k == Moves.Key.W) dy++; if (k == Moves.Key.S) dy--; if (k == Moves.Key.A) dx--; if (k == Moves.Key.D) dx++; } double nn = Math.hypot(dx, dy); x += s.tiles * dx / nn; y += s.tiles * dy / nn; }
                  boolean closes = java.util.Arrays.asList("Snake", "Lines", "Squares", "Diamonds", "Fork").contains(n);
                  if (pst.isEmpty() || (closes && Math.hypot(x, y) > 0.01)) bad.append(n).append(' '); else ok++;
              } catch (Exception e) { bad.append(n).append("(").append(e.getMessage()).append(") "); }
          }
          check(bad.length() == 0, "patterns: all " + ok + " Natro patterns read; Snake/Lines/Squares/Diamonds/Fork end where they start " + bad); }
        { String psrc = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/patterns/CornerXSnake.ahk")), "UTF-8");
          java.util.List<Moves.Step> pst = Patterns.steps(psrc, 1.5, 3, false, false, true, 33.35);
          check(pst.get(0).toString().equals("D 6.0t") && pst.get(1).toString().equals("W 3.0t") && pst.get(2).toString().equals("A 12.0t") && pst.get(5).keys.length == 2,
                "patterns: CornerXSnake size L, left/right inverted = D 6, W 3, A 12 … then a diagonal (" + pst.get(0) + ", " + pst.get(1) + ", " + pst.get(2) + ")"); }
        { // pattern reach, for lining up in a corner: an L of 3 forward then 2 right reaches right 2 / forward 3; turned 180° it is mirrored
          java.util.List<Moves.Step> l = Patterns.steps("send \"{\" FwdKey \" down}\"\nWalk(3)\nsend \"{\" FwdKey \" up}{\" RightKey \" down}\"\nWalk(2)\nsend \"{\" RightKey \" up}\"", 1, 1, false, false, false, 33.35);
          double[] e = Patterns.extent(l);
          java.util.List<Moves.Step> t = new java.util.ArrayList<>(); t.add(new Moves.Step('R', null, 0, 4, null)); t.addAll(l);
          double[] et = Patterns.extent(t);
          java.util.List<Moves.Step> d = new java.util.ArrayList<>(); d.add(new Moves.Step('R', null, 0, 1, null)); d.addAll(l);
          check(e[0] == 0 && e[1] == 2 && e[2] == 0 && e[3] == 3 && Math.abs(et[0] + 2) < 1e-9 && Math.abs(et[2] + 3) < 1e-9 && et[1] == 0 && et[3] == 0 && Patterns.extent(d) == null,
                "patterns: reach from the start (corner line-up) " + java.util.Arrays.toString(e) + ", turned 180° " + java.util.Arrays.toString(et) + ", 45° = none"); }
        { // Bee Swarm's red "You must be standing in a field to build a Sprinkler" banner: found on that frame only
          int refused = 0, worst = 0; String worstF = "";
          for (File f : new File("test/fixtures").listFiles()) {
              if (!f.getName().startsWith("tab_") || !f.getName().endsWith(".png")) continue;
              BufferedImage im = ImageIO.read(f); int iw = im.getWidth(), ih = im.getHeight();
              int rows = Vision.redBannerRows(im.getRGB(0, 0, iw, ih, null, 0, iw), iw, ih);
              if (f.getName().equals("tab_sprinkler_refused.png")) refused = rows;
              else if (rows > worst) { worst = rows; worstF = f.getName(); }
          }
          check(refused >= 4 && worst < 4, "sprinkler refused: red banner rows " + refused + " on the refusal frame, at most " + worst + " elsewhere " + worstF + " (roses next to the field don't count)"); }
        { // drift by sight: a known slide of the world is measured back, with the HUD and the character left in place
          StringBuilder res = new StringBuilder(); boolean ok = true;
          for (String fx : new String[]{"tab_field_middle.png", "tab_under_tree.png", "tab_outside_grass.png"}) {
              BufferedImage fm = ImageIO.read(new File("test/fixtures/" + fx));
              Remap.Out o = Remap.apply(fm.getRGB(0, 0, fm.getWidth(), fm.getHeight(), null, 0, fm.getWidth()), fm.getWidth(), fm.getHeight());
              int W = o.w, H = o.h; double plx = 0.486, ply = 0.73;
              VisualAnchor anc = new VisualAnchor(o.px, W, H, plx, ply);
              double[] same0 = anc.shiftTo(new VisualAnchor(o.px, W, H, plx, ply), 0.30);
              ok &= same0[0] == 0 && same0[1] == 0 && same0[2] > 0.99;
              for (int[] d : new int[][]{{48, -32}, {-96, 40}, {16, 88}}) {
                  int[] b = slide(o.px, W, H, d[0], d[1], plx, ply, d[0] == 16 ? 0.7 : 1.0, d[0] == -96);
                  double[] s = anc.shiftTo(new VisualAnchor(b, W, H, plx, ply), 0.30);
                  boolean good = Math.abs(s[0] - d[0]) <= 8 && Math.abs(s[1] - d[1]) <= 8 && s[2] > 0.6;
                  ok &= good;
                  res.append(String.format(java.util.Locale.US, " %s %+d/%+d→%+.0f/%+.0f (%.2f)%s", fx.replace("tab_", "").replace(".png", ""), d[0], d[1], s[0], s[1], s[2], good ? "" : "!"));
              }
          }
          check(ok, "drift by sight: slides measured back within 8 px (HUD and character fixed, one dimmed, one with a bee swarm):" + res); }
        { // pollen coming in, read from the counter's digits: same frame = no change, the world moving = no change,
          // a different pollen number = change (tablet frames)
          BufferedImage pa = ImageIO.read(new File("test/fixtures/tab_under_tree.png")), pb = ImageIO.read(new File("test/fixtures/tab_sprinkler_refused.png"));
          int aw = pa.getWidth(), ah = pa.getHeight();
          int[] apx = pa.getRGB(0, 0, aw, ah, null, 0, aw), bpx = pb.getRGB(0, 0, pb.getWidth(), pb.getHeight(), null, 0, pb.getWidth());
          boolean[] sa = Vision.pollenText(apx, aw, ah);
          int unchanged = Vision.textDiff(sa, Vision.pollenText(apx.clone(), aw, ah));
          int world = Vision.textDiff(sa, Vision.pollenText(slide(apx, aw, ah, 40, 30, 0.486, 0.73, 1.0, true), aw, ah));
          int other = Vision.textDiff(sa, Vision.pollenText(bpx, pb.getWidth(), pb.getHeight()));
          int thr = Math.max(10, sa.length / 400);
          check(unchanged == 0 && world <= thr && other > thr, "pollen counter: unchanged " + unchanged + ", world moved " + world + ", other number " + other + " px differ (threshold " + thr + ")"); }
        // ---- sprinkler: learned from a before/after pair, then found again elsewhere (synthetic sprinkler on a real field frame)
        { BufferedImage fm = ImageIO.read(new File("test/fixtures/tab_field_middle.png"));
          Remap.Out o = Remap.apply(fm.getRGB(0, 0, fm.getWidth(), fm.getHeight(), null, 0, fm.getWidth()), fm.getWidth(), fm.getHeight());
          int W = Remap.W, H = Remap.H;
          int[] before = o.px.clone(), after = o.px.clone(), moved = o.px.clone();
          drawSprinkler(after, W, 1170, 430); drawSprinkler(moved, W, 860, 560);
          com.iipxp.beemacro.SprinklerFinder sf = com.iipxp.beemacro.SprinklerFinder.learn(before, after, W, H, 585, 194, 1755, 760);
          double[] at = sf == null ? null : sf.locate(moved, W, H, 172, H - 60, 0.8), none = sf == null ? null : sf.locate(before, W, H, 172, H - 60, 0.8);
          System.out.println("      sprinkler learned " + (sf == null ? "-" : sf.learnedW + "x" + sf.learnedH + " at " + sf.learnedX + "," + sf.learnedY)
                  + ", found at " + (at == null ? "-" : String.format(java.util.Locale.US, "%.0f,%.0f score %.2f", at[0], at[1], at[2])) + ", on the empty field: " + (none == null ? "none" : String.format(java.util.Locale.US, "%.0f,%.0f %.2f", none[0], none[1], none[2])));
          StringBuilder fps = new StringBuilder();   // and no false sprinkler on any other real frame
          for (File fx : new File("test/fixtures").listFiles((dd, nm) -> nm.startsWith("tab_") && nm.endsWith(".png"))) {
              BufferedImage im2 = ImageIO.read(fx);
              Remap.Out o2 = Remap.apply(im2.getRGB(0, 0, im2.getWidth(), im2.getHeight(), null, 0, im2.getWidth()), im2.getWidth(), im2.getHeight());
              double[] sfp = o2.tablet && sf != null ? sf.locate(o2.px, W, H, 172, H - 60, 0.8) : null;
              if (sfp != null) fps.append(fx.getName()).append(String.format(java.util.Locale.US, "=%.2f ", sfp[2]));
          }
          check(fps.length() == 0, "sprinkler: no false sprinkler on any of the real tablet frames " + fps);
          check(sf != null && Math.abs(sf.learnedX + sf.learnedW / 2.0 - 1170) < 12 && at != null && Math.hypot(at[0] - 860, at[1] - 560) < 16 && none == null,
                "sprinkler: learned from before/after frames, found again after moving, nothing found on the empty field"); }
        // ---- S23 phone with the emulated keyboard (live frames 2026-11-21): same menu as touch mode, read as before
        { Vision.Result km = see("s23_kb_menu.png"), kd = see("s23_kb_respawn_dialog.png"), kh = see("s23_kb_hive.png");
          check(km.screen == Vision.Screen.MENU && kd.screen == Vision.Screen.RESPAWN_DIALOG && kh.screen == Vision.Screen.GAME && kh.prompt == Vision.Prompt.BANNER_BLUE,
                "phone keyboard mode: Esc menu, respawn dialog and hive banner recognised (" + km.screen + ", " + kd.screen + ", " + kh.screen + "/" + kh.prompt + ")"); }
        // ---- phone perspective: the world readers look through the tablet-shaped window, and skip our own panel
        { java.util.List<int[]> panel = java.util.Collections.singletonList(new int[]{1670, 170, 2340, 830});   // the overlay on the S23 frames
          double[] f = new double[2]; String[] nm = {"s23_kb_turn0.png", "s23_kb_turn1.png"};
          for (int i = 0; i < 2; i++) { BufferedImage im = ImageIO.read(new File("test/fixtures/" + nm[i])); Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
              f[i] = NavGuard.hiveFacing(o.px, o.w, o.h, o.worldX0, o.w - o.worldX0, panel); }
          BufferedImage tw = ImageIO.read(new File("test/fixtures/s23_kb_turn0.png")); Remap.Out to = Remap.apply(tw.getRGB(0, 0, tw.getWidth(), tw.getHeight(), null, 0, tw.getWidth()), tw.getWidth(), tw.getHeight());
          check(!to.tablet && Math.abs(to.worldX0 - 306) < 1 && f[0] >= 0.18 && f[1] < 0.05,
                String.format(java.util.Locale.US, "phone: world window 306..2034 like the tablet; hives seen at the right angle %.2f, not past it %.2f", f[0], f[1])); }
        // ---- phone camera reset by eye: far out, the hive row is centred only in the right direction; zoomed in there = pad view
        { java.util.List<int[]> pnl = java.util.Collections.singletonList(new int[]{1680, 237, 2340, 850});
          double[] v = new double[3]; String[] nm = {"s23_far_turn0.png", "s23_far_turn1.png", "s23_zoomed_pad.png"};
          for (int i = 0; i < 3; i++) { BufferedImage im = ImageIO.read(new File("test/fixtures/" + nm[i])); Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
              v[i] = i < 2 ? NavGuard.goldRow(o.px, o.w, o.h, o.worldX0, o.w - o.worldX0, pnl) : NavGuard.hiveFacing(o.px, o.w, o.h, o.worldX0, o.w - o.worldX0, pnl); }
          check(v[0] > 0.2 && v[1] < 0.12 && v[2] >= 0.35, String.format(java.util.Locale.US,
                "phone camera: hive row %.2f facing the hives vs %.2f side-on; zoomed in there the pad view reads %.2f (≥ 0.35)", v[0], v[1], v[2])); }
        // ---- sprinkler on a phone frame (S23 in Pepper, not remapped): same learn / find, box sized from the height
        { BufferedImage pf = ImageIO.read(new File("test/fixtures/s23_pepper_field.png"));
          int pw = pf.getWidth(), ph = pf.getHeight(); int[] pbase = pf.getRGB(0, 0, pw, ph, null, 0, pw);
          int[] bef = pbase.clone(), aft = pbase.clone(), mov = pbase.clone();
          drawSprinkler(aft, pw, 1100, 420); drawSprinkler(mov, pw, 800, 600);
          int half = (int) (0.54 * ph);
          com.iipxp.beemacro.SprinklerFinder ps = com.iipxp.beemacro.SprinklerFinder.learn(bef, aft, pw, ph, pw / 2 - half, (int) (0.18 * ph), pw / 2 + half, 760);
          double[] pa = ps == null ? null : ps.locate(mov, pw, ph, (int) (0.16 * ph), ph - 60, 0.8), pn = ps == null ? null : ps.locate(bef, pw, ph, (int) (0.16 * ph), ph - 60, 0.8);
          check(ps != null && pa != null && Math.hypot(pa[0] - 800, pa[1] - 600) < 16 && pn == null,
                "phone sprinkler: learned on the S23 Pepper frame, found again after moving" + (pa == null ? "" : String.format(java.util.Locale.US, " (%.0f,%.0f score %.2f)", pa[0], pa[1], pa[2])) + ", nothing on the empty field"); }
        // ---- walking routes for every field (Natro's gtf-/wf- files, walk branch)
        { StringBuilder bad = new StringBuilder(); int n = 0;
          for (String[] f : com.iipxp.beemacro.Routes.FIELDS) for (String kind : new String[]{"gtf-", "wf-"}) for (int slot = 1; slot <= 6; slot++) {
              try {
                  String rs = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/paths/" + kind + f[0] + ".ahk")), "UTF-8");
                  if (com.iipxp.beemacro.Routes.steps(rs, slot, 33.35).isEmpty()) bad.append(kind).append(f[0]).append(" empty ");
                  n++;
              } catch (Exception e) { bad.append(kind).append(f[0]).append(" slot ").append(slot).append(": ").append(e.getMessage()).append(' '); }
          }
          check(bad.length() == 0, "routes: all " + n + " field routes (17 fields × to/from × 6 hive slots) read " + bad);
          String gp = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/paths/gtf-pinetree.ahk")), "UTF-8");
          java.util.List<Moves.Step> ours = new java.util.ArrayList<>(Moves.natroToRamp(2, 33.35)); ours.addAll(Moves.natroToPine(33.35));
          check(String.valueOf(com.iipxp.beemacro.Routes.steps(gp, 2, 33.35)).equals(String.valueOf(ours)), "routes: Natro's gtf-pinetree read by Routes = our live-tested Pine Tree route, step for step"); }
        // ---- other screens: UI scale measured from the honey counter, then every HUD piece placed by its anchor
        { BufferedImage hp = ImageIO.read(new File("test/fixtures/tab_hive_prompt.png"));
          double u1 = Remap.calibrate(hp.getRGB(0, 0, 2304, 1440, null, 0, 2304), 2304, 1440);
          check(Math.abs(u1 - 1) < 0.005, "screen: the Tab S9 FE measures as UI scale 1.0 (" + String.format("%.4f", u1) + ")"); }
        { int n = 0; StringBuilder bad = new StringBuilder();   // every S9 FE frame with a readable counter must measure 1.0
          for (File fx : new File("test/fixtures").listFiles((dd, nm) -> nm.startsWith("tab_") && nm.endsWith(".png"))) {
              BufferedImage im = ImageIO.read(fx); int[] cpx = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
              double uu = Remap.calibrate(cpx, im.getWidth(), im.getHeight());
              if (uu == 0) continue; n++;
              if (Math.abs(uu - 1) > 0.01) bad.append(fx.getName()).append('=').append(String.format("%.3f", uu)).append(' ');
          }
          check(n >= 20 && bad.length() == 0, "screen: all " + n + " S9 FE frames calibrate to 1.0, 11 and 12 digit honey (live 91.6% failure) " + bad); }
        String sc1 = screenCheck("tab_hive_prompt.png", 0.8, 1152, 16.26);
        check(sc1.startsWith("ok") && sc1.contains("TAP_BLUE") && sc1.endsWith("69237825773"), "screen: 1843x1152 at UI scale 0.8 calibrated, pollen + prompt + honey read: " + sc1);
        String sc2 = screenCheck("tab_hive_prompt.png", 1.0, 1296, 16.26);
        check(sc2.startsWith("ok") && sc2.contains("TAP_BLUE"), "screen: 16:9 2304x1296 (height guess would be 0.9) calibrated to 1.0: " + sc2);
        String sc3 = screenCheck("tab_field_wrong.png", 1.15, 1656, -99);
        check(sc3.contains("scale 1.1") && sc3.endsWith("374721818022"), "screen: 2650x1656 at UI scale 1.15, honey read: " + sc3);
        check(honeyOf("tab_haste_x4.png") == 74270659791L && honeyOf("tab_field_wrong.png") == 374721818022L, "honey counter read exactly (74,270,659,791 and 374,721,818,022)");
        List<Moves.Step> toPine = Moves.natroToPine(33.35);
        check(toPine.size() == 11 && toPine.get(1).type == 'R' && toPine.get(1).count == 4, "route: walk to Pine Tree keeps Natro's steps and its 180° turn");

        Vision.Result h4 = tab("tab_haste_x4.png");
        check(h4.haste && (h4.hasteStacks == 4 || h4.hasteStacks == -1), "tablet: Haste x4 is never taken for a lower count (read " + h4.hasteStacks + "; -1 = wait it out)");
        Vision.Result h1 = tab("tab_haste_x1.png"), h3 = tab("tab_haste_x3.png");
        check(h1.haste && h1.hasteStacks == 1 && h3.haste && (h3.hasteStacks == 3 || h3.hasteStacks == -1) && !tab("tab_facing_hive.png").haste, "tablet: Haste found with its stack count (" + h1.hasteStacks + ", " + h3.hasteStacks + "), absent when not active");

        // ---- Profile round trip
        File dir = Files.createTempDirectory("bm").toFile();
        Profile p = Profile.load(dir);
        check(Profile.file(dir).exists() && p.backpackThreshold == 90 && p.hiveSlot == 3, "profile created with defaults");
        p.backpackThreshold = 85; p.hiveConfirmed = true; p.pxPer45 = 340; p.save(dir);
        Profile q = Profile.load(dir);
        check(q.backpackThreshold == 85 && q.hiveConfirmed && q.pxPer45 == 340, "profile persists across reload");
        check(Profile.DW_FDC == 0.2 && Profile.HW_FDC == 0.3 && Profile.CR_FDC == 0.3 && Profile.DW_ALIGN == 1.5 && Profile.HW_ALIGN == 1.5, "Skillet calibration originals intact");

        System.out.println(fails == 0 ? "\nALL TESTS PASSED" : "\n" + fails + " TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static FieldMap.Read fieldOf(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
        FieldMap.Read r = FieldMap.analyze(o.px, o.w, o.h, o.worldX0, Remap.W - o.worldX0);
        System.out.println("      " + f + ": " + r);
        return r;
    }

    /** A drawn field: flowers on grass inside the rectangle (cells), hedge outside, optional grey blob hiding the ground. */
    static FieldMap.Read drawnField(int l, int t, int r, int b, boolean blob) {
        int w = 2340, h = 1080; int[] px = new int[w * h];
        double cw = w / (double) FieldMap.CW, ch = h / (double) FieldMap.CH;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int cx = (int) (x / cw), cy = (int) (y / ch);
            boolean in = cx >= l && cx < r && cy >= t && cy < b;
            boolean petal = (x % 40) < 22 && (y % 40) < 22;
            px[y * w + x] = !in ? ((x + y) % 7 < 3 ? 0xFF103A18 : 0xFF1C5A28) : petal ? 0xFF2040E0 : 0xFF3C9A44;
            if (blob && Math.hypot(x - w * .35, y - h * .5) < 150) px[y * w + x] = 0xFFB08AD0 & 0xFF555577 | 0xFF000000;
        }
        return FieldMap.analyze(px, w, h, 0, w);
    }

    static void fieldMapTests() throws Exception {
        FieldMap.Read mid = fieldOf("tab_field_middle.png"), hedge = fieldOf("tab_field_outside.png"), edge = fieldOf("tab_field_edge2.png");
        { boolean okW = fieldOf("tab_field_wrong.png").wrongField && fieldOf("tab_other_field.png").wrongField;
          StringBuilder fp = new StringBuilder();
          for (String pf : new String[]{"tab_field_middle.png", "tab_field_edge2.png", "tab_field_night.png", "tab_pine_high.png", "tab_pinetree_zoomed.png", "tab_field_outside.png"})
              if (fieldOf(pf).wrongField) fp.append(pf).append(' ');
          check(okW && fp.length() == 0, "field map: another field is flagged WRONG (reset while farming), no Pine Tree frame is " + fp); }
        check(mid.state == FieldMap.State.INSIDE && mid.ring > .8 && mid.nearest() > 10, "field map: middle of Pine Tree, honey tiles and bees are not edges");
        check(hedge.state == FieldMap.State.OUTSIDE, "field map: standing on the hedge reads as outside");
        check(edge.state == FieldMap.State.INSIDE && edge.left <= 3, "field map: hedge close on the left is measured (" + edge + ")");
        check(fieldOf("tab_field_wrong.png").state != FieldMap.State.INSIDE, "field map: the red-flower field next door is not Pine Tree");
        check(fieldOf("tab_field_night.png").state == FieldMap.State.INSIDE, "field map: Pine Tree at night is still read as the field");
        boolean sizes = true;
        for (int[] f : new int[][]{{4, 3, 36, 21}, {14, 8, 34, 20}, {2, 6, 25, 22}, {10, 2, 30, 16}}) {
            FieldMap.Read r = drawnField(f[0], f[1], f[2], f[3], false);
            int pi = FieldMap.CW / 2, pj = FieldMap.CH / 2;
            // an edge in the last 3 columns of the view is not yet confirmed, so it may read as none
            sizes &= (f[0] < 3 ? r.left == FieldMap.NO_EDGE : Math.abs(r.left - (pi - f[0])) <= 2) && Math.abs(r.right - (f[2] - pi)) <= 2 && r.state == FieldMap.State.INSIDE;
            System.out.println("      drawn field " + java.util.Arrays.toString(f) + ": " + r);
        }
        check(sizes, "field map: edge distances are right for fields of different sizes and positions");
        FieldMap.Read clear = drawnField(0, 0, 40, 24, false), hidden = drawnField(0, 0, 40, 24, true);
        check(clear.nearest() == FieldMap.NO_EDGE && hidden.left == FieldMap.NO_EDGE, "field map: something hiding the ground is not taken for an edge");

        FieldMap.Tracker tr = new FieldMap.Tracker();
        FieldMap.Read good = drawnField(4, 3, 36, 21, false), bad = drawnField(19, 11, 22, 13, false);
        tr.add(good, 1000); tr.add(good, 1100); tr.add(bad, 1200); tr.add(good, 1300); tr.add(good, 1400);
        FieldMap.Read st = tr.steady(1450);
        check(st != null && st.state == FieldMap.State.INSIDE && tr.steady(3000) == null, "field map: one bad frame is ignored, stale readings are not used");

        // long simulated sessions: pattern steps with a sideways bias and random error, screen view limited to 18 cells
        boolean stayed = true; long total = 0; int flips = 0;
        for (int[] size : new int[][]{{26, 22}, {40, 30}, {64, 30}}) for (double speed : new double[]{1, 1.4, 2}) for (double bias : new double[]{.15, -.3}) {
            java.util.Random rnd = new java.util.Random(size[0] * 31L + (long) (speed * 10));
            FieldMap.Controller c = new FieldMap.Controller();
            double x = size[0] / 2.0, y = size[1] / 2.0; long now = 0, lastDxAt = -99999; int lastDx = 0;
            for (int k = 0; k < 40_000; k++, total++) {
                now += 400;
                FieldMap.Read r = new FieldMap.Read(); r.state = FieldMap.State.INSIDE;
                r.left = see(x); r.right = see(size[0] - x); r.down = see(y); r.up = see(size[1] - y);
                FieldMap.Controller.Move m = c.update(r, now, speed);
                if (m != null) { double d = m.ms * speed / 120.0; x += m.x * d; y += m.y * d; if (m.x != 0 && m.x == -lastDx && now - lastDxAt < 2500) flips++; if (m.x != 0) { lastDx = m.x; lastDxAt = now; } }
                int sx = rnd.nextInt(3) - 1, sy = rnd.nextInt(3) - 1; double len = new double[]{1.5, 3, 6}[rnd.nextInt(3)];
                if (!FieldMap.blocked(r, sx, sy, FieldMap.stepLimit(len))) { x += sx * len + bias; y += sy * len + rnd.nextGaussian() * .1; }
                if (x < 0 || y < 0 || x > size[0] || y > size[1]) stayed = false;
            }
        }
        check(stayed, "field control: never left the field in " + total + " simulated steps (3 field sizes, 3 speeds, drift both ways)");
        check(flips == 0, "field control: never reverses a correction within 2.5 s (" + flips + ")");
    }
    static int see(double d) { return d > 18 ? FieldMap.NO_EDGE : (int) d; }

    /** Other-device check: the S9 FE frame resized smoothly to scale k, then cut to newH rows from the top
     *  (keeps the top HUD where Roblox anchors it; models a screen with a different shape). */
    static int[][] otherScreen(String f, double k, int newH) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        int w = (int) Math.round(im.getWidth() * k), h = (int) Math.round(im.getHeight() * k);
        BufferedImage sc = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);   // smooth resize, like text drawn at that size
        sc.getGraphics().drawImage(im.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        int[] px = sc.getRGB(0, 0, w, newH, null, 0, w);
        return new int[][]{px, {w, newH}};
    }
    static String screenCheck(String f, double k, int newH, double wantPollen) throws Exception {
        int[][] o = otherScreen(f, k, newH); int w = o[1][0], h = o[1][1];
        Remap.uiCal = 0;
        double u = Remap.calibrate(o[0], w, h);
        Remap.uiCal = u;
        Vision.Result r = Vision.analyze(Remap.apply(o[0], w, h));
        long hv = com.iipxp.beemacro.HoneyOcr.read(o[0], w, h);
        Remap.uiCal = 0;
        String s = String.format(java.util.Locale.US, "%dx%d scale %.3f (true %.3f) pollen %.1f%% prompt %s honey %d", w, h, u, k, r.pollen, r.prompt, hv);
        System.out.println("      " + s);
        return Math.abs(u - k) < 0.01 * k && Math.abs(r.pollen - wantPollen) < 1.5 && r.pollenConf > 0.8 ? "ok " + s : "BAD " + s;
    }

    /** A made-up sprinkler (grey body, dark centre, four gold nozzles) drawn into a frame, for the finder test. */
    /** The world slid by (dx, dy) while the HUD and the character stay put (what drift looks like on screen);
     *  `dim` scales brightness (day/night), `swarm` sprinkles moving bee-coloured dots everywhere. */
    static int[] slide(int[] px, int w, int h, int dx, int dy, double plx, double ply, double dim, boolean swarm) {
        int[] out = px.clone();
        java.util.Random rnd = new java.util.Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double fx = (double) x / w, fy = (double) y / h;
            boolean world = fx > 0.03 && fx < 0.84 && fy > 0.16 && fy < 0.84 && !(Math.abs(fx - plx) < 0.06 && fy > ply - 0.2 && fy < ply + 0.05);
            int sx = Math.min(w - 1, Math.max(0, x - dx)), sy = Math.min(h - 1, Math.max(0, y - dy));
            int p = world ? px[sy * w + sx] : px[y * w + x];
            if (dim != 1.0) { int r = (int) (((p >> 16) & 255) * dim), g = (int) (((p >> 8) & 255) * dim), b = (int) ((p & 255) * dim); p = 0xFF000000 | r << 16 | g << 8 | b; }
            out[y * w + x] = p;
        }
        if (swarm) for (int i = 0; i < 400; i++) {   // bees: small yellow/black blobs
            int cx = rnd.nextInt(w), cy = (int) (h * (0.2 + 0.6 * rnd.nextDouble())), col = rnd.nextBoolean() ? 0xFFF2C230 : 0xFF202020;
            for (int y = cy - 6; y < cy + 6; y++) for (int x = cx - 8; x < cx + 8; x++) if (x >= 0 && y >= 0 && x < w && y < h) out[y * w + x] = col;
        }
        return out;
    }

    static void drawSprinkler(int[] px, int w, int cx, int cy) {
        for (int y = -34; y <= 34; y++) for (int x = -34; x <= 34; x++) {
            double r = Math.hypot(x, y); int c;
            if (r <= 12) c = 0xFF1F2A44; else if (r <= 30) c = 0xFF9AA3AD; else if (r <= 34 && (Math.abs(x) < 6 || Math.abs(y) < 6)) c = 0xFFE8B030; else continue;
            px[(cy + y) * w + cx + x] = c;
        }
    }

    static long honeyOf(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        return com.iipxp.beemacro.HoneyOcr.read(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
    }

    static double facing(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
        double v = NavGuard.hiveFacing(o.px, o.w, o.h, o.worldX0, Remap.W - o.worldX0);
        System.out.println("      " + f + ": hive view " + v);
        return v;
    }

    static NavGuard.View guard(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
        NavGuard.View v = NavGuard.view(o.px, o.w, o.h, o.worldX0, o.w - o.worldX0);
        System.out.println("      " + f + ": " + v);
        return v;
    }

    static Vision.Result tab(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File("test/fixtures/" + f));
        Remap.Out o = Remap.apply(im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()), im.getWidth(), im.getHeight());
        Vision.Result r = Vision.analyze(o);
        System.out.println("      " + f + " (remapped): " + r.summary());
        return r;
    }

    static String key(String k) { return k.equals("FwdKey") ? "W" : k.equals("BackKey") ? "S" : k.equals("LeftKey") ? "A" : "D"; }
}
