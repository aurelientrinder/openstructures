package rdm.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Ready-made structures (Fichier › Exemples), also handy to try the solver. */
public final class Examples {
  private Examples() {}

  public static final Map<String, Supplier<Structure>> ALL = new LinkedHashMap<>();

  static {
    ALL.put("Poutre console (force en bout)", Examples::cantilever);
    ALL.put("Poutre sur deux appuis (charge répartie)", Examples::simplySupported);
    ALL.put("Poutre continue sur trois appuis (h = 1)", Examples::continuous);
    ALL.put("Portique encastré", Examples::portal);
    ALL.put("Portique à trois articulations", Examples::threeHinged);
    ALL.put("Treillis Warren", Examples::warren);
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
    s.addElement(a, b, false).qy = -5e3;
    return s;
  }

  static Structure continuous() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(4, 0), c = s.addNode(8, 0);
    a.support = Support.PIVOT;
    b.support = Support.APPUI_SIMPLE;
    c.support = Support.APPUI_SIMPLE;
    s.addElement(a, b, false).qy = -10e3;
    s.addElement(b, c, false).qy = -10e3;
    return s;
  }

  static Structure portal() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 4), c = s.addNode(6, 4), d = s.addNode(6, 0);
    a.support = d.support = Support.ENCASTREMENT;
    b.fx = 5e3;
    s.addElement(a, b, false);
    s.addElement(b, c, false).qy = -8e3;
    s.addElement(c, d, false);
    return s;
  }

  static Structure threeHinged() {
    Structure s = new Structure();
    Node a = s.addNode(0, 0), b = s.addNode(0, 3), top = s.addNode(4, 5), c = s.addNode(8, 3), d = s.addNode(8, 0);
    a.support = d.support = Support.PIVOT;
    s.addElement(a, b, false);
    s.addElement(b, top, false).qy = -6e3;
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
}
