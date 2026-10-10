package rdm;

import rdm.io.StructureFile;
import rdm.model.Element;
import rdm.model.Examples;
import rdm.model.Node;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Solution;
import rdm.solver.Solver;

/** The solver against closed-form results of the RdM formulary. Run: verify.cmd (exit code 1 on a failure). */
public final class Verify {
  private static int failures, checks;

  public static void main(String[] args) {
    cantilever();
    simplySupported();
    fixedFixed();
    proppedCantilever();
    twoBarTruss();
    skewedRoller();
    gerber();
    mechanisms();
    examples();
    roundTrip();
    System.out.printf("%d vérifications, %d échec(s)%n", checks, failures);
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

  static void cantilever() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    node(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("console : flèche PL³/3EI", sol.disp[1][1], -P * L * L * L / (3 * EI));
    check("console : rotation PL²/2EI", sol.disp[1][2], -P * L * L / (2 * EI));
    check("console : moment d'encastrement", sol.reaction[0][2], P * L);
    check("console : Mf(0) = −PL", sol.of(s.elements.get(0)).M(0), -P * L);
    check("console : h = 0", sol.h, 0);
  }

  static void simplySupported() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).qy = -Q;
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    double EI = EI(s);
    check("deux appuis : flèche 5qL⁴/384EI (entre les nœuds)", r.deflection(L / 2), -5 * Q * Math.pow(L, 4) / (384 * EI));
    check("deux appuis : Mf max qL²/8", r.M(r.momentPeak()), Q * L * L / 8);
    check("deux appuis : abscisse du max L/2", r.momentPeak(), L / 2);
    check("deux appuis : réaction qL/2", sol.reaction[1][1], Q * L / 2);
    check("deux appuis : T(0) = −qL/2", r.T(0), -Q * L / 2);
    check("deux appuis : h = 0", sol.h, 0);
    equilibrium("deux appuis", s, sol);
  }

  static void fixedFixed() {
    Structure s = beam(2);
    node(s, 0).support = node(s, 2).support = Support.ENCASTREMENT;
    for (Element e : s.elements) e.qy = -Q;
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("bi-encastrée : flèche qL⁴/384EI", sol.disp[1][1], -Q * Math.pow(L, 4) / (384 * EI));
    check("bi-encastrée : Mf aux encastrements −qL²/12", sol.of(s.elements.get(0)).M(0), -Q * L * L / 12);
    check("bi-encastrée : Mf au milieu qL²/24", sol.of(s.elements.get(0)).M(L / 2), Q * L * L / 24);
    check("bi-encastrée : h = 3", sol.h, 3);
  }

  static void proppedCantilever() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).qy = -Q;
    Solution sol = Solver.solve(s);
    check("encastrée–appuyée : réaction de l'appui 3qL/8", sol.reaction[1][1], 3 * Q * L / 8);
    check("encastrée–appuyée : réaction de l'encastrement 5qL/8", sol.reaction[0][1], 5 * Q * L / 8);
    check("encastrée–appuyée : moment d'encastrement qL²/8", sol.reaction[0][2], Q * L * L / 8);
    check("encastrée–appuyée : h = 1", sol.h, 1);
  }

  /** Two bars at α from the horizontal, pinned at their feet, a load P down at the apex. */
  static void twoBarTruss() {
    double a = 3, b = 2;
    Structure s = new Structure();
    Node l = s.addNode(-a, 0), top = s.addNode(0, b), r = s.addNode(a, 0);
    l.support = r.support = Support.PIVOT;
    top.fy = -P;
    s.addElement(l, top, true);
    s.addElement(top, r, true);
    Solution sol = Solver.solve(s);
    double len = Math.hypot(a, b), sin = b / len;
    double Nexp = -P / (2 * sin);
    Element e = s.elements.get(0);
    double EA = e.material.pascals() * e.section.area();
    check("treillis 2 barres : N = −P/(2 sin α)", sol.of(e).N(0), Nexp);
    // Castigliano: δ = Σ N² L / (EA P)
    check("treillis 2 barres : flèche Σ N²L/(EA·P)", sol.disp[1][1], -2 * Nexp * Nexp * len / (EA * P));
    check("treillis 2 barres : h = 0", sol.h, 0);
    check("treillis 2 barres : pas de rotation propre au sommet", Double.isNaN(sol.disp[1][2]) ? 0 : 1, 0);
  }

  /** Pivot at the left, roller of normal at 45° at the right, P down at mid-span. */
  static void skewedRoller() {
    Structure s = beam(2);
    node(s, 0).support = Support.PIVOT;
    node(s, 2).support = Support.APPUI_SIMPLE;
    node(s, 2).supportAngle = 45;
    node(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    check("appui incliné : Rx à droite P/2", sol.reaction[2][0], P / 2);
    check("appui incliné : Ry à droite P/2", sol.reaction[2][1], P / 2);
    check("appui incliné : Rx à gauche −P/2", sol.reaction[0][0], -P / 2);
    double[] u = sol.disp[2];
    check("appui incliné : déplacement ⟂ à la normale", u[0] * Math.cos(Math.PI / 4) + u[1] * Math.sin(Math.PI / 4), 0);
    equilibrium("appui incliné", s, sol);
  }

  /** Gerber beam: fixed at 0, hinge at 2, roller at 4, P at 3. */
  static void gerber() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), hinge = s.addNode(2, 0), load = s.addNode(3, 0), roller = s.addNode(4, 0);
    a.support = Support.ENCASTREMENT;
    roller.support = Support.APPUI_SIMPLE;
    load.fy = -P;
    s.addElement(a, hinge, false).hingeB = true;
    s.addElement(hinge, load, false);
    s.addElement(load, roller, false);
    Solution sol = Solver.solve(s);
    check("Gerber : réaction de l'appui P/2", sol.reaction[3][1], P / 2);
    check("Gerber : moment d'encastrement P/2 × 2", sol.reaction[0][2], P);
    check("Gerber : Mf nul à la rotule", sol.of(s.elements.get(0)).M(2), 0);
    check("Gerber : h = 0", sol.h, 0);
  }

  static void mechanisms() {
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.APPUI_SIMPLE;
    Solution sol = Solver.solve(s);
    check("deux rouleaux : mécanisme", sol.status == Solution.Status.MECHANISM ? 1 : 0, 1);
    check("deux rouleaux : m = 1", sol.m, 1);

    Structure q = new Structure();
    Node a = q.addNode(0, 0), b = q.addNode(2, 0), c = q.addNode(2, 2), d = q.addNode(0, 2);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    q.addElement(a, b, true);
    q.addElement(b, c, true);
    q.addElement(c, d, true);
    q.addElement(d, a, true);
    Solution qs = Solver.solve(q);
    check("carré sans diagonale : m = 1", qs.m, 1);
    q.addElement(a, c, true);
    Solution braced = Solver.solve(q);
    check("carré contreventé : m = 0, h = 0", braced.m + 10 * braced.h, 0);
  }

  static void examples() {
    for (var ex : Examples.ALL.entrySet()) {
      Structure s = ex.getValue().get();
      Solution sol = Solver.solve(s);
      check(ex.getKey() + " : résolu", sol.ok() ? 1 : 0, 1);
      if (sol.ok()) equilibrium(ex.getKey(), s, sol);
    }
    Solution warren = Solver.solve(Examples.ALL.get("Treillis Warren").get());
    check("Warren : h = 0", warren.h, 0);
    Solution arch = Solver.solve(Examples.ALL.get("Portique à trois articulations").get());
    check("trois articulations : h = 0", arch.h, 0);
    Solution portal = Solver.solve(Examples.ALL.get("Portique encastré").get());
    check("portique encastré : h = 3", portal.h, 3);
  }

  static void roundTrip() {
    Structure s = Examples.ALL.get("Portique à trois articulations").get();
    String once = StructureFile.write(s);
    check("fichier : relu à l'identique", StructureFile.write(StructureFile.read(once)).equals(once) ? 1 : 0, 1);
  }

  /** Loads + reactions balance: forces and moment about the origin. */
  static void equilibrium(String name, Structure s, Solution sol) {
    double fx = 0, fy = 0, mz = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      Node n = s.nodes.get(i);
      double[] r = sol.reaction[i];
      double x = n.fx + (r == null ? 0 : r[0]), y = n.fy + (r == null ? 0 : r[1]);
      fx += x;
      fy += y;
      mz += n.mz + (r == null ? 0 : r[2]) + n.x * y - n.y * x;
    }
    for (Element e : s.elements) {
      double len = e.length(), mx = (e.a.x + e.b.x) / 2, my = (e.a.y + e.b.y) / 2;
      fx += e.qx * len;
      fy += e.qy * len;
      mz += mx * e.qy * len - my * e.qx * len;
    }
    double scale = 1e3;
    check(name + " : équilibre global", (Math.abs(fx) + Math.abs(fy) + Math.abs(mz)) / scale, 0);
  }

  static void check(String what, double got, double expected) {
    checks++;
    double tol = 1e-9 * Math.max(1, Math.abs(expected));
    boolean ok = Math.abs(got - expected) <= Math.max(tol, 1e-9 * Math.abs(got));
    if (!ok) {
      failures++;
      System.out.printf("ÉCHEC  %s : obtenu %.12g, attendu %.12g%n", what, got, expected);
    } else {
      System.out.printf("ok     %s%n", what);
    }
  }
}
