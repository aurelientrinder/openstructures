package rdm.model;

import static rdm.i18n.Tr.tr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Ready-made structures (File › Examples), also handy to try the solver. Keyed by name; title() gives the title. */
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
  }

  /** The title of an example, in the interface's language. */
  public static String title(String name) {
    return tr("example." + name);
  }

  private static void uniform(Element e, double qy) {
    e.loads.add(ElementLoad.Distributed.uniform(0, qy));
  }

  static Structure cantilever() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(4, 0);
    a.support = Support.ENCASTREMENT;
    b.fy = -10e3;
    s.addElement(a, b, false);
    return s;
  }

  static Structure simplySupported() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(6, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    uniform(s.addElement(a, b, false), -5e3);
    return s;
  }

  static Structure continuous() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(4, 0), c = s.addNode(8, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    c.support = Support.APPUI_SIMPLE;
    uniform(s.addElement(a, b, false), -10e3);
    uniform(s.addElement(b, c, false), -10e3);
    return s;
  }

  static Structure portal() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 4), c = s.addNode(6, 4), d = s.addNode(6, 0);
    a.support = d.support = Support.ENCASTREMENT;
    b.fx = 5e3;
    s.addElement(a, b, false);
    uniform(s.addElement(b, c, false), -8e3);
    s.addElement(c, d, false);
    return s;
  }

  static Structure threeHinged() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 3), top = s.addNode(4, 5), c = s.addNode(8, 3), d = s.addNode(8, 0);
    a.support = d.support = Support.PIVOT;
    s.addElement(a, b, false);
    uniform(s.addElement(b, top, false), -6e3);
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
    for (int i = 1; i < panels; i++) low[i].fy = -20e3;
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
    Node a = s.addNode(0, 0), b = s.addNode(6, 0), c = s.addNode(8, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    Element span = s.addElement(a, b, false);
    span.loads.add(new ElementLoad.Force(1.0 / 3, 0, -15e3, false));
    span.loads.add(new ElementLoad.Distributed(0.5, 1, 0, 0, 0, -8e3, false));
    s.addElement(b, c, false).loads.add(new ElementLoad.Couple(0.5, 5e3));
    c.fy = -4e3;
    return s;
  }

  static Structure springAndSettlement() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(5, 0), c = s.addNode(10, 0);
    a.support = Support.ENCASTREMENT;
    b.support = Support.ELASTIQUE;
    b.springN = 5e6;
    c.support = Support.APPUI_SIMPLE;
    c.settleN = -0.01;
    uniform(s.addElement(a, b, false), -10e3);
    uniform(s.addElement(b, c, false), -10e3);
    return s;
  }
}
