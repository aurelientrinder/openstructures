package rdm.model;

/**
 * A straight element between two nodes: a beam (poutre: traction, compression and bending) whose ends are
 * welded to their nodes unless a hinge (rotule) releases the rotation there. A truss bar (barre) is an
 * element hinged at both ends: with no load along it, it only carries a normal force.
 */
public final class Element {
  public Node a, b;
  public boolean hingeA, hingeB;
  public Section section;
  public Material material = Material.STEEL;
  /** uniform distributed load, N per metre of element, on the global axes */
  public double qx, qy;

  public Element(Node a, Node b, Section section) {
    this.a = a;
    this.b = b;
    this.section = section;
  }

  public boolean isBar() {
    return hingeA && hingeB;
  }

  public boolean loaded() {
    return qx != 0 || qy != 0;
  }

  public double length() {
    return Math.hypot(b.x - a.x, b.y - a.y);
  }

  public boolean touches(Node n) {
    return a == n || b == n;
  }
}
