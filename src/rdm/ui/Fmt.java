package rdm.ui;

import java.math.BigDecimal;
import java.math.MathContext;

/** Numbers on screen: 4 significant digits, decimal comma; typed numbers accept a comma or a point. */
final class Fmt {
  private Fmt() {}

  static String num(double v) {
    if (Double.isNaN(v)) return "—";
    if (Math.abs(v) < 1e-12) return "0";
    String s;
    double a = Math.abs(v);
    if (a >= 1e-3 && a < 1e7) s = new BigDecimal(v).round(new MathContext(4)).stripTrailingZeros().toPlainString();
    else s = String.format(java.util.Locale.ROOT, "%.3e", v);
    return s.replace('.', ',').replace('-', '−');
  }

  /** A typed number; throws NumberFormatException. */
  static double parse(String s) {
    return Double.parseDouble(s.replaceAll("[\\s  ]", "").replace(',', '.').replace('−', '-'));
  }

  static String kN(double newtons) {
    return num(newtons / 1e3) + " kN";
  }

  static String kNm(double nm) {
    return num(nm / 1e3) + " kN·m";
  }

  static String mm(double m) {
    return num(m * 1e3) + " mm";
  }

  static String MPa(double pa) {
    return num(pa / 1e6) + " MPa";
  }
}
