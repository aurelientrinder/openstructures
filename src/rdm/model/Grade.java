package rdm.model;

/**
 * A structural steel grade of EN 10025-2, with its yield strength f_y as EN 1993-1-1 Table 3.1 gives it: for a
 * nominal thickness up to 40 mm, and above (up to 80 mm).
 */
public enum Grade {
  S235(235, 215), S275(275, 255), S355(355, 335);

  private final double thin, thick;

  Grade(double thin, double thick) {
    this.thin = thin;
    this.thick = thick;
  }

  /** f_y (MPa) of a part t mm thick: the thickest part of a section decides for all of it. */
  public double fy(double t) {
    return t <= 40 ? thin : thick;
  }
}
