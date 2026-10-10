package rdm.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import rdm.model.Element;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;

/**
 * The .rdm text format, one item per line (SI units: m, N, N·m, N/m; E in MPa; section parameters as in Section):
 *
 * <pre>
 * RDM 1
 * node x y SUPPORT angle fx fy mz
 * elem a b hingeA hingeB KIND p1 p2 p3 E qx qy      (a, b: node numbers from 1)
 * </pre>
 *
 * Also the undo snapshots.
 */
public final class StructureFile {
  private StructureFile() {}

  public static String write(Structure s) {
    StringBuilder sb = new StringBuilder("RDM 1\n");
    for (Node n : s.nodes) sb.append(line("node", n.x, n.y, n.support.name(), n.supportAngle, n.fx, n.fy, n.mz));
    for (Element e : s.elements) {
      sb.append(line("elem", s.number(e.a), s.number(e.b), e.hingeA ? 1 : 0, e.hingeB ? 1 : 0, e.section.kind().name(),
          e.section.p1(), e.section.p2(), e.section.p3(), e.material.E(), e.qx, e.qy));
    }
    return sb.toString();
  }

  private static String line(String head, Object... values) {
    StringBuilder sb = new StringBuilder(head);
    for (Object v : values) sb.append(' ').append(v instanceof Double d ? String.format(Locale.ROOT, "%s", d) : v);
    return sb.append('\n').toString();
  }

  public static Structure read(String text) {
    Structure s = new Structure();
    String[] lines = text.split("\\R");
    if (lines.length == 0 || !lines[0].trim().startsWith("RDM")) throw new IllegalArgumentException("ce n'est pas un fichier RDM");
    for (int k = 1; k < lines.length; k++) {
      String l = lines[k].trim();
      if (l.isEmpty() || l.startsWith("#")) continue;
      String[] w = l.split("\\s+");
      try {
        switch (w[0]) {
          case "node" -> {
            Node n = s.addNode(d(w[1]), d(w[2]));
            n.support = Support.valueOf(w[3]);
            n.supportAngle = d(w[4]);
            n.fx = d(w[5]);
            n.fy = d(w[6]);
            n.mz = d(w[7]);
          }
          case "elem" -> {
            Element e = new Element(s.nodes.get(Integer.parseInt(w[1]) - 1), s.nodes.get(Integer.parseInt(w[2]) - 1),
                new Section(Section.Kind.valueOf(w[5]), d(w[6]), d(w[7]), d(w[8])));
            e.hingeA = w[3].equals("1");
            e.hingeB = w[4].equals("1");
            e.material = Material.ofE(d(w[9]));
            e.qx = d(w[10]);
            e.qy = d(w[11]);
            s.elements.add(e);
          }
          default -> throw new IllegalArgumentException("ligne inconnue");
        }
      } catch (RuntimeException ex) {
        throw new IllegalArgumentException("ligne " + (k + 1) + " illisible : " + l, ex);
      }
    }
    return s;
  }

  private static double d(String s) {
    return Double.parseDouble(s);
  }

  public static void save(Structure s, Path p) throws IOException {
    Files.writeString(p, write(s), StandardCharsets.UTF_8);
  }

  public static Structure load(Path p) throws IOException {
    return read(Files.readString(p, StandardCharsets.UTF_8));
  }
}
