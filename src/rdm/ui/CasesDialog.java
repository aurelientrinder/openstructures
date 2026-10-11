package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import rdm.io.StructureFile;
import rdm.model.Combination;
import rdm.model.LoadCase;
import rdm.model.Structure;

/**
 * The load cases and their combinations, in two tables: the cases (name, permanent or variable, self-weight), then
 * the combinations with their limit state and a factor per case, one column each. Works on a copy of the structure: OK hands it back,
 * Cancel forgets it. A combination named after its formula ("1.35 G + 1.5 Q") keeps that name up to date as its
 * factors or the cases' names change.
 */
final class CasesDialog {
  private final Structure s;
  private final JDialog dialog;
  private final JTable caseTable, comboTable;
  private final CaseModel cases = new CaseModel();
  private final ComboModel combos = new ComboModel();
  private boolean ok;

  private CasesDialog(MainFrame app, Structure original) {
    s = StructureFile.read(StructureFile.write(original));
    dialog = new JDialog(app, tr("cases.title"), true);
    caseTable = new JTable(cases);
    caseTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(LoadCase.Type.values())));
    javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
    right.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
    // the factors' columns come and go with the cases: their renderer and editor are chosen here, not set on each column
    DefaultCellEditor limits = new DefaultCellEditor(new JComboBox<>(Combination.Limit.values()));
    comboTable = new JTable(combos) {
      @Override
      public javax.swing.table.TableCellRenderer getCellRenderer(int row, int col) {
        return col <= 1 ? super.getCellRenderer(row, col) : right;
      }

      @Override
      public javax.swing.table.TableCellEditor getCellEditor(int row, int col) {
        return col == 1 ? limits : super.getCellEditor(row, col);
      }
    };
    for (JTable t : List.of(caseTable, comboTable)) {
      t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      t.putClientProperty("terminateEditOnFocusLost", true);
      t.setRowHeight(Math.max(t.getRowHeight(), t.getFontMetrics(t.getFont()).getHeight() + 6));
      t.getTableHeader().setReorderingAllowed(false);
    }

    JPanel body = new JPanel();
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    body.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
    body.add(heading(tr("cases.cases")));
    body.add(scroll(caseTable, 120));
    body.add(buttons(button(tr("cases.addCase"), this::addCase), button(tr("cases.removeCase"), this::removeCase)));
    body.add(Box.createVerticalStrut(8));
    body.add(heading(tr("cases.combinations")));
    body.add(scroll(comboTable, 170));
    body.add(buttons(button(tr("cases.addCombination"), this::addCombination), button(tr("cases.removeCombination"), this::removeCombination),
        button(tr("cases.generate"), this::generate)));
    body.add(note(tr("cases.note")));

    JButton okButton = button(tr("cases.ok"), this::accept);
    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    bottom.add(okButton);
    bottom.add(button(tr("sectionDialog.cancel"), dialog::dispose));
    dialog.getRootPane().setDefaultButton(okButton);
    dialog.add(body, BorderLayout.CENTER);
    dialog.add(bottom, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setSize(Math.max(dialog.getWidth(), 640), dialog.getHeight());
    dialog.setLocationRelativeTo(app);
  }

  /** The structure with its cases and combinations as edited, or null when cancelled. */
  static Structure edit(MainFrame app, Structure s) {
    CasesDialog d = new CasesDialog(app, s);
    d.dialog.setVisible(true);
    return d.ok ? d.s : null;
  }

  // ——— the tables ———

  private final class CaseModel extends AbstractTableModel {
    @Override
    public int getRowCount() {
      return s.cases.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(int col) {
      return tr(col == 0 ? "cases.colName" : col == 1 ? "cases.colType" : "cases.colSelfWeight");
    }

    @Override
    public Class<?> getColumnClass(int col) {
      return col == 2 ? Boolean.class : Object.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
      return true;
    }

    @Override
    public Object getValueAt(int row, int col) {
      LoadCase c = s.cases.get(row);
      return col == 0 ? c.name : col == 1 ? c.type : c.selfWeight;
    }

    @Override
    public void setValueAt(Object v, int row, int col) {
      LoadCase c = s.cases.get(row);
      if (col == 0) {
        String name = v.toString().strip();
        if (name.isEmpty() || name.equals(c.name)) return;
        keepingFormulas(() -> c.name = name);
        combos.fireTableStructureChanged();
      } else if (col == 1 && v instanceof LoadCase.Type t) {
        c.type = t;
      } else if (col == 2) {
        c.selfWeight = (Boolean) v;
      }
      fireTableRowsUpdated(row, row);
    }
  }

  private final class ComboModel extends AbstractTableModel {
    @Override
    public int getRowCount() {
      return s.combinations.size();
    }

    @Override
    public int getColumnCount() {
      return 2 + s.cases.size();
    }

    @Override
    public String getColumnName(int col) {
      return col == 0 ? tr("cases.colName") : col == 1 ? tr("cases.colLimit") : tr("cases.colFactor", s.cases.get(col - 2).name);
    }

    @Override
    public boolean isCellEditable(int row, int col) {
      return true;
    }

    @Override
    public Object getValueAt(int row, int col) {
      Combination k = s.combinations.get(row);
      return col == 0 ? k.name : col == 1 ? k.limit : Fmt.num(k.factor(s.cases.get(col - 2)));
    }

    @Override
    public void setValueAt(Object v, int row, int col) {
      Combination k = s.combinations.get(row);
      if (col == 0) {
        String name = v.toString().strip();
        if (!name.isEmpty()) k.name = name;
      } else if (col == 1) {
        if (v instanceof Combination.Limit l) k.limit = l;
      } else {
        try {
          double f = Fmt.parse(v.toString());
          LoadCase c = s.cases.get(col - 2);
          boolean named = k.name.equals(k.formula(s));
          k.setFactor(c, f);
          if (named) k.name = k.formula(s);
        } catch (NumberFormatException ex) {
          java.awt.Toolkit.getDefaultToolkit().beep();
        }
      }
      fireTableRowsUpdated(row, row);
    }
  }

  /** A change after which the combinations named after their formula are named after the new one. */
  private void keepingFormulas(Runnable change) {
    List<Combination> named = s.combinations.stream().filter(k -> k.name.equals(k.formula(s))).toList();
    change.run();
    for (Combination k : named) k.name = k.formula(s);
  }

  // ——— the buttons ———

  private void addCase() {
    stopEditing();
    String name = "Q";
    for (int i = 2; nameTaken(name); i++) name = "Q" + i;
    s.addCase(name, LoadCase.Type.VARIABLE);
    cases.fireTableDataChanged();
    combos.fireTableStructureChanged();
    select(caseTable, s.cases.size() - 1);
  }

  private boolean nameTaken(String name) {
    return s.cases.stream().anyMatch(c -> c.name.equals(name));
  }

  private void removeCase() {
    stopEditing();
    int row = caseTable.getSelectedRow();
    if (row < 0) return;
    LoadCase c = s.cases.get(row);
    if (s.cases.size() == 1) {
      JOptionPane.showMessageDialog(dialog, tr("cases.lastCase"), tr("cases.title"), JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    long loads = s.elements.stream().mapToLong(e -> e.loadsIn(c).size()).sum() + s.nodes.stream().filter(n -> !n.load(c).isZero()).count();
    if (loads > 0 && JOptionPane.showConfirmDialog(dialog, tr("cases.removeWithLoads", c.name, loads), tr("cases.title"),
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
      return;
    }
    keepingFormulas(() -> s.removeCase(c));
    cases.fireTableDataChanged();
    combos.fireTableStructureChanged();
    select(caseTable, Math.min(row, s.cases.size() - 1));
  }

  private void addCombination() {
    stopEditing();
    Combination k = s.addCombination("");
    for (LoadCase c : s.cases) k.setFactor(c, 1);
    k.name = k.formula(s);
    combos.fireTableDataChanged();
    select(comboTable, s.combinations.size() - 1);
  }

  private void removeCombination() {
    stopEditing();
    int row = comboTable.getSelectedRow();
    if (row < 0) return;
    s.combinations.remove(row);
    combos.fireTableDataChanged();
    if (!s.combinations.isEmpty()) select(comboTable, Math.min(row, s.combinations.size() - 1));
  }

  private void generate() {
    stopEditing();
    int added = s.generateCombinations();
    combos.fireTableDataChanged();
    if (added == 0) JOptionPane.showMessageDialog(dialog, tr("cases.generatedNone"), tr("cases.title"), JOptionPane.INFORMATION_MESSAGE);
  }

  private void accept() {
    stopEditing();
    ok = true;
    dialog.dispose();
  }

  private void stopEditing() {
    for (JTable t : List.of(caseTable, comboTable)) if (t.isEditing()) t.getCellEditor().stopCellEditing();
  }

  private static void select(JTable t, int row) {
    if (row >= 0) t.setRowSelectionInterval(row, row);
  }

  // ——— layout ———

  private static JComponent heading(String text) {
    JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    return left(l);
  }

  private static JComponent scroll(JTable t, int height) {
    JScrollPane p = new JScrollPane(t);
    p.setPreferredSize(new Dimension(600, height));
    p.setAlignmentX(0);
    return p;
  }

  private static JComponent buttons(JButton... buttons) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    for (JButton b : buttons) p.add(b);
    p.setAlignmentX(0);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
    return p;
  }

  private static JButton button(String label, Runnable action) {
    JButton b = new JButton(label);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static JComponent note(String text) {
    JTextArea t = new JTextArea(text);
    t.setLineWrap(true);
    t.setWrapStyleWord(true);
    t.setEditable(false);
    t.setFocusable(false);
    t.setOpaque(false);
    t.setFont(new JLabel().getFont().deriveFont(Font.ITALIC));
    t.setColumns(40);
    t.setAlignmentX(0);
    t.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
    return t;
  }

  private static JComponent left(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.add(c, BorderLayout.WEST);
    p.setAlignmentX(0);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    return p;
  }
}
