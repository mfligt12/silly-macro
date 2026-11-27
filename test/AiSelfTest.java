import com.iipxp.beemacro.AiBandit;
import com.iipxp.beemacro.AiBrain;
import com.iipxp.beemacro.AiPower;
import com.iipxp.beemacro.FrameWire;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Desktop tests for the AI layer and the frame sender's wire format (run by build.ps1 step 1). */
public class AiSelfTest {
    static int fails, passes;
    static void check(boolean ok, String what) { if (ok) passes++; else { fails++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) throws Exception {
        // --- bandit converges on the best arm and survives save/load
        AiBandit b = new AiBandit(0.3f, new Random(3));
        List<String> arms = Arrays.asList("Snake", "e_lol", "Squares");
        float[] truth = {2f, 5f, 3f};
        for (int i = 0; i < 200; i++) { String c = b.choose(arms, 0.1f); b.update(c, truth[arms.indexOf(c)]); }
        check(b.pulls("e_lol") > b.pulls("Snake") && b.pulls("e_lol") > b.pulls("Squares"), "bandit picks the best pattern most");
        AiBandit b2 = new AiBandit(0.3f, new Random(1)); b2.load(b.save());
        check(Math.abs(b2.estimate("e_lol") - b.estimate("e_lol")) < 1e-4 && b2.pulls("Snake") == b.pulls("Snake"), "bandit save/load round trip");
        check(new AiBandit().score("new", arms) >= 1f, "untried arm gets the exploration bonus");

        // --- brain: shortlist, trip scoring rules, persistence across runs
        check(AiBrain.shortlist(" Snake, e_lol ,Nope,Snake", Arrays.asList("Snake", "e_lol", "Squares")).equals(Arrays.asList("Snake", "e_lol")), "shortlist keeps known names once");
        File dir = new File(System.getProperty("java.io.tmpdir"), "ai_selftest_" + System.nanoTime()); dir.mkdirs();
        Class<?> bc = AiBrain.class;
        java.lang.reflect.Constructor<?> ctor = bc.getDeclaredConstructor(File.class, Random.class); ctor.setAccessible(true);
        File f = new File(dir, "ai_brain.properties");
        AiBrain brain = (AiBrain) ctor.newInstance(f, new Random(5));
        check(Float.isNaN(brain.reportTrip("pinetree", "Snake", 10, 90, 30_000)), "trips under a minute are not scored");
        check(Float.isNaN(brain.reportTrip("pinetree", "Snake", 50, 20, 120_000)), "a falling bag % is not scored");
        check(Float.isNaN(brain.reportTrip("pinetree", "Snake", -1, 80, 120_000)), "unreadable start is not scored");
        check(Math.abs(brain.reportTrip("pinetree", "Snake", 10, 90, 8 * 60_000) - 10f) < 1e-3, "80% in 8 min = 10%/min");
        for (int i = 0; i < 6; i++) { brain.reportTrip("pinetree", "e_lol", 0, 96, 6 * 60_000); brain.reportTrip("pinetree", "Snake", 0, 60, 6 * 60_000); }
        AiBrain again = (AiBrain) ctor.newInstance(f, new Random(5));
        check("e_lol".equals(again.pickPattern("pinetree", Arrays.asList("Snake", "e_lol"), 0f)), "learned best pattern survives a restart");
        check(again.pickPattern("pinetree", java.util.Collections.<String>emptyList(), 0f) == null, "empty shortlist keeps the app's pattern");
        check(again.summary("pinetree", Arrays.asList("Snake", "e_lol")).contains("e_lol: 16.00%/min"), "summary shows the learned rate");

        // --- heat governor with hysteresis
        check(AiPower.classify(35, 0, false, 41, 45, 39) == AiPower.Level.NORMAL, "cool phone is normal");
        check(AiPower.classify(42, 0, false, 41, 45, 39) == AiPower.Level.THROTTLED, "warm phone throttles");
        check(AiPower.classify(30, 2, false, 41, 45, 39) == AiPower.Level.THROTTLED, "thermal MODERATE throttles");
        check(AiPower.classify(46, 0, false, 41, 45, 39) == AiPower.Level.HOT, "hot phone rests");
        check(AiPower.classify(30, 3, false, 41, 45, 39) == AiPower.Level.HOT, "thermal SEVERE rests");
        check(AiPower.classify(42, 0, true, 41, 45, 39) == AiPower.Level.HOT, "stays resting until it cools (hysteresis)");
        check(AiPower.classify(38, 0, true, 41, 45, 39) == AiPower.Level.NORMAL, "cooled down: back to normal");
        check(AiPower.classify(-1, -1, false, 41, 45, 39) == AiPower.Level.NORMAL, "no sensors: normal");
        check(AiPower.periodFactor(AiPower.Level.THROTTLED) > 1f && AiPower.periodFactor(AiPower.Level.NORMAL) == 1f, "throttle slows the vision loop");

        // --- screencap raw output (12- and 16-byte headers)
        for (int hdr : new int[]{12, 16}) {
            ByteBuffer bb = ByteBuffer.allocate(hdr + 3 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(3).putInt(2).putInt(1); if (hdr == 16) bb.putInt(0);
            int[] l = FrameWire.screencapLayout(bb.array());
            check(l != null && l[0] == 3 && l[1] == 2 && l[2] == hdr, "screencap header " + hdr);
        }
        check(FrameWire.screencapLayout(new byte[20]) == null, "garbage screencap rejected");

        System.out.println("AiSelfTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
