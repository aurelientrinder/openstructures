package rdm.ui;

import static rdm.i18n.Tr.tr;

import rdm.i18n.Tr;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Solution;

/** The Results tab: every number of the solution as a plain-text listing (copied as is into a report). */
final class Report {
  private Report() {}

  static String of(Structure s, Solution sol) {
    StringBuilder b = new StringBuilder();
    b.append(tr("report.header", s.nodes.size(), s.elements.size())).append('\n');
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
    if (s.selfWeight) b.append(tr("report.selfWeight")).append('\n');

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
