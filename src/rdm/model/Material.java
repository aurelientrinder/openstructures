package rdm.model;

import java.util.List;

/** A material: its name and Young's modulus E (MPa). The list is schemes' (mass.ts), with E added. */
public record Material(String name, double E) {
  public static final List<Material> PRESETS = List.of(
      new Material("Acier", 210000),
      new Material("Aluminium", 70000),
      new Material("Fonte", 100000),
      new Material("Laiton", 100000),
      new Material("Titane", 110000),
      new Material("Bois (chêne)", 12000),
      new Material("Béton", 30000),
      new Material("PLA", 3500),
      new Material("ABS", 2300));

  public static final Material STEEL = PRESETS.get(0);

  /** The preset with this modulus, or a custom material. */
  public static Material ofE(double E) {
    for (Material m : PRESETS) if (m.E == E) return m;
    return new Material("Autre", E);
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
