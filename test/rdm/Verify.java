package rdm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rdm.i18n.Tr;
import rdm.design.Design;
import rdm.design.SteelSection;
import rdm.io.StructureFile;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Examples;
import rdm.model.Grade;
import rdm.model.LoadCase;
import rdm.model.Loading;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.NodeLoad;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.Buckling;
import rdm.solver.ElementResult;
import rdm.solver.Envelope;
import rdm.solver.Solution;
import rdm.solver.Solver;
import rdm.ui.PointEntry;

/** The solver against closed-form results of the RdM formulary. Run: verify.cmd (exit code 1 on a failure). */
public final class Verify {
  private static int failures, checks;

  public static void main(String[] args) {
    // first, as Settings.applyAtStartup does: the languages load before Tr (no cycle between the two)
    check("languages loaded before Tr", Tr.Lang.values().length, 2);
    cantilever();
    simplySupported();
    fixedFixed();
    proppedCantilever();
    twoBarTruss();
    skewedRoller();
    gerber();
    mechanisms();
    pointLoad();
    cantileverMidLoad();
    triangular();
    fixedFixedPoint();
    coupleInSpan();
    halfSpan();
    inclinedLocalLoad();
    selfWeight();
    settlement();
    spring();
    splitKeepsLoads();
    combinations();
    envelope();
    eulerColumns();
    bucklingOfFrames();
    loadCaseFiles();
    readVersion1();
    examples();
    roundTrip();
    sections();
    design();
    typedPoints();
    texts();
    System.out.printf("%d checks, %d failure(s)%n", checks, failures);
    if (failures > 0) System.exit(1);
  }

  static final double P = 10e3, Q = 5e3, L = 4;

  /** A beam from (0,0) to (L,0) in n elements, rectangle 50 × 100 steel: EI = 210e9 × 4.1667e-6. */
  static Structure beam(int n) {
    Structure s = new Structure();
    Node prev = s.addNode(0, 0);
    for (int i = 1; i <= n; i++) {
      Node nx = s.addNode(L * i / n, 0);
      s.addElement(prev, nx, false).section = new Section(Section.Kind.RECT, 50, 100, 0);
      prev = nx;
    }
    return s;
  }

  static double EI(Structure s) {
    Element e = s.elements.get(0);
    return e.material.pascals() * e.section.inertia();
  }

  static Node node(Structure s, int i) {
    return s.nodes.get(i);
  }

  /** The structure's first load case, G, which every check puts its loads in unless it says otherwise. */
  static LoadCase g(Structure s) {
    return s.cases.get(0);
  }

  /** What case G does at node i, to change. */
  static NodeLoad load(Structure s, int i) {
    return s.nodes.get(i).loadIn(g(s));
  }

  static void cantilever() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    load(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("cantilever: deflection PL³/3EI", sol.disp[1][1], -P * L * L * L / (3 * EI));
    check("cantilever: rotation PL²/2EI", sol.disp[1][2], -P * L * L / (2 * EI));
    check("cantilever: fixed-end moment", sol.reaction[0][2], P * L);
    check("cantilever: M(0) = −PL", sol.of(s.elements.get(0)).M(0), -P * L);
    check("cantilever: h = 0", sol.h, 0);
  }

  static void simplySupported() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    uniform(s, s.elements.get(0), -Q);
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    double EI = EI(s);
    check("simply supported: deflection 5qL⁴/384EI (between the nodes)", r.deflection(L / 2), -5 * Q * Math.pow(L, 4) / (384 * EI));
    check("simply supported: max M qL²/8", r.maxAbsM(), Q * L * L / 8);
    check("simply supported: max at x = L/2", r.maxMAt(), L / 2);
    check("simply supported: reaction qL/2", sol.reaction[1][1], Q * L / 2);
    check("simply supported: V(0) = −qL/2", r.T(0), -Q * L / 2);
    check("simply supported: h = 0", sol.h, 0);
    equilibrium("simply supported", s, sol);
  }

  static void fixedFixed() {
    Structure s = beam(2);
    node(s, 0).support = node(s, 2).support = Support.ENCASTREMENT;
    for (Element e : s.elements) uniform(s, e, -Q);
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("fixed-fixed: deflection qL⁴/384EI", sol.disp[1][1], -Q * Math.pow(L, 4) / (384 * EI));
    check("fixed-fixed: M at the fixed ends −qL²/12", sol.of(s.elements.get(0)).M(0), -Q * L * L / 12);
    check("fixed-fixed: M at mid-span qL²/24", sol.of(s.elements.get(0)).M(L / 2), Q * L * L / 24);
    check("fixed-fixed: h = 3", sol.h, 3);
  }

  static void proppedCantilever() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    uniform(s, s.elements.get(0), -Q);
    Solution sol = Solver.solve(s);
    check("propped cantilever: roller reaction 3qL/8", sol.reaction[1][1], 3 * Q * L / 8);
    check("propped cantilever: fixed-end reaction 5qL/8", sol.reaction[0][1], 5 * Q * L / 8);
    check("propped cantilever: fixed-end moment qL²/8", sol.reaction[0][2], Q * L * L / 8);
    check("propped cantilever: h = 1", sol.h, 1);
  }

  /** Two bars at α from the horizontal, pinned at their feet, a load P down at the apex. */
  static void twoBarTruss() {
    double a = 3, b = 2;
    Structure s = new Structure();
    Node l = s.addNode(-a, 0), top = s.addNode(0, b), r = s.addNode(a, 0);
    l.support = r.support = Support.PIVOT;
    top.loadIn(g(s)).fy = -P;
    s.addElement(l, top, true);
    s.addElement(top, r, true);
    Solution sol = Solver.solve(s);
    double len = Math.hypot(a, b), sin = b / len;
    double Nexp = -P / (2 * sin);
    Element e = s.elements.get(0);
    double EA = e.material.pascals() * e.section.area();
    check("two-bar truss: N = −P/(2 sin α)", sol.of(e).N(0), Nexp);
    // Castigliano: δ = Σ N² L / (EA P)
    check("two-bar truss: deflection Σ N²L/(EA·P)", sol.disp[1][1], -2 * Nexp * Nexp * len / (EA * P));
    check("two-bar truss: h = 0", sol.h, 0);
    check("two-bar truss: no rotation of its own at the apex", Double.isNaN(sol.disp[1][2]) ? 0 : 1, 0);
  }

  /** Pivot at the left, roller of normal at 45° at the right, P down at mid-span. */
  static void skewedRoller() {
    Structure s = beam(2);
    node(s, 0).support = Support.PIVOT;
    node(s, 2).support = Support.APPUI_SIMPLE;
    node(s, 2).supportAngle = 45;
    load(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    check("skewed roller: Rx on the right P/2", sol.reaction[2][0], P / 2);
    check("skewed roller: Ry on the right P/2", sol.reaction[2][1], P / 2);
    check("skewed roller: Rx on the left −P/2", sol.reaction[0][0], -P / 2);
    double[] u = sol.disp[2];
    check("skewed roller: displacement ⟂ to the normal", u[0] * Math.cos(Math.PI / 4) + u[1] * Math.sin(Math.PI / 4), 0);
    equilibrium("skewed roller", s, sol);
  }

  /** Gerber beam: fixed at 0, hinge at 2, roller at 4, P at 3. */
  static void gerber() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), hinge = s.addNode(2, 0), load = s.addNode(3, 0), roller = s.addNode(4, 0);
    a.support = Support.ENCASTREMENT;
    roller.support = Support.APPUI_SIMPLE;
    load.loadIn(g(s)).fy = -P;
    s.addElement(a, hinge, false).hingeB = true;
    s.addElement(hinge, load, false);
    s.addElement(load, roller, false);
    Solution sol = Solver.solve(s);
    check("Gerber: roller reaction P/2", sol.reaction[3][1], P / 2);
    check("Gerber: fixed-end moment P/2 × 2", sol.reaction[0][2], P);
    check("Gerber: M zero at the hinge", sol.of(s.elements.get(0)).M(2), 0);
    check("Gerber: h = 0", sol.h, 0);
  }

  static void mechanisms() {
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.APPUI_SIMPLE;
    Solution sol = Solver.solve(s);
    check("two rollers: mechanism", sol.status == Solution.Status.MECHANISM ? 1 : 0, 1);
    check("two rollers: m = 1", sol.m, 1);

    Structure q = new Structure();
    Node a = q.addNode(0, 0), b = q.addNode(2, 0), c = q.addNode(2, 2), d = q.addNode(0, 2);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    q.addElement(a, b, true);
    q.addElement(b, c, true);
    q.addElement(c, d, true);
    q.addElement(d, a, true);
    Solution qs = Solver.solve(q);
    check("square without a diagonal: m = 1", qs.m, 1);
    q.addElement(a, c, true);
    Solution braced = Solver.solve(q);
    check("braced square: m = 0, h = 0", braced.m + 10 * braced.h, 0);
  }

  static void examples() {
    for (var ex : Examples.ALL.entrySet()) {
      Structure s = ex.getValue().get();
      Solution sol = Solver.solve(s);
      check(ex.getKey() + ": solved", sol.ok() ? 1 : 0, 1);
      if (sol.ok()) equilibrium(ex.getKey(), s, sol);
    }
    Solution warren = Solver.solve(Examples.ALL.get("warren").get());
    check("Warren: h = 0", warren.h, 0);
    Solution arch = Solver.solve(Examples.ALL.get("threeHinged").get());
    check("three-hinged arch: h = 0", arch.h, 0);
    Solution portal = Solver.solve(Examples.ALL.get("portal").get());
    check("fixed portal frame: h = 3", portal.h, 3);
    Structure column = Examples.ALL.get("eulerColumn").get();
    Element tube = column.elements.get(0);
    near("Euler's column: λcr = π²EI/L² / 40 kN", Buckling.of(Solver.solve(column)).factor(),
        Math.PI * Math.PI * tube.material.pascals() * tube.section.inertia() / (3 * 3) / 40e3, 1e-3);
  }

  static void roundTrip() {
    Structure s = Examples.ALL.get("threeHinged").get();
    s.elements.get(0).loads.add(new ElementLoad.Force(g(s), 0.25, 1e3, -2e3, true));
    s.elements.get(0).loads.add(new ElementLoad.Couple(g(s), 0.6, 3e3));
    s.nodes.get(0).loadIn(g(s)).settleN = -0.002;
    g(s).selfWeight = true;
    String once = StructureFile.write(s);
    check("file: read back identical", StructureFile.write(StructureFile.read(once)).equals(once) ? 1 : 0, 1);
  }

  /** Loads + reactions balance: forces and moment about the origin, for the loading the solution is for. */
  static void equilibrium(String name, Structure s, Solution sol) {
    double fx = 0, fy = 0, mz = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      Node n = s.nodes.get(i);
      NodeLoad l = n.load(sol.loading);
      double[] r = sol.reaction[i];
      double x = l.fx + (r == null ? 0 : r[0]), y = l.fy + (r == null ? 0 : r[1]);
      fx += x;
      fy += y;
      mz += l.mz + (r == null ? 0 : r[2]) + n.x * y - n.y * x;
    }
    for (Element e : s.elements) {
      double[] t = totals(e, sol.loading, sol.loading.selfWeight(s));
      fx += t[0];
      fy += t[1];
      mz += t[2];
    }
    double scale = 1e3;
    check(name + ": global equilibrium", (Math.abs(fx) + Math.abs(fy) + Math.abs(mz)) / scale, 0);
  }

  /**
   * Resultant (global) and moment about the origin of an element's loads, each times its case's factor, and of its
   * weight times selfWeight; exact (Gauss rules of the right degree).
   */
  static double[] totals(Element e, Loading loading, double selfWeight) {
    double len = e.length(), fx = 0, fy = 0, mz = 0;
    List<ElementLoad> loads = new ArrayList<>();
    for (ElementLoad l : e.loads) loads.add(l.scaled(loading.factor(l.loadCase())));
    if (selfWeight != 0) {
      double w = selfWeight * e.material.rho() * e.section.area() * Structure.G;
      loads.add(ElementLoad.Distributed.uniform(null, 0, -w));
    }
    for (ElementLoad l : loads) {
      switch (l) {
        case ElementLoad.Force f -> {
          double[] g = f.local() ? e.toGlobal(f.fx(), f.fy()) : new double[] {f.fx(), f.fy()};
          double px = e.a.x + f.t() * (e.b.x - e.a.x), py = e.a.y + f.t() * (e.b.y - e.a.y);
          fx += g[0];
          fy += g[1];
          mz += px * g[1] - py * g[0];
        }
        case ElementLoad.Couple c -> mz += c.c();
        case ElementLoad.Distributed d -> {
          double h = (d.t2() - d.t1()) / 2, m = (d.t1() + d.t2()) / 2;
          for (double gx : new double[] {-1 / Math.sqrt(3), 1 / Math.sqrt(3)}) {
            double t = m + h * gx, w = h * len;
            double[] q = d.at(t);
            double[] g = d.local() ? e.toGlobal(q[0], q[1]) : q;
            double px = e.a.x + t * (e.b.x - e.a.x), py = e.a.y + t * (e.b.y - e.a.y);
            fx += w * g[0];
            fy += w * g[1];
            mz += w * (px * g[1] - py * g[0]);
          }
        }
      }
    }
    return new double[] {fx, fy, mz};
  }

  static void uniform(Structure s, Element e, double qy) {
    e.loads.add(ElementLoad.Distributed.uniform(g(s), 0, qy));
  }

  /** Simply supported, P at a = L/4 (b = 3L/4). */
  static void pointLoad() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Force(g(s), 0.25, 0, -P, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    double a = L / 4, b = 3 * L / 4, EI = EI(s);
    check("point load in the span: reaction Pb/L", sol.reaction[0][1], P * b / L);
    check("point load in the span: M under the load Pab/L", r.M(a), P * a * b / L);
    check("point load in the span: max M under the load", r.maxMAt(), a);
    check("point load in the span: deflection under the load Pa²b²/3EIL", r.deflection(a), -P * a * a * b * b / (3 * EI * L));
    check("point load in the span: jump of V = P", r.T(a + 1e-9) - r.T(a - 1e-9), P);
    equilibrium("point load in the span", s, sol);
  }

  static void cantileverMidLoad() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    s.elements.get(0).loads.add(new ElementLoad.Force(g(s), 0.5, 0, -P, false));
    Solution sol = Solver.solve(s);
    check("cantilever, load at mid-length: tip deflection 5PL³/48EI", sol.disp[1][1], -5 * P * L * L * L / (48 * EI(s)));
  }

  /** Simply supported, load growing from 0 at a to q at b. */
  static void triangular() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Distributed(g(s), 0, 1, 0, 0, 0, -Q, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("triangular load: reaction qL/6", sol.reaction[0][1], Q * L / 6);
    check("triangular load: reaction qL/3", sol.reaction[1][1], Q * L / 3);
    check("triangular load: max M qL²/9√3", r.maxAbsM(), Q * L * L / (9 * Math.sqrt(3)));
    check("triangular load: at x = L/√3", r.maxMAt(), L / Math.sqrt(3));
    check("triangular load: deflection at mid-span 5qL⁴/768EI", r.deflection(L / 2), -5 * Q * Math.pow(L, 4) / (768 * EI(s)));
    equilibrium("triangular load", s, sol);
  }

  static void fixedFixedPoint() {
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.ENCASTREMENT;
    s.elements.get(0).loads.add(new ElementLoad.Force(g(s), 0.5, 0, -P, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("fixed-fixed, load at mid-span: M at the fixed ends −PL/8", r.M(0), -P * L / 8);
    check("fixed-fixed, load at mid-span: M at mid-span PL/8", r.M(L / 2), P * L / 8);
    check("fixed-fixed, load at mid-span: deflection PL³/192EI", r.deflection(L / 2), -P * L * L * L / (192 * EI(s)));
  }

  static void coupleInSpan() {
    double C = 6e3;
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Couple(g(s), 0.5, C));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("moment in the span: reactions ±C/L", sol.reaction[0][1], C / L);
    check("moment in the span: M just before C/2", r.M(L / 2 - 1e-12), C / 2);
    check("moment in the span: M just after −C/2", r.M(L / 2 + 1e-12), -C / 2);
    check("moment in the span: no deflection at mid-span (antisymmetry)", r.deflection(L / 2), 0);
    equilibrium("moment in the span", s, sol);
  }

  static void halfSpan() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Distributed(g(s), 0, 0.5, 0, -Q, 0, -Q, false));
    Solution sol = Solver.solve(s);
    check("load on half the span: reaction 3qL/8", sol.reaction[0][1], 3 * Q * L / 8);
    check("load on half the span: reaction qL/8", sol.reaction[1][1], Q * L / 8);
    equilibrium("load on half the span", s, sol);
  }

  /** A beam at 30°, a uniform load across it (local axes): the moment is the straight beam's. */
  static void inclinedLocalLoad() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(L * Math.cos(Math.PI / 6), L * Math.sin(Math.PI / 6));
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    Element e = s.addElement(a, b, false);
    e.loads.add(new ElementLoad.Distributed(g(s), 0, 1, 0, -Q, 0, -Q, true));
    Solution sol = Solver.solve(s);
    check("inclined beam, local load: max M qL²/8", sol.of(e).maxAbsM(), Q * L * L / 8);
    equilibrium("inclined beam, local load", s, sol);
  }

  static void selfWeight() {
    Structure s = beam(1);
    g(s).selfWeight = true;
    node(s, 0).support = Support.ENCASTREMENT;
    Solution sol = Solver.solve(s);
    Element e = s.elements.get(0);
    double w = e.material.rho() * e.section.area() * Structure.G;
    check("self-weight, cantilever: deflection wL⁴/8EI", sol.disp[1][1], -w * Math.pow(L, 4) / (8 * EI(s)));
    check("self-weight, cantilever: reaction = weight", sol.reaction[0][1], w * L);
  }

  /** Fixed-fixed beam whose right end settles by δ. */
  static void settlement() {
    double delta = 0.005;
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.ENCASTREMENT;
    load(s, 1).settleN = -delta;
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("settlement: imposed displacement", sol.disp[1][1], -delta);
    check("settlement: reaction −12EIδ/L³", sol.reaction[1][1], -12 * EI * delta / (L * L * L));
    check("settlement: fixed-end M 6EIδ/L²", Math.abs(sol.of(s.elements.get(0)).M(0)), 6 * EI * delta / (L * L));
    check("settlement: h = 3", sol.h, 3);
  }

  /** Cantilever whose tip rests on a spring: the tip load shares between both. */
  static void spring() {
    double k = 2e6;
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    node(s, 1).support = Support.ELASTIQUE;
    node(s, 1).springN = k;
    load(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    double kb = 3 * EI(s) / (L * L * L);
    check("spring support: deflection P/(k + 3EI/L³)", sol.disp[1][1], -P / (k + kb));
    check("spring support: spring force", sol.reaction[1][1], P * k / (k + kb));
    check("spring support: h = 1", sol.h, 1);
    equilibrium("spring support", s, sol);
  }

  /** Cutting an element through its loads changes nothing (a force right at the cut goes to the new node). */
  static void splitKeepsLoads() {
    for (double t : new double[] {0.4, 0.5, 1.0 / 3, 0.75}) {
      Structure s = Examples.ALL.get("mixedLoads").get();
      Solution before = Solver.solve(s);
      double mid = before.of(s.elements.get(0)).M(5);
      s.split(s.elements.get(0), t);
      Solution after = Solver.solve(s);
      Element second = s.elements.get(1);
      String at = String.format(Locale.ROOT, "%.3f", t);
      check("cut at t = " + at + ": reaction unchanged", after.reaction[0][1], before.reaction[0][1]);
      check("cut at t = " + at + ": M unchanged at x = 5 m", after.of(second).M(5 - second.a.x), mid);
    }
  }

  /** The example with cases, with a few more kinds of load: a settlement, a nodal moment, a force along an element. */
  static Structure withCases() {
    Structure s = Examples.ALL.get("portalCases").get();
    LoadCase q = s.cases.get(1), w = s.cases.get(2);
    s.nodes.get(3).loadIn(q).settleN = -0.003;
    s.nodes.get(1).loadIn(w).mz = 4e3;
    s.nodes.get(2).loadIn(w).fx = 3e3;
    s.elements.get(1).loads.add(new ElementLoad.Force(q, 0.3, 1e3, -6e3, false));
    return s;
  }

  /** Generated combinations, and each one's results against its cases' results, factored and added up. */
  static void combinations() {
    Structure s = withCases();
    LoadCase g = s.cases.get(0), q = s.cases.get(1), w = s.cases.get(2);
    check("generated combinations: 6", s.combinations.size(), 6);
    double[][] expected = {{1.35, 1.5, 1.05}, {1.35, 1.05, 1.5}, {1, 1.5, 0}, {1, 0, 1.5}, {1, 1, 0.7}, {1, 0.7, 1}};
    for (int i = 0; i < 6; i++) {
      var k = s.combinations.get(i);
      check("combination " + (i + 1) + ": EN 1990 factors", Math.abs(k.factor(g) - expected[i][0]) + Math.abs(k.factor(q) - expected[i][1])
          + Math.abs(k.factor(w) - expected[i][2]), 0);
      check("combination " + (i + 1) + ": " + (i < 4 ? "ULS" : "SLS"), k.limit == (i < 4 ? Combination.Limit.ULS : Combination.Limit.SLS) ? 1 : 0, 1);
    }
    check("combinations generated twice: nothing more", s.generateCombinations(), 0);
    Structure onlyG = beam(1);
    onlyG.generateCombinations();
    check("no variable load: 1.35 G (ULS) and G (SLS)", onlyG.combinations.size() == 2 && onlyG.combinations.get(0).factor(g(onlyG)) == 1.35
        && onlyG.combinations.get(1).factor(g(onlyG)) == 1 && onlyG.combinations.get(1).limit == Combination.Limit.SLS ? 1 : 0, 1);

    List<Solution> alone = s.cases.stream().map(c -> Solver.solve(s, Loading.of(c))).toList();
    for (var k : s.combinations) {
      Solution sol = Solver.solve(s, k.loading());
      String name = "combination " + k.name;
      check(name + ": solved", sol.ok() ? 1 : 0, 1);
      equilibrium(name, s, sol);
      double du = 0, dr = 0, de = 0, umax = 0, fmax = 0;
      for (int i = 0; i < s.nodes.size(); i++) {
        for (int j = 0; j < 3; j++) {
          double sum = 0;
          for (int c = 0; c < s.cases.size(); c++) sum += k.factor(s.cases.get(c)) * alone.get(c).disp[i][j];
          du = Math.max(du, Math.abs(sol.disp[i][j] - sum));
          umax = Math.max(umax, Math.abs(sum));
          if (sol.reaction[i] == null) continue;
          sum = 0;
          for (int c = 0; c < s.cases.size(); c++) sum += k.factor(s.cases.get(c)) * alone.get(c).reaction[i][j];
          dr = Math.max(dr, Math.abs(sol.reaction[i][j] - sum));
          fmax = Math.max(fmax, Math.abs(sum));
        }
      }
      for (Element e : s.elements) {
        ElementResult r = sol.of(e);
        for (double x : r.samples(50)) {
          for (Envelope.Effort f : Envelope.Effort.values()) {
            double sum = 0;
            for (int c = 0; c < s.cases.size(); c++) sum += k.factor(s.cases.get(c)) * f.at(alone.get(c).of(e), x);
            de = Math.max(de, Math.abs(f.at(r, x) - sum));
          }
        }
      }
      check(name + ": displacements = Σ factor × case", du / umax, 0);
      check(name + ": reactions = Σ factor × case", dr / fmax, 0);
      check(name + ": N, V, M along the elements = Σ factor × case", de / fmax, 0);
    }
  }

  /** The envelope's extremes are the combinations' extremes, solved one by one. */
  static void envelope() {
    Structure s = withCases();
    Envelope env = Envelope.of(s);
    check("envelope: solved", env.ok() ? 1 : 0, 1);
    List<Solution> each = s.combinations.stream().map(k -> Solver.solve(s, k.loading())).toList();
    double gap = 0;
    for (Element e : s.elements) {
      Envelope.Along a = env.of(e);
      for (Envelope.Effort f : Envelope.Effort.values()) {
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (Solution sol : each) {
          ElementResult r = sol.of(e);
          List<Double> xs = new ArrayList<>(r.extremaM());
          for (double x : r.samples(200)) xs.add(x);
          for (double x : xs) {
            hi = Math.max(hi, f.at(r, x));
            lo = Math.min(lo, f.at(r, x));
          }
        }
        String what = "envelope, element " + s.number(e) + ": " + f;
        check(what + " max = the largest of the combinations", a.max(f)[a.highest(f)], hi);
        check(what + " min = the smallest of the combinations", a.min(f)[a.lowest(f)], lo);
        for (int i = 0; i < a.x.length; i++) {
          double top = Double.NEGATIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
          for (Solution sol : each) {
            top = Math.max(top, f.at(sol.of(e), a.x[i]));
            bottom = Math.min(bottom, f.at(sol.of(e), a.x[i]));
          }
          gap = Math.max(gap, Math.max(Math.abs(a.max(f)[i] - top), Math.abs(a.min(f)[i] - bottom)) / 1e3);
        }
      }
    }
    check("envelope: at each abscissa, the extremes of the combinations", gap, 0);
    double rgap = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      if (env.maxReaction[i] == null) continue;
      for (int j = 0; j < 3; j++) {
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (Solution sol : each) {
          hi = Math.max(hi, sol.reaction[i][j]);
          lo = Math.min(lo, sol.reaction[i][j]);
        }
        rgap = Math.max(rgap, Math.abs(env.maxReaction[i][j] - hi) + Math.abs(env.minReaction[i][j] - lo));
      }
    }
    check("envelope: extreme reactions of the supports", rgap / 1e3, 0);
    double dmax = each.stream().mapToDouble(sol -> sol.maxDisplacement).max().orElse(-1);
    check("envelope: largest displacement", env.maxDisplacement, dmax);
    s.combinations.clear();
    check("envelope without a combination: no result", Envelope.of(s).ok() ? 1 : 0, 0);
  }

  /**
   * A vertical column from (0,0) to (0,L) in n elements, rectangle 50 × 100 steel, P down on its head. The head's
   * roller or slider has its normal along x: it holds the head sideways and lets it go down.
   */
  static Structure column(int n, Support foot, Support head) {
    Structure s = new Structure();
    Node prev = s.addNode(0, 0);
    prev.support = foot;
    for (int i = 1; i <= n; i++) {
      Node nx = s.addNode(0, L * i / n);
      s.addElement(prev, nx, false).section = new Section(Section.Kind.RECT, 50, 100, 0);
      prev = nx;
    }
    prev.support = head;
    prev.supportAngle = 0;
    prev.loadIn(g(s)).fy = -P;
    return s;
  }

  /**
   * Euler's four cases: Pcr = π²EI/Lf², Lf the buckling length. Each element is cut in 8 for the geometric
   * stiffness, which puts λcr a little above the exact value (an upper bound): within 0.1 %.
   */
  static void eulerColumns() {
    // fixed-pinned: k L is the root of tan kL = kL between π and 3π/2 (4.4934)
    double kl = root(x -> Math.tan(x) - x, Math.PI + 1e-9, 1.5 * Math.PI - 1e-9);
    String[] names = {"pinned-pinned column: Pcr = π²EI/L², Lf = L", "fixed-free column: Pcr = π²EI/4L², Lf = 2L",
        "fixed-pinned column: Pcr = 20.19 EI/L² (tan kL = kL), Lf = 0.699 L", "fixed-fixed column: Pcr = 4π²EI/L², Lf = L/2"};
    Support[][] ends = {{Support.PIVOT, Support.APPUI_SIMPLE}, {Support.ENCASTREMENT, Support.NONE}, {Support.ENCASTREMENT, Support.APPUI_SIMPLE},
        {Support.ENCASTREMENT, Support.GLISSIERE}};
    double[] lf = {1, 2, Math.PI / kl, 0.5};
    for (int i = 0; i < 4; i++) {
      Structure s = column(1, ends[i][0], ends[i][1]);
      Buckling b = Buckling.of(Solver.solve(s));
      double pcr = Math.PI * Math.PI * EI(s) / Math.pow(lf[i] * L, 2);
      check("buckling, " + names[i] + ": solved", b.ok() ? 1 : 0, 1);
      near("buckling, " + names[i] + ": λcr·P", b.factor() * P, pcr, 1e-3);
      check("buckling, " + names[i] + ": λcr·P above Pcr (upper bound)", b.factor() * P >= pcr ? 1 : 0, 1);
      Buckling.Member m = b.of(s.elements.get(0));
      near("buckling, " + names[i] + ": Lf/L", m.length(b.factor()) / L, lf[i], 1e-3);
      near("buckling, " + names[i] + ": Ncr = λcr·|N|", m.critical(b.factor()), b.factor() * P, 1e-9);
    }

    Structure pinned = column(1, Support.PIVOT, Support.APPUI_SIMPLE);
    Buckling b = Buckling.of(Solver.solve(pinned));
    double euler = Math.PI * Math.PI * EI(pinned) / (L * L);
    near("buckling, pinned-pinned column: mode 2 at 4π²EI/L²", b.modes.get(1).factor * P, 4 * euler, 1e-3);
    near("buckling, pinned-pinned column: mode 3 at 9π²EI/L²", b.modes.get(2).factor * P, 9 * euler, 3e-3);
    Element e = pinned.elements.get(0);
    Buckling.Mode one = b.modes.get(0);
    near("buckling, pinned-pinned column: mode 1 is sin(πy/L), at L/4", one.displacement(e, L / 4)[0] / one.displacement(e, L / 2)[0],
        Math.sin(Math.PI / 4), 1e-3);
    near("buckling, pinned-pinned column: mode 1 largest at mid-height, at 1", one.displacement(e, L / 2)[0], 1, 1e-9);
    check("buckling, pinned-pinned column: mode 1 sideways only", Math.abs(one.displacement(e, L / 3)[1]), 0);
    near("buckling, pinned-pinned column: Euler's load of the element π²EI/L²", b.of(e).euler(), euler, 1e-12);
    near("buckling, pinned-pinned column: slenderness L/i = L√12/h", b.of(e).slenderness(b.factor()), L * Math.sqrt(12) / 0.1, 1e-3);
    near("buckling, fixed-free column: mode 2 at 9π²EI/4L²", Buckling.of(Solver.solve(column(1, Support.ENCASTREMENT, Support.NONE))).modes.get(1).factor * P,
        9 * euler / 4, 1e-3);
    near("buckling, pinned-pinned column in 3 elements: π²EI/L²", Buckling.of(Solver.solve(column(3, Support.PIVOT, Support.APPUI_SIMPLE))).factor() * P,
        euler, 1e-4);

    // a load case and a combination of it: λcr is the case's over the factor
    Structure k = column(1, Support.PIVOT, Support.APPUI_SIMPLE);
    k.addCombination("1.5 G").setFactor(g(k), 1.5);
    near("buckling, combination 1.5 G: λcr = λcr(G) / 1.5", Buckling.of(Solver.solve(k, k.combinations.get(0).loading())).factor(),
        Buckling.of(Solver.solve(k, Loading.of(g(k)))).factor() / 1.5, 1e-9);

    // a rigid column on a pin, held at its head by a spring of stiffness k: it tips over at Pcr = kL (< π²EI/L²)
    double spring = 0.2 * euler / L;
    Structure tip = column(1, Support.PIVOT, Support.ELASTIQUE);
    tip.nodes.get(1).supportAngle = 90;
    tip.nodes.get(1).springT = spring;
    near("buckling, pinned column held by a spring at its head: Pcr = kL", Buckling.of(Solver.solve(tip)).factor() * P, spring * L, 1e-6);

    Structure pulled = column(1, Support.PIVOT, Support.APPUI_SIMPLE);
    load(pulled, 1).fy = P;
    check("buckling, column in traction: nothing to buckle", Buckling.of(Solver.solve(pulled)).status == Buckling.Status.NO_COMPRESSION ? 1 : 0, 1);
    Structure bent = beam(1);
    node(bent, 0).support = Support.ENCASTREMENT;
    load(bent, 1).fy = -P;
    check("buckling, cantilever bent only: nothing to buckle", Buckling.of(Solver.solve(bent)).status == Buckling.Status.NO_COMPRESSION ? 1 : 0, 1);
    Structure loose = column(1, Support.APPUI_SIMPLE, Support.NONE);
    check("buckling, mechanism: no buckling", Buckling.of(Solver.solve(loose)).status == Buckling.Status.NOT_SOLVED ? 1 : 0, 1);
  }

  /** Portal frames (Timoshenko & Gere, 2.8) and a truss whose bars buckle one by one. */
  static void bucklingOfFrames() {
    // h = l, every member the same I, P on the head of each column: the frame sways
    double h = L;
    for (boolean fixed : new boolean[] {true, false}) {
      Structure s = new Structure();
      Node a = s.addNode(0, 0), b = s.addNode(0, h), c = s.addNode(h, h), d = s.addNode(h, 0);
      a.support = d.support = fixed ? Support.ENCASTREMENT : Support.PIVOT;
      for (Node[] ends : new Node[][] {{a, b}, {b, c}, {c, d}}) s.addElement(ends[0], ends[1], false).section = new Section(Section.Kind.RECT, 50, 100, 0);
      b.loadIn(g(s)).fy = c.loadIn(g(s)).fy = -P;
      Buckling bk = Buckling.of(Solver.solve(s));
      // fixed feet: kh / tan kh = −6 (I_b h / I_c l); pinned feet: kh tan kh = 6 (I_b h / I_c l)
      double kh = fixed ? root(x -> x / Math.tan(x) + 6, Math.PI / 2 + 1e-9, Math.PI - 1e-9) : root(x -> x * Math.tan(x) - 6, 1e-9, Math.PI / 2 - 1e-9);
      String name = fixed ? "portal frame, fixed feet: sway at kh/tan kh = −6 (kh = 2.716)" : "portal frame, pinned feet: sway at kh·tan kh = 6 (kh = 1.350)";
      near("buckling, " + name, bk.factor() * P, kh * kh * EI(s) / (h * h), 1e-3);
      Buckling.Mode one = bk.modes.get(0);
      check("buckling, " + name + ": the beam sways, both heads alike", Math.abs(one.displacement(s.elements.get(1), 0)[0] - one.displacement(s.elements.get(1), h)[0]),
          0);
      check("buckling, " + name + ": the beam is not compressed", bk.of(s.elements.get(1)).compressed() ? 1 : 0, 0);
    }

    // the two-bar truss: both bars compressed by P/(2 sin α), each buckles alone between its hinges at π²EI/l²
    double a = 3, b = 2;
    Structure s = new Structure();
    Node l = s.addNode(-a, 0), top = s.addNode(0, b), r = s.addNode(a, 0);
    l.support = r.support = Support.PIVOT;
    top.loadIn(g(s)).fy = -P;
    s.addElement(l, top, true);
    s.addElement(top, r, true);
    Buckling bk = Buckling.of(Solver.solve(s));
    double len = Math.hypot(a, b), sin = b / len;
    Element bar = s.elements.get(0);
    double ncr = Math.PI * Math.PI * bar.material.pascals() * bar.section.inertia() / (len * len);
    near("buckling, two-bar truss: each bar alone, P·λcr/(2 sin α) = π²EI/l²", bk.factor() * P / (2 * sin), ncr, 1e-3);
    near("buckling, two-bar truss: Lf of a bar = its length", bk.of(bar).length(bk.factor()), len, 1e-3);
    near("buckling, two-bar truss: mode 2 the other bar, at the same λcr", bk.modes.get(1).factor, bk.factor(), 1e-6);
    check("buckling, two-bar truss: the apex does not move", Math.hypot(bk.modes.get(0).displacement(bar, len)[0], bk.modes.get(0).displacement(bar, len)[1]), 0);
  }

  /** The root of f between lo and hi, where f changes sign once (bisection). */
  static double root(java.util.function.DoubleUnaryOperator f, double lo, double hi) {
    double flo = f.applyAsDouble(lo);
    for (int i = 0; i < 200; i++) {
      double m = (lo + hi) / 2, fm = f.applyAsDouble(m);
      if (flo * fm <= 0) hi = m;
      else {
        lo = m;
        flo = fm;
      }
    }
    return (lo + hi) / 2;
  }

  /** Cases and combinations in a file, by name; an older file in one case; a case removed with its loads. */
  static void loadCaseFiles() {
    Structure s = withCases();
    s.combinations.get(1).name = "ELU vent dominant";
    String once = StructureFile.write(s);
    Structure back = StructureFile.read(once);
    check("file: read back identical", StructureFile.write(back).equals(once) ? 1 : 0, 1);
    check("file: 3 cases, 6 combinations", back.cases.size() * 10 + back.combinations.size(), 36);
    check("file: name with spaces", back.combinations.get(1).name.equals("ELU vent dominant") ? 1 : 0, 1);
    check("file: a serviceability combination", back.combinations.get(5).limit == Combination.Limit.SLS ? 1 : 0, 1);
    check("file: same reaction for a combination", Solver.solve(back, back.combinations.get(1).loading()).reaction[0][1],
        Solver.solve(s, s.combinations.get(1).loading()).reaction[0][1]);

    s.elements.get(0).grade = Grade.S355;
    s.elements.get(0).bucklingFactor = 0.7;
    s.deflectionLimit = 250;
    back = StructureFile.read(StructureFile.write(s));
    check("file: grade, buckling length, deflection limit", back.elements.get(0).grade == Grade.S355 && back.elements.get(0).bucklingFactor == 0.7
        && back.elements.get(1).grade == Grade.S235 && back.elements.get(1).bucklingFactor == 0 && back.deflectionLimit == 250 ? 1 : 0, 1);

    Structure v4 = StructureFile.read("RDM 4\ncase PERMANENT 0 G\ncase VARIABLE 0 Q\ncombination 1.35 1.5 ELU 1\nnode 0.0 0.0 ENCASTREMENT 90.0 0.0 0.0 0.0\n"
        + "node 2.0 0.0 NONE 90.0 0.0 0.0 0.0\nelem 1 2 0 0 IPE 200.0 0.0 0.0 0.0 0 210000.0 7850.0\n");
    check("file version 4: a combination, ULS, its name and factors", v4.combinations.size() == 1 && v4.combinations.get(0).limit == Combination.Limit.ULS
        && v4.combinations.get(0).name.equals("ELU 1") && v4.combinations.get(0).factor(v4.cases.get(1)) == 1.5 ? 1 : 0, 1);
    check("file version 4: S235, buckling length auto, L/300", v4.elements.get(0).grade == Grade.S235 && v4.elements.get(0).bucklingFactor == 0
        && v4.deflectionLimit == 300 ? 1 : 0, 1);

    Structure v3 = StructureFile.read("RDM 3\nselfweight 1\nnode 0.0 0.0 ENCASTREMENT 90.0 0.0 0.0 0.0 0.0 0.002 0.0 0.0 0.0 0.0\n"
        + "node 2.0 0.0 NONE 90.0 0.0 -1000.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0\nelem 1 2 0 0 RECT 50.0 100.0 0.0 0.0 0 70000.0 2700.0\n"
        + "force 1 0.5 0.0 -500.0 0\n");
    LoadCase only = v3.cases.get(0);
    check("file version 3: one case G, permanent", v3.cases.size() == 1 && only.name.equals("G") && only.type == LoadCase.Type.PERMANENT ? 1 : 0, 1);
    check("file version 3: self-weight in G", only.selfWeight ? 1 : 0, 1);
    check("file version 3: nodal load in G", v3.nodes.get(1).load(only).fy, -1000);
    check("file version 3: settlement in G", v3.nodes.get(0).load(only).settleN, 0.002);
    check("file version 3: force on the element in G", v3.elements.get(0).loadsIn(only).size(), 1);

    LoadCase w = s.cases.get(2);
    check("case removed", s.removeCase(w) ? 1 : 0, 1);
    check("case removed: no load left in it", s.elements.stream().mapToLong(e -> e.loadsIn(w).size()).sum()
        + s.nodes.stream().filter(n -> n.load(w).loaded()).count(), 0);
    check("case removed: no factor left in the combinations", s.combinations.stream().mapToDouble(k -> Math.abs(k.factor(w))).sum(), 0);
    check("case removed: the file reads back", StructureFile.read(StructureFile.write(s)).cases.size(), 2);
    Structure one = beam(1);
    check("the last case cannot be removed", one.removeCase(g(one)) ? 1 : 0, 0);

    // cut through a load: each half keeps it in its case
    Structure cut = withCases();
    LoadCase q = cut.cases.get(1);
    Solution before = Solver.solve(cut, Loading.of(q));
    cut.split(cut.elements.get(1), 0.3);
    check("cut at a force of case Q: it goes to the node, in Q", cut.nodes.get(4).load(q).fy, -6e3);
    check("cut: reaction of case Q unchanged", Solver.solve(cut, Loading.of(q)).reaction[0][1], before.reaction[0][1]);
  }

  static void readVersion1() {
    Structure s = StructureFile.read("RDM 1\nnode 0.0 0.0 PIVOT 90.0 0.0 0.0 0.0\nnode 6.0 0.0 APPUI_SIMPLE 90.0 0.0 0.0 0.0\n"
        + "elem 1 2 0 0 IPE 200.0 0.0 0.0 210000.0 0.0 -5000.0\n");
    Solution sol = Solver.solve(s);
    check("file version 1: distributed load read", sol.reaction[0][1], 5000 * 6 / 2.0);
    check("file version 1: steel", s.elements.get(0).material.rho(), 7850);
  }

  /** The templates' area, inertia and extreme fibre, against the textbook formulas (mm, then SI). */
  /**
   * The design checks: the steel sections' W_pl, A_v and class against the tables, a beam's bending and shear and
   * its deflection, a column's buckling (χ), a sway frame's buckling lengths, class 4, the limit states taken.
   */
  static void design() {
    // W_pl and A_v as tabulated (ArcelorMittal; EN 10210-2 for the hollow ones), cm³ and cm²
    Object[][] table = {{new Section(Section.Kind.IPE, 200, 0, 0), 220.6, 14.0}, {new Section(Section.Kind.IPE, 600, 0, 0), 3512.0, 83.78},
        {new Section(Section.Kind.HEA, 200, 0, 0), 429.5, 18.08}, {new Section(Section.Kind.HEB, 300, 0, 0), 1869.0, 47.43},
        {new Section(Section.Kind.IPE, 200, 0, 0, 0, true), 44.61, Double.NaN}, {new Section(Section.Kind.UPN, 200, 0, 0), 228.0, Double.NaN},
        {new Section(Section.Kind.SHS, 100, 5, 0), 66.4, Double.NaN}, {new Section(Section.Kind.CHS, 114.3, 6.3, 0), 73.6, Double.NaN}};
    for (Object[] row : table) {
      Section sec = (Section) row[0];
      SteelSection st = new SteelSection(sec);
      near(sec.label() + ": W_pl as tabulated", st.Wpl * 1e6, (Double) row[1], 6e-3);
      if (!Double.isNaN((Double) row[2])) near(sec.label() + ": A_v as tabulated", st.Av * 1e4, (Double) row[2], 3e-3);
    }
    // hot-finished RHS: the slices give back the A and I of the catalogue (EN 10210-2 Annex A)
    Section rhs = new Section(Section.Kind.RHS, 200, 100, 8, 0, false);
    SteelSection rhsSteel = new SteelSection(rhs);
    near("RHS 200 × 100 × 8: W_pl 282 cm³ (Annex A)", rhsSteel.Wpl * 1e6, 282, 2e-3);
    near("RHS 200 × 100 × 8: W_el from the catalogue's I", rhsSteel.Wel * 1e6, 223.4, 1e-3);

    SteelSection ipe600 = new SteelSection(new Section(Section.Kind.IPE, 600, 0, 0));
    check("IPE 600 S235: class 1 in bending", ipe600.classify(0, 100e3, 235e6), 1);
    check("IPE 600 S235: class 4 in compression", ipe600.classify(-100e3, 0, 235e6), 4);
    check("IPE 600 S235: class 1 in traction", ipe600.classify(100e3, 0, 235e6), 1);
    SteelSection ipe200 = new SteelSection(new Section(Section.Kind.IPE, 200, 0, 0));
    // its web, c / t = 159 / 5.6 = 28.4: within 33 ε in S235, between 33 ε and 38 ε in S355
    check("IPE 200 S235: class 1 in compression", ipe200.classify(-100e3, 0, 235e6), 1);
    check("IPE 200 S355: class 2 in compression", ipe200.classify(-100e3, 0, 355e6), 2);
    check("CHS 114.3 × 6.3: class 1", new SteelSection(new Section(Section.Kind.CHS, 114.3, 6.3, 0)).classify(-100e3, 0, 355e6), 1);
    check("tube 100 × 1: class 4 (d/t = 100 > 90 ε²)", new SteelSection(new Section(Section.Kind.TUBE, 100, 1, 0)).classify(-1e3, 0, 235e6), 4);
    check("S355 above 40 mm: 335 MPa", Grade.S355.fy(41), 335);

    // a simply supported IPE 200, 6 m, in two elements, 10 kN/m, no combination: M = 45 kN·m, V = 30 kN, δ = 5 q L⁴ / 384 EI
    Structure s = new Structure();
    Node a = s.addNode(0, 0), m = s.addNode(3, 0), b = s.addNode(6, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    for (Element e : List.of(s.addElement(a, m, false), s.addElement(m, b, false))) uniform(s, e, -10e3);
    Design d = Design.of(s);
    check("beam: the loads as given for both limit states", !d.ulsGiven && !d.slsGiven && d.uls.size() == 1 && d.sls.size() == 1 ? 1 : 0, 1);
    Design.Member left = d.of(s.elements.get(0));
    near("beam: M/Mc = 45 kN·m / (W_pl 235 MPa)", left.worst(Design.Check.BENDING).ratio, 45e3 / (ipe200.Wpl * 235e6), 1e-6);
    near("beam: V/Vpl = 30 kN / (A_v 235 / √3 MPa)", left.worst(Design.Check.SHEAR).ratio, 30e3 / (ipe200.Av * 235e6 / Math.sqrt(3)), 1e-6);
    check("beam: no N, no buckling", left.worst(Design.Check.AXIAL).ratio + (left.worst(Design.Check.BUCKLING).found() ? 1 : 0), 0);
    check("beam: governed by bending (with N = 0, the same)", left.ratio(), left.worst(Design.Check.BENDING).ratio);
    check("beam: one span of two elements", d.spans.size() == 1 && d.spans.get(0).elements.size() == 2 && !d.spans.get(0).cantilever ? 1 : 0, 1);
    double EI = Material.STEEL.pascals() * s.elements.get(0).section.inertia();
    near("beam: deflection 5 q L⁴ / 384 EI, at mid-span", Math.abs(d.spans.get(0).deflection), 5 * 10e3 * Math.pow(6, 4) / (384 * EI), 1e-9);
    near("beam: where it is largest, 3 m", d.spans.get(0).at, 3, 1e-9);
    near("beam: allowed L/300 = 20 mm", d.spans.get(0).allowed, 0.02, 1e-12);
    s.elements.get(0).material = Material.PRESETS.get(1);
    check("aluminium: not checked", Design.of(s).of(s.elements.get(0)).checked() ? 1 : 0, 0);

    // a cantilever, 2 m, P at its tip: P L³ / 3 EI, allowed 2 L / 300
    Structure c = new Structure();
    Node root = c.addNode(0, 0), tip = c.addNode(2, 0);
    root.support = Support.ENCASTREMENT;
    c.addElement(root, tip, false);
    c.nodes.get(1).loadIn(g(c)).fy = -5e3;
    Design dc = Design.of(c);
    Design.Span arm = dc.spans.get(0);
    near("cantilever: P L³ / 3 EI at its tip", Math.abs(arm.deflection), 5e3 * 8 / (3 * Material.STEEL.pascals() * c.elements.get(0).section.inertia()), 1e-9);
    check("cantilever: from its fixed end, allowed 2 L / 300", arm.cantilever && arm.from == root && Math.abs(arm.allowed - 4.0 / 300) < 1e-12 ? 1 : 0, 1);

    // Euler's column: tube 60 × 4 (curve c), 3 m, 40 kN; with L_cr = L, then from the structure's buckling
    Structure col = Examples.ALL.get("eulerColumn").get();
    Element tube = col.elements.get(0);
    tube.bucklingFactor = 1;
    Design dk = Design.of(col);
    SteelSection ts = new SteelSection(tube.section);
    double Ncr = Math.PI * Math.PI * Material.STEEL.pascals() * tube.section.inertia() / 9, Npl = ts.A * 235e6, lambda = Math.sqrt(Npl / Ncr);
    double phi = 0.5 * (1 + 0.49 * (lambda - 0.2) + lambda * lambda), chi = 1 / (phi + Math.sqrt(phi * phi - lambda * lambda));
    Design.Worst w = dk.of(tube).worst(Design.Check.BUCKLING);
    near("column: λ̄ = √(A fy / Ncr) = 1.609", w.slenderness, 1.609, 1e-3);
    near("column: χ, curve c", w.chi, chi, 1e-9);
    near("column: χ = 0.2816 (λ̄ 1.609, curve c)", w.chi, 0.2816, 1e-3);
    near("column: N Ed / (χ A fy)", w.ratio, 40e3 / (chi * Npl), 1e-9);
    tube.bucklingFactor = 0;
    near("column: L_cr from the buckling analysis, the same", Design.of(col).of(tube).worst(Design.Check.BUCKLING).ratio, 40e3 / (chi * Npl), 1e-3);

    // the sway portal: each column's buckling length more than its height (sway), taken from the structure's mode
    Structure portal = Examples.ALL.get("bucklingPortal").get();
    Design dp = Design.of(portal);
    Design.Worst column = dp.of(portal.elements.get(0)).worst(Design.Check.BUCKLING);
    check("sway portal: the column's L_cr above its height", column.found() && column.Lcr > 4 * 1.05 ? 1 : 0, 1);
    check("sway portal: λcr = 4 gives the column's N_cr", Buckling.of(dp.ulsSolutions.get(0)).factor() * -column.N, column.Ncr);

    // the Warren truss: its bars buckle between their nodes at most (L_cr ≤ L), and do not bend (no span)
    Structure truss = Examples.ALL.get("warren").get();
    Design dt = Design.of(truss);
    boolean within = true;
    int compressed = 0;
    for (Element e : truss.elements) {
      Design.Worst wb = dt.of(e).worst(Design.Check.BUCKLING);
      if (!wb.found()) continue;
      compressed++;
      within &= wb.Lcr <= e.length() * (1 + 1e-9);
    }
    check("truss: every compressed bar's L_cr at most its length", compressed > 0 && within ? 1 : 0, 1);
    check("truss: no span (bars only)", dt.spans.size(), 0);

    // an IPE 600 strut: class 4 in compression, not checked
    Structure strut = Examples.ALL.get("eulerColumn").get();
    strut.elements.get(0).section = new Section(Section.Kind.IPE, 600, 0, 0);
    check("IPE 600 strut S235: class 4, not checked", Double.isNaN(Design.of(strut).of(strut.elements.get(0)).ratio()) ? 1 : 0, 1);

    // the portal with cases: its 4 ULS combinations for the strength, its 2 SLS ones for the deflections
    Structure pc = Examples.ALL.get("portalCases").get();
    Design dpc = Design.of(pc);
    check("cases: 4 ULS and 2 SLS combinations taken", dpc.ulsGiven && dpc.slsGiven && dpc.uls.size() == 4 && dpc.sls.size() == 2 ? 1 : 0, 1);
    Design.Span roof = dpc.spanOf(pc.elements.get(1));
    double worst = 0;
    for (var k : pc.combinations(Combination.Limit.SLS)) {
      Solution sol = Solver.solve(pc, k.loading());
      ElementResult r = sol.of(pc.elements.get(1));
      for (double x : r.samples(24)) {
        // across the beam (horizontal): its deflection from the line through its ends, in y
        double ya = sol.disp[1][1], yb = sol.disp[2][1];
        worst = Math.max(worst, Math.abs(r.displacement(x)[1] - (ya + (yb - ya) * x / r.L)));
      }
    }
    near("cases: the roof's deflection, the worst SLS combination", Math.abs(roof.deflection), worst, 1e-9);
  }

  /** Points typed while drawing: absolute, relative, polar, a length towards the pointer; commas as each language has them. */
  static void typedPoints() {
    Tr.Lang was = Tr.lang();
    try {
      Tr.use(Tr.Lang.EN);
      double[] from = {2, 3}, none = null;
      point("typed x y", PointEntry.parse("4 5", none, none), 4, 5);
      point("typed x,y (English: a comma separates)", PointEntry.parse("4,5", none, none), 4, 5);
      point("typed x;y with decimals", PointEntry.parse(" 1.5 ; -2 ", none, none), 1.5, -2);
      point("typed @dx dy", PointEntry.parse("@1 -2", from, none), 3, 1);
      point("typed L<a", PointEntry.parse("4<90", from, none), 2, 7);
      point("typed @L<a", PointEntry.parse("@2<180", from, none), 0, 3);
      point("typed L towards the pointer", PointEntry.parse("5", new double[] {0, 0}, new double[] {6, 8}), 3, 4);
      check("typed: nothing to measure from", PointEntry.parse("@1 1", none, none) == null && PointEntry.parse("3", none, new double[] {1, 1}) == null
          && PointEntry.parse("3<45", none, none) == null ? 1 : 0, 1);
      check("typed: not a point", PointEntry.parse("a b", from, from) == null && PointEntry.parse("1 2 3", from, from) == null
          && PointEntry.parse("", from, from) == null ? 1 : 0, 1);
      Tr.use(Tr.Lang.FR);
      point("typed in French: 2,5 3 (a decimal comma)", PointEntry.parse("2,5 3", none, none), 2.5, 3);
      point("typed in French: 2,5;-1,25", PointEntry.parse("2,5;-1,25", none, none), 2.5, -1.25);
    } finally {
      Tr.use(was);
    }
  }

  static void point(String what, double[] got, double x, double y) {
    check(what + ": x", got == null ? Double.NaN : got[0], x);
    check(what + ": y", got == null ? Double.NaN : got[1], y);
  }

  static void sections() {
    catalogues();
    Section rect = new Section(Section.Kind.RECT, 50, 100, 0);
    section("rectangle 50 × 100", rect, 5000, 50 * Math.pow(100, 3) / 12, 50);
    section("rectangle 50 × 100 turned", rect.turned(true), 5000, 100 * Math.pow(50, 3) / 12, 25);

    section("rectangular hollow 200 × 100 × 8", new Section(Section.Kind.BOX, 200, 100, 8, 0, false), 200 * 100 - 184 * 84,
        (100 * Math.pow(200, 3) - 84 * Math.pow(184, 3)) / 12, 100);

    double h = 300, b = 150, tw = 7, tf = 11;
    Section i = new Section(Section.Kind.I_SECTION, h, b, tw, tf, false);
    section("I 300 × 150 × 7 × 11", i, b * h - (b - tw) * (h - 2 * tf), (b * Math.pow(h, 3) - (b - tw) * Math.pow(h - 2 * tf, 3)) / 12, h / 2);
    section("I 300 × 150 × 7 × 11 turned", i.turned(true), b * h - (b - tw) * (h - 2 * tf),
        2 * tf * Math.pow(b, 3) / 12 + (h - 2 * tf) * Math.pow(tw, 3) / 12, b / 2);

    // T: flange 100 × 10 on top, web 8 × 90 under it
    double aF = 100 * 10, aW = 8 * 90, yc = (aF * 95 + aW * 45) / (aF + aW);
    section("T 100 × 100 × 8 × 10", new Section(Section.Kind.T_SECTION, 100, 100, 8, 10, false), aF + aW,
        100 * Math.pow(10, 3) / 12 + aF * Math.pow(95 - yc, 2) + 8 * Math.pow(90, 3) / 12 + aW * Math.pow(45 - yc, 2), Math.max(yc, 100 - yc));

    // U turned: bending about the axis parallel to the web, the centroid nearer the web
    h = 200;
    b = 75;
    tw = 8.5;
    tf = 11.5;
    double aWeb = h * tw, aFl = (b - tw) * tf, zc = (aWeb * tw / 2 + 2 * aFl * (tw + (b - tw) / 2)) / (aWeb + 2 * aFl);
    double iU = h * Math.pow(tw, 3) / 12 + aWeb * Math.pow(tw / 2 - zc, 2) + 2 * (tf * Math.pow(b - tw, 3) / 12 + aFl * Math.pow(tw + (b - tw) / 2 - zc, 2));
    section("U 200 × 75 turned", new Section(Section.Kind.CHANNEL, h, b, tw, tf, true), aWeb + 2 * aFl, iU, Math.max(zc, b - zc));

    section("round tube 60 × 4", Section.BAR_DEFAULT, Math.PI * (60 * 60 - 52 * 52) / 4, Math.PI * (Math.pow(60, 4) - Math.pow(52, 4)) / 64, 30);
    check("hollow 200 × 100 × 60: invalid", new Section(Section.Kind.BOX, 200, 100, 60, 0, false).problem() != null ? 1 : 0, 1);
    check("I with a web wider than its flanges: invalid", new Section(Section.Kind.I_SECTION, 300, 100, 120, 10, false).problem() != null ? 1 : 0, 1);
    check("round: does not turn", new Section(Section.Kind.ROUND, 20, 0, 0).turned(true).turned() ? 1 : 0, 0);

    // the same A, I, v typed in: the same results
    Structure a = new Structure(), c = new Structure();
    for (Structure st : new Structure[] {a, c}) {
      Node n0 = st.addNode(0, 0), n1 = st.addNode(3, 0);
      n0.support = Support.ENCASTREMENT;
      n1.loadIn(g(st)).fy = -P;
      st.addElement(n0, n1, false);
    }
    Section t = new Section(Section.Kind.T_SECTION, 100, 100, 8, 10, false);
    a.elements.get(0).section = t;
    c.elements.get(0).section = new Section(Section.Kind.CUSTOM, t.area() * 1e4, t.inertia() * 1e8, t.fibre() * 1e2);
    Solution sa = Solver.solve(a), sc = Solver.solve(c);
    check("T and its A, I, v typed in: same deflection", sa.disp[1][1], sc.disp[1][1]);
    check("T and its A, I, v typed in: same σ max", sa.of(a.elements.get(0)).sigmaMax(), sc.of(c.elements.get(0)).sigmaMax());

    // kept in a file, turned or not; a version 2 file still reads
    a.elements.get(0).section = new Section(Section.Kind.CHANNEL, 200, 75, 8.5, 11.5, true);
    Section back = StructureFile.read(StructureFile.write(a)).elements.get(0).section;
    check("turned U read back from the file", back.equals(a.elements.get(0).section) ? 1 : 0, 1);
    Structure v2 = StructureFile.read("RDM 2\nselfweight 0\nnode 0.0 0.0 ENCASTREMENT 90.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0\n"
        + "node 2.0 0.0 NONE 90.0 0.0 -1000.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0\nelem 1 2 0 0 RECT 50.0 100.0 0.0 70000.0 2700.0\n");
    check("file version 2: section", v2.elements.get(0).section.inertia(), 50 * Math.pow(100, 3) / 12 * 1e-12);
    check("file version 2: material", v2.elements.get(0).material.rho(), 2700);
  }

  /**
   * The catalogues against their sources: rows of the ArcelorMittal sales programme 2024-1 as printed there, every
   * rolled row against its own dimensions, and the hollow sections' EN 10210-2 Annex A formulae against published
   * tables (rounded to 3 or 4 figures).
   */
  static void catalogues() {
    // A (cm²), Iy (cm⁴), Wel.y (cm³), Iz (cm⁴), Wel.z (cm³): the sales programme's pages of properties
    rolled(new Section(Section.Kind.IPE, 300, 0, 0), 53.8, 8356, 557, 603.7, 80.5);
    rolled(new Section(Section.Kind.HEA, 200, 0, 0), 53.8, 3692, 388.6, 1335, 133.5);
    rolled(new Section(Section.Kind.HEA, 400, 0, 0), 159, 45060, 2311, 8563, 570.9);
    rolled(new Section(Section.Kind.HEB, 160, 0, 0), 54.3, 2491, 311.4, 889.2, 111.1);
    rolled(new Section(Section.Kind.HEB, 300, 0, 0), 149.1, 25160, 1677, 8562, 570.8);
    rolled(new Section(Section.Kind.UPN, 100, 0, 0), 13.5, 206, 41.2, 29.3, 8.49);
    rolled(new Section(Section.Kind.UPN, 200, 0, 0), 32.2, 1910, 191, 148, 27);
    rolled(new Section(Section.Kind.UPN, 300, 0, 0), 58.8, 8030, 535, 495, 67.8);
    // turned, the U's centroid is ys = 2,0 cm from the back of its web (to the table's 1 mm)
    near("UPN 200 turned: centroid at ys", new Section(Section.Kind.UPN, 200, 0, 0).turned(true).centroid(), 20, 0.025);

    // each row against its dimensions: A with the root fillets, v = h/2 (and b/2 turned, but for the U)
    for (Section.Kind k : new Section.Kind[] {Section.Kind.IPE, Section.Kind.HEA, Section.Kind.HEB, Section.Kind.UPN}) {
      int bad = 0;
      for (Section s : Section.sizes(k)) {
        Section like = s.like();
        double h = like.p1(), b = like.p2(), tw = like.p3(), tf = like.p4(), r = s.cornerRadius();
        double a = (2 * b * tf + (h - 2 * tf) * tw + (4 - Math.PI) * r * r) * 1e-6;
        if (k != Section.Kind.UPN && Math.abs(s.area() / a - 1) > 0.01) bad++;
        if (Math.abs(s.fibre() / (h / 2e3) - 1) > 0.005) bad++;
        if (k != Section.Kind.UPN && Math.abs(s.turned(true).fibre() / (b / 2e3) - 1) > 0.005) bad++;
      }
      check(k + ": " + Section.sizes(k).size() + " rows consistent with their dimensions", bad, 0);
    }

    // CHS: EN 10210-2:2019 Table B.1; SHS, RHS: the Steel for Life Blue Book's hot-finished tables
    hollow(new Section(Section.Kind.CHS, 60.3, 4, 0), 7.07, 28.2, 9.34, 28.2, 9.34);
    hollow(new Section(Section.Kind.CHS, 114.3, 6.3, 0), 21.4, 313, 54.7, 313, 54.7);
    hollow(new Section(Section.Kind.CHS, 168.3, 8, 0), 40.3, 1297, 154, 1297, 154);
    hollow(new Section(Section.Kind.CHS, 219.1, 10, 0), 65.7, 3598, 328, 3598, 328);
    hollow(new Section(Section.Kind.CHS, 323.9, 12.5, 0), 122, 14850, 917, 14850, 917);
    hollow(new Section(Section.Kind.SHS, 100, 5, 0), 18.7, 279, 55.9, 279, 55.9);
    hollow(new Section(Section.Kind.SHS, 200, 10, 0), 74.9, 4470, 447, 4470, 447);
    hollow(new Section(Section.Kind.SHS, 300, 12.5, 0), 142, 19400, 1300, 19400, 1300);
    hollow(new Section(Section.Kind.RHS, 120, 80, 5, 0, false), 18.7, 365, 60.9, 193, 48.2);
    hollow(new Section(Section.Kind.RHS, 200, 100, 8, 0, false), 44.8, 2230, 223, 739, 148);
    hollow(new Section(Section.Kind.RHS, 300, 200, 10, 0, false), 94.9, 11800, 788, 6280, 628);
    hollow(new Section(Section.Kind.RHS, 400, 200, 12.5, 0, false), 142, 29100, 1450, 9740, 974);
    int bad = 0;
    for (Section.Kind k : new Section.Kind[] {Section.Kind.SHS, Section.Kind.RHS, Section.Kind.CHS}) {
      for (Section s : Section.sizes(k)) if (s.problem() != null || !(s.inertia() > 0)) bad++;
    }
    check("hollow sections: every size is a section", bad, 0);

    Structure f = new Structure();
    f.addElement(f.addNode(0, 0), f.addNode(2, 0), false).section = new Section(Section.Kind.HEB, 300, 0, 0, 0, true);
    check("turned HEB 300 read back from the file", StructureFile.read(StructureFile.write(f)).elements.get(0).section.equals(f.elements.get(0).section) ? 1 : 0, 1);
  }

  /** A rolled section's table values, as printed: exactly, about both axes (Wel = I / v). */
  private static void rolled(Section s, double area, double iy, double wy, double iz, double wz) {
    Section t = s.turned(true);
    check(s.label() + ": A", s.area(), area * 1e-4);
    check(s.label() + ": Iy", s.inertia(), iy * 1e-8);
    check(s.label() + ": Wel.y", s.inertia() / s.fibre(), wy * 1e-6);
    check(s.label() + ": Iz", t.inertia(), iz * 1e-8);
    check(s.label() + ": Wel.z", t.inertia() / t.fibre(), wz * 1e-6);
  }

  /** A hollow section against a table that rounds to 3 figures: within half a percent, about both axes. */
  private static void hollow(Section s, double area, double iy, double wy, double iz, double wz) {
    Section t = s.turned(true);
    near(s.label() + ": A", s.area(), area * 1e-4, 0.005);
    near(s.label() + ": Iy", s.inertia(), iy * 1e-8, 0.005);
    near(s.label() + ": Wel.y", s.inertia() / s.fibre(), wy * 1e-6, 0.005);
    near(s.label() + ": Iz", t.inertia(), iz * 1e-8, 0.005);
    near(s.label() + ": Wel.z", t.inertia() / t.fibre(), wz * 1e-6, 0.005);
  }

  private static void section(String name, Section s, double area, double inertia, double fibre) {
    check(name + ": A", s.area(), area * 1e-6);
    check(name + ": I", s.inertia(), inertia * 1e-12);
    check(name + ": v", s.fibre(), fibre * 1e-3);
  }

  /**
   * Every language has the same texts, with the same arguments; every key the code asks for exists, including
   * the ones it builds from enum names ("tool." + name()…).
   */
  static void texts() {
    Map<String, String> en = Tr.Lang.EN.table();
    for (Tr.Lang l : Tr.Lang.values()) {
      Map<String, String> t = l.table();
      Set<String> missing = new TreeSet<>(en.keySet()), extra = new TreeSet<>(t.keySet());
      missing.removeAll(t.keySet());
      extra.removeAll(en.keySet());
      check(l + ": missing texts " + missing, missing.size(), 0);
      check(l + ": extra texts " + extra, extra.size(), 0);
      int wrong = 0;
      for (var e : t.entrySet()) {
        String ref = en.get(e.getKey());
        if (ref != null && !specifiers(ref).equals(specifiers(e.getValue()))) {
          System.out.println("       different arguments: " + l + " " + e.getKey());
          wrong++;
        }
      }
      check(l + ": same arguments as in English", wrong, 0);
    }
    Set<String> used = new TreeSet<>();
    try (var files = Files.walk(Path.of("src"))) {
      for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        Matcher m = Pattern.compile("\\btr\\(\"([\\w.]+)\"").matcher(Files.readString(f));
        // "tool." + name(): the prefixes are checked below, with each name
        while (m.find()) if (!m.group(1).endsWith(".")) used.add(m.group(1));
      }
    } catch (java.io.IOException ex) {
      throw new RuntimeException(ex);
    }
    check("texts are asked for in src", used.size() > 100 ? 1 : 0, 1);
    try {
      for (String c : new String[] {"rdm.ui.Tool", "rdm.ui.MainFrame$Diagram"}) {
        String prefix = c.endsWith("Tool") ? "tool." : "diagram.";
        for (Object v : Class.forName(c).getEnumConstants()) {
          used.add(prefix + ((Enum<?>) v).name());
          if (prefix.equals("tool.")) used.add(prefix + ((Enum<?>) v).name() + ".help");
        }
      }
    } catch (ClassNotFoundException ex) {
      throw new RuntimeException(ex);
    }
    for (Support v : Support.values()) used.add("support." + v.name());
    for (LoadCase.Type v : LoadCase.Type.values()) used.add("case.type." + v.name());
    // chosen in the code by a condition, not written as tr("…")
    used.addAll(List.of("insp.caseHelp", "insp.combinationHelp", "insp.envelopeHelp", "cases.colName", "cases.colType", "cases.colSelfWeight"));
    for (Section.Kind v : Section.Kind.values()) {
      used.add("section." + v.name());
      if (v != Section.Kind.IPE) used.add("section.label." + v.name());
    }
    for (Material m : Material.PRESETS) used.add("material." + m.name());
    used.add("material." + Material.of(1, 1).name());
    for (String name : Examples.ALL.keySet()) used.add("example." + name);
    used.removeAll(en.keySet());
    check("texts asked for but missing " + used, used.size(), 0);
  }

  private static List<String> specifiers(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("%[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]").matcher(text);
    while (m.find()) out.add(m.group());
    return out;
  }

  /** got within a relative tolerance of expected (a buckling load of cut elements, a value from a table, rounded). */
  static void near(String what, double got, double expected, double tolerance) {
    checks++;
    if (Math.abs(got - expected) <= tolerance * Math.abs(expected)) {
      System.out.printf("ok     %s%n", what);
    } else {
      failures++;
      System.out.printf("FAIL   %s: got %.12g, expected %.12g (± %.1g)%n", what, got, expected, tolerance);
    }
  }

  static void check(String what, double got, double expected) {
    checks++;
    double tol = 1e-9 * Math.max(1, Math.abs(expected));
    boolean ok = Math.abs(got - expected) <= Math.max(tol, 1e-9 * Math.abs(got));
    if (!ok) {
      failures++;
      System.out.printf("FAIL   %s: got %.12g, expected %.12g%n", what, got, expected);
    } else {
      System.out.printf("ok     %s%n", what);
    }
  }
}
