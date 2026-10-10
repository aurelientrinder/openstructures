package rdm.model;

import java.util.ArrayList;
import java.util.List;

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
  /** the loads along it (the self-weight is added by the solver when the structure asks for it) */
  public final List<ElementLoad> loads = new ArrayList<>();

  public Element(Node a, Node b, Section section) {
    this.a = a;
    this.b = b;
    this.section = section;
  }

  public boolean isBar() {
    return hingeA && hingeB;
  }

  public boolean loaded() {
    return !loads.isEmpty();
  }

  public double length() {
    return Math.hypot(b.x - a.x, b.y - a.y);
  }

  public boolean touches(Node n) {
    return a == n || b == n;
  }

  /** Global components of a load given on this element's local axes (x from a to b). */
  public double[] toGlobal(double lx, double ly) {
    double L = length(), c = (b.x - a.x) / L, s = (b.y - a.y) / L;
    return new double[] {c * lx - s * ly, s * lx + c * ly};
  }
}
