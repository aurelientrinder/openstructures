package rdm.design;

import static rdm.i18n.Tr.tr;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.Loading;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.Buckling;
import rdm.solver.ElementResult;
import rdm.solver.Solution;
import rdm.solver.Solver;

/**
 * The design checks: every steel element's strength under the ultimate limit state combinations (EN 1993-1-1, in
 * the plane of the structure), and every span's deflection under the serviceability ones. Without a combination of
 * a limit state, its checks take the loads as they are given, every case at 1.
 *
 * Strength, γ_M0 = γ_M1 = 1 (as recommended, and in the French national annex): along each element, at the points
 * where N, V or M may peak, the section's class (5.5) and its resistances N_pl = A f_y, V_pl = A_v f_y / √3 (6.2.6),
 * M_c = W f_y with W_pl in class 1 and 2, W_el in class 3, reduced by (1 − ρ) where V > V_pl / 2 (6.2.8, on the whole
 * section: conservative); then N / N_pl + M / M_c ≤ 1 (6.2.1 (7), conservative). Class 4 sections are not checked.
 *
 * Flexural buckling in the plane (6.3.1, 6.3.3): N_cr from the element's buckling length k L when it is given, else
 * from the structure's first buckling mode in that combination (N_cr = λcr N_Ed), so that a sway frame's columns
 * get their longer length; a truss bar (hinged at both ends) at least its own Euler load π²EI/L², as the bars of a
 * triangulated truss, whose nodes do not sway, buckle between them (a lightly loaded bar would otherwise get the
 * buckling of the most loaded one); χ from the section's curve, and with the largest |M| along the element, N_Ed / (χ N_pl)
 * + k_yy M_Ed / M_c ≤ 1 (6.61, Annex B: k_yy with C_my = 1, conservative; χ_LT = 1). Out of the plane, buckling and
 * lateral-torsional buckling are not checked: the elements must be held out of the plane (bracing).
 *
 * Deflection: a span is a straight run of elements, through nodes where only two of them meet and nothing holds
 * them (truss bars alone are left out: straight between their nodes, they do not bend); its deflection is measured from the line through its ends after they move, at most span / limit; a
 * cantilever's (one end free) from its fixed end, at most twice its length / limit.
 */
public final class Design {
  public enum Check {
    AXIAL, SHEAR, BENDING, COMBINED, BUCKLING;

    @Override
    public String toString() {
      return tr("check." + name());
    }
  }

  /** The loads a check is made under: a combination's, or every case at 1 (its name then "", combination null). */
  public record Loads(String name, Combination combination, Loading loading) {}

  /** The largest ratio of one check along an element, over the loads, and where it is. */
  public static final class Worst {
    public double ratio = -1;
    /** the index of the loads, x along the element (m), N, V, M there (N, N·m), the class */
    public int by = -1, cls;
    public double x, N, V, M;
    /** buckling: N_cr (N), L_cr (m), λ̄, χ, k_yy */
    public double Ncr, Lcr, slenderness, chi, kyy;

    public boolean found() {
      return by >= 0;
    }
  }

  /** An element's checks. */
  public static final class Member {
    public final Element element;
    /** null when it is not steel */
    public final SteelSection steel;
    /** f_y, Pa */
    public final double fy;
    /** each check's worst (ordered as Check) */
    public final Worst[] worst = new Worst[Check.values().length];
    /** a class 4 section somewhere: not checked; and where (the loads' index, x) */
    public boolean class4;
    public int class4By;
    public double class4At;

    Member(Element element) {
      this.element = element;
      steel = element.material.name().equals("steel") ? new SteelSection(element.section) : null;
      fy = steel == null ? 0 : steel.fy(element.grade);
      for (int i = 0; i < worst.length; i++) worst[i] = new Worst();
    }

    /** Checked: steel, a shape, no class 4. */
    public boolean checked() {
      return steel != null && steel.unchecked == null && !class4;
    }

    /** Why it is not checked (a text), or null. */
    public String why() {
      if (steel == null) return tr("design.notSteel", element.material);
      if (steel.unchecked != null) return tr("design.custom");
      if (class4) return tr("design.class4");
      return null;
    }

    public Worst worst(Check c) {
      return worst[c.ordinal()];
    }

    /** The check with the largest ratio. */
    public Check governing() {
      Check g = Check.AXIAL;
      for (Check c : Check.values()) if (worst(c).ratio > worst(g).ratio) g = c;
      return g;
    }

    /** Its utilisation: the largest ratio (NaN when not checked). */
    public double ratio() {
      return checked() ? worst(governing()).ratio : Double.NaN;
    }

    /** The web is slender enough for shear buckling (6.2.6 (6)), which is not checked. */
    public boolean shearBuckling() {
      return steel != null && steel.webSlenderness > 72 * Math.sqrt(235e6 / fy);
    }
  }

  /** A span for the deflection: straight elements end to end, from a node to another. */
  public static final class Span {
    public final List<Element> elements;
    public final Node from, to;
    /** one end free: the deflection is measured from the other one */
    public final boolean cantilever;
    public final double length;
    /** what the deflection may reach (m) */
    public final double allowed;
    /** the largest deflection (m), the loads' index, how far from the start (m) */
    public double deflection;
    public int by = -1;
    public double at;

    Span(List<Element> elements, Node from, Node to, boolean cantilever, double limit) {
      this.elements = elements;
      this.from = from;
      this.to = to;
      this.cantilever = cantilever;
      length = Math.hypot(to.x - from.x, to.y - from.y);
      allowed = (cantilever ? 2 * length : length) / limit;
    }

    public double ratio() {
      return Math.abs(deflection) / allowed;
    }
  }

  public final Structure structure;
  /** the ultimate and serviceability loads; given: from combinations (else every case at 1) */
  public final List<Loads> uls = new ArrayList<>(), sls = new ArrayList<>();
  public final boolean ulsGiven, slsGiven;
  public final List<Solution> ulsSolutions = new ArrayList<>(), slsSolutions = new ArrayList<>();
  /** the first loads that could not be solved, or null; and their name */
  public Solution failed;
  public String failedIn;
  public final List<Member> members = new ArrayList<>();
  public final List<Span> spans = new ArrayList<>();
  private final Map<Element, Member> byElement = new IdentityHashMap<>();
  private final Map<Element, Span> spanOf = new IdentityHashMap<>();
  private final Buckling[] buckling;

  private Design(Structure s) {
    structure = s;
    ulsGiven = !s.combinations(Combination.Limit.ULS).isEmpty();
    slsGiven = !s.combinations(Combination.Limit.SLS).isEmpty();
    loads(s, Combination.Limit.ULS, ulsGiven, uls);
    loads(s, Combination.Limit.SLS, slsGiven, sls);
    buckling = new Buckling[uls.size()];
  }

  private static void loads(Structure s, Combination.Limit limit, boolean given, List<Loads> out) {
    if (given) for (Combination k : s.combinations(limit)) out.add(new Loads(k.name, k, k.loading()));
    else out.add(new Loads("", null, Loading.all(s)));
  }

  /** Solves every combination and checks every element and span. */
  public static Design of(Structure s) {
    Design d = new Design(s);
    for (Loads l : d.uls) d.ulsSolutions.add(d.solve(l));
    for (Loads l : d.sls) d.slsSolutions.add(d.solve(l));
    if (d.ok()) {
      for (Element e : s.elements) {
        Member m = new Member(e);
        d.members.add(m);
        d.byElement.put(e, m);
        if (m.steel != null && m.steel.unchecked == null) d.strength(m);
      }
      d.spans();
      for (Span sp : d.spans) d.deflection(sp);
    }
    return d;
  }

  private Solution solve(Loads l) {
    Solution sol = Solver.solve(structure, l.loading());
    if (!sol.ok() && failed == null) {
      failed = sol;
      failedIn = l.name();
    }
    return sol;
  }

  /** Every load solved. */
  public boolean ok() {
    return failed == null && !structure.elements.isEmpty();
  }

  public Member of(Element e) {
    return byElement.get(e);
  }

  public Span spanOf(Element e) {
    return spanOf.get(e);
  }

  /** The largest utilisation of any element checked (NaN when none is). */
  public double maxRatio() {
    double best = Double.NaN;
    for (Member m : members) if (m.checked() && !(m.ratio() <= best)) best = m.ratio();
    return best;
  }

  // ——— strength ———

  private void strength(Member m) {
    SteelSection st = m.steel;
    double fy = m.fy, Npl = st.A * fy, Vpl = st.Av * fy / Math.sqrt(3);
    for (int k = 0; k < uls.size(); k++) {
      ElementResult r = ulsSolutions.get(k).of(m.element);
      TreeSet<Double> xs = new TreeSet<>();
      for (double x : r.samples(20)) xs.add(x);
      xs.addAll(r.extremaM());
      double nEd = 0, mEd = 0;
      for (double x : xs) {
        double N = r.N(x), V = r.T(x), M = r.M(x);
        nEd = Math.max(nEd, -N);
        mEd = Math.max(mEd, Math.abs(M));
        int cls = st.classify(N, M, fy);
        if (cls == 4) {
          if (!m.class4) {
            m.class4 = true;
            m.class4By = k;
            m.class4At = x;
          }
          continue;
        }
        double rho = Math.abs(V) > Vpl / 2 ? Math.min(1, Math.pow(2 * Math.abs(V) / Vpl - 1, 2)) : 0;
        double Mc = (cls <= 2 ? st.Wpl : st.Wel) * fy * Math.max(1e-9, 1 - rho);
        double axial = Math.abs(N) / Npl, bending = Math.abs(M) / Mc;
        keep(m.worst(Check.AXIAL), axial, k, x, N, V, M, cls);
        keep(m.worst(Check.SHEAR), Math.abs(V) / Vpl, k, x, N, V, M, cls);
        keep(m.worst(Check.BENDING), bending, k, x, N, V, M, cls);
        keep(m.worst(Check.COMBINED), axial + bending, k, x, N, V, M, cls);
      }
      if (nEd > 1e-6 * Npl) buckle(m, k, r, nEd, mEd);
    }
  }

  private static boolean keep(Worst w, double ratio, int k, double x, double N, double V, double M, int cls) {
    if (ratio <= w.ratio) return false;
    w.ratio = ratio;
    w.by = k;
    w.x = x;
    w.N = N;
    w.V = V;
    w.M = M;
    w.cls = cls;
    return true;
  }

  /** Flexural buckling in the plane under N_Ed (compression, positive) and the largest |M| along the element. */
  private void buckle(Member m, int k, ElementResult r, double nEd, double mEd) {
    SteelSection st = m.steel;
    Element e = m.element;
    double fy = m.fy, EI = r.EI, Ncr;
    if (e.bucklingFactor > 0) {
      double lcr = e.bucklingFactor * r.L;
      Ncr = Math.PI * Math.PI * EI / (lcr * lcr);
    } else {
      if (buckling[k] == null) buckling[k] = Buckling.of(ulsSolutions.get(k));
      Buckling bk = buckling[k];
      Buckling.Member bm = bk.ok() ? bk.of(e) : null;
      Ncr = bm != null && bm.compressed() ? bm.critical(bk.factor()) : Double.POSITIVE_INFINITY;
      if (e.isBar()) Ncr = Math.max(Ncr, Math.PI * Math.PI * EI / (r.L * r.L));
    }
    // the class under this compression with the moment either way (an asymmetric section)
    int cls = Math.max(st.classify(-nEd, mEd, fy), st.classify(-nEd, -mEd, fy));
    if (cls == 4) {
      if (!m.class4) {
        m.class4 = true;
        m.class4By = k;
        m.class4At = 0;
      }
      return;
    }
    double Npl = st.A * fy, lambda = Math.sqrt(Npl / Ncr);
    double phi = 0.5 * (1 + st.alpha * (lambda - 0.2) + lambda * lambda);
    double chi = lambda <= 0.2 ? 1 : Math.min(1, 1 / (phi + Math.sqrt(phi * phi - lambda * lambda)));
    double n = nEd / (chi * Npl);
    double kyy = cls <= 2 ? Math.min(1 + (lambda - 0.2) * n, 1 + 0.8 * n) : Math.min(1 + 0.6 * lambda * n, 1 + 0.6 * n);
    double Mc = (cls <= 2 ? st.Wpl : st.Wel) * fy;
    Worst w = m.worst(Check.BUCKLING);
    if (keep(w, n + kyy * mEd / Mc, k, 0, -nEd, 0, mEd, cls)) {
      w.Ncr = Ncr;
      w.Lcr = Math.PI * Math.sqrt(EI / Ncr);
      w.slenderness = lambda;
      w.chi = chi;
      w.kyy = kyy;
    }
  }

  // ——— deflection ———

  /** Cuts the structure into spans. */
  private void spans() {
    for (Element e : structure.elements) {
      if (spanOf.containsKey(e)) continue;
      LinkedList<Element> run = new LinkedList<>(List.of(e));
      Node from = e.a, to = e.b;
      for (Element next; (next = through(to, run.getLast())) != null && !run.contains(next); ) {
        run.addLast(next);
        to = next.a == to ? next.b : next.a;
      }
      for (Element prev; (prev = through(from, run.getFirst())) != null && !run.contains(prev); ) {
        run.addFirst(prev);
        from = prev.a == from ? prev.b : prev.a;
      }
      boolean freeFrom = free(from), freeTo = free(to);
      for (Element x : run) spanOf.put(x, null);
      if (freeFrom && freeTo || from == to || run.stream().allMatch(Element::isBar)) continue;
      // a cantilever starts at its fixed end
      Span sp = freeFrom ? new Span(List.copyOf(run.reversed()), to, from, true, structure.deflectionLimit)
          : new Span(List.copyOf(run), from, to, freeTo, structure.deflectionLimit);
      spans.add(sp);
      for (Element x : run) spanOf.put(x, sp);
    }
  }

  /** The element that carries on straight from this one through a node where nothing else meets, or null. */
  private Element through(Node n, Element e) {
    if (n.support != Support.NONE) return null;
    List<Element> at = structure.elementsAt(n);
    if (at.size() != 2) return null;
    Element o = at.get(0) == e ? at.get(1) : at.get(0);
    double[] u = direction(e, n), v = direction(o, n);
    // pointing away from the node, both: straight on when they are opposite
    return Math.abs(u[0] * v[1] - u[1] * v[0]) < 1e-3 && u[0] * v[0] + u[1] * v[1] < 0 ? o : null;
  }

  private static double[] direction(Element e, Node from) {
    Node o = e.a == from ? e.b : e.a;
    double l = Math.hypot(o.x - from.x, o.y - from.y);
    return new double[] {(o.x - from.x) / l, (o.y - from.y) / l};
  }

  /** A free end: one element, no support. */
  private boolean free(Node n) {
    return n.support == Support.NONE && structure.elementsAt(n).size() == 1;
  }

  private void deflection(Span sp) {
    double ux = (sp.to.x - sp.from.x) / sp.length, uy = (sp.to.y - sp.from.y) / sp.length;
    int ia = structure.nodes.indexOf(sp.from), ib = structure.nodes.indexOf(sp.to);
    for (int k = 0; k < sls.size(); k++) {
      Solution sol = slsSolutions.get(k);
      double[] da = sol.disp[ia], db = sol.disp[ib];
      for (Element e : sp.elements) {
        ElementResult r = sol.of(e);
        for (double x : r.samples(24)) {
          double px = e.a.x + r.c * x - sp.from.x, py = e.a.y + r.s * x - sp.from.y;
          double t = (px * ux + py * uy) / sp.length;
          double[] d = r.displacement(x);
          double cx = sp.cantilever ? da[0] : da[0] + (db[0] - da[0]) * t, cy = sp.cantilever ? da[1] : da[1] + (db[1] - da[1]) * t;
          double delta = -(d[0] - cx) * uy + (d[1] - cy) * ux;
          if (Math.abs(delta) > Math.abs(sp.deflection) || sp.by < 0) {
            sp.deflection = delta;
            sp.by = k;
            sp.at = t * sp.length;
          }
        }
      }
    }
  }
}
