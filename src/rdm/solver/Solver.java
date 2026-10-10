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
 * Displacement method (méthode des déplacements) for plane frames and trusses.
 *
 * Every node has three unknowns, ux, uy and θz, written in its support frame (t, n) when its support is skewed
 * so that the support blocks unknowns, not combinations of them. A node's rotation is an unknown only when an
 * element is welded to it; where every element is hinged, each has its own end rotation, condensed in the
 * element (see ElementResult). The stiffness of the free unknowns is factored once: its null pivots are the
 * mechanism's degrees of freedom.
 *
 * Degree of hyperstatism, as in schemes' hyperstatism.ts (unknowns − rank of the equilibrium equations): each
 * element carries 3 independent internal unknowns, less one per hinge; the supports' reactions balance their
 * own equations. Hence h = Σ(3 − hinges) − n_free + m, with n_free the unknown displacements and m the mobility.
 */
public final class Solver {
  private Solver() {}

  public static Solution solve(Structure st) {
    Solution sol = new Solution(st);
    if (st.elements.isEmpty()) return sol;
    int n = st.nodes.size();
    Map<Node, Integer> index = new IdentityHashMap<>();
    for (int i = 0; i < n; i++) index.put(st.nodes.get(i), i);

    boolean[] used = new boolean[n], turns = new boolean[n];
    int internal = 0;
    for (Element e : st.elements) {
      int a = index.get(e.a), b = index.get(e.b);
      String bad = e.length() == 0 ? "élément " + st.number(e) + " de longueur nulle"
          : e.section.problem() != null ? "élément " + st.number(e) + " : " + e.section.problem()
          : e.material.E() <= 0 ? "élément " + st.number(e) + " : module d'Young nul" : null;
      if (bad != null) {
        sol.status = Solution.Status.ERROR;
        sol.error = bad;
        return sol;
      }
      used[a] = used[b] = true;
      if (!e.hingeA) turns[a] = true;
      if (!e.hingeB) turns[b] = true;
      internal += 3 - (e.hingeA ? 1 : 0) - (e.hingeB ? 1 : 0);
      sol.add(new ElementResult(e, a, b));
    }

    // support frames: columns t and n, u_global = R u_frame
    double[][] frame = new double[n][];
    for (int i = 0; i < n; i++) {
      Node nd = st.nodes.get(i);
      if (nd.support.skewed()) {
        double th = Math.toRadians(nd.supportAngle), nx = Math.cos(th), ny = Math.sin(th);
        frame[i] = new double[] {ny, -nx, nx, ny};
      } else {
        frame[i] = new double[] {1, 0, 0, 1};
      }
    }

    int N = 3 * n;
    double[][] K = new double[N][N];
    double[] F = new double[N];
    for (ElementResult r : sol.elements) {
      double[][] G = transform(r, frame[r.a], frame[r.b]);
      int[] dofs = {3 * r.a, 3 * r.a + 1, 3 * r.a + 2, 3 * r.b, 3 * r.b + 1, 3 * r.b + 2};
      for (int p = 0; p < 6; p++) {
        double fp = 0;
        for (int i = 0; i < 6; i++) fp += G[i][p] * r.fc[i];
        F[dofs[p]] += fp;
        for (int q = 0; q < 6; q++) {
          double v = 0;
          for (int i = 0; i < 6; i++) {
            if (G[i][p] == 0) continue;
            for (int j = 0; j < 6; j++) v += G[i][p] * r.kc[i][j] * G[j][q];
          }
          K[dofs[p]][dofs[q]] += v;
        }
      }
    }
    for (int i = 0; i < n; i++) {
      Node nd = st.nodes.get(i);
      if (!used[i]) {
        if (nd.loaded() || nd.support != Support.NONE) sol.warnings.add("nœud " + (i + 1) + " isolé : ignoré");
        continue;
      }
      double[] R = frame[i];
      F[3 * i] += R[0] * nd.fx + R[1] * nd.fy;
      F[3 * i + 1] += R[2] * nd.fx + R[3] * nd.fy;
      if (turns[i]) F[3 * i + 2] += nd.mz;
      else if (nd.mz != 0) sol.warnings.add("nœud " + (i + 1) + " : moment ignoré, tous les éléments y sont articulés");
    }

    // the unknowns: used and not blocked by the support
    List<Integer> free = new ArrayList<>();
    boolean[] blocked = new boolean[N];
    for (int i = 0; i < n; i++) {
      if (!used[i]) continue;
      Support sp = st.nodes.get(i).support;
      blocked[3 * i] = sp.t;
      blocked[3 * i + 1] = sp.n;
      blocked[3 * i + 2] = sp.r;
      for (int k = 0; k < 3; k++) if ((k < 2 || turns[i]) && !blocked[3 * i + k]) free.add(3 * i + k);
    }

    int nf = free.size();
    double[][] Kff = new double[nf][nf];
    double[] Ff = new double[nf];
    for (int p = 0; p < nf; p++) {
      Ff[p] = F[free.get(p)];
      for (int q = 0; q < nf; q++) Kff[p][q] = K[free.get(p)][free.get(q)];
    }
    Cholesky ch = new Cholesky(Kff);
    sol.m = ch.nullity;
    sol.h = internal - nf + sol.m;
    if (sol.m > 0) {
      String[] names = {"ux", "uy", "θz"};
      for (int p = 0; p < nf; p++) {
        if (!ch.nullPivot[p]) continue;
        int d = free.get(p);
        Node nd = st.nodes.get(d / 3);
        String name = d % 3 < 2 && nd.support.skewed() ? (d % 3 == 0 ? "t (le long de l'appui)" : "n") : names[d % 3];
        sol.free.add("nœud " + (d / 3 + 1) + " : " + name);
      }
      sol.status = Solution.Status.MECHANISM;
      return sol;
    }

    double[] uf = ch.solve(Ff);
    double[] U = new double[N];
    for (int p = 0; p < nf; p++) U[free.get(p)] = uf[p];

    // displacements back on the global axes
    sol.disp = new double[n][];
    for (int i = 0; i < n; i++) {
      double[] R = frame[i];
      double ut = U[3 * i], un = U[3 * i + 1];
      sol.disp[i] = new double[] {R[0] * ut + R[2] * un, R[1] * ut + R[3] * un, used[i] && turns[i] ? U[3 * i + 2] : Double.NaN};
    }
    // reactions: K u − F on the blocked unknowns, back on the global axes
    sol.reaction = new double[n][];
    for (int i = 0; i < n; i++) {
      Node nd = st.nodes.get(i);
      if (!used[i] || nd.support == Support.NONE) continue;
      double[] rf = new double[3];
      for (int k = 0; k < 3; k++) {
        int d = 3 * i + k;
        if (!blocked[d] || (k == 2 && !turns[i])) continue;
        double v = -F[d];
        for (int j = 0; j < N; j++) v += K[d][j] * U[j];
        rf[k] = v;
      }
      double[] R = frame[i];
      sol.reaction[i] = new double[] {R[0] * rf[0] + R[2] * rf[1], R[1] * rf[0] + R[3] * rf[1], rf[2]};
    }
    for (ElementResult r : sol.elements) {
      double[][] G = transform(r, frame[r.a], frame[r.b]);
      int[] dofs = {3 * r.a, 3 * r.a + 1, 3 * r.a + 2, 3 * r.b, 3 * r.b + 1, 3 * r.b + 2};
      double[] ul = new double[6];
      for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) ul[i] += G[i][j] * U[dofs[j]];
      r.finish(ul);
      for (int k = 0; k <= 20; k++) {
        double[] d = r.displacement(r.L * k / 20);
        sol.maxDisplacement = Math.max(sol.maxDisplacement, Math.hypot(d[0], d[1]));
      }
    }
    sol.status = Solution.Status.OK;
    return sol;
  }

  /**
   * From the end nodes' unknowns (in their support frames) to the element's local end displacements:
   * per node, the rotation to local axes [c s; −s c] times the frame [t n].
   */
  private static double[][] transform(ElementResult r, double[] Ra, double[] Rb) {
    double[][] G = new double[6][6];
    for (int end = 0; end < 2; end++) {
      double[] R = end == 0 ? Ra : Rb;
      int o = 3 * end;
      G[o][o] = r.c * R[0] + r.s * R[1];
      G[o][o + 1] = r.c * R[2] + r.s * R[3];
      G[o + 1][o] = -r.s * R[0] + r.c * R[1];
      G[o + 1][o + 1] = -r.s * R[2] + r.c * R[3];
      G[o + 2][o + 2] = 1;
    }
    return G;
  }
}
