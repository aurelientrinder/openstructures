package rdm.solver;

/**
 * Eigenvalues and eigenvectors of a symmetric matrix: Householder reduction to a tridiagonal matrix, then the QL
 * algorithm with implicit shifts (tred2 and tql2 of EISPACK, as JAMA writes them). Every eigenpair, in O(n³).
 */
final class Eigen {
  /** the eigenvalues, from the largest down */
  final double[] values;
  /** the eigenvectors, as columns, in the same order; orthonormal */
  final double[][] vectors;

  Eigen(double[][] A) {
    int n = A.length;
    double[][] V = new double[n][];
    for (int i = 0; i < n; i++) V[i] = A[i].clone();
    double[] d = new double[n], e = new double[n];
    if (n > 0) {
      tred2(V, d, e);
      tql2(V, d, e);
    }
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    java.util.Arrays.sort(order, (p, q) -> Double.compare(d[q], d[p]));
    values = new double[n];
    vectors = new double[n][n];
    for (int j = 0; j < n; j++) {
      values[j] = d[order[j]];
      for (int i = 0; i < n; i++) vectors[i][j] = V[i][order[j]];
    }
  }

  /** Householder: V becomes the orthogonal transformation, d the diagonal, e the subdiagonal (e[0] = 0). */
  private static void tred2(double[][] V, double[] d, double[] e) {
    int n = d.length;
    for (int j = 0; j < n; j++) d[j] = V[n - 1][j];
    for (int i = n - 1; i > 0; i--) {
      double scale = 0, h = 0;
      for (int k = 0; k < i; k++) scale += Math.abs(d[k]);
      if (scale == 0) {
        e[i] = d[i - 1];
        for (int j = 0; j < i; j++) {
          d[j] = V[i - 1][j];
          V[i][j] = 0;
          V[j][i] = 0;
        }
      } else {
        for (int k = 0; k < i; k++) {
          d[k] /= scale;
          h += d[k] * d[k];
        }
        double f = d[i - 1], g = Math.sqrt(h);
        if (f > 0) g = -g;
        e[i] = scale * g;
        h -= f * g;
        d[i - 1] = f - g;
        for (int j = 0; j < i; j++) e[j] = 0;
        for (int j = 0; j < i; j++) {
          f = d[j];
          V[j][i] = f;
          g = e[j] + V[j][j] * f;
          for (int k = j + 1; k <= i - 1; k++) {
            g += V[k][j] * d[k];
            e[k] += V[k][j] * f;
          }
          e[j] = g;
        }
        f = 0;
        for (int j = 0; j < i; j++) {
          e[j] /= h;
          f += e[j] * d[j];
        }
        double hh = f / (h + h);
        for (int j = 0; j < i; j++) e[j] -= hh * d[j];
        for (int j = 0; j < i; j++) {
          f = d[j];
          g = e[j];
          for (int k = j; k <= i - 1; k++) V[k][j] -= f * e[k] + g * d[k];
          d[j] = V[i - 1][j];
          V[i][j] = 0;
        }
      }
      d[i] = h;
    }
    // accumulate the transformations
    for (int i = 0; i < n - 1; i++) {
      V[n - 1][i] = V[i][i];
      V[i][i] = 1;
      double h = d[i + 1];
      if (h != 0) {
        for (int k = 0; k <= i; k++) d[k] = V[k][i + 1] / h;
        for (int j = 0; j <= i; j++) {
          double g = 0;
          for (int k = 0; k <= i; k++) g += V[k][i + 1] * V[k][j];
          for (int k = 0; k <= i; k++) V[k][j] -= g * d[k];
        }
      }
      for (int k = 0; k <= i; k++) V[k][i + 1] = 0;
    }
    for (int j = 0; j < n; j++) {
      d[j] = V[n - 1][j];
      V[n - 1][j] = 0;
    }
    V[n - 1][n - 1] = 1;
    e[0] = 0;
  }

  /** QL with implicit shifts on the tridiagonal (d, e): d becomes the eigenvalues, V the eigenvectors. */
  private static void tql2(double[][] V, double[] d, double[] e) {
    int n = d.length;
    for (int i = 1; i < n; i++) e[i - 1] = e[i];
    e[n - 1] = 0;
    double f = 0, tst1 = 0, eps = Math.ulp(1.0);
    for (int l = 0; l < n; l++) {
      tst1 = Math.max(tst1, Math.abs(d[l]) + Math.abs(e[l]));
      int m = l;
      while (m < n && Math.abs(e[m]) > eps * tst1) m++;
      if (m > l) {
        do {
          double g = d[l], p = (d[l + 1] - g) / (2 * e[l]), r = Math.hypot(p, 1);
          if (p < 0) r = -r;
          d[l] = e[l] / (p + r);
          d[l + 1] = e[l] * (p + r);
          double dl1 = d[l + 1], h = g - d[l];
          for (int i = l + 2; i < n; i++) d[i] -= h;
          f += h;
          p = d[m];
          double c = 1, c2 = c, c3 = c, el1 = e[l + 1], s = 0, s2 = 0;
          for (int i = m - 1; i >= l; i--) {
            c3 = c2;
            c2 = c;
            s2 = s;
            g = c * e[i];
            h = c * p;
            r = Math.hypot(p, e[i]);
            e[i + 1] = s * r;
            s = e[i] / r;
            c = p / r;
            p = c * d[i] - s * g;
            d[i + 1] = h + s * (c * g + s * d[i]);
            for (int k = 0; k < n; k++) {
              h = V[k][i + 1];
              V[k][i + 1] = s * V[k][i] + c * h;
              V[k][i] = c * V[k][i] - s * h;
            }
          }
          p = -s * s2 * c3 * el1 * e[l] / dl1;
          e[l] = s * p;
          d[l] = c * p;
        } while (Math.abs(e[l]) > eps * tst1);
      }
      d[l] += f;
      e[l] = 0;
    }
  }
}
