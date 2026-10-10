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
import rdm.io.StructureFile;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Examples;
import rdm.model.Material;
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
    // first, as Settings.applyAtStartup does: the languages load before Tr (no cycle between the two)
    check("langues chargées avant Tr", Tr.Lang.values().length, 2);
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
    readVersion1();
    examples();
    roundTrip();
    texts();
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
    uniform(s.elements.get(0), -Q);
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    double EI = EI(s);
    check("deux appuis : flèche 5qL⁴/384EI (entre les nœuds)", r.deflection(L / 2), -5 * Q * Math.pow(L, 4) / (384 * EI));
    check("deux appuis : Mf max qL²/8", r.maxAbsM(), Q * L * L / 8);
    check("deux appuis : abscisse du max L/2", r.maxMAt(), L / 2);
    check("deux appuis : réaction qL/2", sol.reaction[1][1], Q * L / 2);
    check("deux appuis : T(0) = −qL/2", r.T(0), -Q * L / 2);
    check("deux appuis : h = 0", sol.h, 0);
    equilibrium("deux appuis", s, sol);
  }

  static void fixedFixed() {
    Structure s = beam(2);
    node(s, 0).support = node(s, 2).support = Support.ENCASTREMENT;
    for (Element e : s.elements) uniform(e, -Q);
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
    uniform(s.elements.get(0), -Q);
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
    Solution warren = Solver.solve(Examples.ALL.get("warren").get());
    check("Warren : h = 0", warren.h, 0);
    Solution arch = Solver.solve(Examples.ALL.get("threeHinged").get());
    check("trois articulations : h = 0", arch.h, 0);
    Solution portal = Solver.solve(Examples.ALL.get("portal").get());
    check("portique encastré : h = 3", portal.h, 3);
  }

  static void roundTrip() {
    Structure s = Examples.ALL.get("threeHinged").get();
    s.elements.get(0).loads.add(new ElementLoad.Force(0.25, 1e3, -2e3, true));
    s.elements.get(0).loads.add(new ElementLoad.Couple(0.6, 3e3));
    s.nodes.get(0).settleN = -0.002;
    s.selfWeight = true;
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
      double[] t = totals(e, s.selfWeight);
      fx += t[0];
      fy += t[1];
      mz += t[2];
    }
    double scale = 1e3;
    check(name + " : équilibre global", (Math.abs(fx) + Math.abs(fy) + Math.abs(mz)) / scale, 0);
  }

  /** Resultant (global) and moment about the origin of an element's loads, exact (Gauss rules of the right degree). */
  static double[] totals(Element e, boolean selfWeight) {
    double len = e.length(), fx = 0, fy = 0, mz = 0;
    List<ElementLoad> loads = new ArrayList<>(e.loads);
    if (selfWeight) {
      double w = e.material.rho() * e.section.area() * Structure.G;
      loads.add(ElementLoad.Distributed.uniform(0, -w));
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

  static void uniform(Element e, double qy) {
    e.loads.add(ElementLoad.Distributed.uniform(0, qy));
  }

  /** Simply supported, P at a = L/4 (b = 3L/4). */
  static void pointLoad() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Force(0.25, 0, -P, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    double a = L / 4, b = 3 * L / 4, EI = EI(s);
    check("force en travée : réaction Pb/L", sol.reaction[0][1], P * b / L);
    check("force en travée : Mf sous la charge Pab/L", r.M(a), P * a * b / L);
    check("force en travée : Mf max sous la charge", r.maxMAt(), a);
    check("force en travée : flèche sous la charge Pa²b²/3EIL", r.deflection(a), -P * a * a * b * b / (3 * EI * L));
    check("force en travée : saut de T = P", r.T(a + 1e-9) - r.T(a - 1e-9), P);
    equilibrium("force en travée", s, sol);
  }

  static void cantileverMidLoad() {
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    s.elements.get(0).loads.add(new ElementLoad.Force(0.5, 0, -P, false));
    Solution sol = Solver.solve(s);
    check("console, force à mi-longueur : flèche en bout 5PL³/48EI", sol.disp[1][1], -5 * P * L * L * L / (48 * EI(s)));
  }

  /** Simply supported, load growing from 0 at a to q at b. */
  static void triangular() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Distributed(0, 1, 0, 0, 0, -Q, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("charge triangulaire : réaction qL/6", sol.reaction[0][1], Q * L / 6);
    check("charge triangulaire : réaction qL/3", sol.reaction[1][1], Q * L / 3);
    check("charge triangulaire : Mf max qL²/9√3", r.maxAbsM(), Q * L * L / (9 * Math.sqrt(3)));
    check("charge triangulaire : à x = L/√3", r.maxMAt(), L / Math.sqrt(3));
    check("charge triangulaire : flèche au milieu 5qL⁴/768EI", r.deflection(L / 2), -5 * Q * Math.pow(L, 4) / (768 * EI(s)));
    equilibrium("charge triangulaire", s, sol);
  }

  static void fixedFixedPoint() {
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.ENCASTREMENT;
    s.elements.get(0).loads.add(new ElementLoad.Force(0.5, 0, -P, false));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("bi-encastrée, force au milieu : Mf aux encastrements −PL/8", r.M(0), -P * L / 8);
    check("bi-encastrée, force au milieu : Mf au milieu PL/8", r.M(L / 2), P * L / 8);
    check("bi-encastrée, force au milieu : flèche PL³/192EI", r.deflection(L / 2), -P * L * L * L / (192 * EI(s)));
  }

  static void coupleInSpan() {
    double C = 6e3;
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Couple(0.5, C));
    Solution sol = Solver.solve(s);
    ElementResult r = sol.of(s.elements.get(0));
    check("couple en travée : réactions ±C/L", sol.reaction[0][1], C / L);
    check("couple en travée : Mf juste avant C/2", r.M(L / 2 - 1e-12), C / 2);
    check("couple en travée : Mf juste après −C/2", r.M(L / 2 + 1e-12), -C / 2);
    check("couple en travée : pas de flèche au milieu (antisymétrie)", r.deflection(L / 2), 0);
    equilibrium("couple en travée", s, sol);
  }

  static void halfSpan() {
    Structure s = beam(1);
    node(s, 0).support = Support.PIVOT;
    node(s, 1).support = Support.APPUI_SIMPLE;
    s.elements.get(0).loads.add(new ElementLoad.Distributed(0, 0.5, 0, -Q, 0, -Q, false));
    Solution sol = Solver.solve(s);
    check("charge sur une demi-travée : réaction 3qL/8", sol.reaction[0][1], 3 * Q * L / 8);
    check("charge sur une demi-travée : réaction qL/8", sol.reaction[1][1], Q * L / 8);
    equilibrium("charge sur une demi-travée", s, sol);
  }

  /** A beam at 30°, a uniform load across it (local axes): the moment is the straight beam's. */
  static void inclinedLocalLoad() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(L * Math.cos(Math.PI / 6), L * Math.sin(Math.PI / 6));
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    Element e = s.addElement(a, b, false);
    e.loads.add(new ElementLoad.Distributed(0, 1, 0, -Q, 0, -Q, true));
    Solution sol = Solver.solve(s);
    check("poutre inclinée, charge locale : Mf max qL²/8", sol.of(e).maxAbsM(), Q * L * L / 8);
    equilibrium("poutre inclinée, charge locale", s, sol);
  }

  static void selfWeight() {
    Structure s = beam(1);
    s.selfWeight = true;
    node(s, 0).support = Support.ENCASTREMENT;
    Solution sol = Solver.solve(s);
    Element e = s.elements.get(0);
    double w = e.material.rho() * e.section.area() * Structure.G;
    check("poids propre, console : flèche wL⁴/8EI", sol.disp[1][1], -w * Math.pow(L, 4) / (8 * EI(s)));
    check("poids propre, console : réaction = poids", sol.reaction[0][1], w * L);
  }

  /** Fixed-fixed beam whose right end settles by δ. */
  static void settlement() {
    double delta = 0.005;
    Structure s = beam(1);
    node(s, 0).support = node(s, 1).support = Support.ENCASTREMENT;
    node(s, 1).settleN = -delta;
    Solution sol = Solver.solve(s);
    double EI = EI(s);
    check("tassement : déplacement imposé", sol.disp[1][1], -delta);
    check("tassement : réaction −12EIδ/L³", sol.reaction[1][1], -12 * EI * delta / (L * L * L));
    check("tassement : Mf d'encastrement 6EIδ/L²", Math.abs(sol.of(s.elements.get(0)).M(0)), 6 * EI * delta / (L * L));
    check("tassement : h = 3", sol.h, 3);
  }

  /** Cantilever whose tip rests on a spring: the tip load shares between both. */
  static void spring() {
    double k = 2e6;
    Structure s = beam(1);
    node(s, 0).support = Support.ENCASTREMENT;
    node(s, 1).support = Support.ELASTIQUE;
    node(s, 1).springN = k;
    node(s, 1).fy = -P;
    Solution sol = Solver.solve(s);
    double kb = 3 * EI(s) / (L * L * L);
    check("appui élastique : flèche P/(k + 3EI/L³)", sol.disp[1][1], -P / (k + kb));
    check("appui élastique : effort du ressort", sol.reaction[1][1], P * k / (k + kb));
    check("appui élastique : h = 1", sol.h, 1);
    equilibrium("appui élastique", s, sol);
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
      check("coupe à t = " + at + " : réaction inchangée", after.reaction[0][1], before.reaction[0][1]);
      check("coupe à t = " + at + " : Mf inchangé à x = 5 m", after.of(second).M(5 - second.a.x), mid);
    }
  }

  static void readVersion1() {
    Structure s = StructureFile.read("RDM 1\nnode 0.0 0.0 PIVOT 90.0 0.0 0.0 0.0\nnode 6.0 0.0 APPUI_SIMPLE 90.0 0.0 0.0 0.0\n"
        + "elem 1 2 0 0 IPE 200.0 0.0 0.0 210000.0 0.0 -5000.0\n");
    Solution sol = Solver.solve(s);
    check("fichier version 1 : charge répartie relue", sol.reaction[0][1], 5000 * 6 / 2.0);
    check("fichier version 1 : acier", s.elements.get(0).material.rho(), 7850);
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
      check(l + " : textes manquants " + missing, missing.size(), 0);
      check(l + " : textes en trop " + extra, extra.size(), 0);
      int wrong = 0;
      for (var e : t.entrySet()) {
        String ref = en.get(e.getKey());
        if (ref != null && !specifiers(ref).equals(specifiers(e.getValue()))) {
          System.out.println("       arguments différents : " + l + " " + e.getKey());
          wrong++;
        }
      }
      check(l + " : mêmes arguments qu'en anglais", wrong, 0);
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
    check("des textes sont demandés dans src", used.size() > 100 ? 1 : 0, 1);
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
    for (Section.Kind v : Section.Kind.values()) {
      used.add("section." + v.name());
      if (v != Section.Kind.IPE) used.add("section.label." + v.name());
    }
    for (Material m : Material.PRESETS) used.add("material." + m.name());
    used.add("material." + Material.of(1, 1).name());
    for (String name : Examples.ALL.keySet()) used.add("example." + name);
    used.removeAll(en.keySet());
    check("textes demandés mais absents " + used, used.size(), 0);
  }

  private static List<String> specifiers(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("%[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]").matcher(text);
    while (m.find()) out.add(m.group());
    return out;
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
