package rdm.model;

import static rdm.i18n.Tr.tr;

/**
 * A support: a liaison between a node and the bâti, in the plane (x, y) with rotations about z.
 *
 * As in schemes' hyperstatism.ts, a liaison is described by what it transmits (its torseur transmissible):
 * the components it blocks. They are written in the node's support frame, t along the support, n across it
 * (n at {@code Node.supportAngle} from x), r the rotation about z. The frame turns with the angle for the supports
 * that treat t and n differently (appui simple, glissière, appui élastique); the others use x and y.
 *
 * A blocked component may be given a value (an imposed displacement: a support that settles); an elastic
 * support blocks nothing but holds the node with springs along t, n and about z.
 */
public enum Support {
  NONE(false, false, false, false),
  /** Encastrement: X, Y and N (the moment) transmitted. */
  ENCASTREMENT(true, true, true, false),
  /** Pivot of axis z with the bâti (appui double, articulation): X and Y transmitted. */
  PIVOT(true, true, false, false),
  /** Appui simple (ponctuelle / rouleau) of normal n: the force along n only. */
  APPUI_SIMPLE(false, true, false, true),
  /** Glissière of direction t (encastrement glissant): the force along n and the moment. */
  GLISSIERE(false, true, true, true),
  /** Appui élastique: springs along t, n and about z (Node.springT, springN, springR). */
  ELASTIQUE(false, false, false, true);

  /** blocked in the support frame: translation along t, along n, rotation about z */
  public final boolean t, n, r;
  private final boolean turns;

  Support(boolean t, boolean n, boolean r, boolean turns) {
    this.t = t;
    this.n = n;
    this.r = r;
    this.turns = turns;
  }

  /** The support frame turns with the node's angle; the others use x, y. */
  public boolean skewed() {
    return turns;
  }

  public boolean blocks(int component) {
    return component == 0 ? t : component == 1 ? n : r;
  }

  public String label() {
    return tr("support." + name());
  }

  @Override
  public String toString() {
    return label();
  }
}
