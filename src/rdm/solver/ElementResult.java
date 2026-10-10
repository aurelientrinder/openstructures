package rdm.solver;

import rdm.model.Element;

/**
 * One element in the solver, and its results: the torseur de cohésion and the deflection all along it.
 *
 * Local axes: x from node a to node b, y at +90° from x. End unknowns, in that order: u_a, v_a, θ_a, u_b, v_b, θ_b.
 * Euler–Bernoulli beam (no shear deformation), linear elasticity, small displacements, constant section.
 *
 * Torseur de cohésion at abscissa x, as in schemes' cohesion.ts: the action of the part after the section (II)
 * on the part before it (I), that is minus the outside actions on I. With f the end forces the nodes apply on
 * the element at a and q the distributed load:
 *
 *   N(x) = −(f_x + q_x·x)   T(x) = −(f_y + q_y·x)   Mf(x) = −m_a + f_y·x + q_y·x²/2
 *
 * so that N > 0 in traction and dMf/dx = −T. The deflection follows from v'' = Mf/EI, u' = N/EA, with the end
 * displacements as boundary values: exact for this load, between the nodes as at them.
 */
public final class ElementResult {
  public final Element element;
  /** indices of the end nodes */
  public final int a, b;
  public final double L, c, s, EA, EI;
  /** the distributed load on the local axes, N/m */
  public final double qx, qy;
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

  ElementResult(Element e, int a, int b) {
    element = e;
    this.a = a;
    this.b = b;
    double dx = e.b.x - e.a.x, dy = e.b.y - e.a.y;
    L = Math.hypot(dx, dy);
    c = dx / L;
    s = dy / L;
    EA = e.material.pascals() * e.section.area();
    EI = e.material.pascals() * e.section.inertia();
    qx = c * e.qx + s * e.qy;
    qy = -s * e.qx + c * e.qy;

    double ea = EA / L, ei = EI / L, ei2 = EI / (L * L), ei3 = EI / (L * L * L);
    double[][] K = {
        {ea, 0, 0, -ea, 0, 0},
        {0, 12 * ei3, 6 * ei2, 0, -12 * ei3, 6 * ei2},
        {0, 6 * ei2, 4 * ei, 0, -6 * ei2, 2 * ei},
        {-ea, 0, 0, ea, 0, 0},
        {0, -12 * ei3, -6 * ei2, 0, 12 * ei3, -6 * ei2},
        {0, 6 * ei2, 2 * ei, 0, -6 * ei2, 4 * ei}};
    for (int i = 0; i < 6; i++) System.arraycopy(K[i], 0, k[i], 0, 6);
    // the loads the nodes would take if both ends were held (fixed-end forces), with their sign as loads
    double[] q = {qx * L / 2, qy * L / 2, qy * L * L / 12, qx * L / 2, qy * L / 2, -qy * L * L / 12};
    System.arraycopy(q, 0, feq, 0, 6);

    released = e.hingeA && e.hingeB ? new int[] {2, 5} : e.hingeA ? new int[] {2} : e.hingeB ? new int[] {5} : new int[0];
    condense();
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

  /** Effort normal (N), positive in traction. */
  public double N(double x) {
    return -(f[0] + qx * x);
  }

  /** Effort tranchant T_y (N). */
  public double T(double x) {
    return -(f[1] + qy * x);
  }

  /** Moment fléchissant Mf_z (N·m). */
  public double M(double x) {
    return -f[2] + f[1] * x + qy * x * x / 2;
  }

  /** Displacement along the element (local x), m. */
  public double axial(double x) {
    return u[0] - (f[0] * x + qx * x * x / 2) / EA;
  }

  /** Deflection across the element (local y), m. */
  public double deflection(double x) {
    double slope = (u[4] - u[1] - bend(L)) / L;
    return u[1] + slope * x + bend(x);
  }

  /** ∫∫ Mf/EI from 0, without the rigid part. */
  private double bend(double x) {
    return (-f[2] * x * x / 2 + f[1] * x * x * x / 6 + qy * x * x * x * x / 24) / EI;
  }

  /** Displacement of the point at abscissa x, on the global axes (m). */
  public double[] displacement(double x) {
    double ul = axial(x), vl = deflection(x);
    return new double[] {c * ul - s * vl, s * ul + c * vl};
  }

  /** Where Mf is extreme inside the element (T = 0), or −1. */
  public double momentPeak() {
    if (qy == 0) return -1;
    double x = -f[1] / qy;
    return x > 0 && x < L ? x : -1;
  }

  public double maxAbsM() {
    double m = Math.max(Math.abs(M(0)), Math.abs(M(L)));
    double p = momentPeak();
    return p > 0 ? Math.max(m, Math.abs(M(p))) : m;
  }

  public double maxAbsN() {
    return Math.max(Math.abs(N(0)), Math.abs(N(L)));
  }

  public double maxAbsT() {
    return Math.max(Math.abs(T(0)), Math.abs(T(L)));
  }

  /** Largest normal stress |N|/A + |Mf|·v/I along the element (Pa). */
  public double sigmaMax() {
    double A = element.section.area(), W = element.section.inertia() / element.section.fibre();
    double best = 0;
    int n = 200;
    for (int i = 0; i <= n; i++) best = Math.max(best, sigma(L * i / n, A, W));
    double p = momentPeak();
    if (p > 0) best = Math.max(best, sigma(p, A, W));
    return best;
  }

  private double sigma(double x, double A, double W) {
    return Math.abs(N(x)) / A + Math.abs(M(x)) / W;
  }
}
