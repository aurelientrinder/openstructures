package rdm.model;

import static rdm.i18n.Tr.tr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ready-made structures (File › Examples), also handy to try the solver. Keyed by name; title() gives the title.
 * Their loads are in the structure's first case, G, except in the one made to show load cases and combinations.
 */
public final class Examples {
  private Examples() {}

  public static final Map<String, Supplier<Structure>> ALL = new LinkedHashMap<>();

  static {
    ALL.put("cantilever", Examples::cantilever);
    ALL.put("simplySupported", Examples::simplySupported);
    ALL.put("continuous", Examples::continuous);
    ALL.put("portal", Examples::portal);
    ALL.put("threeHinged", Examples::threeHinged);
    ALL.put("warren", Examples::warren);
    ALL.put("mixedLoads", Examples::mixedLoads);
    ALL.put("springAndSettlement", Examples::springAndSettlement);
    ALL.put("portalCases", Examples::portalCases);
    ALL.put("eulerColumn", Examples::eulerColumn);
    ALL.put("bucklingPortal", Examples::bucklingPortal);
  }

  /** The title of an example, in the interface's language. */
  public static String title(String name) {
    return tr("example." + name);
  }

  private static void uniform(Element e, LoadCase c, double qy) {
    e.loads.add(ElementLoad.Distributed.uniform(c, 0, qy));
  }

  private static void uniform(Element e, Structure s, double qy) {
    uniform(e, s.cases.get(0), qy);
  }

  private static NodeLoad load(Node n, Structure s) {
    return n.loadIn(s.cases.get(0));
  }

  static Structure cantilever() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(4, 0);
    a.support = Support.ENCASTREMENT;
    load(b, s).fy = -10e3;
    s.addElement(a, b, false);
    return s;
  }

  static Structure simplySupported() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(6, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    uniform(s.addElement(a, b, false), s, -5e3);
    return s;
  }

  static Structure continuous() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(4, 0), c = s.addNode(8, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    c.support = Support.APPUI_SIMPLE;
    uniform(s.addElement(a, b, false), s, -10e3);
    uniform(s.addElement(b, c, false), s, -10e3);
    return s;
  }

  static Structure portal() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 4), c = s.addNode(6, 4), d = s.addNode(6, 0);
    a.support = d.support = Support.ENCASTREMENT;
    load(b, s).fx = 5e3;
    s.addElement(a, b, false);
    uniform(s.addElement(b, c, false), s, -8e3);
    s.addElement(c, d, false);
    return s;
  }

  static Structure threeHinged() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 3), top = s.addNode(4, 5), c = s.addNode(8, 3), d = s.addNode(8, 0);
    a.support = d.support = Support.PIVOT;
    s.addElement(a, b, false);
    uniform(s.addElement(b, top, false), s, -6e3);
    s.addElement(top, c, false).hingeA = true;
    s.addElement(c, d, false);
    return s;
  }

  static Structure warren() {
    Structure s = new Structure();
    int panels = 4;
    double w = 2, h = 2;
    Node[] low = new Node[panels + 1];
    Node[] high = new Node[panels];
    for (int i = 0; i <= panels; i++) low[i] = s.addNode(i * w, 0);
    for (int i = 0; i < panels; i++) high[i] = s.addNode(i * w + w / 2, h);
    low[0].support = Support.PIVOT;
    low[panels].support = Support.APPUI_SIMPLE;
    for (int i = 1; i < panels; i++) load(low[i], s).fy = -20e3;
    for (int i = 0; i < panels; i++) {
      s.addElement(low[i], low[i + 1], true);
      s.addElement(low[i], high[i], true);
      s.addElement(high[i], low[i + 1], true);
      if (i + 1 < panels) s.addElement(high[i], high[i + 1], true);
    }
    return s;
  }

  static Structure mixedLoads() {
    Structure s = new Structure();
    LoadCase g = s.cases.get(0);
    Node a = s.addNode(0, 0), b = s.addNode(6, 0), c = s.addNode(8, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    Element span = s.addElement(a, b, false);
    span.loads.add(new ElementLoad.Force(g, 1.0 / 3, 0, -15e3, false));
    span.loads.add(new ElementLoad.Distributed(g, 0.5, 1, 0, 0, 0, -8e3, false));
    s.addElement(b, c, false).loads.add(new ElementLoad.Couple(g, 0.5, 5e3));
    load(c, s).fy = -4e3;
    return s;
  }

  static Structure springAndSettlement() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(5, 0), c = s.addNode(10, 0);
    a.support = Support.ENCASTREMENT;
    b.support = Support.ELASTIQUE;
    b.springN = 5e6;
    c.support = Support.APPUI_SIMPLE;
    load(c, s).settleN = -0.01;
    uniform(s.addElement(a, b, false), s, -10e3);
    uniform(s.addElement(b, c, false), s, -10e3);
    return s;
  }

  /**
   * The fixed portal frame under three cases: G its self-weight and the roof's (6 kN/m), Q an imposed load on the
   * roof (5 kN/m), W the wind on the columns (pressure 2 kN/m on the left one, suction 1 kN/m on the right one);
   * with the usual combinations, whose envelope the beam and the columns are designed for.
   */
  static Structure portalCases() {
    Structure s = new Structure();
    LoadCase g = s.cases.get(0), q = s.addCase("Q", LoadCase.Type.VARIABLE), w = s.addCase("W", LoadCase.Type.VARIABLE);
    g.selfWeight = true;
    Node a = s.addNode(0, 0), b = s.addNode(0, 4), c = s.addNode(6, 4), d = s.addNode(6, 0);
    a.support = d.support = Support.ENCASTREMENT;
    Element left = s.addElement(a, b, false), beam = s.addElement(b, c, false), right = s.addElement(c, d, false);
    uniform(beam, g, -6e3);
    uniform(beam, q, -5e3);
    left.loads.add(ElementLoad.Distributed.uniform(w, 2e3, 0));
    right.loads.add(ElementLoad.Distributed.uniform(w, 1e3, 0));
    s.generateCombinations();
    return s;
  }

  /**
   * Euler's column: a round tube 60 × 4, 3 m high, pinned at its foot, its head held sideways by a roller (normal
   * along x), 40 kN down on it. π²EI/L² = 63.9 kN: λcr = 1.6.
   */
  static Structure eulerColumn() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 3);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    b.supportAngle = 0;
    load(b, s).fy = -40e3;
    s.addElement(a, b, false).section = new Section(Section.Kind.TUBE, 60, 4, 0);
    return s;
  }

  /**
   * A portal frame with fixed feet, IPE 200 columns 4 m high and an IPE 240 beam 6 m long, 500 kN on the head of
   * each column: it buckles sideways (sway), each column's buckling length more than its height; λcr = 4.
   */
  static Structure bucklingPortal() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 4), c = s.addNode(6, 4), d = s.addNode(6, 0);
    a.support = d.support = Support.ENCASTREMENT;
    load(b, s).fy = load(c, s).fy = -500e3;
    s.addElement(a, b, false);
    s.addElement(b, c, false).section = new Section(Section.Kind.IPE, 240, 0, 0);
    s.addElement(c, d, false);
    return s;
  }
}
