package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.util.ArrayList;
import java.util.List;
import rdm.design.Design;
import rdm.design.Design.Check;
import rdm.i18n.Tr;
import rdm.i18n.Units;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.Node;
import rdm.model.NodeLoad;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.Buckling;
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
    b.append(row(tr("col.node"), "Rx (" + Units.f() + ")", "Ry (" + Units.f() + ")", "Mz (" + Units.m() + ")", tr("col.support")));
    double sx = 0, sy = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] r = sol.reaction[i];
      Node n = s.nodes.get(i);
      if (r == null || n.support == Support.NONE) continue;
      sx += r[0];
      sy += r[1];
      b.append(row(Integer.toString(i + 1), k(r[0]), k(r[1]), km(r[2]), n.support.label()));
    }
    b.append(row("Σ", k(sx), k(sy), "", ""));

    b.append('\n').append(tr("report.ends")).append('\n');
    b.append(row(tr("col.element"), "N₀ (" + Units.f() + ")", tr("col.T0"), tr("col.M0"), "N₁ (" + Units.f() + ")", tr("col.T1"), tr("col.M1")));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.N(0)), k(r.T(0)), km(r.M(0)), k(r.N(r.L)), k(r.T(r.L)), km(r.M(r.L))));
    }
    b.append('\n').append(tr("report.maxima")).append('\n');
    b.append(row(tr("col.element"), "|N| (" + Units.f() + ")", tr("col.maxT"), tr("col.maxM"), "σmax (MPa)", tr("col.section")));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.maxAbsN()), k(r.maxAbsT()), km(r.maxAbsM()), Fmt.num(r.sigmaMax() / 1e6),
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
      b.append(row(Integer.toString(i + 1), k(lo[0]), k(hi[0]), k(lo[1]), k(hi[1]), km(lo[2]), km(hi[2]), n.support.label()));
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
        boolean mq = q == Envelope.Effort.M;
        cells[c++] = mq ? km(a.min(q)[a.lowest(q)]) : k(a.min(q)[a.lowest(q)]);
        cells[c++] = mq ? km(a.max(q)[a.highest(q)]) : k(a.max(q)[a.highest(q)]);
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
      b.append(row(Integer.toString(s.number(e)), km(m), "C" + (byM + 1), Fmt.num(sigma / 1e6), "C" + (byS + 1), e.section.label()));
    }
    b.append('\n').append(tr("report.envelopeDisplacement", Fmt.mm(env.maxDisplacement), "C" + (env.maxDisplacementBy + 1))).append('\n');
    return b.toString();
  }

  /**
   * Linear buckling, after the results it is of: the critical load factors of the modes, the safety factor, the
   * critical load (the nodal loads × λcr), and each compressed element's critical load and buckling length. With
   * the envelope, of the combination with the smallest λcr, after every combination's.
   */
  static String of(Structure s, Buckling bk, Combination by, Envelope env, double[] factors) {
    StringBuilder b = new StringBuilder();
    b.append(tr("report.buckling", bk.parts)).append('\n');
    if (by != null) {
      for (int k = 0; k < env.combinations.size(); k++) {
        String f = Double.isNaN(factors[k]) ? tr("report.bucklingNever") : tr("report.bucklingFactor", Fmt.num(factors[k]));
        b.append(String.format("  C%-3d %s", k + 1, f)).append('\n');
      }
      b.append(tr("report.bucklingIn", "C" + (env.combinations.indexOf(by) + 1), by.name)).append('\n');
    }
    if (!bk.ok()) return b.append(tr("report.bucklingNone")).append('\n').toString();
    b.append(tr("report.bucklingWhat")).append('\n');
    for (int k = 0; k < bk.modes.size(); k++) b.append(tr("report.bucklingMode", k + 1, Fmt.num(bk.modes.get(k).factor))).append('\n');
    double f = bk.factor();
    b.append(tr(f >= 1 ? "report.bucklingSafe" : "report.bucklingUnsafe", Fmt.num(f))).append('\n');
    b.append(tr("report.bucklingAssumptions")).append('\n');

    StringBuilder loads = new StringBuilder();
    for (int i = 0; i < s.nodes.size(); i++) {
      NodeLoad l = s.nodes.get(i).load(bk.solution.loading);
      if (l.fx == 0 && l.fy == 0 && l.mz == 0) continue;
      loads.append(row(Integer.toString(i + 1), k(l.fx * f), k(l.fy * f), km(l.mz * f)));
    }
    if (!loads.isEmpty()) {
      b.append('\n').append(tr("report.bucklingLoads")).append('\n');
      b.append(row(tr("col.node"), "Fx (" + Units.f() + ")", "Fy (" + Units.f() + ")", "Mz (" + Units.m() + ")")).append(loads);
    }

    b.append('\n').append(tr("report.bucklingMembers")).append('\n');
    b.append(row(tr("col.element"), "N (" + Units.f() + ")", tr("col.Ncr"), tr("col.Lf"), tr("col.LfL"), tr("col.Lfi"), "π²EI/L²"));
    for (Buckling.Member m : bk.members) {
      if (!m.compressed()) continue;
      b.append(row(Integer.toString(s.number(m.element())), k(m.N()), k(m.critical(f)), len(m.length(f)), Fmt.num(m.length(f) / m.L()),
          Fmt.num(m.slenderness(f)), k(m.euler())));
    }
    b.append(tr("report.bucklingEuler")).append('\n');
    return b.toString();
  }

  /**
   * The design checks: the loads they are made under, then every element's ratios (the largest is its
   * utilisation), the buckling of the compressed ones, and every span's deflection, with the verdict.
   */
  static String of(Structure s, Design d) {
    StringBuilder b = new StringBuilder();
    b.append(tr("report.header", s.nodes.size(), s.elements.size())).append('\n');
    b.append(tr("report.checks")).append('\n');
    if (!d.ok()) {
      if (d.failed == null) return b.append(tr("report.empty")).append('\n').toString();
      String in = d.failedIn.isEmpty() ? "" : tr("status.inCombination", d.failedIn);
      return b.append(d.failed.status == Solution.Status.MECHANISM ? tr("status.mechanism", d.failed.m, String.join(", ", d.failed.free))
          : tr("status.error", d.failed.error)).append(in).append('\n').toString();
    }
    loads(b, s, d.uls, d.ulsGiven, "U", "report.checksUls", "report.checksNoUls");
    loads(b, s, d.sls, d.slsGiven, "S", "report.checksSls", "report.checksNoSls");
    b.append(tr("report.checksAssumptions")).append('\n');

    b.append('\n').append(tr("report.strength")).append('\n');
    b.append(row(tr("col.element"), "N/Npl", "V/Vpl", "M/Mc", "N+M", tr("col.buckling"), tr("col.ratio"), tr("col.in"), tr("col.class"), "fy (MPa)",
        tr("col.grade")) .stripTrailing() + "  " + tr("col.section") + "\n");
    List<String> failing = new ArrayList<>(), unchecked = new ArrayList<>(), slender = new ArrayList<>();
    Design.Member worst = null;
    for (Design.Member m : d.members) {
      String n = Integer.toString(s.number(m.element));
      if (!m.checked()) {
        b.append(String.format("%-5s %s", n, tr("report.notChecked", m.why()))).append('\n');
        unchecked.add(n);
        continue;
      }
      Check g = m.governing();
      int cls = 1;
      for (Check c : Check.values()) if (m.worst(c).found()) cls = Math.max(cls, m.worst(c).cls);
      b.append(row(n, ratio(m, Check.AXIAL), ratio(m, Check.SHEAR), ratio(m, Check.BENDING), ratio(m, Check.COMBINED), ratio(m, Check.BUCKLING),
          Fmt.ratio(m.ratio()), by(d.uls, m.worst(g).by, "U"), Integer.toString(cls), Fmt.num(m.fy / 1e6), m.element.grade.name()).stripTrailing())
          .append("  ").append(m.element.section.label()).append('\n');
      if (m.ratio() > 1) failing.add(n);
      if (worst == null || m.ratio() > worst.ratio()) worst = m;
      if (m.shearBuckling()) slender.add(n);
    }
    if (worst != null) {
      Check g = worst.governing();
      Design.Worst w = worst.worst(g);
      b.append(g == Check.BUCKLING ? tr("report.governsBuckling", s.number(worst.element), Fmt.ratio(worst.ratio()), g, by(d.uls, w.by, "U"))
          : tr("report.governs", s.number(worst.element), Fmt.ratio(worst.ratio()), g, by(d.uls, w.by, "U"), len(w.x))).append('\n');
    }
    b.append(failing.isEmpty() ? (worst == null ? tr("report.nothingChecked") : tr("report.strengthOk")) : tr("report.strengthFails", String.join(", ", failing)))
        .append('\n');
    if (!unchecked.isEmpty()) b.append(tr("report.uncheckedList", String.join(", ", unchecked))).append('\n');
    if (!slender.isEmpty()) b.append(tr("report.shearBuckling", String.join(", ", slender))).append('\n');

    StringBuilder buckling = new StringBuilder();
    for (Design.Member m : d.members) {
      Design.Worst w = m.worst(Check.BUCKLING);
      if (!m.checked() || !w.found()) continue;
      boolean given = m.element.bucklingFactor > 0;
      buckling.append(row(Integer.toString(s.number(m.element)), k(-w.N), km(w.M), Double.isInfinite(w.Ncr) ? "∞" : k(w.Ncr),
          Double.isInfinite(w.Ncr) ? "—" : len(w.Lcr) + (given ? "" : "*"), Double.isInfinite(w.Ncr) ? "—" : Fmt.num(w.Lcr / m.element.length()),
          Fmt.num(w.slenderness), String.valueOf(m.steel.curve), Fmt.num(w.chi), Fmt.num(w.kyy), Fmt.ratio(w.ratio), by(d.uls, w.by, "U")));
    }
    if (!buckling.isEmpty()) {
      b.append('\n').append(tr("report.inPlaneBuckling")).append('\n');
      b.append(row(tr("col.element"), "N Ed (" + Units.f() + ")", tr("col.MEd"), tr("col.Ncr"), "Lcr (" + Units.l() + ")", "Lcr/L", "λ̄", tr("col.curve"), "χ", "kyy", tr("col.ratio"),
          tr("col.in")));
      b.append(buckling);
      b.append(tr("report.bucklingLengths")).append('\n');
    }

    b.append('\n').append(tr("report.deflections", Fmt.num(s.deflectionLimit))).append('\n');
    b.append(row(tr("col.span"), tr("col.elements"), "L (" + Units.l() + ")", "δ (mm)", tr("col.allowed"), "L/δ", tr("col.ratio"), tr("col.in")));
    List<String> sagging = new ArrayList<>();
    for (int i = 0; i < d.spans.size(); i++) {
      Design.Span sp = d.spans.get(i);
      String els = String.join("-", sp.elements.stream().map(e -> Integer.toString(s.number(e))).toList());
      double delta = Math.abs(sp.deflection);
      b.append(row(Integer.toString(i + 1) + (sp.cantilever ? "c" : ""), els, len(sp.length), Fmt.num(delta * 1e3), Fmt.num(sp.allowed * 1e3),
          delta < 1e-9 ? "—" : Fmt.num(Math.round((sp.cantilever ? 2 : 1) * sp.length / delta)), Fmt.ratio(sp.ratio()), by(d.sls, sp.by, "S")));
      if (sp.ratio() > 1) sagging.add(Integer.toString(i + 1));
    }
    if (d.spans.stream().anyMatch(sp -> sp.cantilever)) b.append(tr("report.cantilever")).append('\n');
    b.append(sagging.isEmpty() ? tr("report.deflectionsOk") : tr("report.deflectionsFail", String.join(", ", sagging))).append('\n');
    return b.toString();
  }

  /** The loads of a limit state: the combinations, numbered with a prefix, or the cases as given. */
  private static void loads(StringBuilder b, Structure s, List<Design.Loads> loads, boolean given, String prefix, String title, String none) {
    if (!given) {
      b.append(tr(none)).append('\n');
      return;
    }
    b.append(tr(title, loads.size())).append('\n');
    for (int k = 0; k < loads.size(); k++) {
      Combination c = loads.get(k).combination();
      String formula = c.formula(s);
      b.append(String.format("  %s%-3d %s", prefix, k + 1, c.name.equals(formula) ? formula : c.name + " = " + formula)).append('\n');
    }
  }

  private static String ratio(Design.Member m, Check c) {
    Design.Worst w = m.worst(c);
    return w.found() ? Fmt.ratio(w.ratio) : "—";
  }

  /** "U2": the loads it is in, "—" for the cases as given. */
  private static String by(List<Design.Loads> loads, int k, String prefix) {
    return k < 0 ? "—" : loads.get(k).combination() == null ? "*" : prefix + (k + 1);
  }

  /** The checks as a table, an element a row: its ratios, its utilisation, its span's deflection. */
  static String csv(Design d) {
    boolean comma = Tr.lang().decimal == ',';
    String sep = comma ? ";" : ",";
    Structure s = d.structure;
    StringBuilder b = new StringBuilder(String.join(sep, tr("csv.element"), tr("csv.section"), tr("col.grade"), "fy (MPa)", tr("col.class"), "N/Npl",
        "V/Vpl", "M/Mc", "N+M", tr("col.buckling"), tr("col.ratio"), tr("csv.governing"), tr("col.in"), "δ (mm)", tr("csv.allowed"), tr("csv.deflectionRatio")));
    b.append("\r\n");
    for (Design.Member m : d.members) {
      List<String> cells = new ArrayList<>(List.of(Integer.toString(s.number(m.element)), m.element.section.label().replace(sep, " "), m.element.grade.name()));
      if (m.checked()) {
        Check g = m.governing();
        int cls = 1;
        for (Check c : Check.values()) if (m.worst(c).found()) cls = Math.max(cls, m.worst(c).cls);
        cells.addAll(List.of(plain(m.fy / 1e6, comma), Integer.toString(cls)));
        for (Check c : Check.values()) cells.add(m.worst(c).found() ? plain(m.worst(c).ratio, comma) : "");
        cells.addAll(List.of(plain(m.ratio(), comma), g.toString(), name(d.uls, m.worst(g).by)));
      } else {
        cells.addAll(List.of("", "", "", "", "", "", "", "", m.why().replace(sep, " "), ""));
      }
      Design.Span sp = d.spanOf(m.element);
      if (sp != null) cells.addAll(List.of(plain(Math.abs(sp.deflection) * 1e3, comma), plain(sp.allowed * 1e3, comma), plain(sp.ratio(), comma)));
      b.append(String.join(sep, cells)).append("\r\n");
    }
    return b.toString();
  }

  private static String name(List<Design.Loads> loads, int k) {
    return k < 0 ? "" : loads.get(k).combination() == null ? tr("csv.asGiven") : loads.get(k).name();
  }

  /**
   * The buckling modes as a table: for each element, points along it with each mode's displacement (the largest
   * at 1), its λcr in the column's name.
   */
  static String csv(Buckling bk) {
    boolean comma = Tr.lang().decimal == ',';
    String sep = comma ? ";" : ",";
    List<String> head = new ArrayList<>(List.of(tr("csv.element"), "x (" + Units.l() + ")", "X (" + Units.l() + ")", "Y (" + Units.l() + ")"));
    for (int k = 0; k < bk.modes.size(); k++) {
      head.add(tr("csv.modeUx", k + 1, plain(bk.modes.get(k).factor, comma)));
      head.add(tr("csv.modeUy", k + 1));
    }
    StringBuilder b = new StringBuilder(String.join(sep, head)).append("\r\n");
    for (ElementResult r : bk.solution.elements) {
      String n = Integer.toString(bk.solution.structure.number(r.element));
      int steps = 5 * bk.parts;
      for (int i = 0; i <= steps; i++) {
        double x = r.L * i / steps;
        b.append(n).append(sep).append(plain(Units.L(x), comma)).append(sep).append(plain(Units.L(r.element.a.x + r.c * x), comma)).append(sep)
            .append(plain(Units.L(r.element.a.y + r.s * x), comma));
        for (Buckling.Mode m : bk.modes) {
          double[] d = m.displacement(r.element, x);
          b.append(sep).append(plain(d[0], comma)).append(sep).append(plain(d[1], comma));
        }
        b.append("\r\n");
      }
    }
    return b.toString();
  }

  /** The envelope's curves as a table: for each element, points along it with the smallest and largest N, V, M. */
  static String csv(Envelope env) {
    boolean comma = Tr.lang().decimal == ',';
    String sep = comma ? ";" : ",";
    StringBuilder b = new StringBuilder(String.join(sep, tr("csv.element"), "x (" + Units.l() + ")", "X (" + Units.l() + ")", "Y (" + Units.l() + ")", "N min (" + Units.f() + ")", "N max (" + Units.f() + ")", tr("csv.Tmin"),
        tr("csv.Tmax"), tr("csv.Mmin"), tr("csv.Mmax")));
    b.append("\r\n");
    for (Element e : env.structure.elements) {
      Envelope.Along a = env.of(e);
      ElementResult r = env.solutions.get(0).of(e);
      String n = Integer.toString(env.structure.number(e));
      for (int i = 0; i < a.x.length; i++) {
        double x = a.x[i];
        b.append(n).append(sep).append(plain(Units.L(x), comma)).append(sep).append(plain(Units.L(e.a.x + r.c * x), comma)).append(sep)
            .append(plain(Units.L(e.a.y + r.s * x), comma));
        for (Envelope.Effort q : Envelope.Effort.values()) {
          java.util.function.DoubleUnaryOperator u = q == Envelope.Effort.M ? Units::M : Units::F;
          b.append(sep).append(plain(u.applyAsDouble(a.min(q)[i]), comma)).append(sep).append(plain(u.applyAsDouble(a.max(q)[i]), comma));
        }
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
    StringBuilder b = new StringBuilder(String.join(sep, tr("csv.element"), "x (" + Units.l() + ")", "X (" + Units.l() + ")", "Y (" + Units.l() + ")", "N (" + Units.f() + ")", tr("csv.T"), tr("csv.M"), "ux (mm)", "uy (mm)"));
    b.append("\r\n");
    for (ElementResult r : sol.elements) {
      String n = Integer.toString(sol.structure.number(r.element));
      for (double x : r.samples(40)) {
        double[] d = r.displacement(x);
        double[] v = {Units.L(x), Units.L(r.element.a.x + r.c * x), Units.L(r.element.a.y + r.s * x), Units.F(r.N(x)), Units.F(r.T(x)), Units.M(r.M(x)),
            d[0] * 1e3, d[1] * 1e3};
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

  /** A force (N) in the force unit, without it. */
  private static String k(double v) {
    return Fmt.num(Units.F(v));
  }

  /** A moment (N·m) in the moment unit, without it. */
  private static String km(double v) {
    return Fmt.num(Units.M(v));
  }

  /** A length (m) in the length unit, without it. */
  private static String len(double v) {
    return Fmt.num(Units.L(v));
  }

  private static String row(String... cells) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < cells.length; i++) b.append(String.format(i == 0 ? "%-5s" : "%12s", cells[i])).append(' ');
    return b.toString().stripTrailing() + "\n";
  }
}
