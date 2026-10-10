package rdm.ui;

import java.util.Locale;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Solution;

/** The « Résultats » tab: every number of the solution as a plain-text listing (copied as is into a report). */
final class Report {
  private Report() {}

  static String of(Structure s, Solution sol) {
    StringBuilder b = new StringBuilder();
    b.append(String.format("Structure : %d nœuds, %d éléments%n", s.nodes.size(), s.elements.size()));
    switch (sol.status) {
      case EMPTY -> {
        return b.append("Rien à calculer.\n").toString();
      }
      case ERROR -> {
        return b.append("Erreur : ").append(sol.error).append('\n').toString();
      }
      case MECHANISM -> {
        b.append(String.format("Mécanisme : mobilité m = %d, hyperstatisme h = %d%n", sol.m, sol.h));
        b.append("La structure peut bouger sans se déformer ; libre selon :\n");
        for (String f : sol.free) b.append("  ").append(f).append('\n');
        return b.append("Ajoutez un appui ou une barre, ou enlevez une rotule.\n").toString();
      }
      default -> {}
    }
    b.append(String.format("Degré d'hyperstatisme h = %d, mobilité m = 0%n", sol.h));
    for (String w : sol.warnings) b.append("Attention : ").append(w).append('\n');
    b.append("Hypothèses : élasticité linéaire, petits déplacements, Euler–Bernoulli.\n");
    if (s.selfWeight) b.append("Poids propre inclus (ρ·A·g, g = 9,81 m/s²).\n");

    b.append("\nDéplacements des nœuds (repère global)\n");
    b.append(row("Nœud", "ux (mm)", "uy (mm)", "θz (mrad)"));
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] d = sol.disp[i];
      b.append(row(Integer.toString(i + 1), Fmt.num(d[0] * 1e3), Fmt.num(d[1] * 1e3), Fmt.num(d[2] * 1e3)));
    }

    b.append("\nActions des appuis sur la structure (appui élastique : effort des ressorts, −k·u)\n");
    b.append(row("Nœud", "Rx (kN)", "Ry (kN)", "Mz (kN·m)", "Appui"));
    double sx = 0, sy = 0;
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] r = sol.reaction[i];
      Node n = s.nodes.get(i);
      if (r == null || n.support == Support.NONE) continue;
      sx += r[0];
      sy += r[1];
      b.append(row(Integer.toString(i + 1), Fmt.num(r[0] / 1e3), Fmt.num(r[1] / 1e3), Fmt.num(r[2] / 1e3), n.support.label));
    }
    b.append(row("Σ", Fmt.num(sx / 1e3), Fmt.num(sy / 1e3), "", ""));

    b.append("\nTorseur de cohésion aux extrémités (repère local, N > 0 en traction)\n");
    b.append(row("Élém", "N₀ (kN)", "T₀ (kN)", "Mf₀ (kN·m)", "N₁ (kN)", "T₁ (kN)", "Mf₁ (kN·m)"));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.N(0)), k(r.T(0)), k(r.M(0)), k(r.N(r.L)), k(r.T(r.L)), k(r.M(r.L))));
    }
    b.append("\nMaxima par élément\n");
    b.append(row("Élém", "|N| (kN)", "|T| (kN)", "|Mf| (kN·m)", "σmax (MPa)", "Section"));
    for (ElementResult r : sol.elements) {
      b.append(row(Integer.toString(s.number(r.element)), k(r.maxAbsN()), k(r.maxAbsT()), k(r.maxAbsM()), Fmt.num(r.sigmaMax() / 1e6),
          r.element.section.label()));
    }
    b.append(String.format(Locale.FRANCE, "%nDéplacement maximal : %s%n", Fmt.mm(sol.maxDisplacement)));
    return b.toString();
  }

  private static String k(double v) {
    return Fmt.num(v / 1e3);
  }

  private static String row(String... cells) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < cells.length; i++) b.append(String.format(i == 0 ? "%-5s" : "%12s", cells[i])).append(' ');
    return b.toString().stripTrailing() + "\n";
  }
}
