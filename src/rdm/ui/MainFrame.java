package rdm.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import rdm.Settings;
import rdm.io.StructureFile;
import rdm.model.Examples;
import rdm.model.Structure;
import rdm.solver.Solution;
import rdm.solver.Solver;

/**
 * The window, and the controller every view goes through: the structure, its solution (solved again after
 * every change, so the results follow the drawing live), the selection, the tool, undo and the display options.
 */
public final class MainFrame extends JFrame {
  enum Diagram {
    NONE("Aucun"), N("Effort normal N"), T("Effort tranchant T"), M("Moment fléchissant Mf");

    final String label;

    Diagram(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private Structure structure = new Structure();
  private Solution solution = Solver.solve(structure);
  private Object selection;
  private Tool tool = Tool.SELECT;
  private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private Path file;

  // display options
  boolean showDeformed = true, showReactions = true, showNumbers = false;
  Diagram diagram = Diagram.M;
  double amplify = 1;
  double grid = 0.5;

  final StructureCanvas canvas = new StructureCanvas(this);
  private final Inspector inspector = new Inspector(this);
  private final JTextArea report = new JTextArea();
  private final JLabel status = new JLabel(" ");
  private final JLabel hint = new JLabel(" ");
  private final Map<Tool, AbstractButton> toolButtons = new EnumMap<>(Tool.class);

  public MainFrame() {
    super("RDM — Ossatures planes");
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setJMenuBar(menus());
    add(displayBar(), BorderLayout.NORTH);
    add(toolBar(), BorderLayout.WEST);
    add(canvas, BorderLayout.CENTER);

    report.setEditable(false);
    report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Propriétés", new JScrollPane(inspector));
    tabs.addTab("Résultats", new JScrollPane(report));
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
    load(Examples.ALL.get("Portique encastré").get(), null);
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

  Solution solution() {
    return solution;
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
    AbstractButton b = toolButtons.get(t);
    if (b != null) b.setSelected(true);
    hint.setText(t.help);
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

  /** Solves again and refreshes every view. */
  void changed() {
    solution = Solver.solve(structure);
    if (selection instanceof rdm.model.Node n && !structure.nodes.contains(n)) selection = null;
    if (selection instanceof rdm.model.Element e && !structure.elements.contains(e)) selection = null;
    inspector.refresh();
    report.setText(Report.of(structure, solution));
    report.setCaretPosition(0);
    status.setText(summary());
    canvas.repaint();
  }

  private String summary() {
    Solution s = solution;
    return switch (s.status) {
      case EMPTY -> "Dessinez une structure : outils Poutre (P) ou Barre (B).";
      case ERROR -> "Erreur : " + s.error;
      case MECHANISM -> "Mécanisme (m = " + s.m + ") : la structure peut bouger — " + String.join(", ", s.free);
      case OK -> (s.h == 0 ? "Isostatique (h = 0)" : "Hyperstatique d'ordre " + s.h) + " — déplacement max "
          + Fmt.mm(s.maxDisplacement) + (s.warnings.isEmpty() ? "" : " — " + String.join(" ; ", s.warnings));
    };
  }

  void hover(String text) {
    status.setText(text == null ? summary() : text);
  }

  private void restore(String snapshot) {
    structure = StructureFile.read(snapshot);
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
    structure = s;
    file = from;
    selection = null;
    undo.clear();
    redo.clear();
    canvas.cancelChain();
    changed();
    canvas.fit();
    setTitle("RDM — Ossatures planes" + (from == null ? "" : " — " + from.getFileName()));
  }

  // ——— window parts ———

  private JMenuBar menus() {
    JMenuBar bar = new JMenuBar();
    JMenu f = new JMenu("Fichier");
    f.add(item("Nouveau", KeyEvent.VK_N, () -> load(new Structure(), null)));
    f.add(item("Ouvrir…", KeyEvent.VK_O, this::open));
    f.add(item("Enregistrer…", KeyEvent.VK_S, this::save));
    JMenu ex = new JMenu("Exemples");
    Examples.ALL.forEach((name, make) -> ex.add(item(name, 0, () -> load(make.get(), null))));
    f.add(ex);
    f.addSeparator();
    f.add(item("Quitter", 0, () -> System.exit(0)));
    bar.add(f);
    JMenu e = new JMenu("Édition");
    e.add(item("Annuler", KeyEvent.VK_Z, this::undo));
    e.add(item("Rétablir", KeyEvent.VK_Y, this::redo));
    JMenuItem del = item("Effacer la sélection", 0, canvas::deleteSelection);
    del.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
    e.add(del);
    bar.add(e);
    JMenu v = new JMenu("Affichage");
    v.add(item("Ajuster à la fenêtre", 0, canvas::fit));
    bar.add(v);
    JMenu r = new JMenu("Réglages");
    JMenu size = new JMenu("Taille de l'interface");
    ButtonGroup sizes = new ButtonGroup();
    for (double k : Settings.UI_SCALES) {
      JRadioButtonMenuItem it = new JRadioButtonMenuItem(k == 0 ? "Automatique (réglage de Windows)" : Math.round(k * 100) + " %", k == Settings.uiScale());
      it.addActionListener(ev -> chooseScale(k));
      sizes.add(it);
      size.add(it);
    }
    r.add(size);
    bar.add(r);
    return bar;
  }

  /** Keeps the new interface size, which applies at the next start: offers to start again now. */
  private void chooseScale(double k) {
    if (k == Settings.uiScale()) return;
    Settings.setUiScale(k);
    int answer = JOptionPane.showConfirmDialog(this, "La nouvelle taille s'applique au redémarrage.\nRedémarrer maintenant ? Le dessin en cours est conservé.",
        "Taille de l'interface", JOptionPane.YES_NO_OPTION);
    if (answer == JOptionPane.YES_OPTION) restart();
  }

  /** Starts the application again with the same Java and class path, handing it the drawing in progress. */
  private void restart() {
    try {
      Path snapshot = Files.createTempFile("rdm-", ".rdm");
      StructureFile.save(structure, snapshot);
      String java = ProcessHandle.current().info().command().orElse("javaw");
      List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), "rdm.App", "--restore", snapshot.toString()));
      if (file != null) cmd.add(file.toString());
      new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      System.exit(0);
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, "Impossible de redémarrer : " + ex.getMessage() + "\nRelancez l'application vous-même.", "Taille de l'interface",
          JOptionPane.WARNING_MESSAGE);
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
      JToggleButton b = new JToggleButton(t.label + "  (" + t.key + ")");
      b.setToolTipText(t.help);
      b.setHorizontalAlignment(SwingConstants.LEFT);
      b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
      b.addActionListener(ev -> setTool(t));
      group.add(b);
      bar.add(b);
      toolButtons.put(t, b);
      if (t == Tool.BAR || t == Tool.SLIDER || t == Tool.HINGE) bar.addSeparator();
    }
    return bar;
  }

  private JToolBar displayBar() {
    JToolBar bar = new JToolBar();
    bar.setFloatable(false);
    JCheckBox deformed = new JCheckBox("Déformée", showDeformed);
    deformed.addActionListener(e -> {
      showDeformed = deformed.isSelected();
      canvas.repaint();
    });
    bar.add(deformed);
    bar.add(new JLabel(" ×"));
    JSpinner amp = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 100.0, 0.5));
    amp.setToolTipText("Amplification de la déformée, en plus de l'échelle automatique");
    amp.setMaximumSize(new Dimension(70, 26));
    amp.addChangeListener(e -> {
      amplify = (Double) amp.getValue();
      canvas.repaint();
    });
    bar.add(amp);
    bar.addSeparator();
    bar.add(new JLabel("Diagramme : "));
    JComboBox<Diagram> diag = new JComboBox<>(Diagram.values());
    diag.setSelectedItem(diagram);
    diag.setMaximumSize(new Dimension(200, 26));
    diag.addActionListener(e -> {
      diagram = (Diagram) diag.getSelectedItem();
      canvas.repaint();
    });
    bar.add(diag);
    bar.addSeparator();
    JCheckBox reactions = new JCheckBox("Réactions", showReactions);
    reactions.addActionListener(e -> {
      showReactions = reactions.isSelected();
      canvas.repaint();
    });
    bar.add(reactions);
    JCheckBox numbers = new JCheckBox("Numéros", showNumbers);
    numbers.addActionListener(e -> {
      showNumbers = numbers.isSelected();
      canvas.repaint();
    });
    bar.add(numbers);
    bar.addSeparator();
    bar.add(new JLabel("Grille : "));
    JComboBox<String> gridBox = new JComboBox<>(new String[] {"0,1 m", "0,25 m", "0,5 m", "1 m"});
    double[] steps = {0.1, 0.25, 0.5, 1};
    gridBox.setSelectedIndex(2);
    gridBox.setMaximumSize(new Dimension(90, 26));
    gridBox.addActionListener(e -> {
      grid = steps[gridBox.getSelectedIndex()];
      canvas.repaint();
    });
    bar.add(gridBox);
    bar.addSeparator();
    JButton fit = new JButton("Ajuster");
    fit.addActionListener(e -> canvas.fit());
    bar.add(fit);
    return bar;
  }

  private JFileChooser chooser() {
    JFileChooser c = new JFileChooser(file == null ? null : file.getParent().toFile());
    c.setFileFilter(new FileNameExtensionFilter("Structures RDM (*.rdm)", "rdm"));
    return c;
  }

  private void open() {
    JFileChooser c = chooser();
    if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path p = c.getSelectedFile().toPath();
    try {
      load(StructureFile.load(p), p);
    } catch (IOException | IllegalArgumentException ex) {
      JOptionPane.showMessageDialog(this, "Impossible d'ouvrir " + p.getFileName() + " :\n" + ex.getMessage(), "Ouvrir", JOptionPane.ERROR_MESSAGE);
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
      setTitle("RDM — Ossatures planes — " + p.getFileName());
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, "Impossible d'enregistrer :\n" + ex.getMessage(), "Enregistrer", JOptionPane.ERROR_MESSAGE);
    }
  }
}
