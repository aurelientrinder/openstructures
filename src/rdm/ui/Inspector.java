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
import rdm.model.Combination;
import rdm.design.Design;
import rdm.i18n.Units;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Grade;
import rdm.model.LoadCase;
import rdm.model.Loading;
import rdm.model.Material;
import rdm.model.Node;
import rdm.model.NodeLoad;
import rdm.model.Section;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.Buckling;
import rdm.solver.ElementResult;
import rdm.solver.Envelope;
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

  /** Builds the form again even if it shows the same thing (its labels changed: the units). */
  void rebuildNow() {
    shownFor = new Object();
    refresh();
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
    k.add(System.identityHashCode(app.shown()));
    k.add(sol.status);
    k.add(app.envelopeShown());
    k.add(app.checksShown());
    k.add(app.buckling() == null ? "no buckling" : app.buckling().status);
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

    Structure s = app.structure();
    heading(app.viewTitle(app.shown()));
    LoadCase c = app.shownCase();
    Combination k = app.shownCombination();
    if (c != null) {
      check(tr("insp.selfWeightIn", c.name), () -> c.selfWeight, v -> c.selfWeight = v);
    } else if (k != null) {
      value(tr("insp.formula"), () -> k.formula(s));
      value(tr("insp.selfWeightShort"), () -> {
        double w = k.loading().selfWeight(s);
        return w == 0 ? tr("insp.none") : "× " + Fmt.num(w);
      });
    } else if (app.shown() == MainFrame.CHECKS) {
      number(tr("insp.deflectionLimit"), () -> s.deflectionLimit, v -> s.deflectionLimit = v > 0 ? v : s.deflectionLimit);
      if (app.checksShown()) {
        value(tr("insp.maxRatio"), () -> app.checksShown() ? Fmt.ratio(app.design().maxRatio()) : "—");
        value(tr("insp.maxDeflectionRatio"), () -> {
          if (!app.checksShown()) return "—";
          double r = 0;
          for (Design.Span sp : app.design().spans) r = Math.max(r, sp.ratio());
          return Fmt.ratio(r);
        });
        liveNote(() -> !app.checksShown() ? "" : (app.design().ulsGiven ? "" : tr("insp.noUls") + " ") + (app.design().slsGiven ? "" : tr("insp.noSls")));
      }
    } else {
      value(tr("insp.combinations"), () -> Integer.toString(s.combinations.size()));
      if (app.envelopeShown()) {
        liveNote(() -> {
          Envelope env = app.envelope();
          return !app.envelopeShown() ? "" : tr("insp.worstIn", tr("insp.maxDisplacement"), Fmt.mm(env.maxDisplacement), env.combinations.get(env.maxDisplacementBy).name);
        });
      }
    }
    note(tr(c != null ? "insp.caseHelp" : k != null ? "insp.combinationHelp" : app.shown() == MainFrame.CHECKS ? "insp.checksHelp" : "insp.envelopeHelp"), false);
    JButton cases = new JButton(tr("insp.editCases"));
    cases.addActionListener(ev -> app.editCases());
    GridBagConstraints cc = constraints(0);
    cc.gridwidth = 2;
    add(cases, cc);
    row++;

    Buckling bk = app.buckling();
    if (bk != null && app.solution().ok()) {
      heading(tr("insp.buckling"));
      if (bk.ok()) {
        for (int i = 0; i < Buckling.MODES; i++) {
          int mode = i;
          value(tr("insp.bucklingMode", i + 1), () -> {
            Buckling b = app.buckling();
            return b == null || mode >= b.modes.size() ? "—" : Fmt.num(b.modes.get(mode).factor);
          });
        }
        value(tr("insp.safety"), () -> app.buckling() == null ? "—" : Fmt.num(app.buckling().factor()));
        if (app.bucklingCombination() != null) liveNote(() -> app.bucklingCombination() == null ? "" : tr("insp.bucklingIn", app.bucklingCombination().name));
        note(tr("insp.bucklingHelp"), false);
      } else {
        note(tr("insp.bucklingNone"), false);
      }
    }

    heading(tr("insp.howTo"));
    note(tr("insp.help1"), false);
    note(tr("insp.help2"), false);
    note(tr("insp.help3"), false);
    note(tr("insp.assumptions"), true);
  }

  private void node(Node n) {
    Structure s = app.structure();
    heading(tr("insp.node", s.number(n)));
    number("x (" + Units.l() + ")", () -> Units.L(n.x), v -> n.x = Units.fromL(v));
    number("y (" + Units.l() + ")", () -> Units.L(n.y), v -> n.y = Units.fromL(v));
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
      number(tr("insp.stiffness", axis[0]), () -> Units.Q(n.springT), v -> n.springT = Units.fromQ(v));
      number(tr("insp.stiffness", axis[1]), () -> Units.Q(n.springN), v -> n.springN = Units.fromQ(v));
      number(tr("insp.rotationStiffness"), () -> Units.M(n.springR), v -> n.springR = Units.fromM(v));
    }

    // the loads and the imposed displacements: of the case shown, or of the combination's cases, factored
    LoadCase c = app.shownCase();
    Loading loading = app.loading();
    int i = s.nodes.indexOf(n);
    if (loading != null) {
      heading(c != null ? tr("insp.loadsIn", c.name) : tr("insp.loadsFactored"));
      List<String> labels = new ArrayList<>(List.of("Fx (" + Units.f() + ")", "Fy (" + Units.f() + ")", "Mz (" + Units.m() + ")"));
      List<Integer> which = new ArrayList<>(List.of(0, 1, 2));
      if (n.support != Support.NONE && n.support != Support.ELASTIQUE) {
        if (n.support.t) {
          labels.add(n.support.skewed() ? tr("insp.imposedAlong", axis[0]) : tr("insp.imposed", axis[0]));
          which.add(3);
        }
        if (n.support.n) {
          labels.add(n.support.skewed() ? tr("insp.imposedAlong", axis[1]) : tr("insp.imposed", axis[1]));
          which.add(4);
        }
        if (n.support.r) {
          labels.add(tr("insp.imposedRotation"));
          which.add(5);
        }
      }
      for (int j = 0; j < labels.size(); j++) {
        int w = which.get(j);
        if (c != null) number(labels.get(j), () -> shown(n.load(c), w), v -> set(n.loadIn(c), w, v));
        else value(labels.get(j), () -> Fmt.num(shown(n.load(loading), w)));
      }
      if (c == null) note(tr("insp.editInCase"), true);
    }
    if (app.envelopeShown()) {
      if (n.support != Support.NONE) {
        heading(tr("insp.envelopeResults"));
        value(tr("insp.reaction", "Rx"), () -> range(i, 0, false));
        value(tr("insp.reaction", "Ry"), () -> range(i, 1, false));
        value(tr("insp.reaction", "Mz"), () -> range(i, 2, true));
      }
    } else if (app.solution().ok() && app.shown() != MainFrame.CHECKS) {
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

  /** A node's load or imposed displacement w (0–2: Fx, Fy, Mz; 3–5: along t, n, rotation) as typed: the force and moment units, mm, mrad. */
  private static double shown(NodeLoad l, int w) {
    return switch (w) {
      case 0 -> Units.F(l.fx);
      case 1 -> Units.F(l.fy);
      case 2 -> Units.M(l.mz);
      default -> l.settle(w - 3) * 1e3;
    };
  }

  private static void set(NodeLoad l, int w, double v) {
    switch (w) {
      case 0 -> l.fx = Units.fromF(v);
      case 1 -> l.fy = Units.fromF(v);
      case 2 -> l.mz = Units.fromM(v);
      case 3 -> l.settleT = v / 1e3;
      case 4 -> l.settleN = v / 1e3;
      default -> l.settleR = v / 1e3;
    }
  }

  /** A reaction's smallest and largest values over the combinations. */
  private String range(int i, int k, boolean moment) {
    Envelope env = app.envelope();
    if (!app.envelopeShown() || env.minReaction[i] == null) return "—";
    double lo = env.minReaction[i][k], hi = env.maxReaction[i][k];
    return Fmt.num(moment ? Units.M(lo) : Units.F(lo)) + " … " + (moment ? Fmt.moment(hi) : Fmt.force(hi));
  }

  /** A load not to change here: in a combination (factored) or with the envelope. */
  private void loadText(String text) {
    JTextArea t = new JTextArea(text);
    t.setLineWrap(true);
    t.setWrapStyleWord(true);
    t.setEditable(false);
    t.setOpaque(false);
    t.setFont(getFont());
    t.setColumns(8);
    GridBagConstraints c = constraints(1);
    c.gridx = 0;
    c.gridwidth = 2;
    add(t, c);
    row++;
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
    return moment ? Fmt.moment(sol.reaction[i][k]) : Fmt.force(sol.reaction[i][k]);
  }

  private void element(Element e) {
    Structure s = app.structure();
    heading(tr(e.isBar() ? "insp.bar" : "insp.beam", s.number(e), s.number(e.a), s.number(e.b)));
    value(tr("insp.length"), () -> Fmt.length(e.length()));
    check(tr("insp.hingeA"), () -> e.hingeA, v -> e.hingeA = v);
    check(tr("insp.hingeB"), () -> e.hingeB, v -> e.hingeB = v);

    heading(tr("insp.section"));
    // the section drawn small with its name, and the templates' dialog
    JLabel shown = new JLabel(e.section.label(), SectionView.icon(e.section, 40), JLabel.LEFT);
    shown.setIconTextGap(8);
    updaters.add(() -> {
      shown.setText(e.section.label());
      shown.setIcon(SectionView.icon(e.section, 40));
    });
    JButton choose = new JButton(tr("insp.chooseSection"));
    choose.addActionListener(ev -> {
      Section v = SectionDialog.ask(this, e.section, e.material.rho());
      if (v != null && !v.equals(e.section)) app.edit(() -> e.section = v);
    });
    JPanel line = new JPanel(new java.awt.BorderLayout(6, 0));
    line.add(shown, java.awt.BorderLayout.CENTER);
    // at its own height, not the icon's
    JPanel holder = new JPanel(new java.awt.GridBagLayout());
    holder.add(choose);
    line.add(holder, java.awt.BorderLayout.EAST);
    GridBagConstraints lc = constraints(1);
    lc.gridx = 0;
    lc.gridwidth = 2;
    add(line, lc);
    row++;
    Section.Kind kind = e.section.kind();
    SectionDialog.pickers(e.section, v -> {
      Section turned = v.turned(e.section.turned());
      if (!turned.equals(e.section)) app.edit(() -> e.section = turned);
    }).forEach(this::field);
    for (int i = 0; i < kind.params.size(); i++) {
      int which = i;
      number(tr("dim." + kind.params.get(i)), () -> e.section.param(which), v -> e.section = e.section.with(which, v));
    }
    if (kind.turns()) check(tr("insp.turned"), () -> e.section.turned(), v -> e.section = e.section.turned(v));
    value("A", () -> Fmt.num(e.section.area() * 1e4) + " cm²");
    value("I", () -> Fmt.num(e.section.inertia() * 1e8) + " cm⁴");
    value("v", () -> Fmt.num(e.section.fibre() * 1e2) + " cm");

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
    if (e.material.name().equals("steel")) {
      JComboBox<Grade> grade = new JComboBox<>(Grade.values());
      grade.setSelectedItem(e.grade);
      grade.addActionListener(ev -> {
        Grade v = (Grade) grade.getSelectedItem();
        if (v != null && v != e.grade) app.edit(() -> e.grade = v);
      });
      updaters.add(() -> grade.setSelectedItem(e.grade));
      field(tr("insp.grade"), grade);
      number(tr("insp.bucklingFactor"), () -> e.bucklingFactor, v -> e.bucklingFactor = Math.max(0, v));
      note(tr("insp.bucklingFactorHelp"), true);
    }

    // the loads of the case shown, to change; of a combination, factored; with the envelope, every case's
    LoadCase c = app.shownCase();
    Loading loading = app.loading();
    if (c != null) {
      heading(tr("insp.elementLoadsIn", c.name));
      List<ElementLoad> mine = e.loadsIn(c);
      if (mine.isEmpty()) note(tr("insp.noLoads"), false);
      for (ElementLoad l : mine) loadRow(e, l);
      JPanel add = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
      add.add(button(tr("insp.addForce"), () -> LoadDialog.force(this, e, new ElementLoad.Force(c, 0.5, 0, -10e3, false)), e));
      add.add(button(tr("insp.addCouple"), () -> LoadDialog.couple(this, e, new ElementLoad.Couple(c, 0.5, 10e3)), e));
      add.add(button(tr("insp.addDistributed"), () -> LoadDialog.distributed(this, e, ElementLoad.Distributed.uniform(c, 0, -10e3)), e));
      GridBagConstraints ac = constraints(0);
      ac.gridwidth = 2;
      add(add, ac);
      row++;
      int others = e.loads.size() - mine.size();
      if (others > 0) note(tr("insp.otherCases", others), true);
    } else {
      heading(loading != null ? tr("insp.loadsFactored") : tr("insp.elementLoadsAll"));
      int shownLoads = 0;
      for (ElementLoad l : e.loads) {
        double k = loading == null ? 1 : loading.factor(l.loadCase());
        if (k == 0) continue;
        String factor = loading == null ? "" : Fmt.num(k) + " × ";
        loadText(tr("insp.caseLoad", factor + l.loadCase().name, LoadDialog.describe(e, l)));
        shownLoads++;
      }
      if (shownLoads == 0) note(tr("insp.noLoadsHere"), false);
      note(tr("insp.editInCase"), true);
    }

    if (app.envelopeShown()) {
      heading(tr("insp.envelopeResults"));
      value(tr("insp.rangeN"), () -> extremes(e, Envelope.Effort.N, false));
      value(tr("insp.rangeT"), () -> extremes(e, Envelope.Effort.V, false));
      value(tr("insp.rangeM"), () -> extremes(e, Envelope.Effort.M, true));
      liveNote(() -> worst(e, tr("insp.maxM"), ElementResult::maxAbsM, Fmt::moment));
      liveNote(() -> worst(e, tr("insp.sigma"), ElementResult::sigmaMax, Fmt::MPa));
    } else if (app.shown() == MainFrame.CHECKS) {
      if (app.checksShown()) checks(e);
    } else if (app.solution().ok()) {
      heading(tr("insp.elementResults"));
      value(tr("insp.endsN"), () -> ends(e, ElementResult::N, false));
      value(tr("insp.endsT"), () -> ends(e, ElementResult::T, false));
      value(tr("insp.endsM"), () -> ends(e, ElementResult::M, true));
      value(tr("insp.maxM"), () -> result(e, r -> Fmt.moment(r.maxAbsM())));
      value(tr("insp.sigma"), () -> result(e, r -> Fmt.MPa(r.sigmaMax())));
    }

    // buckling: its compression at λcr of the first mode, its buckling length, and Euler's load of the element alone
    if (app.buckling() != null && app.buckling().ok()) {
      heading(tr("insp.bucklingElement"));
      value(tr("insp.compression"), () -> member(e, m -> Fmt.force(m.N())));
      value(tr("insp.Ncr"), () -> member(e, m -> Fmt.force(m.critical(app.buckling().factor()))));
      value(tr("insp.Lf"), () -> member(e, m -> Fmt.length(m.length(app.buckling().factor())) + " (" + Fmt.num(m.length(app.buckling().factor()) / m.L()) + " L)"));
      value(tr("insp.euler"), () -> member(e, m -> Fmt.force(m.euler())));
      note(tr("insp.bucklingElementHelp"), true);
    }
  }

  /** The element's checks: its utilisation and each check's ratio, its span's deflection. */
  private void checks(Element e) {
    heading(tr("insp.checks"));
    Design.Member m0 = app.design().of(e);
    if (m0 == null || !m0.checked()) {
      liveNote(() -> {
        Design.Member m = member(e);
        return m == null || m.why() == null ? "" : tr("report.notChecked", m.why());
      });
    } else {
      value(tr("insp.fyClass"), () -> {
        Design.Member m = member(e);
        if (m == null || !m.checked()) return "—";
        int cls = 1;
        for (Design.Check c : Design.Check.values()) if (m.worst(c).found()) cls = Math.max(cls, m.worst(c).cls);
        return Fmt.MPa(m.fy) + ", " + tr("insp.class", cls);
      });
      value(tr("insp.utilisation"), () -> {
        Design.Member m = member(e);
        return m == null || !m.checked() ? "—" : Fmt.ratio(m.ratio());
      });
      liveNote(() -> {
        Design.Member m = member(e);
        if (m == null || !m.checked()) return "";
        Design.Check g = m.governing();
        Design.Worst w = m.worst(g);
        Design.Loads l = app.design().uls.get(w.by);
        String in = l.combination() == null ? tr("insp.asGiven") : l.name();
        return g == Design.Check.BUCKLING ? tr("insp.governsBuckling", g, in) : tr("insp.governs", g, in, Fmt.num(Units.L(w.x)));
      });
      for (Design.Check c : Design.Check.values()) {
        value(c.toString(), () -> {
          Design.Member m = member(e);
          return m == null || !m.checked() || !m.worst(c).found() ? "—" : Fmt.ratio(m.worst(c).ratio);
        });
      }
      value(tr("insp.Lcr"), () -> {
        Design.Member m = member(e);
        Design.Worst w = m == null || !m.checked() ? null : m.worst(Design.Check.BUCKLING);
        return w == null || !w.found() || Double.isInfinite(w.Ncr) ? "—" : Fmt.length(w.Lcr) + ", χ = " + Fmt.num(w.chi);
      });
    }
    value(tr("insp.deflection"), () -> {
      Design.Span sp = app.checksShown() ? app.design().spanOf(e) : null;
      return sp == null ? "—" : Fmt.mm(Math.abs(sp.deflection)) + " / " + Fmt.mm(sp.allowed) + " (" + Fmt.ratio(sp.ratio()) + ")";
    });
    note(tr("insp.checksElementHelp"), true);
  }

  private Design.Member member(Element e) {
    return app.checksShown() ? app.design().of(e) : null;
  }

  /** A buckling result of the element, "—" when it is not compressed. */
  private String member(Element e, java.util.function.Function<Buckling.Member, String> f) {
    Buckling bk = app.buckling();
    Buckling.Member m = bk == null || !bk.ok() ? null : bk.of(e);
    return m == null || !m.compressed() ? "—" : f.apply(m);
  }

  private interface At {
    double at(ElementResult r, double x);
  }

  private String ends(Element e, At q, boolean moment) {
    return result(e, r -> {
      double a = q.at(r, 0), b = q.at(r, r.L);
      return moment ? Fmt.num(Units.M(a)) + " / " + Fmt.moment(b) : Fmt.num(Units.F(a)) + " / " + Fmt.force(b);
    });
  }

  /** The smallest and largest value of an internal force along the element, over the combinations. */
  private String extremes(Element e, Envelope.Effort q, boolean moment) {
    if (!app.envelopeShown()) return "—";
    Envelope.Along a = app.envelope().of(e);
    double lo = a.min(q)[a.lowest(q)], hi = a.max(q)[a.highest(q)];
    return Fmt.num(moment ? Units.M(lo) : Units.F(lo)) + " … " + (moment ? Fmt.moment(hi) : Fmt.force(hi));
  }

  /** The largest of a result over the combinations, and the combination that gives it. */
  private String worst(Element e, String label, java.util.function.ToDoubleFunction<ElementResult> f, java.util.function.DoubleFunction<String> fmt) {
    if (!app.envelopeShown()) return "";
    Envelope env = app.envelope();
    int by = 0;
    double best = -1;
    for (int k = 0; k < env.solutions.size(); k++) {
      double v = f.applyAsDouble(env.solutions.get(k).of(e));
      if (v > best) {
        best = v;
        by = k;
      }
    }
    return tr("insp.worstIn", label, fmt.apply(best), env.combinations.get(by).name);
  }

  private String result(Element e, java.util.function.Function<ElementResult, String> f) {
    Solution sol = app.solution();
    ElementResult r = sol.ok() ? sol.of(e) : null;
    return r == null ? "—" : f.apply(r);
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

  /** A paragraph whose text follows the results, the panel's width. */
  private void liveNote(Supplier<String> text) {
    note(text.get(), false);
    JTextArea t = (JTextArea) getComponent(getComponentCount() - 1);
    updaters.add(() -> t.setText(text.get()));
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
    JTextField f = new JTextField(Fmt.field(get.getAsDouble()), 8);
    Runnable commit = () -> {
      try {
        double v = Fmt.parse(f.getText());
        if (v != get.getAsDouble()) app.edit(() -> set.accept(v));
      } catch (NumberFormatException ex) {
        f.setText(Fmt.field(get.getAsDouble()));
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
      if (!f.isFocusOwner()) f.setText(Fmt.field(get.getAsDouble()));
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
