package rdm.ui;

import rdm.i18n.Tr;

/**
 * A point typed while drawing, as in CAD: "x y" a point (m), "@dx dy" from the last point, "L<a" a length L from the
 * last point at a degrees (counter-clockwise from x, "@" or not), and "L" alone a length from the last point towards
 * the pointer. The numbers are separated by a space or ";", and by "," too when the language writes decimal points
 * (in French "2,5" is two and a half).
 */
public final class PointEntry {
  private PointEntry() {}

  /**
   * The point typed, or null when the text says none: from is the last point (null when there is none), toward
   * where the pointer is (for a length alone).
   */
  public static double[] parse(String text, double[] from, double[] toward) {
    String t = text.strip();
    boolean relative = t.startsWith("@");
    if (relative) t = t.substring(1).strip();
    if (t.isEmpty()) return null;
    try {
      if (t.contains("<")) {
        String[] p = t.split("<", -1);
        if (p.length != 2 || from == null) return null;
        double l = number(p[0]), a = Math.toRadians(number(p[1]));
        return new double[] {from[0] + l * Math.cos(a), from[1] + l * Math.sin(a)};
      }
      String sep = Tr.lang().decimal == ',' ? "[\\s;]+" : "[\\s;,]+";
      String[] p = t.split(sep);
      if (p.length == 1) {
        if (from == null || toward == null || relative) return null;
        double l = number(p[0]), dx = toward[0] - from[0], dy = toward[1] - from[1], d = Math.hypot(dx, dy);
        if (d < 1e-12) return null;
        return new double[] {from[0] + l * dx / d, from[1] + l * dy / d};
      }
      if (p.length != 2) return null;
      double x = number(p[0]), y = number(p[1]);
      if (!relative) return new double[] {x, y};
      return from == null ? null : new double[] {from[0] + x, from[1] + y};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static double number(String s) {
    double v = Fmt.parse(s);
    if (!Double.isFinite(v)) throw new NumberFormatException(s);
    return v;
  }
}
