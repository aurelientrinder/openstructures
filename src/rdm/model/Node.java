package rdm.model;

import java.util.IdentityHashMap;
import java.util.Map;

/** A node of the structure, in metres, with its support and, per load case, the loads applied on it. */
public final class Node {
  public double x, y;
  public Support support = Support.NONE;
  /** direction of the support's normal n, degrees from x, counter-clockwise (90: the ground is below) */
  public double supportAngle = 90;
  /** spring stiffnesses of an appui élastique, in the support frame (t, n: N/m; rotation: N·m/rad); 0 = free */
  public double springT, springN, springR;
  private final Map<LoadCase, NodeLoad> loads = new IdentityHashMap<>();

  public Node(double x, double y) {
    this.x = x;
    this.y = y;
  }

  /** What a case does here, to read: all zero when nothing (changing it changes nothing; see loadIn). */
  public NodeLoad load(LoadCase c) {
    NodeLoad l = loads.get(c);
    return l != null ? l : new NodeLoad();
  }

  /** What a case does here, to change. */
  public NodeLoad loadIn(LoadCase c) {
    return loads.computeIfAbsent(c, k -> new NodeLoad());
  }

  /** The cases' loads and imposed displacements, factored and added up. */
  public NodeLoad load(Loading l) {
    NodeLoad sum = new NodeLoad();
    loads.forEach((c, v) -> sum.add(v, l.factor(c)));
    return sum;
  }

  void forget(LoadCase c) {
    loads.remove(c);
  }

  /** A nodal load in some case. */
  public boolean loaded() {
    return loads.values().stream().anyMatch(NodeLoad::loaded);
  }

  /** Spring stiffness of support-frame component k (0: t, 1: n, 2: rotation). */
  public double spring(int k) {
    return k == 0 ? springT : k == 1 ? springN : springR;
  }
}
