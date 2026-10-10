package rdm.model;

import static rdm.i18n.Tr.tr;

import java.util.List;
import rdm.i18n.Tr;

/**
 * A constant cross-section, bending about z (the plane of the structure is x, y): a template and its dimensions.
 *
 * Drawn as the section is seen along the element: up is the element's local y, in the plane of the structure;
 * across is z, out of the plane. Dimensions in mm, by kind (see {@link Kind#params}): rectangle b × h, round d,
 * round tube d × t, rectangular hollow h × b × t, I, T and U h × b × tw × tf (U open towards +z), IPE its height
 * (from the table), custom A (cm²), I (cm⁴), v (cm: from the neutral axis to the furthest fibre).
 *
 * Turned: the shape a quarter turn round, so that it bends about its weak axis (rectangle, hollow, I, T, U).
 * The shapes made of rectangles (thin-walled, without root fillets) are computed exactly from them, about their
 * centroid: an asymmetric shape (T, turned U) has its v on the side furthest from the centroid.
 */
public record Section(Kind kind, double p1, double p2, double p3, double p4, boolean turned) {
  public enum Kind {
    RECT("b", "h"), ROUND("d"), TUBE("d", "t"), BOX("h", "b", "t"), I_SECTION("h", "b", "tw", "tf"), T_SECTION("h", "b", "tw", "tf"),
    CHANNEL("h", "b", "tw", "tf"), IPE, CUSTOM("A", "I", "v");

    /** the dimensions p1, p2… by their symbol (texts "dim." + symbol) */
    public final List<String> params;

    Kind(String... params) {
      this.params = List.of(params);
    }

    /** It can be turned a quarter round (the round ones are the same either way; the IPE table has the strong axis only). */
    public boolean turns() {
      return this == RECT || this == BOX || this == I_SECTION || this == T_SECTION || this == CHANNEL;
    }

    @Override
    public String toString() {
      return tr("section." + name());
    }
  }

  public Section(Kind kind, double p1, double p2, double p3) {
    this(kind, p1, p2, p3, 0, false);
  }

  /** IPE sections, strong axis (EN 10365): height h (mm), area A (cm²), inertia I (cm⁴). */
  public static final double[][] IPE_TABLE = {
      {80, 7.64, 80.1}, {100, 10.3, 171}, {120, 13.2, 318}, {140, 16.4, 541}, {160, 20.1, 869}, {180, 23.9, 1317},
      {200, 28.5, 1943}, {220, 33.4, 2772}, {240, 39.1, 3892}, {270, 45.9, 5790}, {300, 53.8, 8356},
      {330, 62.6, 11770}, {360, 72.7, 16270}, {400, 84.5, 23130}, {450, 98.8, 33740}, {500, 116, 48200},
      {550, 134, 67120}, {600, 156, 92080}};

  public static final Section BEAM_DEFAULT = new Section(Kind.IPE, 200, 0, 0);
  public static final Section BAR_DEFAULT = new Section(Kind.TUBE, 60, 4, 0);

  /** A section of this kind with usual dimensions. */
  public static Section of(Kind k) {
    return switch (k) {
      case RECT -> new Section(k, 50, 100, 0);
      case ROUND -> new Section(k, 20, 0, 0);
      case TUBE -> BAR_DEFAULT;
      case BOX -> new Section(k, 200, 100, 8, 0, false);
      case I_SECTION -> new Section(k, 300, 150, 7, 11, false);
      case T_SECTION -> new Section(k, 100, 100, 8, 10, false);
      case CHANNEL -> new Section(k, 200, 75, 8.5, 11.5, false);
      case IPE -> BEAM_DEFAULT;
      case CUSTOM -> new Section(k, 28.5, 1943, 10);
    };
  }

  /** The dimension of rank i (0 for p1). */
  public double param(int i) {
    return switch (i) {
      case 0 -> p1;
      case 1 -> p2;
      case 2 -> p3;
      default -> p4;
    };
  }

  /** The same section with dimension i changed. */
  public Section with(int i, double v) {
    return new Section(kind, i == 0 ? v : p1, i == 1 ? v : p2, i == 2 ? v : p3, i == 3 ? v : p4, turned);
  }

  public Section turned(boolean t) {
    return new Section(kind, p1, p2, p3, p4, t && kind.turns());
  }

  private double[] ipe() {
    for (double[] row : IPE_TABLE) if (row[0] == p1) return row;
    return IPE_TABLE[6];
  }

  /**
   * The shape as rectangles {z0, y0, z1, y1, +1 or −1 for a hole}, mm, as drawn (turned if it is); null for the
   * round ones, the IPE and the custom section, which have no such shape.
   */
  public double[][] rectangles() {
    double[][] r = switch (kind) {
      case RECT -> new double[][] {{0, 0, p1, p2, 1}};
      case BOX -> new double[][] {{0, 0, p2, p1, 1}, {p3, p3, p2 - p3, p1 - p3, -1}};
      case I_SECTION -> new double[][] {{0, 0, p2, p4, 1}, {0, p1 - p4, p2, p1, 1}, {(p2 - p3) / 2, p4, (p2 + p3) / 2, p1 - p4, 1}};
      case T_SECTION -> new double[][] {{0, p1 - p4, p2, p1, 1}, {(p2 - p3) / 2, 0, (p2 + p3) / 2, p1 - p4, 1}};
      case CHANNEL -> new double[][] {{0, 0, p3, p1, 1}, {p3, 0, p2, p4, 1}, {p3, p1 - p4, p2, p1, 1}};
      default -> null;
    };
    if (r != null && turned) {
      // a quarter turn, as z ↔ y: a mirror image of it, with the same properties about the horizontal axis
      for (double[] q : r) {
        double z0 = q[0], z1 = q[2];
        q[0] = q[1];
        q[2] = q[3];
        q[1] = z0;
        q[3] = z1;
      }
    }
    return r;
  }

  /** {A (mm²), centroid height (mm), I about the centroid's horizontal axis (mm⁴), v (mm)} from the rectangles. */
  private double[] fromRectangles() {
    double[][] rs = rectangles();
    double a = 0, ay = 0;
    for (double[] r : rs) {
      double w = r[2] - r[0], h = r[3] - r[1];
      a += r[4] * w * h;
      ay += r[4] * w * h * (r[1] + r[3]) / 2;
    }
    double yc = ay / a, i = 0, v = 0;
    for (double[] r : rs) {
      double w = r[2] - r[0], h = r[3] - r[1], d = (r[1] + r[3]) / 2 - yc;
      i += r[4] * (w * h * h * h / 12 + w * h * d * d);
      if (r[4] > 0) v = Math.max(v, Math.max(r[3] - yc, yc - r[1]));
    }
    return new double[] {a, yc, i, v};
  }

  /** Area, m². */
  public double area() {
    return switch (kind) {
      case ROUND -> Math.PI * p1 * p1 / 4 * 1e-6;
      case TUBE -> Math.PI * (p1 * p1 - Math.pow(p1 - 2 * p2, 2)) / 4 * 1e-6;
      case IPE -> ipe()[1] * 1e-4;
      case CUSTOM -> p1 * 1e-4;
      default -> fromRectangles()[0] * 1e-6;
    };
  }

  /** Second moment of area about z, m⁴. */
  public double inertia() {
    return switch (kind) {
      case ROUND -> Math.PI * Math.pow(p1, 4) / 64 * 1e-12;
      case TUBE -> Math.PI * (Math.pow(p1, 4) - Math.pow(p1 - 2 * p2, 4)) / 64 * 1e-12;
      case IPE -> ipe()[2] * 1e-8;
      case CUSTOM -> p2 * 1e-8;
      default -> fromRectangles()[2] * 1e-12;
    };
  }

  /** Distance from the neutral axis to the furthest fibre, m. */
  public double fibre() {
    return switch (kind) {
      case ROUND, TUBE -> p1 / 2 * 1e-3;
      case IPE -> ipe()[0] / 2 * 1e-3;
      case CUSTOM -> p3 * 1e-2;
      default -> fromRectangles()[3] * 1e-3;
    };
  }

  /** Height of the centroid above the bottom of the drawn shape, mm (the neutral axis, drawn). */
  public double centroid() {
    return switch (kind) {
      case ROUND, TUBE -> p1 / 2;
      case IPE -> ipe()[0] / 2;
      case CUSTOM -> p3 * 10;
      default -> fromRectangles()[1];
    };
  }

  /** Why this section can't be used, or null. */
  public String problem() {
    boolean bad = switch (kind) {
      case RECT -> p1 <= 0 || p2 <= 0;
      case ROUND -> p1 <= 0;
      case TUBE -> p1 <= 0 || p2 <= 0 || 2 * p2 > p1;
      case BOX -> p1 <= 0 || p2 <= 0 || p3 <= 0 || 2 * p3 >= Math.min(p1, p2);
      case I_SECTION, CHANNEL -> p1 <= 0 || p2 <= 0 || p3 <= 0 || p4 <= 0 || p3 >= p2 || 2 * p4 >= p1;
      case T_SECTION -> p1 <= 0 || p2 <= 0 || p3 <= 0 || p4 <= 0 || p3 > p2 || p4 >= p1;
      case IPE -> false;
      case CUSTOM -> p1 <= 0 || p2 <= 0 || p3 <= 0;
    };
    return bad ? tr("section.invalid", label()) : null;
  }

  public String label() {
    String s = switch (kind) {
      case RECT -> tr("section.label.RECT", g(p1), g(p2));
      case ROUND -> tr("section.label.ROUND", g(p1));
      case TUBE -> tr("section.label.TUBE", g(p1), g(p2));
      case BOX -> tr("section.label.BOX", g(p1), g(p2), g(p3));
      case I_SECTION -> tr("section.label.I_SECTION", g(p1), g(p2), g(p3), g(p4));
      case T_SECTION -> tr("section.label.T_SECTION", g(p1), g(p2), g(p3), g(p4));
      case CHANNEL -> tr("section.label.CHANNEL", g(p1), g(p2), g(p3), g(p4));
      case IPE -> "IPE " + g(p1);
      case CUSTOM -> tr("section.label.CUSTOM", g(p1), g(p2));
    };
    return turned ? s + tr("section.turnedSuffix") : s;
  }

  private static String g(double v) {
    return v == Math.rint(v) ? Long.toString((long) v) : Tr.decimal(Double.toString(v));
  }
}
