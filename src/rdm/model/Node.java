package rdm.model;

/** A node of the structure, in metres, with its support and the loads applied on it. */
public final class Node {
  public double x, y;
  public Support support = Support.NONE;
  /** direction of the support's normal n, degrees from x, counter-clockwise (90: the ground is below) */
  public double supportAngle = 90;
  /** nodal load: forces in N, moment in N·m (counter-clockwise positive) */
  public double fx, fy, mz;
  /** imposed displacement of the blocked components, in the support frame (t, n: m; rotation: rad) */
  public double settleT, settleN, settleR;
  /** spring stiffnesses of an appui élastique, in the support frame (t, n: N/m; rotation: N·m/rad); 0 = free */
  public double springT, springN, springR;

  public Node(double x, double y) {
    this.x = x;
    this.y = y;
  }

  public boolean loaded() {
    return fx != 0 || fy != 0 || mz != 0;
  }

  /** Imposed displacement of support-frame component k (0: t, 1: n, 2: rotation). */
  public double settle(int k) {
    return k == 0 ? settleT : k == 1 ? settleN : settleR;
  }

  /** Spring stiffness of support-frame component k (0: t, 1: n, 2: rotation). */
  public double spring(int k) {
    return k == 0 ? springT : k == 1 ? springN : springR;
  }
}
