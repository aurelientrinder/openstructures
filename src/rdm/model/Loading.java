package rdm.model;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What the solver puts on the structure: each load case with a factor (a case on its own has 1, a combination its
 * factors; a missing case counts 0). The structure is linear, so the results are the cases' results, factored and
 * added up.
 */
public record Loading(Map<LoadCase, Double> factors) {
  public Loading {
    factors = new IdentityHashMap<>(factors);
  }

  public double factor(LoadCase c) {
    return factors.getOrDefault(c, 0.0);
  }

  /** One case on its own. */
  public static Loading of(LoadCase c) {
    return new Loading(Map.of(c, 1.0));
  }

  /** Every case of the structure at 1. */
  public static Loading all(Structure s) {
    Map<LoadCase, Double> f = new IdentityHashMap<>();
    for (LoadCase c : s.cases) f.put(c, 1.0);
    return new Loading(f);
  }

  /** The sum of the factors of the cases that take in the self-weight: how many times the weight is applied. */
  public double selfWeight(Structure s) {
    double k = 0;
    for (LoadCase c : s.cases) if (c.selfWeight) k += factor(c);
    return k;
  }
}
