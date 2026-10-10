package rdm.model;

/**
 * A support: a liaison between a node and the bâti, in the plane (x, y) with rotations about z.
 *
 * As in schemes' hyperstatism.ts, a liaison is described by what it transmits (its torseur transmissible):
 * the components it blocks. They are written in the node's support frame, t along the support, n across it
 * (n at {@code Node.supportAngle} from x), r the rotation about z. The angle only matters for the supports
 * that block one translation and leave the other free (appui simple, glissière).
 */
public enum Support {
  NONE("Aucun", false, false, false),
  /** Encastrement: X, Y and N (the moment) transmitted. */
  ENCASTREMENT("Encastrement", true, true, true),
  /** Pivot of axis z with the bâti (appui double, articulation): X and Y transmitted. */
  PIVOT("Pivot (appui double)", true, true, false),
  /** Appui simple (ponctuelle / rouleau) of normal n: the force along n only. */
  APPUI_SIMPLE("Appui simple (rouleau)", false, true, false),
  /** Glissière of direction t (encastrement glissant): the force along n and the moment. */
  GLISSIERE("Glissière", false, true, true);

  public final String label;
  /** blocked in the support frame: translation along t, along n, rotation about z */
  public final boolean t, n, r;

  Support(String label, boolean t, boolean n, boolean r) {
    this.label = label;
    this.t = t;
    this.n = n;
    this.r = r;
  }

  /** The support frame turns with the node's angle; the others block both translations and use x, y. */
  public boolean skewed() {
    return t != n;
  }

  @Override
  public String toString() {
    return label;
  }
}
