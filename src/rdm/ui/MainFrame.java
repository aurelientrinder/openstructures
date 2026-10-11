package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import rdm.Settings;
import rdm.i18n.Tr;
import rdm.i18n.Units;
import rdm.io.StructureFile;
import rdm.model.Combination;
import rdm.model.Examples;
import rdm.model.LoadCase;
import rdm.model.Loading;
import rdm.model.Structure;
import rdm.design.Design;
import rdm.solver.Buckling;
import rdm.solver.Envelope;
import rdm.solver.Solution;
import rdm.solver.Solver;

/**
 * The window, and the controller every view goes through: the structure, its solution (solved again after
 * every change, so the results follow the drawing live), the selection, the tool, undo and the display options.
 */
public final class MainFrame extends JFrame {
  enum Diagram {
    NONE, N, T, M;

    @Override
    public String toString() {
      return tr("diagram." + name());
    }
  }

  /** The envelope of the combinations, as a view (the others are a load case or a combination). */
  static final Object ENVELOPE = new Object();
  /** The design checks (EN 1993-1-1 and deflections), as a view: always there. */
  static final Object CHECKS = new Object();

  private Structure structure = new Structure();
  /** what is solved and shown: a load case of the structure, one of its combinations, ENVELOPE or CHECKS */
  private Object shown = structure.cases.get(0);
  private Solution solution = Solver.solve(structure, Loading.of(structure.cases.get(0)));
  /** the combinations solved, when the envelope is shown; null otherwise */
  private Envelope envelope;
  /** the checks, when they are shown; null otherwise */
  private Design design;
  /** the buckling of the solution shown (with the envelope, of the combination with the smallest λcr); null when off */
  private Buckling buckling;
  /** with the envelope, the index of that combination, and every combination's λcr (NaN: it can't buckle); −1, null otherwise */
  private int bucklingBy = -1;
  private double[] bucklingFactors;
  private Object selection;
  private Tool tool = Tool.SELECT;
  private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private Path file;

  // display options
  boolean showDeformed = true, showReactions = true, showNumbers = false;
  Diagram diagram = Diagram.M;
  /** the buckling mode drawn, from 1; 0: no buckling */
  int bucklingMode;
  /** the deformed shape's scale: displacements drawn this many times their size; 0 chooses one where they show */
  double deformedScale;
  double grid = 0.5;

  final StructureCanvas canvas = new StructureCanvas(this);
  private final Inspector inspector = new Inspector(this);
  private final JTextArea report = new JTextArea();
  private final JLabel status = new JLabel(" ");
  private final JLabel hint = new JLabel(" ");
  private final Map<Tool, AbstractButton> toolButtons = new EnumMap<>(Tool.class);
  private final JComboBox<Object> showBox = new JComboBox<>();
  /** the grid's steps, in the length unit */
  private final JComboBox<String> gridBox = new JComboBox<>();
  private boolean fillingGridBox;
  /** while the views' list is filled again: its selection events are not the user's */
  private boolean fillingShowBox;

  public MainFrame() {
    super(tr("app.title"));
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setJMenuBar(menus());
    add(displayBar(), BorderLayout.NORTH);
    add(toolBar(), BorderLayout.WEST);
    add(canvas, BorderLayout.CENTER);

    report.setEditable(false);
    report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab(tr("tab.properties"), new JScrollPane(inspector));
    tabs.addTab(tr("tab.results"), new JScrollPane(report));
    tabs.setPreferredSize(new Dimension(340, 600));
    add(tabs, BorderLayout.EAST);

    JToolBar south = new JToolBar();
    south.setFloatable(false);
    status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
    hint.setForeground(java.awt.Color.GRAY);
    south.add(status);
    south.addSeparator();
    south.add(hint);
    add(south, BorderLayout.SOUTH);

    // at a large interface size the usual window would not fit on the screen (sizes here are before scaling)
    java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    setSize(Math.min(1280, screen.width), Math.min(800, screen.height));
    setLocationRelativeTo(null);
    setTool(Tool.SELECT);
    load(Examples.ALL.get("portal").get(), null);
    addWindowListener(new java.awt.event.WindowAdapter() {
      @Override
      public void windowOpened(java.awt.event.WindowEvent e) {
        canvas.fit();
        canvas.requestFocusInWindow();
      }
    });
  }

  // ——— state ———

  Structure structure() {
    return structure;
  }

  /**
   * The solution shown: of the load case or the combination; with the envelope, of the first combination that
   * fails, else of the first one (for h, m and the status: the loads change neither).
   */
  Solution solution() {
    return solution;
  }

  /** The envelope, when it is shown (null otherwise); see its ok(). */
  Envelope envelope() {
    return envelope;
  }

  /** The buckling of what is shown, when a mode is asked for (null otherwise); see its ok(). */
  Buckling buckling() {
    return buckling;
  }

  /** The combination the buckling is of, with the envelope (the one with the smallest λcr); null otherwise. */
  Combination bucklingCombination() {
    return bucklingBy < 0 || envelope == null ? null : envelope.combinations.get(bucklingBy);
  }

  /** The buckling mode drawn, among those found: the one asked for, or the last there is; null without one. */
  Buckling.Mode mode() {
    if (buckling == null || !buckling.ok()) return null;
    return buckling.modes.get(Math.min(bucklingMode, buckling.modes.size()) - 1);
  }

  /** The envelope is shown and every combination was solved. */
  boolean envelopeShown() {
    return envelope != null && envelope.ok();
  }

  /** The checks, when they are shown (null otherwise); see its ok(). */
  Design design() {
    return design;
  }

  /** The checks are shown and every combination was solved. */
  boolean checksShown() {
    return design != null && design.ok();
  }

  Object shown() {
    return shown;
  }

  /** The load case shown, or null when it is a combination or the envelope. */
  LoadCase shownCase() {
    return shown instanceof LoadCase c ? c : null;
  }

  Combination shownCombination() {
    return shown instanceof Combination k ? k : null;
  }

  /** The loads of the case or the combination shown (null for the envelope and the checks). */
  Loading loading() {
    return shown instanceof LoadCase c ? Loading.of(c) : shown instanceof Combination k ? k.loading() : null;
  }

  /** Shows (and solves) a load case, a combination or ENVELOPE. Not a change of the structure: no undo. */
  void show(Object view) {
    if (view == null || view == shown) return;
    shown = view;
    changed();
  }

  /**
   * The case new loads go in: the one shown, else one the user picks (which is then shown); null when cancelled.
   * Loads are always put in a case, never in a combination.
   */
  LoadCase caseForLoads() {
    if (shown instanceof LoadCase c) return c;
    JComboBox<LoadCase> pick = new JComboBox<>(structure.cases.toArray(LoadCase[]::new));
    pick.setRenderer(viewRenderer());
    javax.swing.JPanel p = new javax.swing.JPanel(new BorderLayout(0, 6));
    p.add(new JLabel(tr("cases.pickForLoads")), BorderLayout.NORTH);
    p.add(pick, BorderLayout.CENTER);
    if (JOptionPane.showConfirmDialog(this, p, tr("cases.pickTitle"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
      return null;
    }
    LoadCase c = (LoadCase) pick.getSelectedItem();
    show(c);
    return c;
  }

  /** Opens the dialog of the load cases and combinations; what it returns replaces the structure, as one change. */
  void editCases() {
    Structure edited = CasesDialog.edit(this, structure);
    if (edited == null) return;
    remember();
    replace(edited);
  }

  /**
   * The structure becomes another version of itself (the same nodes and elements, in order): the selection follows
   * by number, the view by match().
   */
  private void replace(Structure s) {
    Structure old = structure;
    int node = selection instanceof rdm.model.Node n ? old.nodes.indexOf(n) : -1;
    int element = selection instanceof rdm.model.Element e ? old.elements.indexOf(e) : -1;
    structure = s;
    shown = match(shown, old, s);
    selection = node >= 0 && node < s.nodes.size() ? s.nodes.get(node) : element >= 0 && element < s.elements.size() ? s.elements.get(element) : null;
    canvas.cancelChain();
    changed();
  }

  /**
   * The same view in another version of the structure (after undo, or a structure read again): the case or
   * combination at the same place when it has the same name, else the first one of that name, else the one at
   * the same place; the envelope while there are combinations; else the first case.
   */
  private static Object match(Object view, Structure from, Structure to) {
    if (view == ENVELOPE) return to.combinations.isEmpty() ? to.cases.get(0) : ENVELOPE;
    if (view == CHECKS) return CHECKS;
    if (view instanceof Combination k) {
      Object m = same(from.combinations.indexOf(k), k.name, to.combinations, c -> c.name);
      return m != null ? m : to.cases.get(0);
    }
    if (view instanceof LoadCase c) {
      Object m = same(from.cases.indexOf(c), c.name, to.cases, x -> x.name);
      return m != null ? m : to.cases.get(0);
    }
    return to.cases.get(0);
  }

  private static <T> T same(int i, String name, List<T> in, java.util.function.Function<T, String> nameOf) {
    if (i >= 0 && i < in.size() && nameOf.apply(in.get(i)).equals(name)) return in.get(i);
    for (T t : in) if (nameOf.apply(t).equals(name)) return t;
    if (in.isEmpty() || i < 0) return null;
    return in.get(Math.min(i, in.size() - 1));
  }

  /** The view as text, to hand it over when the application starts again: "case 1", "combination 2", "envelope". */
  String viewKey() {
    if (shown == ENVELOPE) return "envelope";
    if (shown == CHECKS) return "checks";
    if (shown instanceof Combination k) return "combination " + structure.combinations.indexOf(k);
    return "case " + Math.max(0, structure.cases.indexOf(shown));
  }

  /** Shows the view a viewKey() names, when the structure has it. */
  public void showKey(String key) {
    String[] w = key.split(" ");
    try {
      if (w[0].equals("envelope") && !structure.combinations.isEmpty()) show(ENVELOPE);
      else if (w[0].equals("checks")) show(CHECKS);
      else if (w[0].equals("combination")) show(structure.combinations.get(Integer.parseInt(w[1])));
      else if (w[0].equals("case")) show(structure.cases.get(Integer.parseInt(w[1])));
    } catch (RuntimeException ignored) {
      // not in this structure: the view stays
    }
  }

  Object selection() {
    return selection;
  }

  Tool tool() {
    return tool;
  }

  void select(Object o) {
    selection = o;
    inspector.refresh();
    canvas.repaint();
  }

  void setTool(Tool t) {
    tool = t;
    canvas.cancelChain();
    if (t != Tool.BEAM && t != Tool.BAR && t != Tool.NODE) canvas.closeEntry();
    AbstractButton b = toolButtons.get(t);
    if (b != null) b.setSelected(true);
    hint.setText(t.help());
    canvas.repaint();
  }

  /** One undoable change: the structure is saved before, solved after. */
  void edit(Runnable change) {
    remember();
    change.run();
    changed();
  }

  /** Saves the structure for undo; for a gesture (a drag), call once at its start, then changed() as it goes. */
  void remember() {
    undo.push(StructureFile.write(structure));
    if (undo.size() > 200) undo.removeLast();
    redo.clear();
  }

  /** Solves the view again (every combination for the envelope) and refreshes every view. */
  void changed() {
    if (!(shown == CHECKS || (shown == ENVELOPE ? !structure.combinations.isEmpty() : structure.cases.contains(shown) || structure.combinations.contains(shown)))) {
      shown = structure.cases.get(0);
    }
    envelope = null;
    design = null;
    if (shown == ENVELOPE) {
      envelope = Envelope.of(structure);
      solution = envelope.failed != null ? envelope.failed : envelope.solutions.get(0);
    } else if (shown == CHECKS) {
      design = Design.of(structure);
      solution = design.failed != null ? design.failed : design.ulsSolutions.get(0);
    } else {
      solution = Solver.solve(structure, loading());
    }
    buckle();
    if (selection instanceof rdm.model.Node n && !structure.nodes.contains(n)) selection = null;
    if (selection instanceof rdm.model.Element e && !structure.elements.contains(e)) selection = null;
    fillShowBox();
    inspector.refresh();
    report.setText(reportText());
    report.setCaretPosition(0);
    // with a buckling mode, the tab opens at the buckling, the linear results above it
    int at = buckling == null ? -1 : report.getText().indexOf("\n\n" + tr("report.buckling", buckling.parts));
    if (at >= 0) javax.swing.SwingUtilities.invokeLater(() -> {
      try {
        java.awt.Rectangle r = report.modelToView2D(at + 2).getBounds();
        r.height = report.getVisibleRect().height;
        report.scrollRectToVisible(r);
      } catch (javax.swing.text.BadLocationException ignored) {
        // the text changed again meanwhile
      }
    });
    status.setText(summary());
    canvas.repaint();
  }

  /**
   * The buckling of the view, when a mode is asked for: of the solution, or with the envelope, of each combination,
   * keeping the one with the smallest λcr (a combination that can't buckle comes after those that can).
   */
  private void buckle() {
    buckling = null;
    bucklingBy = -1;
    bucklingFactors = null;
    // the checks take the buckling into account themselves
    if (bucklingMode == 0 || shown == CHECKS) return;
    if (!envelopeShown()) {
      buckling = Buckling.of(solution);
      return;
    }
    bucklingFactors = new double[envelope.solutions.size()];
    for (int k = 0; k < envelope.solutions.size(); k++) {
      Buckling b = Buckling.of(envelope.solutions.get(k));
      bucklingFactors[k] = b.factor();
      if (buckling == null || (b.ok() && (!buckling.ok() || b.factor() < buckling.factor()))) {
        buckling = b;
        bucklingBy = k;
      }
    }
  }

  /** Shows a buckling mode (from 1), or none (0). Not a change of the structure: no undo. */
  void showBuckling(int mode) {
    if (mode == bucklingMode) return;
    bucklingMode = mode;
    changed();
  }

  /** The Results tab, for the view; with the buckling after the linear results when a mode is shown. */
  private String reportText() {
    if (shown == CHECKS) return Report.of(structure, design);
    String text = envelopeShown() ? Report.of(structure, envelope) : Report.of(structure, solution, viewTitle(shown));
    if (buckling != null && solution.ok()) text += "\n" + Report.of(structure, buckling, bucklingCombination(), envelope, bucklingFactors);
    return text;
  }

  private String summary() {
    Solution s = solution;
    String failing = envelope != null && envelope.failed != null ? tr("status.inCombination", envelope.combinations.get(envelope.solutions.indexOf(s)).name)
        : design != null && design.failed != null && !design.failedIn.isEmpty() ? tr("status.inCombination", design.failedIn) : "";
    return switch (s.status) {
      case EMPTY -> tr("status.empty");
      case ERROR -> tr("status.error", s.error) + failing;
      case MECHANISM -> tr("status.mechanism", s.m, String.join(", ", s.free)) + failing;
      case OK -> (s.h == 0 ? tr("status.determinate") : tr("status.indeterminate", s.h)) + " — "
          + (checksShown() ? checksSummary() : envelopeShown()
              ? tr("status.envelope", envelope.solutions.size(), Fmt.mm(envelope.maxDisplacement), envelope.combinations.get(envelope.maxDisplacementBy).name)
              : tr("status.maxDisplacement", Fmt.mm(s.maxDisplacement)))
          + (buckling == null ? "" : buckling.ok() ? tr("status.buckling", Fmt.num(buckling.factor())) : tr("status.noBuckling"))
          + (s.warnings.isEmpty() ? "" : " — " + String.join(tr("list.separator"), s.warnings));
    };
  }

  /** The largest utilisation and the largest deflection's ratio, and how many fail. */
  private String checksSummary() {
    double strength = design.maxRatio(), deflection = 0;
    long fail = design.members.stream().filter(m -> m.checked() && m.ratio() > 1).count();
    for (Design.Span sp : design.spans) deflection = Math.max(deflection, sp.ratio());
    long sag = design.spans.stream().filter(sp -> sp.ratio() > 1).count();
    return tr("status.checks", Fmt.ratio(strength), Fmt.ratio(deflection)) + (fail + sag == 0 ? tr("status.checksOk") : tr("status.checksFail", fail, sag));
  }

  /** "Case G (permanent)", "Combination 1.35 G + 1.5 Q", "Envelope of the combinations", "Design checks". */
  String viewTitle(Object view) {
    if (view == ENVELOPE) return tr("view.envelope");
    if (view == CHECKS) return tr("view.checks");
    if (view instanceof LoadCase c) return tr("view.case", c.name, c.type);
    if (view instanceof Combination k) {
      String formula = k.formula(structure);
      return k.name.equals(formula) ? tr("view.combination", k.name, k.limit) : tr("view.combinationNamed", k.name, formula, k.limit);
    }
    return "";
  }

  void hover(String text) {
    status.setText(text == null ? summary() : text);
  }

  private void restore(String snapshot) {
    Structure old = structure;
    structure = StructureFile.read(snapshot);
    shown = match(shown, old, structure);
    selection = null;
    canvas.cancelChain();
    changed();
  }

  void undo() {
    if (undo.isEmpty()) return;
    redo.push(StructureFile.write(structure));
    restore(undo.pop());
  }

  void redo() {
    if (redo.isEmpty()) return;
    undo.push(StructureFile.write(structure));
    restore(redo.pop());
  }

  public void load(Structure s, Path from) {
    shown = match(shown, structure, s);
    structure = s;
    file = from;
    selection = null;
    undo.clear();
    redo.clear();
    canvas.cancelChain();
    changed();
    canvas.fit();
    setTitle(tr("app.title") + (from == null ? "" : " — " + from.getFileName()));
  }

  // ——— window parts ———

  private JMenuBar menus() {
    JMenuBar bar = new JMenuBar();
    JMenu f = new JMenu(tr("menu.file"));
    f.add(item(tr("menu.new"), KeyEvent.VK_N, () -> load(new Structure(), null)));
    f.add(item(tr("menu.open"), KeyEvent.VK_O, this::open));
    f.add(item(tr("menu.save"), KeyEvent.VK_S, this::save));
    JMenu ex = new JMenu(tr("menu.examples"));
    Examples.ALL.forEach((name, make) -> ex.add(item(Examples.title(name), 0, () -> load(make.get(), null))));
    f.add(ex);
    JMenu export = new JMenu(tr("menu.export"));
    export.add(item(tr("export.png"), 0, () -> export("png", tr("export.pngFilter"), p -> ImageIO.write(canvas.image(2), "png", p.toFile()))));
    export.add(item(tr("export.svg"), 0, () -> export("svg", tr("export.svgFilter"), p -> Files.writeString(p, canvas.svg(), StandardCharsets.UTF_8))));
    export.addSeparator();
    export.add(item(tr("export.text"), 0, () -> export("txt", tr("export.textFilter"),
        p -> Files.writeString(p, reportText().replace("\n", System.lineSeparator()), StandardCharsets.UTF_8))));
    export.add(item(tr("export.csv"), 0, () -> {
      if (!solution.ok() || (envelope != null && !envelope.ok())) {
        JOptionPane.showMessageDialog(this, tr("export.notSolved"), tr("menu.export"), JOptionPane.WARNING_MESSAGE);
        return;
      }
      String csv = checksShown() ? Report.csv(design) : buckling != null && buckling.ok() ? Report.csv(buckling) : envelopeShown() ? Report.csv(envelope)
          : Report.csv(solution);
      // with a byte order mark, so that a spreadsheet reads it as UTF-8 (kN·m)
      export("csv", tr("export.csvFilter"), p -> Files.writeString(p, "\uFEFF" + csv, StandardCharsets.UTF_8));
    }));
    f.add(export);
    f.addSeparator();
    f.add(item(tr("menu.quit"), 0, () -> System.exit(0)));
    bar.add(f);
    JMenu e = new JMenu(tr("menu.edit"));
    e.add(item(tr("menu.undo"), KeyEvent.VK_Z, this::undo));
    e.add(item(tr("menu.redo"), KeyEvent.VK_Y, this::redo));
    JMenuItem del = item(tr("menu.deleteSelection"), 0, canvas::deleteSelection);
    del.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
    e.add(del);
    e.addSeparator();
    e.add(item(tr("menu.cases"), KeyEvent.VK_L, this::editCases));
    bar.add(e);
    JMenu v = new JMenu(tr("menu.view"));
    v.add(item(tr("menu.fit"), 0, canvas::fit));
    bar.add(v);
    JMenu r = new JMenu(tr("menu.settings"));
    JMenu size = new JMenu(tr("menu.uiSize"));
    ButtonGroup sizes = new ButtonGroup();
    for (double k : Settings.UI_SCALES) {
      JRadioButtonMenuItem it = new JRadioButtonMenuItem(k == 0 ? tr("menu.uiSize.auto") : tr("menu.uiSize.percent", Math.round(k * 100)), k == Settings.uiScale());
      it.addActionListener(ev -> chooseScale(k));
      sizes.add(it);
      size.add(it);
    }
    r.add(size);
    JMenu language = new JMenu(tr("menu.language"));
    ButtonGroup languages = new ButtonGroup();
    List<Tr.Lang> choices = new ArrayList<>();
    choices.add(null);
    choices.addAll(List.of(Tr.Lang.values()));
    for (Tr.Lang l : choices) {
      JRadioButtonMenuItem it = new JRadioButtonMenuItem(l == null ? tr("menu.language.auto") : l.label, l == Settings.language());
      it.addActionListener(ev -> chooseLanguage(l));
      languages.add(it);
      language.add(it);
    }
    r.add(language);
    JMenu units = new JMenu(tr("menu.units"));
    ButtonGroup lengths = new ButtonGroup(), forces = new ButtonGroup();
    units.add(new JLabel("  " + tr("menu.units.length")));
    for (Units.Length l : Units.Length.values()) {
      JRadioButtonMenuItem it = new JRadioButtonMenuItem(l.symbol, l == Units.length());
      it.addActionListener(ev -> chooseUnits(l, Units.force()));
      lengths.add(it);
      units.add(it);
    }
    units.addSeparator();
    units.add(new JLabel("  " + tr("menu.units.force")));
    for (Units.Force force : Units.Force.values()) {
      JRadioButtonMenuItem it = new JRadioButtonMenuItem(force.symbol, force == Units.force());
      it.addActionListener(ev -> chooseUnits(Units.length(), force));
      forces.add(it);
      units.add(it);
    }
    units.addSeparator();
    units.add(new JLabel("  " + tr("menu.units.note")));
    r.add(units);
    bar.add(r);
    return bar;
  }

  /**
   * Shows and takes lengths and forces in other units, at once: the grid's steps follow the length unit, and a
   * drawing still empty is shown at a scale for it. The structure itself (in m and N) does not change.
   */
  private void chooseUnits(Units.Length l, Units.Force f) {
    boolean lengthChanged = l != Units.length();
    Settings.setUnits(l, f);
    if (lengthChanged) {
      fillGridBox();
      if (structure.isEmpty()) canvas.fit();
    }
    // the tool's hint and the panel's labels carry the units
    setTool(tool);
    inspector.rebuildNow();
    changed();
  }

  /** The grid's steps in the length unit (m), the middle one chosen. */
  private static double[] gridSteps() {
    return switch (Units.length()) {
      case M -> new double[] {0.1, 0.25, 0.5, 1};
      case CM -> new double[] {0.01, 0.05, 0.1, 0.25, 0.5};
      case MM -> new double[] {0.001, 0.005, 0.01, 0.025, 0.05, 0.1};
    };
  }

  private void fillGridBox() {
    double[] steps = gridSteps();
    fillingGridBox = true;
    try {
      gridBox.removeAllItems();
      for (double step : steps) gridBox.addItem(Fmt.length(step));
      int middle = steps.length / 2;
      gridBox.setSelectedIndex(middle);
      grid = steps[middle];
    } finally {
      fillingGridBox = false;
    }
    canvas.repaint();
  }

  /** Keeps the new interface size, which applies at the next start: offers to start again now. */
  private void chooseScale(double k) {
    if (k == Settings.uiScale()) return;
    Settings.setUiScale(k);
    offerRestart(tr("restart.uiSize"), tr("menu.uiSize"));
  }

  /** Keeps the new language (null: the system's), which applies at the next start: offers to start again now. */
  private void chooseLanguage(Tr.Lang l) {
    if (l == Settings.language()) return;
    Settings.setLanguage(l);
    offerRestart(tr("restart.language"), tr("menu.language"));
  }

  private void offerRestart(String question, String title) {
    if (JOptionPane.showConfirmDialog(this, question, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) restart(title);
  }

  /** Starts the application again with the same Java and class path, handing it the drawing in progress. */
  private void restart(String title) {
    try {
      Path snapshot = Files.createTempFile("rdm-", ".rdm");
      StructureFile.save(structure, snapshot);
      // a packaged application (jpackage) has its own launcher, which takes only the application's arguments
      String launcher = System.getProperty("jpackage.app-path");
      List<String> cmd = new ArrayList<>();
      if (launcher != null) {
        cmd.add(launcher);
      } else {
        cmd.addAll(List.of(ProcessHandle.current().info().command().orElse("javaw"), "-cp", System.getProperty("java.class.path"), "rdm.App"));
      }
      cmd.addAll(List.of("--view", viewKey(), "--restore", snapshot.toString()));
      if (file != null) cmd.add(file.toString());
      new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      System.exit(0);
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, tr("restart.failed", ex.getMessage()), title, JOptionPane.WARNING_MESSAGE);
    }
  }

  private static JMenuItem item(String label, int key, Runnable action) {
    JMenuItem it = new JMenuItem(label);
    if (key != 0) it.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
    it.addActionListener(ev -> action.run());
    return it;
  }

  private JToolBar toolBar() {
    JToolBar bar = new JToolBar(SwingConstants.VERTICAL);
    bar.setFloatable(false);
    ButtonGroup group = new ButtonGroup();
    for (Tool t : Tool.values()) {
      JToggleButton b = new JToggleButton(t.label() + "  (" + t.key + ")");
      b.setToolTipText(t.help());
      b.setHorizontalAlignment(SwingConstants.LEFT);
      b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
      b.addActionListener(ev -> setTool(t));
      group.add(b);
      bar.add(b);
      toolButtons.put(t, b);
      if (t == Tool.BAR || t == Tool.SPRING || t == Tool.HINGE) bar.addSeparator();
    }
    return bar;
  }

  /** The views' list: the cases, the combinations, and the envelope when there are combinations. */
  private void fillShowBox() {
    fillingShowBox = true;
    try {
      showBox.removeAllItems();
      structure.cases.forEach(showBox::addItem);
      structure.combinations.forEach(showBox::addItem);
      if (!structure.combinations.isEmpty()) showBox.addItem(ENVELOPE);
      showBox.addItem(CHECKS);
      showBox.setSelectedItem(shown);
    } finally {
      fillingShowBox = false;
    }
  }

  /** Writes a case, a combination or the envelope as viewTitle() does. */
  javax.swing.ListCellRenderer<Object> viewRenderer() {
    javax.swing.DefaultListCellRenderer r = new javax.swing.DefaultListCellRenderer();
    return (list, value, index, selected, focus) -> r.getListCellRendererComponent(list, value == null ? "" : viewTitle(value), index, selected, focus);
  }

  private JToolBar displayBar() {
    JToolBar bar = new JToolBar();
    bar.setFloatable(false);
    bar.add(new JLabel(tr("display.show")));
    showBox.setRenderer(viewRenderer());
    showBox.setToolTipText(tr("display.showTip"));
    showBox.setMaximumSize(new Dimension(300, 26));
    showBox.addActionListener(e -> {
      if (!fillingShowBox && showBox.getSelectedItem() != null) show(showBox.getSelectedItem());
    });
    bar.add(showBox);
    JButton cases = new JButton(tr("display.cases"));
    cases.setToolTipText(tr("display.casesTip"));
    cases.addActionListener(e -> editCases());
    bar.add(cases);
    bar.addSeparator();
    JCheckBox deformed = new JCheckBox(tr("display.deformed"), showDeformed);
    deformed.addActionListener(e -> {
      showDeformed = deformed.isSelected();
      canvas.repaint();
    });
    bar.add(deformed);
    bar.add(new JLabel(" ×"));
    // the scale itself (1: true size), or Auto; another number may be typed in
    JComboBox<String> amp = new JComboBox<>(new String[] {tr("display.auto"), "1", "10", "100", "1000", "10000"});
    amp.setEditable(true);
    amp.setToolTipText(tr("display.amplify"));
    amp.setMaximumSize(new Dimension(90, 26));
    amp.addActionListener(e -> {
      Object v = amp.getSelectedItem();
      if (v == null || v.toString().strip().equals(tr("display.auto"))) {
        deformedScale = 0;
      } else {
        try {
          double k = Fmt.parse(v.toString());
          if (!(k > 0) || Double.isInfinite(k)) throw new NumberFormatException();
          deformedScale = k;
        } catch (NumberFormatException ex) {
          java.awt.Toolkit.getDefaultToolkit().beep();
          amp.setSelectedItem(deformedScale > 0 ? Fmt.field(deformedScale) : tr("display.auto"));
          return;
        }
      }
      canvas.repaint();
    });
    bar.add(amp);
    bar.addSeparator();
    bar.add(new JLabel(tr("display.diagram")));
    JComboBox<Diagram> diag = new JComboBox<>(Diagram.values());
    diag.setSelectedItem(diagram);
    diag.setMaximumSize(new Dimension(200, 26));
    diag.addActionListener(e -> {
      diagram = (Diagram) diag.getSelectedItem();
      canvas.repaint();
    });
    bar.add(diag);
    bar.addSeparator();
    bar.add(new JLabel(tr("display.buckling")));
    JComboBox<String> modes = new JComboBox<>();
    modes.addItem(tr("buckling.none"));
    for (int k = 1; k <= Buckling.MODES; k++) modes.addItem(tr("buckling.mode", k));
    modes.setToolTipText(tr("display.bucklingTip"));
    modes.setMaximumSize(new Dimension(110, 26));
    modes.addActionListener(e -> showBuckling(modes.getSelectedIndex()));
    bar.add(modes);
    bar.addSeparator();
    JCheckBox reactions = new JCheckBox(tr("display.reactions"), showReactions);
    reactions.addActionListener(e -> {
      showReactions = reactions.isSelected();
      canvas.repaint();
    });
    bar.add(reactions);
    JCheckBox numbers = new JCheckBox(tr("display.numbers"), showNumbers);
    numbers.addActionListener(e -> {
      showNumbers = numbers.isSelected();
      canvas.repaint();
    });
    bar.add(numbers);
    bar.addSeparator();
    bar.add(new JLabel(tr("display.grid")));
    fillGridBox();
    gridBox.setMaximumSize(new Dimension(90, 26));
    gridBox.addActionListener(e -> {
      if (fillingGridBox || gridBox.getSelectedIndex() < 0) return;
      grid = gridSteps()[gridBox.getSelectedIndex()];
      canvas.repaint();
    });
    bar.add(gridBox);
    bar.addSeparator();
    JButton fit = new JButton(tr("display.fit"));
    fit.addActionListener(e -> canvas.fit());
    bar.add(fit);
    return bar;
  }

  private JFileChooser chooser() {
    JFileChooser c = new JFileChooser(file == null ? null : file.getParent().toFile());
    c.setFileFilter(new FileNameExtensionFilter(tr("file.filter"), "rdm"));
    return c;
  }

  private void open() {
    JFileChooser c = chooser();
    if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path p = c.getSelectedFile().toPath();
    try {
      load(StructureFile.load(p), p);
    } catch (IOException | IllegalArgumentException ex) {
      JOptionPane.showMessageDialog(this, tr("file.openFailed", p.getFileName(), ex.getMessage()), tr("file.open"), JOptionPane.ERROR_MESSAGE);
    }
  }

  private interface Writer {
    void write(Path p) throws IOException;
  }

  /** Asks where to write a file of this kind (named after the structure's file), then writes it. */
  private void export(String extension, String filter, Writer writer) {
    JFileChooser c = new JFileChooser(file == null ? null : file.getParent().toFile());
    c.setFileFilter(new FileNameExtensionFilter(filter, extension));
    String name = file == null ? "structure" : file.getFileName().toString().replaceFirst("\\.rdm$", "");
    c.setSelectedFile(new java.io.File(c.getCurrentDirectory(), name + "." + extension));
    if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path p = c.getSelectedFile().toPath();
    if (!p.getFileName().toString().toLowerCase().endsWith("." + extension)) p = p.resolveSibling(p.getFileName() + "." + extension);
    try {
      writer.write(p);
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, tr("export.failed", ex.getMessage()), tr("menu.export"), JOptionPane.ERROR_MESSAGE);
    }
  }

  private void save() {
    JFileChooser c = chooser();
    if (file != null) c.setSelectedFile(file.toFile());
    if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path p = c.getSelectedFile().toPath();
    if (!p.getFileName().toString().toLowerCase().endsWith(".rdm")) p = p.resolveSibling(p.getFileName() + ".rdm");
    try {
      StructureFile.save(structure, p);
      file = p;
      setTitle(tr("app.title") + " — " + p.getFileName());
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, tr("file.saveFailed", ex.getMessage()), tr("file.save"), JOptionPane.ERROR_MESSAGE);
    }
  }
}
