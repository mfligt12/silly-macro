import com.iipxp.beemacro.Remap;
import com.iipxp.beemacro.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** java ShotCheck img...: what the phone calibration and the HUD reader make of real screenshots (user reports). */
public class ShotCheck {
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            BufferedImage im = ImageIO.read(new File(f));
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            Remap.Phone ph = Remap.locatePhone(px, w, h);
            Remap.phoneCal = ph;
            Vision.Result r = ph == null ? null : Vision.analyze(Remap.apply(px, w, h));
            System.out.println(new File(f).getName() + " " + w + "x" + h + ": " + (ph == null ? "pills NOT found" : ph)
                    + (r == null ? "" : " -> " + r.screen + "(" + Math.round(r.screenConf * 100) + "%) bag " + Math.round(r.pollen) + "% (" + Math.round(r.pollenConf * 100) + "%) prompt " + r.prompt + " haste " + r.haste));
            if (ph != null && System.getProperty("dump") != null) {   // the remapped S23-sized frame, to see where readers sample
                Remap.Out o = Remap.apply(px, w, h);
                BufferedImage out = new BufferedImage(o.w, o.h, BufferedImage.TYPE_INT_RGB);
                out.setRGB(0, 0, o.w, o.h, o.px, 0, o.w);
                ImageIO.write(out, "png", new File(System.getProperty("dump"), new File(f).getName() + "_remap.png"));
            }
            Remap.phoneCal = null;
        }
    }
}
