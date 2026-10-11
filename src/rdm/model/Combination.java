package rdm.model;

import static rdm.i18n.Tr.tr;

import java.util.IdentityHashMap;
import java.util.Map;
import rdm.i18n.Tr;

/**
 * A combination of load cases (combinaison de charges): a factor per case, 1.35 G + 1.5 Q for instance, for a
 * limit state: the strength checks take the ultimate ones, the deflection checks the serviceability ones.
 */
public final class Combination {
  /** ELU, ultimate limit state (strength); ELS, serviceability limit state (deflections) */
  public enum Limit {
    ULS, SLS;

    @Override
    public String toString() {
      return tr("limit." + name());
    }
  }

  public String name;
  public Limit limit = Limit.ULS;
  private final Map<LoadCase, Double> factors = new IdentityHashMap<>();

  public Combination(String name) {
    this.name = name;
  }

  /** The factor of a case, 0 when it is not in the combination. */
  public double factor(LoadCase c) {
    return factors.getOrDefault(c, 0.0);
  }

  public void setFactor(LoadCase c, double k) {
    if (k == 0) factors.remove(c);
    else factors.put(c, k);
  }

  void forget(LoadCase c) {
    factors.remove(c);
  }

  public Loading loading() {
    return new Loading(factors);
  }

  /** The same limit state and factors for every case of the structure. */
  boolean sameFactors(Combination o, Structure s) {
    if (limit != o.limit) return false;
    for (LoadCase c : s.cases) if (factor(c) != o.factor(c)) return false;
    return true;
  }

  /** "1.35 G + 1.5 Q", from the factors, in the structure's order of cases and the language's decimals. */
  public String formula(Structure s) {
    StringBuilder b = new StringBuilder();
    for (LoadCase c : s.cases) {
      double k = factor(c);
      if (k == 0) continue;
      if (b.length() > 0) b.append(k < 0 ? " − " : " + ");
      else if (k < 0) b.append('−');
      double a = Math.abs(k);
      if (a != 1) b.append(Tr.decimal(new java.math.BigDecimal(Double.toString(a)).stripTrailingZeros().toPlainString())).append(' ');
      b.append(c.name);
    }
    return b.length() == 0 ? "0" : b.toString();
  }

  @Override
  public String toString() {
    return name;
  }
}
