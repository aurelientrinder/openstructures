package rdm.model;

/**
 * A load on an element. Positions are fractions of its length (0 at node a, 1 at node b), so that a load follows
 * its element when a node moves. Components are on the global axes, or on the element's own axes when
 * {@code local} (x from a to b, y at +90° from x).
 */
public sealed interface ElementLoad {
  /** A force (N) at t, strictly inside the element (a force at an end is a nodal load). */
  record Force(double t, double fx, double fy, boolean local) implements ElementLoad {}

  /** A couple (N·m, counter-clockwise positive) at t, strictly inside the element. */
  record Couple(double t, double c) implements ElementLoad {}

  /** A distributed load from t1 to t2 (N per metre of element), varying linearly from (qx1, qy1) to (qx2, qy2). */
  record Distributed(double t1, double t2, double qx1, double qy1, double qx2, double qy2, boolean local) implements ElementLoad {
    public static Distributed uniform(double qx, double qy) {
      return new Distributed(0, 1, qx, qy, qx, qy, false);
    }

    /** The load at fraction t of the element (between t1 and t2). */
    public double[] at(double t) {
      double k = t2 == t1 ? 0 : (t - t1) / (t2 - t1);
      return new double[] {qx1 + k * (qx2 - qx1), qy1 + k * (qy2 - qy1)};
    }
  }
}
