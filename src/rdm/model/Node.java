package rdm.model;

/** A node of the structure, in metres, with its support and the loads applied on it. */
public final class Node {
  public double x, y;
  public Support support = Support.NONE;
  /** direction of the support's normal n, degrees from x, counter-clockwise (90: the ground is below) */
  public double supportAngle = 90;
  /** nodal load: forces in N, moment in N·m (counter-clockwise positive) */
  public double fx, fy, mz;

  public Node(double x, double y) {
    this.x = x;
    this.y = y;
  }

  public boolean loaded() {
    return fx != 0 || fy != 0 || mz != 0;
  }
}
