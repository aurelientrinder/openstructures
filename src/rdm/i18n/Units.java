package rdm.i18n;

/**
 * The units lengths and forces are shown and typed in: m, cm or mm, and N, daN or kN; moments (force × length),
 * distributed loads and spring stiffnesses (force / length) follow. The model and the solver stay in SI (m, N):
 * only what is shown and typed is converted. Displacements are always in mm, rotations in mrad, stresses in MPa,
 * section dimensions in mm.
 *
 * A text may write the units as {L}, {F}, {M} (force·length) and {Q} (force/length): Tr.tr puts in the current ones.
 */
public final class Units {
  private Units() {}

  public enum Length {
    M("m", 1), CM("cm", 0.01), MM("mm", 0.001);

    public final String symbol;
    /** metres in one */
    public final double metres;

    Length(String symbol, double metres) {
      this.symbol = symbol;
      this.metres = metres;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  public enum Force {
    N("N", 1), DAN("daN", 10), KN("kN", 1000);

    public final String symbol;
    /** newtons in one */
    public final double newtons;

    Force(String symbol, double newtons) {
      this.symbol = symbol;
      this.newtons = newtons;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  private static Length length = Length.M;
  private static Force force = Force.KN;

  public static Length length() {
    return length;
  }

  public static Force force() {
    return force;
  }

  public static void use(Length l, Force f) {
    length = l;
    force = f;
  }

  // ——— from SI to the units shown, and back ———

  /** A length (m) in the length unit. */
  public static double L(double m) {
    return m / length.metres;
  }

  public static double fromL(double v) {
    return v * length.metres;
  }

  /** A force (N) in the force unit. */
  public static double F(double n) {
    return n / force.newtons;
  }

  public static double fromF(double v) {
    return v * force.newtons;
  }

  /** A moment (N·m) in force × length. */
  public static double M(double nm) {
    return nm / (force.newtons * length.metres);
  }

  public static double fromM(double v) {
    return v * force.newtons * length.metres;
  }

  /** A force per length (N/m: a distributed load, a stiffness) in force / length. */
  public static double Q(double nPerM) {
    return nPerM * length.metres / force.newtons;
  }

  public static double fromQ(double v) {
    return v * force.newtons / length.metres;
  }

  // ——— their symbols ———

  public static String l() {
    return length.symbol;
  }

  public static String f() {
    return force.symbol;
  }

  public static String m() {
    return force.symbol + "·" + length.symbol;
  }

  public static String q() {
    return force.symbol + "/" + length.symbol;
  }

  /** A text with {L}, {F}, {M} and {Q} written in the current units. */
  static String fill(String t) {
    if (t.indexOf('{') < 0) return t;
    return t.replace("{L}", l()).replace("{F}", f()).replace("{M}", m()).replace("{Q}", q());
  }
}
