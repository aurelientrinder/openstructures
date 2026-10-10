package rdm.ui;

import static rdm.i18n.Tr.tr;

import rdm.i18n.Tr;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Envelope;
import rdm.solver.Solution;

/**
 * The Results tab: every number of the solution, for a load case or a combination, or of the envelope of the
 * combinations, as a plain-text listing (copied as is into a report).
 */
final class Report {
  private Report() {}

  /** The results of a load case or a combination, the view's title under the header. */
  static String of(Structure s, Solution sol, String title) {
    StringBuilder b = new StringBuilder();
    b.append(tr("report.header", s.nodes.size(), s.elements.size())).append('\n');
    b.append(title).append('\n');
    switch (sol.status) {
      case EMPTY -> {
        return b.append(tr("report.empty")).append('\n').toString();
      }
      case ERROR -> {
        return b.append(tr("status.error", sol.error)).append('\n').toString();
      }
      case MECHANISM -> {
        b.append(tr("report.mechanism", sol.m, sol.h)).append('\n');
        b.append(tr("report.free")).append('\n');
        for (String f : sol.free) b.append("  ").append(f).append('\n');
        return b.append(tr("report.mechanismFix")).append('\n').toString();
      }
      default -> {}
    }
    b.append(tr("report.degree", sol.h)).append('\n');
    for (String w : sol.warnings) b.append(tr("report.warning", w)).append('\n');
    b.append(tr("report.assumptions")).append('\n');
    double weight = sol.loading.selfWeight(s);
    if (weight != 0) b.append(weight == 1 ? tr("report.selfWeight") : tr("report.selfWeightTimes", Fmt.num(weight))).append('\n');

    b.append('\n').append(tr("report.displacements")).append('\n');
    b.append(row(tr("col.node"), "ux (mm)", "uy (mm)", "θz (mrad)"));
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] d = sol.disp[i];
      b.append(row(Integer.toString(i + 1), Fmt.num(d[0] * 1e3), Fmt.num(d[1] * 1e3), Fmt.num(d[2] * 1e3)));
    }

    b.append('\n').append(tr("report.reactions")).append('\n');
    b.append(row(tr("col.node"), "Rx (kN)", "Ry (kN)", "Mz (kN·m)", tr("col.support")));
    double sx = 0, sy = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] r = sol.reaction[i];
      Node n = s.nodes.get(i);
      if (r == null || n.support == Support.NONE) continue;
      sx += r[0];
      sy += r[1];
      b.append(row(Integer.toString(i + 1), Fmt.num(r[0] / 1e3), Fmt.num(r[1] / 1e3), Fmt.num(r[2] / 1e3), n.support.label()));
    }
    b.append(row("Σ", Fmt.num(sx / 1e3), Fmt.num(sy / 1e3), "", ""));

    b.append('\n').append(tr("report.ends")).append('\n');
    b.append(row(tr("col.element"), "N₀ (kN)", tr("col.T0"), tr("col.M0"), "N₁ (kN)", tr("col.T1"), tr("col.M1")));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.N(0)), k(r.T(0)), k(r.M(0)), k(r.N(r.L)), k(r.T(r.L)), k(r.M(r.L))));
    }
    b.append('\n').append(tr("report.maxima")).append('\n');
    b.append(row(tr("col.element"), "|N| (kN)", tr("col.maxT"), tr("col.maxM"), "σmax (MPa)", tr("col.section")));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.maxAbsN()), k(r.maxAbsT()), k(r.maxAbsM()), Fmt.num(r.sigmaMax() / 1e6),
          r.element.section.label()));
    }
    b.append('\n').append(tr("report.maxDisplacement", Fmt.mm(sol.maxDisplacement))).append('\n');
    return b.toString();
  }

  /**
   * The envelope of the combinations: the combinations, then the smallest and largest reactions, internal forces
   * along each element, and the largest |M|, σ and displacement, with the combination that gives them.
   */
  static String of(Structure s, Envelope env) {
    StringBuilder b = new StringBuilder();
    b.append(tr("report.header", s.nodes.size(), s.elements.size())).append('\n');
    b.append(tr("report.envelope", env.combinations.size())).append('\n');
    for (int k = 0; k < env.combinations.size(); k++) {
      Combination c = env.combinations.get(k);
      String formula = c.formula(s);
      b.append(String.format("  C%-3d %s", k + 1, c.name.equals(formula) ? formula : c.name + " = " + formula)).append('\n');
    }
    Solution first = env.solutions.get(0);
    b.append(tr("report.degree", first.h)).append('\n');
    java.util.Set<String> warnings = new java.util.LinkedHashSet<>();
    for (Solution sol : env.solutions) warnings.addAll(sol.warnings);
    for (String w : warnings) b.append(tr("report.warning", w)).append('\n');
    b.append(tr("report.assumptions")).append('\n');

    b.append('\n').append(tr("report.envelopeReactions")).append('\n');
    b.append(row(tr("col.node"), "Rx min", "Rx max", "Ry min", "Ry max", "Mz min", "Mz max", tr("col.support")));
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] lo = env.minReaction[i], hi = env.maxReaction[i];
      Node n = s.nodes.get(i);
      if (lo == null || n.support == Support.NONE) continue;
      b.append(row(Integer.toString(i + 1), k(lo[0]), k(hi[0]), k(lo[1]), k(hi[1]), k(lo[2]), k(hi[2]), n.support.label()));
    }
    b.append(tr("report.envelopeUnits")).append('\n');

    b.append('\n').append(tr("report.envelopeForces")).append('\n');
    b.append(row(tr("col.element"), "N min", "N max", tr("col.Tmin"), tr("col.Tmax"), tr("col.Mmin"), tr("col.Mmax")));
    for (Element e : s.elements) {
      Envelope.Along a = env.of(e);
      String[] cells = new String[7];
      cells[0] = Integer.toString(s.number(e));
      int c = 1;
      for (Envelope.Effort q : Envelope.Effort.values()) {
        cells[c++] = k(a.min(q)[a.lowest(q)]);
        cells[c++] = k(a.max(q)[a.highest(q)]);
      }
      b.append(row(cells));
    }
    b.append(tr("report.envelopeUnits")).append('\n');

    b.append('\n').append(tr("report.envelopeMaxima")).append('\n');
    b.append(row(tr("col.element"), tr("col.maxM"), "", "σmax (MPa)", "", tr("col.section")));
    for (Element e : s.elements) {
      int byM = 0, byS = 0;
      double m = -1, sigma = -1;
      for (int k = 0; k < env.solutions.size(); k++) {
        ElementResult r = env.solutions.get(k).of(e);
        if (r.maxAbsM() > m) {
          m = r.maxAbsM();
          byM = k;
        }
        if (r.sigmaMax() > sigma) {
          sigma = r.sigmaMax();
          byS = k;
        }
      }
      b.append(row(Integer.toString(s.number(e)), k(m), "C" + (byM + 1), Fmt.num(sigma / 1e6), "C" + (byS + 1), e.section.label()));
    }
    b.append('\n').append(tr("report.envelopeDisplacement", Fmt.mm(env.maxDisplacement), "C" + (env.maxDisplacementBy + 1))).append('\n');
    return b.toString();
  }

  /** The envelope's curves as a table: for each element, points along it with the smallest and largest N, V, M. */
  static String csv(Envelope env) {
    boolean comma = Tr.lang().decimal == ',';
    String sep = comma ? ";" : ",";
    StringBuilder b = new StringBuilder(String.join(sep, tr("csv.element"), "x (m)", "X (m)", "Y (m)", "N min (kN)", "N max (kN)", tr("csv.Tmin"),
        tr("csv.Tmax"), tr("csv.Mmin"), tr("csv.Mmax")));
    b.append("\r\n");
    for (Element e : env.structure.elements) {
      Envelope.Along a = env.of(e);
      ElementResult r = env.solutions.get(0).of(e);
      String n = Integer.toString(env.structure.number(e));
      for (int i = 0; i < a.x.length; i++) {
        double x = a.x[i];
        b.append(n).append(sep).append(plain(x, comma)).append(sep).append(plain(e.a.x + r.c * x, comma)).append(sep).append(plain(e.a.y + r.s * x, comma));
        for (Envelope.Effort q : Envelope.Effort.values()) b.append(sep).append(plain(a.min(q)[i] / 1e3, comma)).append(sep).append(plain(a.max(q)[i] / 1e3, comma));
        b.append("\r\n");
      }
    }
    return b.toString();
  }

  /**
   * The diagrams as a table, for a spreadsheet: for each element, points along it (both sides of every jump) with
   * their position, N, V, M and displacement. Separated by ";" with decimal commas in French, "," and points in English.
   */
  static String csv(Solution sol) {
    boolean comma = Tr.lang().decimal == ',';
    String sep = comma ? ";" : ",";
    StringBuilder b = new StringBuilder(String.join(sep, tr("csv.element"), "x (m)", "X (m)", "Y (m)", "N (kN)", tr("csv.T"), tr("csv.M"), "ux (mm)", "uy (mm)"));
    b.append("\r\n");
    for (ElementResult r : sol.elements) {
      String n = Integer.toString(sol.structure.number(r.element));
      for (double x : r.samples(40)) {
        double[] d = r.displacement(x);
        double[] v = {x, r.element.a.x + r.c * x, r.element.a.y + r.s * x, r.N(x) / 1e3, r.T(x) / 1e3, r.M(x) / 1e3, d[0] * 1e3, d[1] * 1e3};
        b.append(n);
        for (double w : v) b.append(sep).append(plain(w, comma));
        b.append("\r\n");
      }
    }
    return b.toString();
  }

  /** 7 significant digits, no exponent, the decimal separator asked for. */
  private static String plain(double v, boolean comma) {
    if (Math.abs(v) < 1e-12) return "0";
    String s = new java.math.BigDecimal(v).round(new java.math.MathContext(7)).stripTrailingZeros().toPlainString();
    return comma ? s.replace('.', ',') : s;
  }

  private static String k(double v) {
    return Fmt.num(v / 1e3);
  }

  private static String row(String... cells) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < cells.length; i++) b.append(String.format(i == 0 ? "%-5s" : "%12s", cells[i])).append(' ');
    return b.toString().stripTrailing() + "\n";
  }
}
