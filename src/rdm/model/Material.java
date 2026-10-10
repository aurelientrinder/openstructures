package rdm.model;

import static rdm.i18n.Tr.tr;

import java.util.List;

/**
 * A material: its name (a key of the texts: steel…), Young's modulus E (MPa) and density ρ (kg/m³, for the
 * self-weight). The list and the densities are schemes' (mass.ts), with E added.
 */
public record Material(String name, double E, double rho) {
  public static final List<Material> PRESETS = List.of(
      new Material("steel", 210000, 7850),
      new Material("aluminium", 70000, 2700),
      new Material("castIron", 100000, 7200),
      new Material("brass", 100000, 8500),
      new Material("titanium", 110000, 4500),
      new Material("oak", 12000, 750),
      new Material("concrete", 30000, 2500),
      new Material("pla", 3500, 1240),
      new Material("abs", 2300, 1050));

  public static final Material STEEL = PRESETS.get(0);

  /** The preset with these values, or a custom material. */
  public static Material of(double E, double rho) {
    for (Material m : PRESETS) if (m.E == E && m.rho == rho) return m;
    return new Material("other", E, rho);
  }

  /** E in Pa. */
  public double pascals() {
    return E * 1e6;
  }

  @Override
  public String toString() {
    return tr("material." + name);
  }
}
