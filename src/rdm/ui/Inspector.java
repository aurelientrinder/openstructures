package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Solution;

/**
 * Properties of the selection (or of the structure when nothing is selected), editable, with its results.
 * The form is built again only when what it shows changes kind; otherwise its values are refreshed in place,
 * so that a field being typed in keeps its focus.
 */
final class Inspector extends JPanel implements Scrollable {
  private final MainFrame app;
  private Object shownFor = new Object();
  private final List<Runnable> updaters = new ArrayList<>();
  private int row;

  Inspector(MainFrame app) {
    this.app = app;
    setLayout(new GridBagLayout());
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
  }

  void refresh() {
    Object key = signature();
    if (!Objects.equals(key, shownFor)) {
      shownFor = key;
      rebuild();
    } else {
      updaters.forEach(Runnable::run);
    }
  }

  /** What decides the form's fields: the selection, its support or section kind, whether there are results. */
  private Object signature() {
    Object sel = app.selection();
    Solution sol = app.solution();
    List<Object> k = new ArrayList<>();
    k.add(System.identityHashCode(app.structure()));
    k.add(sel == null ? "structure" : System.identityHashCode(sel));
    k.add(sol.status);
    if (sel instanceof Node n) k.add(n.support);
    if (sel instanceof Element e) {
      k.add(e.section.kind());
      k.add(e.material.name());
      // the list of loads is a row each: built again when it changes
      k.add(List.copyOf(e.loads));
    }
    return k;
  }

  private void rebuild() {
    removeAll();
    updaters.clear();
    row = 0;
    Object sel = app.selection();
    if (sel instanceof Node n) node(n);
    else if (sel instanceof Element e) element(e);
    else structure();
    GridBagConstraints filler = new GridBagConstraints();
    filler.gridy = row;
    filler.weighty = 1;
    add(new JPanel(), filler);
    revalidate();
    repaint();
  }

  // ——— forms ———

  private void structure() {
    heading(tr("insp.structure"));
    value(tr("insp.nodes"), () -> Integer.toString(app.structure().nodes.size()));
    value(tr("insp.elements"), () -> Integer.toString(app.structure().elements.size()));
    Solution sol = app.solution();
    if (sol.status == Solution.Status.OK || sol.status == Solution.Status.MECHANISM) {
      value(tr("insp.h"), () -> Integer.toString(app.solution().h));
      value(tr("insp.m"), () -> Integer.toString(app.solution().m));
    }
    check(tr("insp.selfWeight"), () -> app.structure().selfWeight, v -> app.structure().selfWeight = v);
    heading(tr("insp.howTo"));
    note(tr("insp.help1"), false);
    note(tr("insp.help2"), false);
    note(tr("insp.help3"), false);
    note(tr("insp.assumptions"), true);
  }

  private void node(Node n) {
    Structure s = app.structure();
    heading(tr("insp.node", s.number(n)));
    number("x (m)", () -> n.x, v -> n.x = v);
    number("y (m)", () -> n.y, v -> n.y = v);
    heading(tr("insp.support"));
    JComboBox<Support> sup = new JComboBox<>(Support.values());
    sup.setSelectedItem(n.support);
    sup.addActionListener(e -> {
      Support v = (Support) sup.getSelectedItem();
      if (v != n.support) app.edit(() -> n.support = v);
    });
    field(tr("insp.type"), sup);
    if (n.support.skewed() || n.support == Support.PIVOT) {
      number(tr("insp.normal"), () -> n.supportAngle, v -> n.supportAngle = v);
    }
    // a blocked component may settle; a skewed support's are along t and n, the others' along x and y
    String[] axis = n.support.skewed() ? new String[] {tr("insp.alongT"), tr("insp.alongN")} : new String[] {"ux", "uy"};
    if (n.support == Support.ELASTIQUE) {
      number(tr("insp.stiffness", axis[0]), () -> n.springT / 1e3, v -> n.springT = v * 1e3);
      number(tr("insp.stiffness", axis[1]), () -> n.springN / 1e3, v -> n.springN = v * 1e3);
      number(tr("insp.rotationStiffness"), () -> n.springR / 1e3, v -> n.springR = v * 1e3);
    } else if (n.support != Support.NONE) {
      if (n.support.t) number(n.support.skewed() ? tr("insp.imposedAlong", axis[0]) : tr("insp.imposed", axis[0]), () -> n.settleT * 1e3, v -> n.settleT = v / 1e3);
      if (n.support.n) number(n.support.skewed() ? tr("insp.imposedAlong", axis[1]) : tr("insp.imposed", axis[1]), () -> n.settleN * 1e3, v -> n.settleN = v / 1e3);
      if (n.support.r) number(tr("insp.imposedRotation"), () -> n.settleR * 1e3, v -> n.settleR = v / 1e3);
    }
    heading(tr("insp.loads"));
    number("Fx (kN)", () -> n.fx / 1e3, v -> n.fx = v * 1e3);
    number("Fy (kN)", () -> n.fy / 1e3, v -> n.fy = v * 1e3);
    number("Mz (kN·m)", () -> n.mz / 1e3, v -> n.mz = v * 1e3);
    if (app.solution().ok()) {
      int i = s.nodes.indexOf(n);
      heading(tr("insp.results"));
      value("ux", () -> app.solution().ok() ? Fmt.mm(app.solution().disp[i][0]) : "—");
      value("uy", () -> app.solution().ok() ? Fmt.mm(app.solution().disp[i][1]) : "—");
      value("θz", () -> app.solution().ok() ? Fmt.num(app.solution().disp[i][2] * 1e3) + " mrad" : "—");
      if (n.support != Support.NONE) {
        value(tr("insp.reaction", "Rx"), () -> reaction(i, 0, false));
        value(tr("insp.reaction", "Ry"), () -> reaction(i, 1, false));
        value(tr("insp.reaction", "Mz"), () -> reaction(i, 2, true));
      }
    }
  }

  /** A load of the element: what it is, a button to change it, one to remove it. */
  private void loadRow(Element e, ElementLoad l) {
    JPanel line = new JPanel(new java.awt.BorderLayout(4, 0));
    JTextArea text = new JTextArea(LoadDialog.describe(e, l));
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setEditable(false);
    text.setOpaque(false);
    text.setFont(getFont());
    text.setColumns(8);
    line.add(text, java.awt.BorderLayout.CENTER);
    JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    JButton change = new JButton(tr("insp.edit"));
    change.addActionListener(ev -> {
      ElementLoad v = LoadDialog.edit(this, e, l);
      if (v != null) app.edit(() -> e.loads.set(e.loads.indexOf(l), v));
    });
    JButton remove = new JButton("×");
    remove.setToolTipText(tr("insp.removeLoad"));
    remove.addActionListener(ev -> app.edit(() -> e.loads.remove(l)));
    buttons.add(change);
    buttons.add(remove);
    line.add(buttons, java.awt.BorderLayout.EAST);
    GridBagConstraints c = constraints(1);
    c.gridx = 0;
    c.gridwidth = 2;
    add(line, c);
    row++;
  }

  /** A button that asks for a new load and adds it to the element. */
  private JButton button(String label, Supplier<ElementLoad> ask, Element e) {
    JButton b = new JButton(label);
    b.addActionListener(ev -> {
      ElementLoad l = ask.get();
      if (l != null) app.edit(() -> e.loads.add(l));
    });
    return b;
  }

  private String reaction(int i, int k, boolean moment) {
    Solution sol = app.solution();
    if (!sol.ok() || sol.reaction[i] == null) return "—";
    return moment ? Fmt.kNm(sol.reaction[i][k]) : Fmt.kN(sol.reaction[i][k]);
  }

  private void element(Element e) {
    Structure s = app.structure();
    heading(tr(e.isBar() ? "insp.bar" : "insp.beam", s.number(e), s.number(e.a), s.number(e.b)));
    value(tr("insp.length"), () -> Fmt.num(e.length()) + " m");
    check(tr("insp.hingeA"), () -> e.hingeA, v -> e.hingeA = v);
    check(tr("insp.hingeB"), () -> e.hingeB, v -> e.hingeB = v);

    heading(tr("insp.section"));
    JComboBox<Section.Kind> kind = new JComboBox<>(Section.Kind.values());
    kind.setSelectedItem(e.section.kind());
    kind.addActionListener(ev -> {
      Section.Kind k = (Section.Kind) kind.getSelectedItem();
      if (k != e.section.kind()) app.edit(() -> e.section = Section.of(k));
    });
    field(tr("insp.shape"), kind);
    switch (e.section.kind()) {
      case RECT -> {
        sectionParam(e, "b (mm)", 1);
        sectionParam(e, tr("insp.rectH"), 2);
      }
      case ROUND -> sectionParam(e, "d (mm)", 1);
      case TUBE -> {
        sectionParam(e, tr("insp.tubeD"), 1);
        sectionParam(e, tr("insp.tubeE"), 2);
      }
      case IPE -> {
        JComboBox<String> size = new JComboBox<>();
        for (double[] r : Section.IPE_TABLE) size.addItem("IPE " + (int) r[0]);
        size.setSelectedItem("IPE " + (int) e.section.p1());
        size.addActionListener(ev -> {
          double h = Section.IPE_TABLE[size.getSelectedIndex()][0];
          if (h != e.section.p1()) app.edit(() -> e.section = new Section(Section.Kind.IPE, h, 0, 0));
        });
        field(tr("insp.profile"), size);
      }
      case CUSTOM -> {
        sectionParam(e, "A (cm²)", 1);
        sectionParam(e, "I (cm⁴)", 2);
        sectionParam(e, tr("insp.fibre"), 3);
      }
    }
    value("A", () -> Fmt.num(e.section.area() * 1e4) + " cm²");
    value("I", () -> Fmt.num(e.section.inertia() * 1e8) + " cm⁴");

    heading(tr("insp.material"));
    JComboBox<Material> mat = new JComboBox<>();
    Material.PRESETS.forEach(mat::addItem);
    if (!Material.PRESETS.contains(e.material)) mat.addItem(e.material);
    mat.setSelectedItem(e.material);
    mat.addActionListener(ev -> {
      Material m = (Material) mat.getSelectedItem();
      if (m != null && !m.equals(e.material)) app.edit(() -> e.material = m);
    });
    field(tr("insp.material"), mat);
    number("E (MPa)", () -> e.material.E(), v -> e.material = Material.of(v, e.material.rho()));
    number("ρ (kg/m³)", () -> e.material.rho(), v -> e.material = Material.of(e.material.E(), v));

    heading(tr("insp.elementLoads"));
    if (e.loads.isEmpty()) note(tr("insp.noLoads"), false);
    for (ElementLoad l : e.loads) loadRow(e, l);
    JPanel add = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    add.add(button(tr("insp.addForce"), () -> LoadDialog.force(this, e, new ElementLoad.Force(0.5, 0, -10e3, false)), e));
    add.add(button(tr("insp.addCouple"), () -> LoadDialog.couple(this, e, new ElementLoad.Couple(0.5, 10e3)), e));
    add.add(button(tr("insp.addDistributed"), () -> LoadDialog.distributed(this, e, ElementLoad.Distributed.uniform(0, -10e3)), e));
    GridBagConstraints ac = constraints(0);
    ac.gridwidth = 2;
    add(add, ac);
    row++;

    if (app.solution().ok()) {
      heading(tr("insp.elementResults"));
      value(tr("insp.endsN"), () -> ends(e, ElementResult::N, false));
      value(tr("insp.endsT"), () -> ends(e, ElementResult::T, false));
      value(tr("insp.endsM"), () -> ends(e, ElementResult::M, true));
      value(tr("insp.maxM"), () -> result(e, r -> Fmt.kNm(r.maxAbsM())));
      value(tr("insp.sigma"), () -> result(e, r -> Fmt.MPa(r.sigmaMax())));
    }
  }

  private interface At {
    double at(ElementResult r, double x);
  }

  private String ends(Element e, At q, boolean moment) {
    return result(e, r -> {
      double a = q.at(r, 0), b = q.at(r, r.L);
      return moment ? Fmt.num(a / 1e3) + " / " + Fmt.kNm(b) : Fmt.num(a / 1e3) + " / " + Fmt.kN(b);
    });
  }

  private String result(Element e, java.util.function.Function<ElementResult, String> f) {
    Solution sol = app.solution();
    ElementResult r = sol.ok() ? sol.of(e) : null;
    return r == null ? "—" : f.apply(r);
  }

  private void sectionParam(Element e, String label, int which) {
    number(label, () -> which == 1 ? e.section.p1() : which == 2 ? e.section.p2() : e.section.p3(), v -> {
      Section c = e.section;
      e.section = new Section(c.kind(), which == 1 ? v : c.p1(), which == 2 ? v : c.p2(), which == 3 ? v : c.p3());
    });
  }

  // ——— rows ———

  private void heading(String text) {
    JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    l.setBorder(BorderFactory.createEmptyBorder(row == 0 ? 0 : 10, 0, 4, 0));
    GridBagConstraints c = constraints(0);
    c.gridwidth = 2;
    add(l, c);
    row++;
  }

  /** A paragraph that wraps to the panel's width. */
  private void note(String text, boolean italic) {
    JTextArea t = new JTextArea(text);
    t.setLineWrap(true);
    t.setWrapStyleWord(true);
    t.setEditable(false);
    t.setFocusable(false);
    t.setOpaque(false);
    t.setFont(getFont().deriveFont(italic ? Font.ITALIC : Font.PLAIN));
    // a small preferred width: the column gives it the panel's, and the text wraps to that
    t.setColumns(8);
    GridBagConstraints c = constraints(1);
    c.gridx = 0;
    c.gridwidth = 2;
    c.insets = new Insets(0, 0, 8, 0);
    add(t, c);
    row++;
  }

  // follows the width of the scroll pane: the notes wrap instead of running past the edge
  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return 16;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return visible.height;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }

  private void field(String label, JComponent comp) {
    add(new JLabel(label), constraints(0));
    add(comp, constraints(1));
    row++;
  }

  private void value(String label, Supplier<String> text) {
    JLabel v = new JLabel(text.get());
    updaters.add(() -> v.setText(text.get()));
    field(label, v);
  }

  /** A number field: committed (one undo step) on Enter or when it loses the focus. */
  private void number(String label, DoubleSupplier get, DoubleConsumer set) {
    JTextField f = new JTextField(Fmt.num(get.getAsDouble()).replace('−', '-'), 8);
    Runnable commit = () -> {
      try {
        double v = Fmt.parse(f.getText());
        if (v != get.getAsDouble()) app.edit(() -> set.accept(v));
      } catch (NumberFormatException ex) {
        f.setText(Fmt.num(get.getAsDouble()).replace('−', '-'));
      }
    };
    f.addActionListener(e -> commit.run());
    f.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        commit.run();
      }
    });
    updaters.add(() -> {
      if (!f.isFocusOwner()) f.setText(Fmt.num(get.getAsDouble()).replace('−', '-'));
    });
    field(label, f);
  }

  private void check(String label, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
    JCheckBox b = new JCheckBox(label, get.get());
    b.addActionListener(e -> app.edit(() -> set.accept(b.isSelected())));
    updaters.add(() -> b.setSelected(get.get()));
    GridBagConstraints c = constraints(0);
    c.gridwidth = 2;
    add(b, c);
    row++;
  }

  private GridBagConstraints constraints(int col) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = col;
    c.gridy = row;
    c.anchor = GridBagConstraints.WEST;
    c.fill = col == 1 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
    c.weightx = col == 1 ? 1 : 0;
    c.insets = new Insets(2, 0, 2, 8);
    return c;
  }
}
