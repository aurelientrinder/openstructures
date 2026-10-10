package rdm.solver;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import rdm.model.Element;
import rdm.model.Loading;
import rdm.model.Structure;

/** What the solver found for a structure. Lengths in m, forces in N, moments in N·m, rotations in rad. */
public final class Solution {
  public enum Status {
    /** nothing to compute */
    EMPTY,
    OK,
    /** the structure moves (hypostatique, m > 0): no equilibrium */
    MECHANISM,
    /** the data can't be computed (zero-length element, bad section…) */
    ERROR
  }

  public final Structure structure;
  /** the factor of each load case it was solved for */
  public final Loading loading;
  public Status status = Status.EMPTY;
  public String error;
  /** degree of hyperstatism (static indeterminacy) and of mobility */
  public int h, m;
  /** the unknowns the structure is free along, when it is a mechanism: "node 3: ux" */
  public final List<String> free = new ArrayList<>();
  public final List<String> warnings = new ArrayList<>();
  /** per node: ux, uy, θz on the global axes (θz NaN where every element is hinged: no rotation of its own) */
  public double[][] disp;
  /** per node: Rx, Ry, Mz the support applies on the structure, or null without a support */
  public double[][] reaction;
  public final List<ElementResult> elements = new ArrayList<>();
  private final Map<Element, ElementResult> byElement = new IdentityHashMap<>();
  /** the largest displacement of any point of the structure, m */
  public double maxDisplacement;

  Solution(Structure s, Loading loading) {
    structure = s;
    this.loading = loading;
  }

  void add(ElementResult r) {
    elements.add(r);
    byElement.put(r.element, r);
  }

  public ElementResult of(Element e) {
    return byElement.get(e);
  }

  public boolean ok() {
    return status == Status.OK;
  }
}
