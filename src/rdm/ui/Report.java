package rdm.ui;

import static rdm.i18n.Tr.tr;

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

  private static String k(double v) {
    return Fmt.num(v / 1e3);
  }

  private static String row(String... cells) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < cells.length; i++) b.append(String.format(i == 0 ? "%-5s" : "%12s", cells[i])).append(' ');
    return b.toString().stripTrailing() + "\n";
  }
}
