import com.iipxp.beemacro.Catalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

/** catalog/catalog_src.tsv + catalog/src/*.png  ->  assets/catalog.tsv, assets/catalog.bin, assets/thumbs/*.png */
public class BuildCatalog {
    public static void main(String[] a) throws Exception {
        new File("assets/thumbs").mkdirs();
        Files.copy(new File("catalog/catalog_src.tsv").toPath(), new File("assets/catalog.tsv").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Catalog c;
        // load names only (templates computed here), via a dummy bin with the right count
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String s : Files.readAllLines(new File("catalog/catalog_src.tsv").toPath(), java.nio.charset.StandardCharsets.UTF_8))
            if (!s.startsWith("#") && !s.trim().isEmpty()) rows.add(s.split("\t", -1));
        ByteArrayOutputStream bin = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bin);
        d.writeInt(rows.size()); d.writeInt(Catalog.N);
        int i = 0;
        for (String[] r : rows) {
            BufferedImage im = ImageIO.read(new File("catalog/src/" + r[3]));
            if (im == null) throw new Exception("unreadable " + r[3]);
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            double[] crop = r[4].equals("all") ? new double[]{0, 0, 1, 1} : java.util.Arrays.stream(r[4].split(",")).mapToDouble(Double::parseDouble).toArray();
            int[] t = Catalog.template(px, w, h, crop);
            for (int v : t) d.writeInt(v);
            BufferedImage th = new BufferedImage(Catalog.N, Catalog.N, BufferedImage.TYPE_INT_ARGB);
            th.setRGB(0, 0, Catalog.N, Catalog.N, t, 0, Catalog.N);
            ImageIO.write(th, "png", new File("assets/thumbs/" + i++ + ".png"));
        }
        d.flush();
        Files.write(new File("assets/catalog.bin").toPath(), bin.toByteArray());
        c = Catalog.load(new FileInputStream("assets/catalog.tsv"), new FileInputStream("assets/catalog.bin"));
        System.out.println("catalog: " + c.entries.size() + " entries -> assets/");
    }
}
