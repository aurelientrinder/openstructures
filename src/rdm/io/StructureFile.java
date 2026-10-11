package rdm.io;

import static rdm.i18n.Tr.tr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Grade;
import rdm.model.LoadCase;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.NodeLoad;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;

/**
 * The .rdm text format, one item per line (SI units: m, N, N·m, N/m, rad; E in MPa, ρ in kg/m³; section
 * parameters as in Section; positions along an element as fractions of its length):
 *
 * <pre>
 * RDM 5
 * deflection limit                           (the deflection checks' L/limit)
 * case TYPE selfweight name                  (TYPE PERMANENT|VARIABLE, selfweight 0|1, the name to the end of the line)
 * combination LIMIT k1 … kn name             (LIMIT ULS|SLS; a factor per case, in their order; the name to the end of the line)
 * node x y SUPPORT angle springT springN springR
 * elem a b hingeA hingeB KIND p1 p2 p3 p4 turned E rho GRADE k
 *                                            (a, b: node numbers from 1; turned 0|1; GRADE S235…; k: L_cr / L, 0 auto)
 * load n fx fy mz settleT settleN settleR c       (n: node number from 1; c: case number from 1)
 * force e t fx fy local c                         (e: element number from 1; local 0|1)
 * couple e t C c
 * dist e t1 t2 qx1 qy1 qx2 qy2 local c
 * </pre>
 *
 * Older files are read too. Version 4 had no deflection limit (300), no limit state on the combinations (ULS),
 * no grade nor buckling length on the elements (S235, auto). Before, every load was in one case (G, permanent): version 3 had no cases nor combinations,
 * a line "selfweight 0|1", the loads and imposed displacements on the node line (node x y SUPPORT angle fx fy mz
 * settleT settleN settleR springT springN springR) and no case number on the element loads; version 2 had no p4
 * nor turned; version 1 no load lines, elem ending with E qx qy, node with its first 8 fields.
 * Also the undo snapshots.
 */
public final class StructureFile {
  private StructureFile() {}

  public static String write(Structure s) {
    StringBuilder sb = new StringBuilder("RDM 5\n");
    sb.append(line("deflection", s.deflectionLimit));
    for (LoadCase c : s.cases) sb.append(line("case", c.type.name(), c.selfWeight ? 1 : 0, c.name.strip()));
    for (Combination k : s.combinations) {
      Object[] values = new Object[s.cases.size() + 2];
      values[0] = k.limit.name();
      for (int i = 0; i < s.cases.size(); i++) values[i + 1] = k.factor(s.cases.get(i));
      values[s.cases.size() + 1] = k.name.strip();
      sb.append(line("combination", values));
    }
    for (Node n : s.nodes) sb.append(line("node", n.x, n.y, n.support.name(), n.supportAngle, n.springT, n.springN, n.springR));
    for (Element e : s.elements) {
      sb.append(line("elem", s.number(e.a), s.number(e.b), e.hingeA ? 1 : 0, e.hingeB ? 1 : 0, e.section.kind().name(),
          e.section.p1(), e.section.p2(), e.section.p3(), e.section.p4(), e.section.turned() ? 1 : 0, e.material.E(), e.material.rho(),
          e.grade.name(), e.bucklingFactor));
    }
    for (Node n : s.nodes) {
      for (LoadCase c : s.cases) {
        NodeLoad l = n.load(c);
        if (!l.isZero()) sb.append(line("load", s.number(n), l.fx, l.fy, l.mz, l.settleT, l.settleN, l.settleR, s.number(c)));
      }
    }
    for (Element e : s.elements) {
      int k = s.number(e);
      for (ElementLoad l : e.loads) {
        int c = s.number(l.loadCase());
        sb.append(switch (l) {
          case ElementLoad.Force f -> line("force", k, f.t(), f.fx(), f.fy(), f.local() ? 1 : 0, c);
          case ElementLoad.Couple cp -> line("couple", k, cp.t(), cp.c(), c);
          case ElementLoad.Distributed d -> line("dist", k, d.t1(), d.t2(), d.qx1(), d.qy1(), d.qx2(), d.qy2(), d.local() ? 1 : 0, c);
        });
      }
    }
    return sb.toString();
  }

  private static String line(String head, Object... values) {
    StringBuilder sb = new StringBuilder(head);
    for (Object v : values) sb.append(' ').append(v instanceof Double d ? String.format(Locale.ROOT, "%s", d) : v);
    return sb.toString().stripTrailing() + '\n';
  }

  public static Structure read(String text) {
    Structure s = new Structure();
    String[] lines = text.split("\\R");
    if (lines.length == 0 || !lines[0].trim().startsWith("RDM")) throw new IllegalArgumentException(tr("file.notRdm"));
    String head = lines[0].trim();
    int version = head.equals("RDM 1") ? 1 : head.equals("RDM 2") ? 2 : head.equals("RDM 3") ? 3 : head.equals("RDM 4") ? 4 : 5;
    // the structure's own case G holds everything until the file lists its cases
    boolean ownCases = false;
    for (int k = 1; k < lines.length; k++) {
      String l = lines[k].trim();
      if (l.isEmpty() || l.startsWith("#")) continue;
      String[] w = l.split("\\s+");
      try {
        switch (w[0]) {
          case "case" -> {
            if (!ownCases) s.cases.clear();
            ownCases = true;
            String[] p = l.split("\\s+", 4);
            s.addCase(p.length > 3 ? p[3] : "", LoadCase.Type.valueOf(p[1])).selfWeight = p[2].equals("1");
          }
          case "combination" -> {
            // from version 5, the limit state comes first
            int n = s.cases.size(), at = version >= 5 ? 2 : 1;
            String[] p = l.split("\\s+", n + at + 1);
            Combination c = s.addCombination(p.length > n + at ? p[n + at] : "");
            if (version >= 5) c.limit = Combination.Limit.valueOf(p[1]);
            for (int i = 0; i < n; i++) c.setFactor(s.cases.get(i), d(p[i + at]));
          }
          case "deflection" -> s.deflectionLimit = d(w[1]);
          case "selfweight" -> s.cases.get(0).selfWeight = w[1].equals("1");
          case "node" -> {
            Node n = s.addNode(d(w[1]), d(w[2]));
            n.support = Support.valueOf(w[3]);
            n.supportAngle = d(w[4]);
            if (version >= 4) {
              n.springT = d(w[5]);
              n.springN = d(w[6]);
              n.springR = d(w[7]);
            } else {
              NodeLoad g = n.loadIn(s.cases.get(0));
              g.fx = d(w[5]);
              g.fy = d(w[6]);
              g.mz = d(w[7]);
              if (w.length > 8) {
                g.settleT = d(w[8]);
                g.settleN = d(w[9]);
                g.settleR = d(w[10]);
                n.springT = d(w[11]);
                n.springN = d(w[12]);
                n.springR = d(w[13]);
              }
            }
          }
          case "elem" -> {
            // from version 3, the section has a 4th dimension and may be turned: E and ρ come 2 places later
            int shift = version >= 3 ? 2 : 0;
            Section section = version >= 3 ? new Section(Section.Kind.valueOf(w[5]), d(w[6]), d(w[7]), d(w[8]), d(w[9]), w[10].equals("1"))
                : new Section(Section.Kind.valueOf(w[5]), d(w[6]), d(w[7]), d(w[8]));
            Element e = new Element(s.nodes.get(Integer.parseInt(w[1]) - 1), s.nodes.get(Integer.parseInt(w[2]) - 1), section);
            e.hingeA = w[3].equals("1");
            e.hingeB = w[4].equals("1");
            double E = d(w[9 + shift]);
            if (version == 1) {
              // version 1: the density was not kept, and the element's uniform load ended the line
              Material m = Material.PRESETS.stream().filter(p -> p.E() == E).findFirst().orElse(null);
              e.material = m != null ? m : Material.of(E, Material.STEEL.rho());
              double qx = d(w[10]), qy = d(w[11]);
              if (qx != 0 || qy != 0) e.loads.add(ElementLoad.Distributed.uniform(s.cases.get(0), qx, qy));
            } else {
              e.material = Material.of(E, d(w[10 + shift]));
            }
            if (version >= 5) {
              e.grade = Grade.valueOf(w[13]);
              e.bucklingFactor = d(w[14]);
            }
            s.elements.add(e);
          }
          case "load" -> {
            NodeLoad g = s.nodes.get(Integer.parseInt(w[1]) - 1).loadIn(loadCase(s, w[8]));
            g.fx = d(w[2]);
            g.fy = d(w[3]);
            g.mz = d(w[4]);
            g.settleT = d(w[5]);
            g.settleN = d(w[6]);
            g.settleR = d(w[7]);
          }
          case "force" -> element(s, w[1]).loads.add(new ElementLoad.Force(loadCase(s, version, w, 6), d(w[2]), d(w[3]), d(w[4]), w[5].equals("1")));
          case "couple" -> element(s, w[1]).loads.add(new ElementLoad.Couple(loadCase(s, version, w, 4), d(w[2]), d(w[3])));
          case "dist" -> element(s, w[1]).loads.add(new ElementLoad.Distributed(loadCase(s, version, w, 9), d(w[2]), d(w[3]), d(w[4]), d(w[5]),
              d(w[6]), d(w[7]), w[8].equals("1")));
          default -> throw new IllegalArgumentException(tr("file.unknownLine"));
        }
      } catch (RuntimeException ex) {
        throw new IllegalArgumentException(tr("file.badLine", k + 1, l), ex);
      }
    }
    return s;
  }

  /** The case of an element load: its number at w[at] from version 4, the first case before. */
  private static LoadCase loadCase(Structure s, int version, String[] w, int at) {
    return version >= 4 ? loadCase(s, w[at]) : s.cases.get(0);
  }

  private static LoadCase loadCase(Structure s, String number) {
    return s.cases.get(Integer.parseInt(number) - 1);
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
