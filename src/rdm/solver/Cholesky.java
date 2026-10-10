package rdm.solver;

/**
 * Cholesky factorization of a symmetric positive semi-definite matrix that reports its null directions
 * instead of failing, like schemes' linalg.ts Echelon: a pivot that vanishes marks an unknown the
 * equations can't fix (a mechanism of the structure).
 *
 * The matrix is first scaled to a unit diagonal (Jacobi), so that one tolerance fits every unknown: a
 * translation stiffness EA/L and a rotation stiffness EI/L differ by orders of magnitude and by units.
 */
final class Cholesky {
  /** a scaled pivot below this is zero: the unknown depends on the previous ones */
  static final double TOL = 1e-10;

  private final int n;
  private final double[][] L;
  private final double[] d;
  final boolean[] nullPivot;
  int nullity;

  Cholesky(double[][] A) {
    n = A.length;
    L = new double[n][n];
    d = new double[n];
    nullPivot = new boolean[n];
    for (int i = 0; i < n; i++) d[i] = A[i][i] > 0 ? 1 / Math.sqrt(A[i][i]) : 0;
    for (int j = 0; j < n; j++) {
      double p = d[j] * d[j] * A[j][j];
      for (int k = 0; k < j; k++) p -= L[j][k] * L[j][k];
      if (d[j] == 0 || p <= TOL) {
        nullPivot[j] = true;
        nullity++;
        continue;
      }
      double ljj = Math.sqrt(p);
      L[j][j] = ljj;
      for (int i = j + 1; i < n; i++) {
        double s = d[i] * A[i][j] * d[j];
        for (int k = 0; k < j; k++) s -= L[i][k] * L[j][k];
        L[i][j] = s / ljj;
      }
    }
  }

  /** The solution of A x = b; an unknown whose pivot vanished is left at 0. */
  double[] solve(double[] b) {
    double[] z = new double[n];
    for (int i = 0; i < n; i++) {
      if (nullPivot[i]) continue;
      double s = d[i] * b[i];
      for (int k = 0; k < i; k++) s -= L[i][k] * z[k];
      z[i] = s / L[i][i];
    }
    double[] y = new double[n];
    for (int i = n - 1; i >= 0; i--) {
      if (nullPivot[i]) continue;
      double s = z[i];
      for (int k = i + 1; k < n; k++) s -= L[k][i] * y[k];
      y[i] = s / L[i][i];
    }
    for (int i = 0; i < n; i++) y[i] *= d[i];
    return y;
  }
}
