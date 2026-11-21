import com.iipxp.beemacro.Detector;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/** Dev tool: draws Detector output on a screenshot. java Annotate in.png out.png [library.txt] */
public class Annotate {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        Detector.Library lib = a.length > 2 ? Detector.Library.load(new File(a[2])) : new Detector.Library();
        long t0 = System.nanoTime();
        List<Detector.Det> ds = Detector.detect(px, w, h, lib, 0.8, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Graphics2D g = im.createGraphics();
        g.setStroke(new BasicStroke(3)); g.setFont(new Font("SansSerif", Font.BOLD, 22));
        int i = 0;
        for (Detector.Det d : ds) {
            g.setColor(new Color(Detector.COLOR[d.cat.ordinal()]));
            g.drawRect(d.x0, d.y0, d.x1 - d.x0, d.y1 - d.y0);
            g.drawString("#" + i++ + " " + d.text(), d.x0, d.y0 - 4);
        }
        ImageIO.write(im, "png", new File(a[1]));
        System.out.println(a[0] + ": " + ds.size() + " detections in " + ms + " ms");
    }
}
