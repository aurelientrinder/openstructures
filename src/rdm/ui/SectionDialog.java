package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rdm.model.Section;

/**
 * Chooses a section from its templates: each drawn, the chosen one large with its dimensions; the dimensions are
 * typed beside it, and its area, inertia, extreme fibre and mass per metre follow as they are typed.
 */
final class SectionDialog extends JDialog {
  private final double rho;
  /** the section being made of each kind, so that going back to a kind finds it as it was left */
  private final Map<Section.Kind, Section> made = new EnumMap<>(Section.Kind.class);
  private Section current;
  private Section result;

  private final JList<Section.Kind> kinds = new JList<>(Section.Kind.values());
  private final SectionView view;
  private final JPanel form = new JPanel(new GridBagLayout());
  private final JLabel area = new JLabel(), inertia = new JLabel(), fibre = new JLabel(), mass = new JLabel();
  private final JTextArea note = new JTextArea();
  private final JButton ok = new JButton("OK");

  /** The section chosen, or null when cancelled. rho: the element's density (kg/m³), for its mass. */
  static Section ask(Component parent, Section init, double rho) {
    SectionDialog d = new SectionDialog(parent, init, rho);
    d.setVisible(true);
    return d.result;
  }

  private SectionDialog(Component parent, Section init, double rho) {
    super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), tr("sectionDialog.title"), ModalityType.APPLICATION_MODAL);
    this.rho = rho;
    current = init;
    made.put(init.kind(), init);
    view = new SectionView(init);

    kinds.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    kinds.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
        JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
        Section.Kind k = (Section.Kind) value;
        l.setIcon(SectionView.icon(made.getOrDefault(k, Section.of(k)), 36));
        l.setIconTextGap(10);
        l.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 10));
        return l;
      }
    });
    kinds.setSelectedValue(init.kind(), true);
    kinds.addListSelectionListener(e -> {
      if (e.getValueIsAdjusting() || kinds.getSelectedValue() == null) return;
      Section.Kind k = kinds.getSelectedValue();
      current = made.computeIfAbsent(k, Section::of);
      buildForm();
      update();
    });
    JPanel left = new JPanel(new BorderLayout(0, 4));
    left.add(heading(tr("sectionDialog.templates")), BorderLayout.NORTH);
    left.add(new JScrollPane(kinds), BorderLayout.CENTER);

    view.setBorder(BorderFactory.createLineBorder(new Color(0xd8, 0xdc, 0xe4)));
    JPanel middle = new JPanel(new BorderLayout(0, 4));
    middle.add(view, BorderLayout.CENTER);
    JLabel legend = new JLabel(tr("sectionDialog.legend"));
    legend.setForeground(Color.GRAY);
    middle.add(legend, BorderLayout.SOUTH);

    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    note.setEditable(false);
    note.setFocusable(false);
    note.setOpaque(false);
    note.setColumns(18);
    note.setFont(getFont() == null ? new JLabel().getFont() : getFont());
    JPanel right = new JPanel(new BorderLayout());
    right.add(form, BorderLayout.NORTH);
    right.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

    JPanel center = new JPanel(new BorderLayout(10, 0));
    center.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
    center.add(left, BorderLayout.WEST);
    center.add(middle, BorderLayout.CENTER);
    center.add(right, BorderLayout.EAST);

    JButton cancel = new JButton(tr("sectionDialog.cancel"));
    ok.addActionListener(e -> {
      result = current;
      dispose();
    });
    cancel.addActionListener(e -> dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(ok);
    buttons.add(cancel);
    getRootPane().setDefaultButton(ok);
    getRootPane().registerKeyboardAction(e -> dispose(), javax.swing.KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

    add(center, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    buildForm();
    update();
    pack();
    // only now that the list has its size
    kinds.ensureIndexIsVisible(kinds.getSelectedIndex());
    setLocationRelativeTo(parent);
  }

  private static JLabel heading(String text) {
    JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }

  private int row;

  /**
   * The lists that choose a catalogue section, each with its label: the sections of a rolled table, or a hollow
   * section's outside size then its thickness. picked gets the section chosen, not turned.
   */
  static Map<String, JComponent> pickers(Section init, Consumer<Section> picked) {
    Map<String, JComponent> out = new LinkedHashMap<>();
    Section.Kind k = init.kind();
    List<Section> all = Section.sizes(k);
    if (k.rolled()) {
      JComboBox<String> size = new JComboBox<>();
      for (Section s : all) size.addItem(s.label());
      size.setSelectedItem(init.turned(false).label());
      size.setMaximumRowCount(16);
      size.addActionListener(e -> picked.accept(all.get(size.getSelectedIndex())));
      out.put(tr("insp.profile"), size);
    } else if (!all.isEmpty()) {
      // the thickness is the last dimension; the sizes are the sections that differ by it only
      int t = k == Section.Kind.RHS ? 2 : 1;
      Map<String, List<Section>> sizes = new LinkedHashMap<>();
      for (Section s : all) sizes.computeIfAbsent(outside(s, t), x -> new ArrayList<>()).add(s);
      JComboBox<String> size = new JComboBox<>(sizes.keySet().toArray(new String[0]));
      JComboBox<String> thickness = new JComboBox<>();
      size.setMaximumRowCount(16);
      boolean[] filling = {false};
      // a new size keeps the thickness when it has it, else takes its first
      Runnable fill = () -> {
        String was = (String) thickness.getSelectedItem();
        filling[0] = true;
        thickness.removeAllItems();
        for (Section s : sizes.get((String) size.getSelectedItem())) thickness.addItem(Fmt.num(s.param(t)));
        thickness.setSelectedItem(was);
        filling[0] = false;
      };
      Runnable pick = () -> picked.accept(sizes.get((String) size.getSelectedItem()).get(thickness.getSelectedIndex()));
      size.setSelectedItem(outside(init, t));
      fill.run();
      thickness.setSelectedItem(Fmt.num(init.param(t)));
      size.addActionListener(e -> {
        fill.run();
        pick.run();
      });
      thickness.addActionListener(e -> {
        if (!filling[0]) pick.run();
      });
      out.put(tr("insp.profile"), size);
      out.put(tr("dim.t"), thickness);
    }
    return out;
  }

  /** The outside size of a hollow section, its dimensions before the thickness t: "200 × 100", "Ø114,3". */
  private static String outside(Section s, int t) {
    if (s.kind() == Section.Kind.CHS) return "Ø" + Fmt.num(s.p1());
    return Fmt.num(s.p1()) + " × " + Fmt.num(t == 1 ? s.p1() : s.p2());
  }

  /** The fields of the current kind: its dimensions, or its catalogue; the quarter turn; then the properties. */
  private void buildForm() {
    form.removeAll();
    row = 0;
    put(heading(current.kind().toString()), null);
    Section.Kind k = current.kind();
    pickers(current, s -> {
      current = s.turned(current.turned());
      changed();
    }).forEach((label, list) -> put(new JLabel(label), list));
    for (int i = 0; i < k.params.size(); i++) {
      int which = i;
      JTextField f = new JTextField(Fmt.field(current.param(i)), 7);
      f.getDocument().addDocumentListener(new DocumentListener() {
        private void typed() {
          try {
            current = current.with(which, Fmt.parse(f.getText()));
            changed();
          } catch (NumberFormatException ex) {
            ok.setEnabled(false);
          }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
          typed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
          typed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
          typed();
        }
      });
      put(new JLabel(tr("dim." + k.params.get(i))), f);
    }
    if (k.turns()) {
      JCheckBox turned = new JCheckBox(tr("insp.turned"), current.turned());
      turned.addActionListener(e -> {
        current = current.turned(turned.isSelected());
        changed();
      });
      put(turned, null);
    }
    put(heading(tr("sectionDialog.properties")), null);
    put(new JLabel("A"), area);
    put(new JLabel("I"), inertia);
    put(new JLabel("v"), fibre);
    put(new JLabel(tr("sectionDialog.mass")), mass);
    put(note, null);
    form.revalidate();
    form.repaint();
  }

  private void put(JComponent a, JComponent b) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = row++;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(row == 1 ? 0 : 3, 0, 3, 8);
    if (b == null) {
      c.gridwidth = 2;
      c.fill = GridBagConstraints.HORIZONTAL;
      if (a instanceof JLabel l && l.getFont().isBold() && row > 1) c.insets = new Insets(12, 0, 3, 8);
      form.add(a, c);
      return;
    }
    form.add(a, c);
    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    form.add(b, c);
  }

  private void changed() {
    made.put(current.kind(), current);
    kinds.repaint();
    update();
  }

  /** The drawing and the properties of the section as it is typed. */
  private void update() {
    view.show(current);
    String problem = current.problem();
    ok.setEnabled(problem == null);
    if (problem != null) {
      for (JLabel l : new JLabel[] {area, inertia, fibre, mass}) l.setText("—");
      note.setForeground(new Color(0xc8, 0x3a, 0x1e));
      note.setText(tr("sectionDialog.invalid"));
      return;
    }
    area.setText(Fmt.num(current.area() * 1e4) + " cm²");
    inertia.setText(Fmt.num(current.inertia() * 1e8) + " cm⁴");
    fibre.setText(Fmt.num(current.fibre() * 1e2) + " cm");
    mass.setText(Fmt.num(current.area() * rho) + " kg/m");
    note.setForeground(Color.GRAY);
    note.setText(switch (current.kind()) {
      case BOX, I_SECTION, T_SECTION, CHANNEL -> tr("sectionDialog.thinWalled");
      case IPE, HEA, HEB, UPN -> tr("sectionDialog.rolled");
      case SHS, RHS, CHS -> tr("sectionDialog.hollow");
      case CUSTOM -> tr("sectionDialog.custom");
      default -> "";
    });
  }
}
