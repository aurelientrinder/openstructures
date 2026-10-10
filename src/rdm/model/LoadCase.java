package rdm.model;

import static rdm.i18n.Tr.tr;

/**
 * A load case (cas de charge): loads that act together, all of one origin, such as the dead load or the wind.
 * Nodal loads and imposed displacements are kept per case on each node (Node.load), element loads carry their
 * case; a case may also take in every element's self-weight. Combinations then add the cases up, factored.
 */
public final class LoadCase {
  /** As in EN 1990: permanent actions G (dead load) and variable ones Q (imposed loads, snow, wind…). */
  public enum Type {
    PERMANENT, VARIABLE;

    @Override
    public String toString() {
      return tr("case.type." + name());
    }
  }

  public String name;
  public Type type;
  /** every element's own weight (ρ·A·g per metre, downwards) belongs to this case */
  public boolean selfWeight;

  public LoadCase(String name, Type type) {
    this.name = name;
    this.type = type;
  }

  @Override
  public String toString() {
    return name;
  }
}
