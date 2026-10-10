package rdm.solver;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import rdm.model.Element;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;

/**
 * Linear (Euler) buckling, flambement eulérien: the factors λ by which the loads of a solution must be multiplied
 * for the structure to buckle, and the shape it buckles into.
 *
 * The normal forces N of the linear solution make the geometric stiffness K_g (consistent with the cubic shape
 * functions; N > 0 in traction stiffens). The structure buckles when K + λ K_g is singular: K φ = λ (−K_g) φ.
 * With K = D⁻¹ L Lᵀ D⁻¹ (Cholesky), this is the symmetric eigenproblem C y = (1/λ) y; the positive λ, smallest
 * first, are the critical load factors λcr, and λcr of the first mode is the safety factor against buckling.
 *
 * A cubic element has the exact Euler load only as it is cut finer: each element is cut into parts sub-elements
 * (8, fewer on a large structure), which puts λcr of a column within 0.01 % (pinned, fixed-free) to 0.1 %
 * (fixed-fixed, higher modes) above the exact value. A hinged end keeps a rotation of its own, so that a truss bar
 * buckles alone, between its nodes. The supports, their frames and their springs are the linear solution's.
 */
public final class Buckling {
  /** how many modes are kept, at most */
  public static final int MODES = 3;

  public enum Status {
    /** at least one mode */
    OK,
    /** the linear solution failed (a mechanism, an error, nothing drawn): no N, nothing to buckle */
    NOT_SOLVED,
    /** nothing is compressed: these loads, however large, cannot make the structure buckle */
    NO_COMPRESSION
  }

  /** One buckling mode: its critical load factor and its shape, the largest displacement at 1. */
  public final class Mode {
    /** λcr: the loads times this buckle the structure */
    public final double factor;
    /** per element, at its parts + 1 points: displacement along and across it (local axes), and rotation */
    private final Map<Element, double[][]> shape = new IdentityHashMap<>();

    private Mode(double factor) {
      this.factor = factor;
    }

    /** The mode's displacement at x along an element (m from node a), on the global axes; at most about 1. */
    public double[] displacement(Element e, double x) {
      ElementResult r = solution.of(e);
      double[][] s = shape.get(e);
      double l = r.L / parts;
      int j = Math.max(0, Math.min(parts - 1, (int) Math.floor(x / l)));
      double t = (x - j * l) / l;
      double u = s[0][j] * (1 - t) + s[0][j + 1] * t;
      double v = (1 - 3 * t * t + 2 * t * t * t) * s[1][j] + l * (t - 2 * t * t + t * t * t) * s[2][j] + (3 * t * t - 2 * t * t * t) * s[1][j + 1]
          + l * (t * t * t - t * t) * s[2][j + 1];
      return new double[] {r.c * u - r.s * v, r.s * u + r.c * v};
    }
  }

  /**
   * An element's compression, and what buckling makes of it: N at λcr (its critical load), the buckling length
   * L_f = π √(EI / Ncr) (longueur de flambement), against the element alone, hinged at both ends: π²EI/L².
   */
  public record Member(Element element, double L, double EI, double A, double N) {
    /** Is it compressed under the loads? */
    public boolean compressed() {
      return N < 0;
    }

    /** Its compression when the structure buckles in a mode of factor λ (N), positive. */
    public double critical(double factor) {
      return -N * factor;
    }

    /** Buckling length in that mode (m). */
    public double length(double factor) {
      return Math.PI * Math.sqrt(EI / critical(factor));
    }

    /** Euler's load of the element on its own, hinged at both ends (N). */
    public double euler() {
      return Math.PI * Math.PI * EI / (L * L);
    }

    /** Slenderness L_f / i, i = √(I/A) the radius of gyration. */
    public double slenderness(double factor) {
      return length(factor) / Math.sqrt(EI / A);
    }
  }

  /** the linear solution whose N is taken */
  public final Solution solution;
  public Status status = Status.NOT_SOLVED;
  /** how many sub-elements each element is cut into */
  public final int parts;
  /** the modes, the smallest positive λcr first */
  public final List<Mode> modes = new ArrayList<>();
  /** every element, with its largest compression (the smallest N along it) */
  public final List<Member> members = new ArrayList<>();

  private Buckling(Solution sol) {
    solution = sol;
    int count = sol.structure.elements.size();
    parts = Math.max(2, Math.min(8, 200 / Math.max(1, count)));
  }

  /** The first buckling modes for the loads of a solution. */
  public static Buckling of(Solution sol) {
    Buckling b = new Buckling(sol);
    if (sol.ok()) b.solve();
    return b;
  }

  /** The first mode's λcr: the safety factor against buckling (NaN without a mode). */
  public double factor() {
    return modes.isEmpty() ? Double.NaN : modes.get(0).factor;
  }

  public boolean ok() {
    return status == Status.OK;
  }

  public Member of(Element e) {
    for (Member m : members) if (m.element == e) return m;
    return null;
  }

  private void solve() {
    Structure st = solution.structure;
    int n = st.nodes.size();
    Map<Node, Integer> index = new IdentityHashMap<>();
    for (int i = 0; i < n; i++) index.put(st.nodes.get(i), i);
    boolean[] used = new boolean[n], turns = new boolean[n];
    double largest = 0;
    for (ElementResult r : solution.elements) {
      used[r.a] = used[r.b] = true;
      if (!r.element.hingeA) turns[r.a] = true;
      if (!r.element.hingeB) turns[r.b] = true;
      double nMin = Double.POSITIVE_INFINITY;
      for (double x : r.samples(40)) {
        nMin = Math.min(nMin, r.N(x));
        largest = Math.max(largest, Math.abs(r.N(x)));
      }
      members.add(new Member(r.element, r.L, r.EI, r.element.section.area(), nMin));
    }
    double tiny = 1e-9 * largest;
    boolean any = false;
    for (int i = 0; i < members.size(); i++) {
      Member m = members.get(i);
      if (m.N > -tiny && m.N < 0) members.set(i, new Member(m.element, m.L, m.EI, m.A, 0));
      any |= m.N <= -tiny;
    }
    if (!any) {
      status = Status.NO_COMPRESSION;
      return;
    }

    // unknowns: the nodes' (as in Solver), each element's points between its nodes, and the rotation of a hinged end
    int next = 3 * n;
    List<int[][]> points = new ArrayList<>();
    for (ElementResult r : solution.elements) {
      int[][] p = new int[parts + 1][];
      p[0] = new int[] {3 * r.a, 3 * r.a + 1, r.element.hingeA ? next++ : 3 * r.a + 2};
      for (int k = 1; k < parts; k++) p[k] = new int[] {next++, next++, next++};
      p[parts] = new int[] {3 * r.b, 3 * r.b + 1, r.element.hingeB ? next++ : 3 * r.b + 2};
      points.add(p);
    }
    int N = next;
    double[][] K = new double[N][N], B = new double[N][N];
    double[][] frame = Solver.frames(st);
    double[] none = {1, 0, 0, 1};
    for (int e = 0; e < solution.elements.size(); e++) {
      ElementResult r = solution.elements.get(e);
      int[][] p = points.get(e);
      double l = r.L / parts;
      for (int k = 0; k < parts; k++) {
        double[][] G = Solver.transform(r, k == 0 ? frame[r.a] : none, k == parts - 1 ? frame[r.b] : none);
        int[] dofs = {p[k][0], p[k][1], p[k][2], p[k + 1][0], p[k + 1][1], p[k + 1][2]};
        add(K, stiffness(r.EA, r.EI, l), G, dofs);
        add(B, geometric(-r.N((k + 0.5) * l), l), G, dofs);
      }
    }
    for (int i = 0; i < n; i++) {
      Node nd = st.nodes.get(i);
      if (!used[i] || nd.support != Support.ELASTIQUE) continue;
      for (int k = 0; k < 3; k++) if (nd.spring(k) > 0 && (k < 2 || turns[i])) K[3 * i + k][3 * i + k] += nd.spring(k);
    }

    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (!used[i]) continue;
      Support sp = st.nodes.get(i).support;
      boolean[] blocked = {sp.t, sp.n, sp.r};
      for (int k = 0; k < 3; k++) if ((k < 2 || turns[i]) && !blocked[k]) free.add(3 * i + k);
    }
    for (int d = 3 * n; d < N; d++) free.add(d);
    int nf = free.size();
    double[][] Kff = new double[nf][nf], Bff = new double[nf][nf];
    for (int a = 0; a < nf; a++) {
      for (int c = 0; c < nf; c++) {
        Kff[a][c] = K[free.get(a)][free.get(c)];
        Bff[a][c] = B[free.get(a)][free.get(c)];
      }
    }
    Cholesky ch = new Cholesky(Kff);
    if (ch.nullity > 0) {
      status = Status.NOT_SOLVED;
      return;
    }
    Eigen eig = new Eigen(ch.reduce(Bff));
    double top = 0;
    for (double mu : eig.values) top = Math.max(top, Math.abs(mu));
    for (int j = 0; j < nf && modes.size() < MODES; j++) {
      double mu = eig.values[j];
      if (mu <= 1e-9 * top) break;
      double[] y = new double[nf];
      for (int i = 0; i < nf; i++) y[i] = eig.vectors[i][j];
      double[] yf = ch.back(y), phi = new double[N];
      for (int i = 0; i < nf; i++) phi[free.get(i)] = yf[i];
      modes.add(shape(1 / mu, phi, points, frame));
    }
    status = modes.isEmpty() ? Status.NO_COMPRESSION : Status.OK;
  }

  /** The mode from the unknowns: each element's points on its local axes, scaled to a largest displacement of 1. */
  private Mode shape(double factor, double[] phi, List<int[][]> points, double[][] frame) {
    Mode m = new Mode(factor);
    double max = 0, sign = 1;
    for (int e = 0; e < solution.elements.size(); e++) {
      ElementResult r = solution.elements.get(e);
      int[][] p = points.get(e);
      double[][] s = new double[3][parts + 1];
      for (int k = 0; k <= parts; k++) {
        double t = phi[p[k][0]], nn = phi[p[k][1]], gx = t, gy = nn;
        double[] R = k == 0 ? frame[r.a] : k == parts ? frame[r.b] : null;
        if (R != null) {
          gx = R[0] * t + R[2] * nn;
          gy = R[1] * t + R[3] * nn;
        }
        s[0][k] = r.c * gx + r.s * gy;
        s[1][k] = -r.s * gx + r.c * gy;
        s[2][k] = phi[p[k][2]];
        double d = Math.hypot(gx, gy);
        if (d > max * (1 + 1e-9)) {
          max = d;
          // the same mode always the same way: its largest displacement towards +x (or +y)
          sign = Math.abs(gx) >= Math.abs(gy) ? Math.signum(gx) : Math.signum(gy);
        }
      }
      m.shape.put(r.element, s);
    }
    double k = max == 0 ? 0 : sign / max;
    for (double[][] s : m.shape.values()) for (double[] row : s) for (int i = 0; i < row.length; i++) row[i] *= k;
    return m;
  }

  /** k_g of a part of length l under a compression P (> 0): the work of N in the cubic's slope, sign flipped. */
  private static double[][] geometric(double P, double l) {
    double a = P / (30 * l);
    return new double[][] {
        {0, 0, 0, 0, 0, 0},
        {0, 36 * a, 3 * l * a, 0, -36 * a, 3 * l * a},
        {0, 3 * l * a, 4 * l * l * a, 0, -3 * l * a, -l * l * a},
        {0, 0, 0, 0, 0, 0},
        {0, -36 * a, -3 * l * a, 0, 36 * a, -3 * l * a},
        {0, 3 * l * a, -l * l * a, 0, -3 * l * a, 4 * l * l * a}};
  }

  /** The Euler–Bernoulli element's stiffness, as ElementResult's. */
  private static double[][] stiffness(double EA, double EI, double l) {
    double ea = EA / l, ei = EI / l, ei2 = EI / (l * l), ei3 = EI / (l * l * l);
    return new double[][] {
        {ea, 0, 0, -ea, 0, 0},
        {0, 12 * ei3, 6 * ei2, 0, -12 * ei3, 6 * ei2},
        {0, 6 * ei2, 4 * ei, 0, -6 * ei2, 2 * ei},
        {-ea, 0, 0, ea, 0, 0},
        {0, -12 * ei3, -6 * ei2, 0, 12 * ei3, -6 * ei2},
        {0, 6 * ei2, 2 * ei, 0, -6 * ei2, 4 * ei}};
  }

  /** M += Gᵀ k G at the unknowns dofs. */
  private static void add(double[][] M, double[][] k, double[][] G, int[] dofs) {
    for (int p = 0; p < 6; p++) {
      for (int q = 0; q < 6; q++) {
        double v = 0;
        for (int i = 0; i < 6; i++) {
          if (G[i][p] == 0) continue;
          for (int j = 0; j < 6; j++) v += G[i][p] * k[i][j] * G[j][q];
        }
        M[dofs[p]][dofs[q]] += v;
      }
    }
  }
}
