package rdm.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Loading;

/**
 * One element in the solver, and its results: the torseur de cohésion and the deflection all along it.
 *
 * Local axes: x from node a to node b, y at +90° from x. End unknowns, in that order: u_a, v_a, θ_a, u_b, v_b, θ_b.
 * Euler–Bernoulli beam (no shear deformation), linear elasticity, small displacements, constant section.
 *
 * Torseur de cohésion at abscissa x, as in schemes' cohesion.ts: the action of the part after the section (II)
 * on the part before it (I), that is minus the outside actions on I. With f the end forces the nodes apply on
 * the element at a, and the loads on [0, x]:
 *
 *   N(x) = −(f_x + Σ p_x)   T(x) = −(f_y + Σ p_y)   Mf(x) = −m_a + f_y·x + Σ p_y·(x − s) − Σ C
 *
 * so that N > 0 in traction and dMf/dx = −T. The deflection follows from v'' = Mf/EI, u' = N/EA, with the end
 * displacements as boundary values: every term is integrated in closed form (Gauss rules exact for the degree),
 * so the results are exact between the nodes as at them.
 */
public final class ElementResult {
  /** a point load (local axes), at s from node a; a couple has px = py = 0 */
  private record Point(double s, double px, double py, double c) {}

  /** a distributed load (local axes, N/m), linear from a to b */
  private record Spread(double a, double b, double px1, double py1, double px2, double py2) {
    double px(double s) {
      return b == a ? px1 : px1 + (px2 - px1) * (s - a) / (b - a);
    }

    double py(double s) {
      return b == a ? py1 : py1 + (py2 - py1) * (s - a) / (b - a);
    }
  }

  /** 3-point Gauss–Legendre on [−1, 1]: exact up to degree 5 */
  private static final double[] GX = {-Math.sqrt(0.6), 0, Math.sqrt(0.6)};
  private static final double[] GW = {5.0 / 9, 8.0 / 9, 5.0 / 9};

  public final Element element;
  /** indices of the end nodes */
  public final int a, b;
  public final double L, c, s, EA, EI;
  private final List<Point> points = new ArrayList<>();
  private final List<Spread> spreads = new ArrayList<>();
  final double[][] k = new double[6][6];
  final double[] feq = new double[6];
  /** stiffness and equivalent loads once the hinged rotations are condensed out (zero rows there) */
  final double[][] kc = new double[6][6];
  final double[] fc = new double[6];
  private final int[] released;
  private double[][] krrInv;
  /** end displacements on the local axes (m, rad); a hinged end's rotation is the element's own */
  public final double[] u = new double[6];
  /** end forces the nodes apply on the element, local axes (N, N·m) */
  public final double[] f = new double[6];

  /**
   * @param selfWeight the element's weight per metre (N/m, downwards), already factored; 0 for none
   * @param loading the factor of each load case: the element's loads are taken times their case's
   */
  ElementResult(Element e, int a, int b, double selfWeight, Loading loading) {
    element = e;
    this.a = a;
    this.b = b;
    double dx = e.b.x - e.a.x, dy = e.b.y - e.a.y;
    L = Math.hypot(dx, dy);
    c = dx / L;
    s = dy / L;
    EA = e.material.pascals() * e.section.area();
    EI = e.material.pascals() * e.section.inertia();
    for (ElementLoad l : e.loads) {
      double k = loading.factor(l.loadCase());
      if (k != 0) add(l.scaled(k));
    }
    if (selfWeight != 0) add(ElementLoad.Distributed.uniform(null, 0, -selfWeight));

    double ea = EA / L, ei = EI / L, ei2 = EI / (L * L), ei3 = EI / (L * L * L);
    double[][] K = {
        {ea, 0, 0, -ea, 0, 0},
        {0, 12 * ei3, 6 * ei2, 0, -12 * ei3, 6 * ei2},
        {0, 6 * ei2, 4 * ei, 0, -6 * ei2, 2 * ei},
        {-ea, 0, 0, ea, 0, 0},
        {0, -12 * ei3, -6 * ei2, 0, 12 * ei3, -6 * ei2},
        {0, 6 * ei2, 2 * ei, 0, -6 * ei2, 4 * ei}};
    for (int i = 0; i < 6; i++) System.arraycopy(K[i], 0, k[i], 0, 6);
    equivalentLoads();

    released = e.hingeA && e.hingeB ? new int[] {2, 5} : e.hingeA ? new int[] {2} : e.hingeB ? new int[] {5} : new int[0];
    condense();
  }

  /** The load on the local axes, positions in metres from node a. */
  private void add(ElementLoad l) {
    switch (l) {
      case ElementLoad.Force f -> {
        double[] p = f.local() ? new double[] {f.fx(), f.fy()} : toLocal(f.fx(), f.fy());
        points.add(new Point(f.t() * L, p[0], p[1], 0));
      }
      case ElementLoad.Couple cp -> points.add(new Point(cp.t() * L, 0, 0, cp.c()));
      case ElementLoad.Distributed d -> {
        double[] p1 = d.local() ? new double[] {d.qx1(), d.qy1()} : toLocal(d.qx1(), d.qy1());
        double[] p2 = d.local() ? new double[] {d.qx2(), d.qy2()} : toLocal(d.qx2(), d.qy2());
        if (d.t2() > d.t1()) spreads.add(new Spread(d.t1() * L, d.t2() * L, p1[0], p1[1], p2[0], p2[1]));
      }
    }
  }

  private double[] toLocal(double gx, double gy) {
    return new double[] {c * gx + s * gy, -s * gx + c * gy};
  }

  /**
   * The loads the nodes would take if both ends were held (fixed-end forces, as loads): the work of each load in
   * the cubic (bending) and linear (traction) shape functions. For an Euler–Bernoulli beam they are exact.
   */
  private void equivalentLoads() {
    for (Point p : points) {
      double[] n = shape(p.s), d = slope(p.s);
      feq[0] += (1 - p.s / L) * p.px;
      feq[3] += p.s / L * p.px;
      for (int i = 0; i < 4; i++) feq[i < 2 ? i + 1 : i + 2] += n[i] * p.py + d[i] * p.c;
    }
    for (Spread q : spreads) {
      double h = (q.b - q.a) / 2, m = (q.a + q.b) / 2;
      for (int g = 0; g < 3; g++) {
        double x = m + h * GX[g], w = h * GW[g];
        double[] n = shape(x);
        feq[0] += w * (1 - x / L) * q.px(x);
        feq[3] += w * x / L * q.px(x);
        for (int i = 0; i < 4; i++) feq[i < 2 ? i + 1 : i + 2] += w * n[i] * q.py(x);
      }
    }
  }

  /** Hermite shape functions at x: v_a, θ_a, v_b, θ_b. */
  private double[] shape(double x) {
    double t = x / L;
    return new double[] {1 - 3 * t * t + 2 * t * t * t, L * (t - 2 * t * t + t * t * t), 3 * t * t - 2 * t * t * t, L * (t * t * t - t * t)};
  }

  /** Their derivatives along x (a couple works in the slope). */
  private double[] slope(double x) {
    double t = x / L;
    return new double[] {(-6 * t + 6 * t * t) / L, 1 - 4 * t + 3 * t * t, (6 * t - 6 * t * t) / L, 3 * t * t - 2 * t};
  }

  /**
   * A hinged end carries no moment: its rotation is solved within the element (f_r = 0) and removed. With r the
   * released unknowns and a the others: k* = k_aa − k_ar k_rr⁻¹ k_ra, f* = f_a − k_ar k_rr⁻¹ f_r.
   */
  private void condense() {
    int nr = released.length;
    for (int i = 0; i < 6; i++) {
      System.arraycopy(k[i], 0, kc[i], 0, 6);
      fc[i] = feq[i];
    }
    if (nr == 0) return;
    krrInv = new double[nr][nr];
    if (nr == 1) {
      krrInv[0][0] = 1 / k[released[0]][released[0]];
    } else {
      double p = k[2][2], q = k[2][5], r = k[5][5], det = p * r - q * q;
      krrInv[0][0] = r / det;
      krrInv[0][1] = krrInv[1][0] = -q / det;
      krrInv[1][1] = p / det;
    }
    for (int i = 0; i < 6; i++) {
      if (isReleased(i)) continue;
      for (int j = 0; j < 6; j++) {
        if (isReleased(j)) continue;
        double sum = 0;
        for (int x = 0; x < nr; x++) for (int y = 0; y < nr; y++) sum += k[i][released[x]] * krrInv[x][y] * k[released[y]][j];
        kc[i][j] -= sum;
      }
      double sum = 0;
      for (int x = 0; x < nr; x++) for (int y = 0; y < nr; y++) sum += k[i][released[x]] * krrInv[x][y] * feq[released[y]];
      fc[i] -= sum;
    }
    for (int r0 : released) {
      for (int j = 0; j < 6; j++) kc[r0][j] = kc[j][r0] = 0;
      fc[r0] = 0;
    }
  }

  boolean isReleased(int i) {
    for (int r : released) if (r == i) return true;
    return false;
  }

  /** Sets the end displacements from the nodes' (local axes; hinged rotations ignored), and the end forces. */
  void finish(double[] ul) {
    System.arraycopy(ul, 0, u, 0, 6);
    if (released.length > 0) {
      // u_r = k_rr⁻¹ (f_r − k_ra u_a)
      double[] rhs = new double[released.length];
      for (int x = 0; x < released.length; x++) {
        double v = feq[released[x]];
        for (int j = 0; j < 6; j++) if (!isReleased(j)) v -= k[released[x]][j] * u[j];
        rhs[x] = v;
      }
      for (int x = 0; x < released.length; x++) {
        double v = 0;
        for (int y = 0; y < released.length; y++) v += krrInv[x][y] * rhs[y];
        u[released[x]] = v;
      }
    }
    for (int i = 0; i < 6; i++) {
      double v = -feq[i];
      for (int j = 0; j < 6; j++) v += k[i][j] * u[j];
      f[i] = v;
    }
  }

  // ——— the loads on [0, x] ———

  /**
   * Sums over the loads before x (points strictly before): {Σp_x, Σp_x·(x−s), Σp_y, Σp_y·(x−s) − ΣC,
   * Σp_y·(x−s)³/6 − ΣC·(x−s)²/2}, the last being the double integral of the load's share of Mf.
   */
  private double[] before(double x) {
    double[] r = new double[5];
    for (Point p : points) {
      if (p.s >= x) continue;
      double d = x - p.s;
      r[0] += p.px;
      r[1] += p.px * d;
      r[2] += p.py;
      r[3] += p.py * d - p.c;
      r[4] += p.py * d * d * d / 6 - p.c * d * d / 2;
    }
    for (Spread q : spreads) {
      double end = Math.min(x, q.b);
      if (end <= q.a) continue;
      double h = (end - q.a) / 2, m = (q.a + end) / 2;
      for (int g = 0; g < 3; g++) {
        double sg = m + h * GX[g], w = h * GW[g], d = x - sg;
        double px = q.px(sg), py = q.py(sg);
        r[0] += w * px;
        r[1] += w * px * d;
        r[2] += w * py;
        r[3] += w * py * d;
        // degree 4 in s on the piece: still exact
        r[4] += w * py * d * d * d / 6;
      }
    }
    return r;
  }

  /** Effort normal (N), positive in traction. */
  public double N(double x) {
    return -(f[0] + before(x)[0]);
  }

  /** Effort tranchant T_y (N). */
  public double T(double x) {
    return -(f[1] + before(x)[2]);
  }

  /** Moment fléchissant Mf_z (N·m). */
  public double M(double x) {
    return -f[2] + f[1] * x + before(x)[3];
  }

  /** Displacement along the element (local x), m. */
  public double axial(double x) {
    return u[0] - (f[0] * x + before(x)[1]) / EA;
  }

  /** Deflection across the element (local y), m. */
  public double deflection(double x) {
    double slope = (u[4] - u[1] - bend(L)) / L;
    return u[1] + slope * x + bend(x);
  }

  /** ∫∫ Mf/EI from 0, without the rigid part. */
  private double bend(double x) {
    return (-f[2] * x * x / 2 + f[1] * x * x * x / 6 + before(x)[4]) / EI;
  }

  /** Displacement of the point at abscissa x, on the global axes (m). */
  public double[] displacement(double x) {
    double ul = axial(x), vl = deflection(x);
    return new double[] {c * ul - s * vl, s * ul + c * vl};
  }

  // ——— where to look ———

  /** Where N, T or Mf may jump or bend: the point loads, and the ends of the distributed ones. */
  private TreeSet<Double> breaks() {
    TreeSet<Double> out = new TreeSet<>();
    for (Point p : points) out.add(p.s);
    for (Spread q : spreads) {
      out.add(q.a);
      out.add(q.b);
    }
    out.removeIf(v -> v <= 0 || v >= L);
    return out;
  }

  /**
   * Abscissas to draw the element's curves at: n even steps, and each side of every jump (a hair apart), sorted.
   */
  public double[] samples(int n) {
    TreeSet<Double> xs = new TreeSet<>();
    for (int i = 0; i <= n; i++) xs.add(L * i / n);
    double eps = 1e-9 * L;
    for (double b : breaks()) {
      xs.add(b - eps);
      xs.add(b + eps);
    }
    return xs.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** The abscissa of the largest |Mf|: at an end, either side of a jump, or where T = 0 (found by bisection). */
  public double maxMAt() {
    double best = 0, at = 0;
    for (double x : extremaM()) {
      double m = Math.abs(M(x));
      if (m > best) {
        best = m;
        at = x;
      }
    }
    return at;
  }

  /** Where Mf may be largest or smallest: the ends, either side of every jump, and where T = 0. */
  public List<Double> extremaM() {
    List<Double> cands = new ArrayList<>(List.of(0.0, L));
    double eps = 1e-9 * L;
    List<Double> edges = new ArrayList<>();
    edges.add(0.0);
    for (double b : breaks()) {
      // Mf is continuous under a force (the peak is right there), it jumps only at a couple
      cands.add(b);
      cands.add(b - eps);
      cands.add(b + eps);
      edges.add(b);
    }
    edges.add(L);
    // on each piece T is a polynomial of degree ≤ 2: its sign changes show the extrema of Mf
    for (int i = 0; i + 1 < edges.size(); i++) {
      double lo = edges.get(i) + eps, hi = edges.get(i + 1) - eps;
      int n = 32;
      double prevX = lo, prevT = T(lo);
      for (int j = 1; j <= n; j++) {
        double x = lo + (hi - lo) * j / n, t = T(x);
        if (prevT == 0) cands.add(prevX);
        else if (prevT * t < 0) cands.add(root(prevX, x));
        prevX = x;
        prevT = t;
      }
    }
    return cands;
  }

  private double root(double lo, double hi) {
    double tl = T(lo);
    for (int i = 0; i < 80; i++) {
      double m = (lo + hi) / 2, tm = T(m);
      if (tl * tm <= 0) hi = m;
      else {
        lo = m;
        tl = tm;
      }
    }
    return (lo + hi) / 2;
  }

  public double maxAbsM() {
    return Math.abs(M(maxMAt()));
  }

  public double maxAbsN() {
    return maxAbs(this::N);
  }

  public double maxAbsT() {
    return maxAbs(this::T);
  }

  private double maxAbs(java.util.function.DoubleUnaryOperator q) {
    return Arrays.stream(samples(200)).map(x -> Math.abs(q.applyAsDouble(x))).max().orElse(0);
  }

  /** Largest normal stress |N|/A + |Mf|·v/I along the element (Pa). */
  public double sigmaMax() {
    double A = element.section.area(), W = element.section.inertia() / element.section.fibre();
    double best = Math.abs(N(maxMAt())) / A + maxAbsM() / W;
    for (double x : samples(200)) best = Math.max(best, Math.abs(N(x)) / A + Math.abs(M(x)) / W);
    return best;
  }
}
