package rdm.model;

/**
 * What one load case does at a node: a nodal load, and imposed displacements of what its support blocks (a
 * support that settles in that case).
 */
public final class NodeLoad {
  /** forces in N, moment in N·m (counter-clockwise positive) */
  public double fx, fy, mz;
  /** imposed displacement of the blocked components, in the support frame (t, n: m; rotation: rad) */
  public double settleT, settleN, settleR;

  public boolean loaded() {
    return fx != 0 || fy != 0 || mz != 0;
  }

  public boolean isZero() {
    return !loaded() && settleT == 0 && settleN == 0 && settleR == 0;
  }

  /** Imposed displacement of support-frame component k (0: t, 1: n, 2: rotation). */
  public double settle(int k) {
    return k == 0 ? settleT : k == 1 ? settleN : settleR;
  }

  /** Adds k times another. */
  void add(NodeLoad o, double k) {
    fx += k * o.fx;
    fy += k * o.fy;
    mz += k * o.mz;
    settleT += k * o.settleT;
    settleN += k * o.settleN;
    settleR += k * o.settleR;
  }
}
