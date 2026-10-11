package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.Component;
import java.awt.GridLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import rdm.i18n.Units;
import rdm.model.Element;
import rdm.model.ElementLoad;

/**
 * The dialogs that create or change a load on an element (Force, Moment and Distributed load tools, and the
 * properties panel). Positions are typed in metres from the element's origin (node a), values in kN, kN·m, kN/m.
 * The load stays in the case of the one it starts from.
 */
final class LoadDialog {
  private LoadDialog() {}

  /** Asks again for whichever kind of load this is; null when cancelled. */
  static ElementLoad edit(Component parent, Element e, ElementLoad l) {
    return switch (l) {
      case ElementLoad.Force f -> force(parent, e, f);
      case ElementLoad.Couple c -> couple(parent, e, c);
      case ElementLoad.Distributed d -> distributed(parent, e, d);
    };
  }

  static ElementLoad force(Component parent, Element e, ElementLoad.Force init) {
    double L = Units.L(e.length());
    Form f = new Form(tr("load.force"), tr("load.forceLocal"), init.local());
    f.field(tr("load.position", Fmt.num(L)), init.t() * L);
    f.field("Fx (" + Units.f() + ")", Units.F(init.fx()));
    f.field("Fy (" + Units.f() + ")", Units.F(init.fy()));
    while (true) {
      double[] v = f.ask(parent);
      if (v == null) return null;
      if (v[0] <= 0 || v[0] >= L) {
        f.complain(parent, tr("load.forceOutside"));
        continue;
      }
      return new ElementLoad.Force(init.loadCase(), v[0] / L, Units.fromF(v[1]), Units.fromF(v[2]), f.local());
    }
  }

  static ElementLoad couple(Component parent, Element e, ElementLoad.Couple init) {
    double L = Units.L(e.length());
    Form f = new Form(tr("load.couple"), null, false);
    f.field(tr("load.position", Fmt.num(L)), init.t() * L);
    f.field(tr("load.coupleValue"), Units.M(init.c()));
    while (true) {
      double[] v = f.ask(parent);
      if (v == null) return null;
      if (v[0] <= 0 || v[0] >= L) {
        f.complain(parent, tr("load.coupleOutside"));
        continue;
      }
      return new ElementLoad.Couple(init.loadCase(), v[0] / L, Units.fromM(v[1]));
    }
  }

  static ElementLoad distributed(Component parent, Element e, ElementLoad.Distributed init) {
    double L = Units.L(e.length());
    Form f = new Form(tr("load.distributed"), tr("load.distributedLocal"), init.local());
    f.field(tr("load.start"), init.t1() * L);
    f.field(tr("load.end", Fmt.num(L)), init.t2() * L);
    f.field(tr("load.qx1"), Units.Q(init.qx1()));
    f.field(tr("load.qy1"), Units.Q(init.qy1()));
    f.field(tr("load.qx2"), Units.Q(init.qx2()));
    f.field(tr("load.qy2"), Units.Q(init.qy2()));
    while (true) {
      double[] v = f.ask(parent);
      if (v == null) return null;
      if (v[0] < 0 || v[1] > L + 1e-9 || v[1] <= v[0]) {
        f.complain(parent, tr("load.range", Fmt.num(L)));
        continue;
      }
      return new ElementLoad.Distributed(init.loadCase(), v[0] / L, Math.min(1, v[1] / L), Units.fromQ(v[2]), Units.fromQ(v[3]), Units.fromQ(v[4]), Units.fromQ(v[5]), f.local());
    }
  }

  /** One line for the properties panel. */
  static String describe(Element e, ElementLoad l) {
    double L = Units.L(e.length());
    return switch (l) {
      case ElementLoad.Force f -> tr("load.describeForce", pair(f.fx(), f.fy()), Fmt.num(f.t() * L)) + (f.local() ? tr("load.describeLocal") : "");
      case ElementLoad.Couple c -> tr("load.describeCouple", Fmt.moment(c.c()), Fmt.num(c.t() * L));
      case ElementLoad.Distributed d -> {
        boolean uniform = d.qx1() == d.qx2() && d.qy1() == d.qy2();
        String values = uniform ? pairQ(d.qx1(), d.qy1()) : pairQ(d.qx1(), d.qy1()) + " → " + pairQ(d.qx2(), d.qy2());
        String span = d.t1() == 0 && d.t2() == 1 ? "" : tr("load.describeSpan", Fmt.num(d.t1() * L), Fmt.num(d.t2() * L));
        yield tr("load.describeDistributed", values) + span + (d.local() ? tr("load.describeLocal") : "");
      }
    };
  }

  /** (x ; y) in the force unit, from newtons. */
  private static String pair(double x, double y) {
    return tr("load.pair", Fmt.num(Units.F(x)), Fmt.num(Units.F(y)));
  }

  /** (x ; y) in force per length, from N/m. */
  private static String pairQ(double x, double y) {
    return tr("load.pair", Fmt.num(Units.Q(x)), Fmt.num(Units.Q(y)));
  }

  /** A column of number fields, and maybe the local-axes box. */
  private static final class Form {
    private final String title;
    private final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 4));
    private final java.util.List<JTextField> fields = new java.util.ArrayList<>();
    private final JCheckBox local;

    Form(String title, String localLabel, boolean isLocal) {
      this.title = title;
      local = localLabel == null ? null : new JCheckBox(localLabel, isLocal);
    }

    void field(String label, double value) {
      JTextField t = new JTextField(Fmt.field(value), 8);
      panel.add(new JLabel(label));
      panel.add(t);
      fields.add(t);
    }

    boolean local() {
      return local != null && local.isSelected();
    }

    double[] ask(Component parent) {
      JPanel all = new JPanel(new java.awt.BorderLayout(0, 8));
      all.add(panel, java.awt.BorderLayout.CENTER);
      if (local != null) all.add(local, java.awt.BorderLayout.SOUTH);
      while (true) {
        if (JOptionPane.showConfirmDialog(parent, all, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        try {
          double[] out = new double[fields.size()];
          for (int i = 0; i < out.length; i++) out[i] = Fmt.parse(fields.get(i).getText());
          return out;
        } catch (NumberFormatException ex) {
          complain(parent, tr("load.number"));
        }
      }
    }

    void complain(Component parent, String why) {
      JOptionPane.showMessageDialog(parent, why, title, JOptionPane.WARNING_MESSAGE);
    }
  }
}
