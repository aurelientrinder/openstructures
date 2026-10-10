package rdm.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;

/**
 * The .rdm text format, one item per line (SI units: m, N, N·m, N/m, rad; E in MPa, ρ in kg/m³; section
 * parameters as in Section; positions along an element as fractions of its length):
 *
 * <pre>
 * RDM 2
 * selfweight 0|1
 * node x y SUPPORT angle fx fy mz settleT settleN settleR springT springN springR
 * elem a b hingeA hingeB KIND p1 p2 p3 E rho      (a, b: node numbers from 1)
 * force e t fx fy local                           (e: element number from 1; local 0|1)
 * couple e t c
 * dist e t1 t2 qx1 qy1 qx2 qy2 local
 * </pre>
 *
 * Version 1 files (no load lines; elem ending with E qx qy, node with its first 8 fields) are read too.
 * Also the undo snapshots.
 */
public final class StructureFile {
  private StructureFile() {}

  public static String write(Structure s) {
    StringBuilder sb = new StringBuilder("RDM 2\n");
    sb.append(line("selfweight", s.selfWeight ? 1 : 0));
    for (Node n : s.nodes) {
      sb.append(line("node", n.x, n.y, n.support.name(), n.supportAngle, n.fx, n.fy, n.mz, n.settleT, n.settleN, n.settleR, n.springT, n.springN,
          n.springR));
    }
    for (Element e : s.elements) {
      sb.append(line("elem", s.number(e.a), s.number(e.b), e.hingeA ? 1 : 0, e.hingeB ? 1 : 0, e.section.kind().name(),
          e.section.p1(), e.section.p2(), e.section.p3(), e.material.E(), e.material.rho()));
    }
    for (Element e : s.elements) {
      int k = s.number(e);
      for (ElementLoad l : e.loads) {
        sb.append(switch (l) {
          case ElementLoad.Force f -> line("force", k, f.t(), f.fx(), f.fy(), f.local() ? 1 : 0);
          case ElementLoad.Couple c -> line("couple", k, c.t(), c.c());
          case ElementLoad.Distributed d -> line("dist", k, d.t1(), d.t2(), d.qx1(), d.qy1(), d.qx2(), d.qy2(), d.local() ? 1 : 0);
        });
      }
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
          case "selfweight" -> s.selfWeight = w[1].equals("1");
          case "node" -> {
            Node n = s.addNode(d(w[1]), d(w[2]));
            n.support = Support.valueOf(w[3]);
            n.supportAngle = d(w[4]);
            n.fx = d(w[5]);
            n.fy = d(w[6]);
            n.mz = d(w[7]);
            if (w.length > 8) {
              n.settleT = d(w[8]);
              n.settleN = d(w[9]);
              n.settleR = d(w[10]);
              n.springT = d(w[11]);
              n.springN = d(w[12]);
              n.springR = d(w[13]);
            }
          }
          case "elem" -> {
            Element e = new Element(s.nodes.get(Integer.parseInt(w[1]) - 1), s.nodes.get(Integer.parseInt(w[2]) - 1),
                new Section(Section.Kind.valueOf(w[5]), d(w[6]), d(w[7]), d(w[8])));
            e.hingeA = w[3].equals("1");
            e.hingeB = w[4].equals("1");
            double E = d(w[9]);
            if (lines[0].trim().equals("RDM 1")) {
              // version 1: the density was not kept, and the element's uniform load ended the line
              Material m = Material.PRESETS.stream().filter(p -> p.E() == E).findFirst().orElse(null);
              e.material = m != null ? m : Material.of(E, Material.STEEL.rho());
              double qx = d(w[10]), qy = d(w[11]);
              if (qx != 0 || qy != 0) e.loads.add(ElementLoad.Distributed.uniform(qx, qy));
            } else {
              e.material = Material.of(E, d(w[10]));
            }
            s.elements.add(e);
          }
          case "force" -> element(s, w[1]).loads.add(new ElementLoad.Force(d(w[2]), d(w[3]), d(w[4]), w[5].equals("1")));
          case "couple" -> element(s, w[1]).loads.add(new ElementLoad.Couple(d(w[2]), d(w[3])));
          case "dist" -> element(s, w[1]).loads.add(new ElementLoad.Distributed(d(w[2]), d(w[3]), d(w[4]), d(w[5]), d(w[6]), d(w[7]), w[8].equals("1")));
          default -> throw new IllegalArgumentException("ligne inconnue");
        }
      } catch (RuntimeException ex) {
        throw new IllegalArgumentException("ligne " + (k + 1) + " illisible : " + l, ex);
      }
    }
    return s;
  }

  private static Element element(Structure s, String number) {
    return s.elements.get(Integer.parseInt(number) - 1);
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
