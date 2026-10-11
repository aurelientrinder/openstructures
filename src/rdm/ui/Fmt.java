package rdm.ui;

import java.math.BigDecimal;
import java.math.MathContext;
import rdm.i18n.Tr;
import rdm.i18n.Units;

/** Numbers on screen: 4 significant digits, the language's decimal separator; typed numbers accept a comma or a point. */
final class Fmt {
  private Fmt() {}

  static String num(double v) {
    if (Double.isNaN(v)) return "—";
    if (Math.abs(v) < 1e-12) return "0";
    String s;
    double a = Math.abs(v);
    if (a >= 1e-3 && a < 1e12) s = grouped(new BigDecimal(v).round(new MathContext(4)).stripTrailingZeros().toPlainString());
    else s = String.format(java.util.Locale.ROOT, "%.3e", v);
    return Tr.decimal(s).replace('-', '−');
  }

  /** From 10 000 on, the thousands apart, by a narrow space (in N·mm, 14 000 000): typed back, it is skipped. */
  private static String grouped(String plain) {
    int dot = plain.indexOf('.'), end = dot < 0 ? plain.length() : dot, start = plain.startsWith("-") ? 1 : 0;
    if (end - start < 5) return plain;
    StringBuilder b = new StringBuilder(plain.substring(end));
    for (int i = end; i > start; i -= 3) {
      b.insert(0, plain.substring(Math.max(start, i - 3), i));
      if (i - 3 > start) b.insert(0, ' ');
    }
    return plain.substring(0, start) + b;
  }

  /**
   * A number in a field to edit: 10 significant digits, so that leaving the field without typing changes nothing
   * (shown with 4, 12 345.6 mm would come back as 12 350).
   */
  static String field(double v) {
    if (Math.abs(v) < 1e-12) return "0";
    return Tr.decimal(new BigDecimal(v).round(new MathContext(10)).stripTrailingZeros().toPlainString());
  }

  /** A typed number; throws NumberFormatException. */
  static double parse(String s) {
    return Double.parseDouble(s.replaceAll("[\\s  ]", "").replace(',', '.').replace('−', '-'));
  }

  /** A force (N) in the force unit, with it. */
  static String force(double newtons) {
    return num(Units.F(newtons)) + " " + Units.f();
  }

  /** A moment (N·m) in the moment unit, with it. */
  static String moment(double nm) {
    return num(Units.M(nm)) + " " + Units.m();
  }

  /** A length (m) in the length unit, with it. */
  static String length(double m) {
    return num(Units.L(m)) + " " + Units.l();
  }

  static String mm(double m) {
    return num(m * 1e3) + " mm";
  }

  /** A utilisation, 2 decimals rounded up: 1.004 fails, so it shows 1.01, not 1.00. */
  static String ratio(double r) {
    if (Double.isNaN(r)) return "—";
    if (Double.isInfinite(r)) return "∞";
    return Tr.decimal(String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, Math.ceil(r * 100 - 1e-9)) / 100));
  }

  static String MPa(double pa) {
    return num(pa / 1e6) + " MPa";
  }
}
