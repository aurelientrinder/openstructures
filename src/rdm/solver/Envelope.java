package rdm.solver;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import rdm.model.Combination;
import rdm.model.Element;
import rdm.model.Structure;

/**
 * The envelope of the combinations (courbe enveloppe): every combination solved, and along each element the
 * largest and smallest N, V and M any of them gives, with the largest and smallest reactions at each support.
 *
 * Along an element the extremes are taken at the abscissas where some combination's curves may peak or jump: even
 * steps, either side of every load's jump, and where each combination's V vanishes (its M peaks).
 */
public final class Envelope {
  /** the internal forces, as ElementResult gives them */
  public enum Effort {
    N, V, M;

    public double at(ElementResult r, double x) {
      return switch (this) {
        case N -> r.N(x);
        case V -> r.T(x);
        case M -> r.M(x);
      };
    }
  }

  /** One element's extremes, at the abscissas x (m from node a). */
  public static final class Along {
    public final Element element;
    public final double[] x;
    private final double[][] min = new double[3][], max = new double[3][];
    private final int[][] minBy = new int[3][], maxBy = new int[3][];

    Along(Element element, double[] x) {
      this.element = element;
      this.x = x;
    }

    public double[] min(Effort q) {
      return min[q.ordinal()];
    }

    public double[] max(Effort q) {
      return max[q.ordinal()];
    }

    /** The index of the combination that gives the smallest (largest) value at x[i]. */
    public int minBy(Effort q, int i) {
      return minBy[q.ordinal()][i];
    }

    public int maxBy(Effort q, int i) {
      return maxBy[q.ordinal()][i];
    }

    /** The index in x of the smallest of the minima (of the largest of the maxima). */
    public int lowest(Effort q) {
      double[] v = min(q);
      int at = 0;
      for (int i = 1; i < v.length; i++) if (v[i] < v[at]) at = i;
      return at;
    }

    public int highest(Effort q) {
      double[] v = max(q);
      int at = 0;
      for (int i = 1; i < v.length; i++) if (v[i] > v[at]) at = i;
      return at;
    }

    /** The largest |value| either curve reaches. */
    public double maxAbs(Effort q) {
      return Math.max(Math.abs(min(q)[lowest(q)]), Math.abs(max(q)[highest(q)]));
    }
  }

  public final Structure structure;
  public final List<Combination> combinations;
  /** each combination's solution, in the same order */
  public final List<Solution> solutions = new ArrayList<>();
  /** the first combination's solution that is not OK (a mechanism, an error), or null */
  public Solution failed;
  private final Map<Element, Along> along = new IdentityHashMap<>();
  /** per node: the smallest and largest Rx, Ry, Mz over the combinations, null without a support */
  public double[][] minReaction, maxReaction;
  /** the largest displacement of any point in any combination, m, and the index of that combination */
  public double maxDisplacement;
  public int maxDisplacementBy;

  private Envelope(Structure s) {
    structure = s;
    combinations = List.copyOf(s.combinations);
  }

  /** Solves every combination of the structure and keeps the extremes. */
  public static Envelope of(Structure s) {
    Envelope env = new Envelope(s);
    for (Combination k : env.combinations) {
      Solution sol = Solver.solve(s, k.loading());
      env.solutions.add(sol);
      if (!sol.ok() && env.failed == null) env.failed = sol;
    }
    if (env.ok()) env.extremes();
    return env;
  }

  /** Every combination solved (and there is at least one). */
  public boolean ok() {
    return !solutions.isEmpty() && failed == null;
  }

  public Along of(Element e) {
    return along.get(e);
  }

  private void extremes() {
    int n = structure.nodes.size();
    minReaction = new double[n][];
    maxReaction = new double[n][];
    for (int k = 0; k < solutions.size(); k++) {
      Solution sol = solutions.get(k);
      if (sol.maxDisplacement > maxDisplacement || k == 0) {
        maxDisplacement = sol.maxDisplacement;
        maxDisplacementBy = k;
      }
      for (int i = 0; i < n; i++) {
        double[] r = sol.reaction[i];
        if (r == null) continue;
        if (minReaction[i] == null) {
          minReaction[i] = r.clone();
          maxReaction[i] = r.clone();
        }
        for (int j = 0; j < 3; j++) {
          minReaction[i][j] = Math.min(minReaction[i][j], r[j]);
          maxReaction[i][j] = Math.max(maxReaction[i][j], r[j]);
        }
      }
    }
    for (Element e : structure.elements) {
      TreeSet<Double> xs = new TreeSet<>();
      for (Solution sol : solutions) {
        ElementResult r = sol.of(e);
        for (double x : r.samples(40)) xs.add(x);
        xs.addAll(r.extremaM());
      }
      Along a = new Along(e, xs.stream().mapToDouble(Double::doubleValue).toArray());
      for (Effort q : Effort.values()) {
        int o = q.ordinal(), m = a.x.length;
        a.min[o] = new double[m];
        a.max[o] = new double[m];
        a.minBy[o] = new int[m];
        a.maxBy[o] = new int[m];
        for (int i = 0; i < m; i++) {
          for (int k = 0; k < solutions.size(); k++) {
            double v = q.at(solutions.get(k).of(e), a.x[i]);
            if (k == 0 || v < a.min[o][i]) {
              a.min[o][i] = v;
              a.minBy[o][i] = k;
            }
            if (k == 0 || v > a.max[o][i]) {
              a.max[o][i] = v;
              a.maxBy[o][i] = k;
            }
          }
        }
      }
      along.put(e, a);
    }
  }

  /** The smallest and largest value of an internal force at x along an element, over the combinations. */
  public double[] range(Element e, Effort q, double x) {
    double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
    for (Solution sol : solutions) {
      double v = q.at(sol.of(e), x);
      lo = Math.min(lo, v);
      hi = Math.max(hi, v);
    }
    return new double[] {lo, hi};
  }
}
