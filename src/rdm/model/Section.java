package rdm.model;

import java.util.Locale;

/**
 * A constant cross-section, bending about z (the plane of the structure is x, y). Parameters by kind:
 * rectangle b × h (mm, h in the plane), round d (mm), tube d × e (mm), IPE p1 = its height (mm, from the
 * table), custom A (cm²), I (cm⁴), v (cm: the distance from the neutral axis to the furthest fibre).
 */
public record Section(Kind kind, double p1, double p2, double p3) {
  public enum Kind {
    RECT("Rectangle"), ROUND("Rond plein"), TUBE("Tube rond"), IPE("IPE"), CUSTOM("Quelconque");

    public final String label;

    Kind(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** IPE sections, strong axis (EN 10365): height h (mm), area A (cm²), inertia I (cm⁴). */
  public static final double[][] IPE_TABLE = {
      {80, 7.64, 80.1}, {100, 10.3, 171}, {120, 13.2, 318}, {140, 16.4, 541}, {160, 20.1, 869}, {180, 23.9, 1317},
      {200, 28.5, 1943}, {220, 33.4, 2772}, {240, 39.1, 3892}, {270, 45.9, 5790}, {300, 53.8, 8356},
      {330, 62.6, 11770}, {360, 72.7, 16270}, {400, 84.5, 23130}, {450, 98.8, 33740}, {500, 116, 48200},
      {550, 134, 67120}, {600, 156, 92080}};

  public static final Section BEAM_DEFAULT = new Section(Kind.IPE, 200, 0, 0);
  public static final Section BAR_DEFAULT = new Section(Kind.TUBE, 60, 4, 0);

  public static Section of(Kind k) {
    return switch (k) {
      case RECT -> new Section(k, 50, 100, 0);
      case ROUND -> new Section(k, 20, 0, 0);
      case TUBE -> BAR_DEFAULT;
      case IPE -> BEAM_DEFAULT;
      case CUSTOM -> new Section(k, 28.5, 1943, 10);
    };
  }

  private double[] ipe() {
    for (double[] row : IPE_TABLE) if (row[0] == p1) return row;
    return IPE_TABLE[6];
  }

  /** Area, m². */
  public double area() {
    return switch (kind) {
      case RECT -> p1 * p2 * 1e-6;
      case ROUND -> Math.PI * p1 * p1 / 4 * 1e-6;
      case TUBE -> Math.PI * (p1 * p1 - Math.pow(p1 - 2 * p2, 2)) / 4 * 1e-6;
      case IPE -> ipe()[1] * 1e-4;
      case CUSTOM -> p1 * 1e-4;
    };
  }

  /** Second moment of area about z, m⁴. */
  public double inertia() {
    return switch (kind) {
      case RECT -> p1 * Math.pow(p2, 3) / 12 * 1e-12;
      case ROUND -> Math.PI * Math.pow(p1, 4) / 64 * 1e-12;
      case TUBE -> Math.PI * (Math.pow(p1, 4) - Math.pow(p1 - 2 * p2, 4)) / 64 * 1e-12;
      case IPE -> ipe()[2] * 1e-8;
      case CUSTOM -> p2 * 1e-8;
    };
  }

  /** Distance from the neutral axis to the furthest fibre, m. */
  public double fibre() {
    return switch (kind) {
      case RECT -> p2 / 2 * 1e-3;
      case ROUND, TUBE -> p1 / 2 * 1e-3;
      case IPE -> ipe()[0] / 2 * 1e-3;
      case CUSTOM -> p3 * 1e-2;
    };
  }

  /** Why this section can't be used, or null. */
  public String problem() {
    boolean bad = switch (kind) {
      case RECT -> p1 <= 0 || p2 <= 0;
      case ROUND -> p1 <= 0;
      case TUBE -> p1 <= 0 || p2 <= 0 || 2 * p2 > p1;
      case IPE -> false;
      case CUSTOM -> p1 <= 0 || p2 <= 0 || p3 <= 0;
    };
    return bad ? "section invalide (" + label() + ")" : null;
  }

  public String label() {
    return switch (kind) {
      case RECT -> String.format(Locale.FRANCE, "Rect. %s × %s mm", g(p1), g(p2));
      case ROUND -> String.format(Locale.FRANCE, "Rond Ø%s mm", g(p1));
      case TUBE -> String.format(Locale.FRANCE, "Tube Ø%s × %s mm", g(p1), g(p2));
      case IPE -> "IPE " + g(p1);
      case CUSTOM -> String.format(Locale.FRANCE, "A = %s cm², I = %s cm⁴", g(p1), g(p2));
    };
  }

  private static String g(double v) {
    return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v).replace('.', ',');
  }
}
