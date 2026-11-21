import com.iipxp.beemacro.StackOcr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** Builds StackOcr.GLYPHS from screenshots whose stack texts were read by eye, then tests on held-out frames.
 *  args: fixturesDir liveDir.  Prints the Java table to paste into StackOcr. */
public class OcrTrain {
    static int[] px; static int w, h;
    static void load(String f) throws Exception { BufferedImage im = ImageIO.read(new File(f)); w = im.getWidth(); h = im.getHeight(); px = im.getRGB(0, 0, w, h, null, 0, w); }
    static List<boolean[]> at(int k) { double s = w / 2340.0; return StackOcr.glyphs(px, w, h, (100 + 60 * k) * s, 179 * h / 1080.0, 58 * s); }

    public static void main(String[] a) throws Exception {
        String F = a[0], L = a[1];
        Object[][] train = {
            {F + "/field_pinetree2.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x176", 18, "x2", 19, "x1.18", 20, "x5"}},
            {F + "/field_pinetree.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x4", 18, "x2"}},
            {F + "/hasteon2.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x189"}},
            {F + "/hasteoff1.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x191"}},
            {F + "/hasteoff2.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x185"}},
            {F + "/hasteoff3.png", new Object[]{1, "x1000", 17, "x191"}},
            {F + "/hasteon1.png", new Object[]{1, "x1000", 17, "x4"}},
            {F + "/haste_x4.png", new Object[]{1, "x1000", 17, "x325"}},
            {F + "/haste_x5.png", new Object[]{1, "x1000", 17, "x325"}},
            {F + "/field_pinetree3.png", new Object[]{1, "x1000", 17, "x314"}},
            {F + "/field_pinetree4.png", new Object[]{1, "x1000", 17, "x320"}},
        };
        Object[][] test = {
            {L + "/f1.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x10", 20, "x2"}},
            {L + "/v2.png", new Object[]{0, "x31", 1, "x1000", 3, "x289", 17, "x36", 19, "11%"}},
        };
        Map<Character, double[]> sum = new TreeMap<>(); Map<Character, Integer> cnt = new TreeMap<>();
        for (Object[] fr : train) {
            load((String) fr[0]); Object[] ks = (Object[]) fr[1];
            for (int i = 0; i < ks.length; i += 2) {
                String txt = (String) ks[i + 1]; List<boolean[]> gs = at((Integer) ks[i]);
                if (gs.size() != txt.length()) { System.out.println("  skip " + fr[0] + " slot " + ks[i] + ": " + gs.size() + " glyphs for '" + txt + "'"); continue; }
                for (int j = 0; j < gs.size(); j++) {
                    double[] s = sum.computeIfAbsent(txt.charAt(j), c -> new double[StackOcr.GW * StackOcr.GH]);
                    if (gs.get(j).length == 0) continue; for (int p = 0; p < s.length; p++) if (gs.get(j)[p]) s[p]++;
                    cnt.merge(txt.charAt(j), 1, Integer::sum);
                }
            }
        }
        List<String[]> table = new ArrayList<>();
        StringBuilder java = new StringBuilder("    static String[][] GLYPHS = {\n");
        for (char c : sum.keySet()) {
            StringBuilder m = new StringBuilder(); double[] s = sum.get(c); int n = cnt.get(c);
            for (double v : s) m.append(v * 2 >= n ? '#' : '.');
            table.add(new String[]{"" + c, m.toString()});
            java.append("        {\"").append(c).append("\", \"").append(m).append("\"},   // ").append(n).append(" samples\n");
        }
        java.append("    };");
        System.out.println(java);
        java.lang.reflect.Field f = StackOcr.class.getDeclaredField("GLYPHS"); f.setAccessible(true); f.set(null, table.toArray(new String[0][]));
        int ok = 0, n = 0;
        for (Object[] fr : test) {
            load((String) fr[0]); Object[] ks = (Object[]) fr[1]; double s = w / 2340.0;
            for (int i = 0; i < ks.length; i += 2) {
                int k = (Integer) ks[i]; String want = (String) ks[i + 1];
                String got = StackOcr.read(px, w, h, (100 + 60 * k) * s, 179 * h / 1080.0, 58 * s);
                List<boolean[]> gg = StackOcr.glyphs(px, w, h, (100 + 60 * k) * s, 179 * h / 1080.0, 58 * s); StringBuilder dbg = new StringBuilder();
                for (boolean[] g : gg) { if (g.length == 0) { dbg.append(". "); continue; } String bc = "?"; double bs = 0; for (String[] t : table) { int same = 0; for (int q = 0; q < g.length; q++) if (g[q] == (t[1].charAt(q) == '#')) same++; if (same > bs) { bs = same; bc = t[0]; } } dbg.append(bc).append(String.format("%.2f ", bs / g.length)); }
                System.out.println("      glyphs: " + dbg);
                n++; if (want.equals(got)) ok++;
                System.out.println("  held-out " + new File((String) fr[0]).getName() + " slot " + k + ": want " + want + " got " + got);
            }
        }
        System.out.println("held-out stack text: " + ok + "/" + n + " exact");
    }
}
