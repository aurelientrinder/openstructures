package rdm.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A plane structure (ossature plane): nodes and the elements between them, its load cases (at least one) and
 * their combinations. Numbers shown to the user start at 1.
 */
public final class Structure {
  public final List<Node> nodes = new ArrayList<>();
  public final List<Element> elements = new ArrayList<>();
  public final List<LoadCase> cases = new ArrayList<>();
  public final List<Combination> combinations = new ArrayList<>();

  /** gravity, m/s² */
  public static final double G = 9.81;

  /** Partial factors of EN 1990 (6.10): permanent actions, unfavourable; variable ones; ψ0 of imposed loads in buildings. */
  public static final double GAMMA_G = 1.35, GAMMA_Q = 1.5, PSI0_GAMMA_Q = 1.05, PSI0 = 0.7;

  /** the deflection checks' limit: an element's deflection at most its span over this (L/300) */
  public double deflectionLimit = 300;

  /** A structure with one permanent load case, G. */
  public Structure() {
    cases.add(new LoadCase("G", LoadCase.Type.PERMANENT));
  }

  public LoadCase addCase(String name, LoadCase.Type type) {
    LoadCase c = new LoadCase(name, type);
    cases.add(c);
    return c;
  }

  /**
   * Removes a load case with its loads (on the nodes and the elements) and its factor in the combinations. The
   * last case stays: false then.
   */
  public boolean removeCase(LoadCase c) {
    if (cases.size() <= 1 || !cases.remove(c)) return false;
    for (Node n : nodes) n.forget(c);
    for (Element e : elements) e.loads.removeIf(l -> l.loadCase() == c);
    for (Combination k : combinations) k.forget(c);
    return true;
  }

  public Combination addCombination(String name) {
    Combination k = new Combination(name);
    combinations.add(k);
    return k;
  }

  /**
   * Adds the usual combinations of EN 1990, named by their formula, unless one with the same limit state and
   * factors is already there. With G every permanent case and Qi each variable one: at the ultimate limit state
   * (6.10), 1.35 G + 1.5 Qi + 1.05 Qj… (Qi leading, the others accompanying with ψ0 = 0.7) and G + 1.5 Qi (the
   * permanent actions favourable); at the serviceability limit state, characteristic (6.14b), G + Qi + 0.7 Qj….
   * With nothing variable, 1.35 G and G. Returns how many were added.
   */
  public int generateCombinations() {
    List<LoadCase> variable = cases.stream().filter(c -> c.type == LoadCase.Type.VARIABLE).toList();
    List<Combination> made = new ArrayList<>();
    if (variable.isEmpty()) {
      made.add(combination(GAMMA_G, null, 1, 0));
      made.add(combination(1, null, 1, 0));
    } else {
      for (LoadCase lead : variable) made.add(combination(GAMMA_G, lead, GAMMA_Q, PSI0_GAMMA_Q));
      for (LoadCase lead : variable) made.add(combination(1, lead, GAMMA_Q, 0));
      for (LoadCase lead : variable) made.add(combination(1, lead, 1, PSI0));
    }
    // the serviceability ones are the last of each list
    int sls = variable.isEmpty() ? 1 : 2 * variable.size();
    for (int i = sls; i < made.size(); i++) made.get(i).limit = Combination.Limit.SLS;
    int added = 0;
    for (Combination k : made) {
      if (combinations.stream().anyMatch(o -> o.sameFactors(k, this))) continue;
      k.name = k.formula(this);
      combinations.add(k);
      added++;
    }
    return added;
  }

  /** Permanent cases at g, the leading variable case at q, the other variable ones at accompanying. */
  private Combination combination(double g, LoadCase lead, double q, double accompanying) {
    Combination k = new Combination("");
    for (LoadCase c : cases) {
      k.setFactor(c, c.type == LoadCase.Type.PERMANENT ? g : c == lead ? q : accompanying);
    }
    return k;
  }

  /** The combinations for one limit state. */
  public List<Combination> combinations(Combination.Limit limit) {
    return combinations.stream().filter(k -> k.limit == limit).toList();
  }

  /** The number of a case, from 1. */
  public int number(LoadCase c) {
    return cases.indexOf(c) + 1;
  }

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
    second.grade = e.grade;
    second.bucklingFactor = e.bucklingFactor;
    second.hingeB = e.hingeB;
    // a truss bar cut in two stays two truss bars
    second.hingeA = e.isBar();
    e.hingeB = e.isBar();

    List<ElementLoad> first = new ArrayList<>();
    for (ElementLoad l : e.loads) {
      switch (l) {
        case ElementLoad.Force f when Math.abs(f.t() - t) < 1e-12 -> {
          double[] g = f.local() ? e.toGlobal(f.fx(), f.fy()) : new double[] {f.fx(), f.fy()};
          NodeLoad nl = n.loadIn(f.loadCase());
          nl.fx += g[0];
          nl.fy += g[1];
        }
        case ElementLoad.Force f -> (f.t() < t ? first : second.loads)
            .add(new ElementLoad.Force(f.loadCase(), f.t() < t ? f.t() / t : (f.t() - t) / (1 - t), f.fx(), f.fy(), f.local()));
        case ElementLoad.Couple c when Math.abs(c.t() - t) < 1e-12 -> n.loadIn(c.loadCase()).mz += c.c();
        case ElementLoad.Couple c -> (c.t() < t ? first : second.loads)
            .add(new ElementLoad.Couple(c.loadCase(), c.t() < t ? c.t() / t : (c.t() - t) / (1 - t), c.c()));
        case ElementLoad.Distributed d -> {
          if (d.t1() < t) {
            double end = Math.min(d.t2(), t);
            double[] q = d.at(end);
            first.add(new ElementLoad.Distributed(d.loadCase(), d.t1() / t, end / t, d.qx1(), d.qy1(), q[0], q[1], d.local()));
          }
          if (d.t2() > t) {
            double start = Math.max(d.t1(), t);
            double[] q = d.at(start);
            second.loads.add(new ElementLoad.Distributed(d.loadCase(), (start - t) / (1 - t), (d.t2() - t) / (1 - t), q[0], q[1], d.qx2(), d.qy2(),
                d.local()));
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
