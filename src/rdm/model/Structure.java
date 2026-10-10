package rdm.model;

import java.util.ArrayList;
import java.util.List;

/** A plane structure (ossature plane): nodes and the elements between them. Numbers shown to the user start at 1. */
public final class Structure {
  public final List<Node> nodes = new ArrayList<>();
  public final List<Element> elements = new ArrayList<>();
  /** add every element's own weight (ρ·A·g per metre, downwards) to its loads */
  public boolean selfWeight;

  /** gravity, m/s² */
  public static final double G = 9.81;

  public Node addNode(double x, double y) {
    Node n = new Node(x, y);
    nodes.add(n);
    return n;
  }

  /** A new element; a truss bar is hinged at both ends. */
  public Element addElement(Node a, Node b, boolean bar) {
    Element e = new Element(a, b, bar ? Section.BAR_DEFAULT : Section.BEAM_DEFAULT);
    e.hingeA = e.hingeB = bar;
    elements.add(e);
    return e;
  }

  /** The element already joining these two nodes, if any. */
  public Element between(Node a, Node b) {
    for (Element e : elements) if ((e.a == a && e.b == b) || (e.a == b && e.b == a)) return e;
    return null;
  }

  public List<Element> elementsAt(Node n) {
    List<Element> out = new ArrayList<>();
    for (Element e : elements) if (e.touches(n)) out.add(e);
    return out;
  }

  /** Removes a node and the elements that join it. */
  public void removeNode(Node n) {
    elements.removeIf(e -> e.touches(n));
    nodes.remove(n);
  }

  /** Removes an element, and its end nodes when nothing is left on them (no element, support nor load). */
  public void removeElement(Element e) {
    elements.remove(e);
    for (Node n : List.of(e.a, e.b)) if (elementsAt(n).isEmpty() && n.support == Support.NONE && !n.loaded()) nodes.remove(n);
  }

  /**
   * Cuts an element in two at the point t (0–1) along it, through a new node: both halves keep its section and
   * material, the first its hinge at a, the second its hinge at b. Its loads go to the half they stand on (a
   * distributed load across the cut is cut too); a force or couple right at the cut becomes the new node's.
   */
  public Node split(Element e, double t) {
    Node n = addNode(e.a.x + t * (e.b.x - e.a.x), e.a.y + t * (e.b.y - e.a.y));
    Element second = new Element(n, e.b, e.section);
    second.material = e.material;
    second.hingeB = e.hingeB;
    // a truss bar cut in two stays two truss bars
    second.hingeA = e.isBar();
    e.hingeB = e.isBar();

    List<ElementLoad> first = new ArrayList<>();
    for (ElementLoad l : e.loads) {
      switch (l) {
        case ElementLoad.Force f when Math.abs(f.t() - t) < 1e-12 -> {
          double[] g = f.local() ? e.toGlobal(f.fx(), f.fy()) : new double[] {f.fx(), f.fy()};
          n.fx += g[0];
          n.fy += g[1];
        }
        case ElementLoad.Force f -> (f.t() < t ? first : second.loads)
            .add(new ElementLoad.Force(f.t() < t ? f.t() / t : (f.t() - t) / (1 - t), f.fx(), f.fy(), f.local()));
        case ElementLoad.Couple c when Math.abs(c.t() - t) < 1e-12 -> n.mz += c.c();
        case ElementLoad.Couple c -> (c.t() < t ? first : second.loads).add(new ElementLoad.Couple(c.t() < t ? c.t() / t : (c.t() - t) / (1 - t), c.c()));
        case ElementLoad.Distributed d -> {
          if (d.t1() < t) {
            double end = Math.min(d.t2(), t);
            double[] q = d.at(end);
            first.add(new ElementLoad.Distributed(d.t1() / t, end / t, d.qx1(), d.qy1(), q[0], q[1], d.local()));
          }
          if (d.t2() > t) {
            double start = Math.max(d.t1(), t);
            double[] q = d.at(start);
            second.loads.add(new ElementLoad.Distributed((start - t) / (1 - t), (d.t2() - t) / (1 - t), q[0], q[1], d.qx2(), d.qy2(), d.local()));
          }
        }
      }
    }
    e.loads.clear();
    e.loads.addAll(first);
    e.b = n;
    elements.add(elements.indexOf(e) + 1, second);
    return n;
  }

  public int number(Node n) {
    return nodes.indexOf(n) + 1;
  }

  public int number(Element e) {
    return elements.indexOf(e) + 1;
  }

  public boolean isEmpty() {
    return nodes.isEmpty();
  }

  /** Bounding box {xmin, ymin, xmax, ymax}, or null when empty. */
  public double[] bounds() {
    if (nodes.isEmpty()) return null;
    double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    for (Node n : nodes) {
      b[0] = Math.min(b[0], n.x);
      b[1] = Math.min(b[1], n.y);
      b[2] = Math.max(b[2], n.x);
      b[3] = Math.max(b[3], n.y);
    }
    return b;
  }
}
