package rdm.model;

/**
 * A load on an element, in a load case. Positions are fractions of its length (0 at node a, 1 at node b), so that
 * a load follows its element when a node moves. Components are on the global axes, or on the element's own axes
 * when {@code local} (x from a to b, y at +90° from x).
 */
public sealed interface ElementLoad {
  LoadCase loadCase();

  /** The same load, k times as large (in the same case). */
  ElementLoad scaled(double k);

  /** The same load in another case. */
  ElementLoad in(LoadCase c);

  /** A force (N) at t, strictly inside the element (a force at an end is a nodal load). */
  record Force(LoadCase loadCase, double t, double fx, double fy, boolean local) implements ElementLoad {
    @Override
    public Force scaled(double k) {
      return new Force(loadCase, t, k * fx, k * fy, local);
    }

    @Override
    public Force in(LoadCase c) {
      return new Force(c, t, fx, fy, local);
    }
  }

  /** A couple (N·m, counter-clockwise positive) at t, strictly inside the element. */
  record Couple(LoadCase loadCase, double t, double c) implements ElementLoad {
    @Override
    public Couple scaled(double k) {
      return new Couple(loadCase, t, k * c);
    }

    @Override
    public Couple in(LoadCase lc) {
      return new Couple(lc, t, c);
    }
  }

  /** A distributed load from t1 to t2 (N per metre of element), varying linearly from (qx1, qy1) to (qx2, qy2). */
  record Distributed(LoadCase loadCase, double t1, double t2, double qx1, double qy1, double qx2, double qy2, boolean local)
      implements ElementLoad {
    public static Distributed uniform(LoadCase c, double qx, double qy) {
      return new Distributed(c, 0, 1, qx, qy, qx, qy, false);
    }

    /** The load at fraction t of the element (between t1 and t2). */
    public double[] at(double t) {
      double k = t2 == t1 ? 0 : (t - t1) / (t2 - t1);
      return new double[] {qx1 + k * (qx2 - qx1), qy1 + k * (qy2 - qy1)};
    }

    @Override
    public Distributed scaled(double k) {
      return new Distributed(loadCase, t1, t2, k * qx1, k * qy1, k * qx2, k * qy2, local);
    }

    @Override
    public Distributed in(LoadCase c) {
      return new Distributed(c, t1, t2, qx1, qy1, qx2, qy2, local);
    }
  }
}
