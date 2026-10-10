package rdm.model;

import java.util.List;

/**
 * A material: its name, Young's modulus E (MPa) and density ρ (kg/m³, for the self-weight). The list and the
 * densities are schemes' (mass.ts), with E added.
 */
public record Material(String name, double E, double rho) {
  public static final List<Material> PRESETS = List.of(
      new Material("Acier", 210000, 7850),
      new Material("Aluminium", 70000, 2700),
      new Material("Fonte", 100000, 7200),
      new Material("Laiton", 100000, 8500),
      new Material("Titane", 110000, 4500),
      new Material("Bois (chêne)", 12000, 750),
      new Material("Béton", 30000, 2500),
      new Material("PLA", 3500, 1240),
      new Material("ABS", 2300, 1050));

  public static final Material STEEL = PRESETS.get(0);

  /** The preset with these values, or a custom material. */
  public static Material of(double E, double rho) {
    for (Material m : PRESETS) if (m.E == E && m.rho == rho) return m;
    return new Material("Autre", E, rho);
  }

  /** E in Pa. */
  public double pascals() {
    return E * 1e6;
  }

  @Override
  public String toString() {
    return name;
  }
}
