package rdm.design;

import java.util.ArrayList;
import java.util.List;
import rdm.model.Grade;
import rdm.model.Section;
import rdm.model.Section.Kind;

/**
 * A section as EN 1993-1-1 sees it, bending in the plane of the structure (about the axis it is drawn bending
 * about, its weak axis when turned): its areas and moduli, the plates its class comes from, its buckling curve.
 *
 * W_pl: a rolled I with its root fillets; a UPN as its parallel-flanged shape (its tapered flanges make up for the
 * fillets: W_pl,y of a UPN 200 229.2 cm³, table 228), but W_el about its weak axis (conservative); a hot-finished hollow section with its rounded corners (outside
 * 1.5 t, inside t, as its A and I); the other templates from their rectangles or exactly (round).
 *
 * Shear area A_v (6.2.6 (3)), η = 1. The class (Table 5.2) is the worst of its compressed plates: a web, internal
 * and symmetric about the axis, with α from the web carrying N (α = (1 + N_c / (c t f_y)) / 2 for class 1 and 2)
 * and ψ from the elastic stresses (class 3); any other plate, conservatively, as if uniformly compressed wherever
 * part of it is. The width c of a plate: a rolled one less its root fillets; a hollow section's side h − 3 t
 * (hot-finished), b − 2 t (welded box).
 */
public final class SteelSection {
  /** A plate of the section: internal (held along both edges) or outstand, c × t (mm), from y0 to y1 about the centroid (mm, up). */
  record Plate(boolean internal, double c, double t, double y0, double y1, int count) {
    boolean web() {
      return internal && y0 != y1;
    }
  }

  public final Section section;
  /** why this section can't be checked (a custom section has no shape), or null */
  public final String unchecked;
  /** m², m⁴, m³, m³, m² */
  public final double A, I, Wel, Wpl, Av;
  /** the thickest plate (mm): f_y comes from it */
  public final double tMax;
  /** the buckling curve: a, b, c or d, and its imperfection factor α */
  public final char curve;
  public final double alpha;
  /** the web's slenderness h_w / t_w, for shear buckling (0 when there is no web carrying the shear) */
  public final double webSlenderness;
  final List<Plate> plates = new ArrayList<>();
  /** a round tube's d / t (0 otherwise) */
  final double tube;
  final boolean solid;

  public SteelSection(Section section) {
    this.section = section;
    Kind k = section.kind();
    boolean turned = section.turned();
    A = section.area();
    I = section.inertia();
    Wel = I / section.fibre();
    Section g = section.like();
    double r = section.cornerRadius();
    double tm = 0, wpl = 0, av = 0, slender = 0, d = 0;
    char cv = 'c';
    String why = null;
    boolean isSolid = false;
    switch (k) {
      case CUSTOM -> why = "custom";
      case RECT -> {
        isSolid = true;
        tm = Math.min(g.p1(), g.p2());
        wpl = fromRectangles(section);
        av = A;
      }
      case ROUND -> {
        isSolid = true;
        tm = g.p1();
        wpl = Math.pow(g.p1(), 3) / 6 * 1e-9;
        av = A;
      }
      case TUBE, CHS -> {
        d = g.p1() / g.p2();
        tm = g.p2();
        wpl = (Math.pow(g.p1(), 3) - Math.pow(g.p1() - 2 * g.p2(), 3)) / 6 * 1e-9;
        av = 2 * A / Math.PI;
        cv = k == Kind.CHS ? 'a' : 'c';
      }
      case BOX, SHS, RHS -> {
        // h the side across the axis of bending, b along it
        double h = turned ? g.p2() : g.p1(), b = turned ? g.p1() : g.p2(), t = g.p3();
        boolean hot = k != Kind.BOX;
        tm = t;
        wpl = hot ? roundedHollow(h, b, t) : fromRectangles(section);
        av = hot ? A * h / (b + h) : 2 * (h - 2 * t) * t * 1e-6;
        double off = hot ? 3 * t : 2 * t;
        plates.add(new Plate(true, b - off, t, h / 2 - t / 2, h / 2 - t / 2, 1));
        plates.add(new Plate(true, b - off, t, -h / 2 + t / 2, -h / 2 + t / 2, 1));
        plates.add(new Plate(true, h - off, t, -(h - off) / 2, (h - off) / 2, 2));
        slender = (h - 2 * t) / t;
        cv = hot ? 'a' : 'b';
      }
      case I_SECTION, IPE, HEA, HEB -> {
        double h = g.p1(), b = g.p2(), tw = g.p3(), tf = g.p4(), hw = h - 2 * tf;
        boolean rolled = k.rolled();
        tm = Math.max(tw, tf);
        double flange = (b - tw - 2 * r) / 2, web = h - 2 * tf - 2 * r;
        if (!turned) {
          wpl = rolled ? (tw * h * h / 4 + (b - tw) * (h - tf) * tf + (4 - Math.PI) * r * r * (h / 2 - tf - FILLET * r)) * 1e-9 : fromRectangles(section);
          av = rolled ? Math.max(A - (2 * b * tf - (tw + 2 * r) * tf) * 1e-6, hw * tw * 1e-6) : hw * tw * 1e-6;
          plates.add(new Plate(false, flange, tf, h / 2 - tf / 2, h / 2 - tf / 2, 1));
          plates.add(new Plate(false, flange, tf, -h / 2 + tf / 2, -h / 2 + tf / 2, 1));
          plates.add(new Plate(true, web, tw, -web / 2, web / 2, 1));
          slender = hw / tw;
        } else {
          wpl = rolled ? (tf * b * b / 2 + hw * tw * tw / 4 + (4 - Math.PI) * r * r * (tw / 2 + FILLET * r)) * 1e-9 : fromRectangles(section);
          av = A - hw * tw * 1e-6;
          plates.add(new Plate(true, web, tw, 0, 0, 1));
          plates.add(new Plate(false, flange, tf, tw / 2 + r, b / 2, 2));
          plates.add(new Plate(false, flange, tf, -b / 2, -tw / 2 - r, 2));
        }
        boolean thick = tf > 40;
        if (rolled) {
          boolean deep = h / b > 1.2;
          cv = deep ? (turned ? (thick ? 'c' : 'b') : (thick ? 'b' : 'a')) : (turned ? 'c' : 'b');
        } else {
          cv = turned ? (thick ? 'd' : 'c') : (thick ? 'c' : 'b');
        }
      }
      case CHANNEL, UPN -> {
        double h = g.p1(), b = g.p2(), tw = g.p3(), tf = g.p4(), hw = h - 2 * tf;
        tm = Math.max(tw, tf);
        // a UPN's weak axis: its tapered flanges would give too much (58 cm³ for a UPN 200, table 51.8), W_el instead
        wpl = k == Kind.UPN && turned ? Wel : fromRectangles(new Section(Kind.CHANNEL, h, b, tw, tf, turned));
        double flange = b - tw - r, web = h - 2 * tf - 2 * r;
        if (!turned) {
          av = k == Kind.UPN ? A - (2 * b * tf - (tw + r) * tf) * 1e-6 : hw * tw * 1e-6;
          plates.add(new Plate(false, flange, tf, h / 2 - tf / 2, h / 2 - tf / 2, 1));
          plates.add(new Plate(false, flange, tf, -h / 2 + tf / 2, -h / 2 + tf / 2, 1));
          plates.add(new Plate(true, web, tw, -web / 2, web / 2, 1));
          slender = hw / tw;
        } else {
          // drawn turned, the web is at the bottom, the flanges stand up from it
          double yc = section.centroid();
          av = A - hw * tw * 1e-6;
          plates.add(new Plate(true, web, tw, tw / 2 - yc, tw / 2 - yc, 1));
          plates.add(new Plate(false, flange, tf, tw + r - yc, b - yc, 2));
        }
      }
      case T_SECTION -> {
        double h = g.p1(), b = g.p2(), tw = g.p3(), tf = g.p4();
        tm = Math.max(tw, tf);
        wpl = fromRectangles(section);
        double flange = (b - tw) / 2, stem = h - tf;
        if (!turned) {
          double yc = section.centroid();
          av = stem * tw * 1e-6;
          plates.add(new Plate(false, flange, tf, h - tf / 2 - yc, h - tf / 2 - yc, 2));
          plates.add(new Plate(false, stem, tw, -yc, stem - yc, 1));
        } else {
          av = A - stem * tw * 1e-6;
          plates.add(new Plate(false, stem, tw, 0, 0, 1));
          plates.add(new Plate(false, flange, tf, tw / 2, b / 2, 1));
          plates.add(new Plate(false, flange, tf, -b / 2, -tw / 2, 1));
        }
      }
    }
    unchecked = why;
    tMax = tm;
    Wpl = wpl;
    Av = av;
    webSlenderness = slender;
    tube = d;
    solid = isSolid;
    curve = cv;
    alpha = switch (cv) {
      case 'a' -> 0.21;
      case 'b' -> 0.34;
      case 'c' -> 0.49;
      default -> 0.76;
    };
  }

  /** a root fillet's centroid from its corner's sides, × r: (10 − 3π) / (3 (4 − π)) */
  private static final double FILLET = (10 - 3 * Math.PI) / (3 * (4 - Math.PI));

  /** f_y of the section in a grade, Pa. */
  public double fy(Grade grade) {
    return grade.fy(tMax) * 1e6;
  }

  /** The class (1 to 4) under N (traction positive, N) and Mf (N·m), for f_y (Pa); 1 when nothing is compressed. */
  public int classify(double N, double M, double fy) {
    if (solid) return 1;
    double eps = Math.sqrt(235e6 / fy);
    double sigmaN = N / A, kM = M / I;
    if (tube > 0) {
      double top = sigmaN - Math.abs(kM) * section.fibre();
      if (top >= 0) return 1;
      double e2 = eps * eps;
      return tube <= 50 * e2 ? 1 : tube <= 70 * e2 ? 2 : tube <= 90 * e2 ? 3 : 4;
    }
    int worst = 1;
    for (Plate p : plates) {
      // stresses at its ends, compression positive (σ = N/A − M y / I, traction positive)
      double s0 = -(sigmaN - kM * p.y0 * 1e-3), s1 = -(sigmaN - kM * p.y1 * 1e-3);
      if (s0 <= 0 && s1 <= 0) continue;
      double ct = p.c / p.t;
      int cls;
      if (!p.web()) {
        double[] lim = p.internal ? new double[] {33, 38, 42} : new double[] {9, 10, 14};
        cls = ct <= lim[0] * eps ? 1 : ct <= lim[1] * eps ? 2 : ct <= lim[2] * eps ? 3 : 4;
      } else {
        // plastic: the web carries the compression N_c, the flanges the moment
        // without a moment the web is uniformly compressed
        boolean axial = Math.abs(kM) * section.fibre() < 1e-6 * Math.abs(sigmaN);
        double a = axial ? 1 : 0.5 * (1 - N / (fy * p.c * p.t * 1e-6 * p.count));
        a = Math.max(1e-6, Math.min(1, a));
        double l1 = a > 0.5 ? 396 * eps / (13 * a - 1) : 36 * eps / a, l2 = a > 0.5 ? 456 * eps / (13 * a - 1) : 41.5 * eps / a;
        double hi = Math.max(s0, s1), psi = Math.min(s0, s1) / hi;
        double l3 = psi > -1 ? 42 * eps / (0.67 + 0.33 * psi) : 62 * eps * (1 - psi) * Math.sqrt(-psi);
        cls = ct <= l1 ? 1 : ct <= l2 ? 2 : ct <= l3 ? 3 : 4;
      }
      worst = Math.max(worst, cls);
    }
    return worst;
  }

  /** W_pl of a shape made of rectangles: about its plastic axis, which halves its area. */
  private static double fromRectangles(Section s) {
    double[][] rs = s.rectangles();
    double area = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
    for (double[] r : rs) {
      area += r[4] * (r[2] - r[0]) * (r[3] - r[1]);
      lo = Math.min(lo, r[1]);
      hi = Math.max(hi, r[3]);
    }
    for (int i = 0; i < 200; i++) {
      double y = (lo + hi) / 2, above = 0;
      for (double[] r : rs) above += r[4] * (r[2] - r[0]) * Math.max(0, r[3] - Math.max(y, r[1]));
      if (above > area / 2) lo = y;
      else hi = y;
    }
    double yp = (lo + hi) / 2, w = 0;
    for (double[] r : rs) w += r[4] * (r[2] - r[0]) * (moment(r[1], r[3], yp));
    return w * 1e-9;
  }

  /** ∫ |y − yp| dy from y0 to y1. */
  private static double moment(double y0, double y1, double yp) {
    if (yp <= y0) return ((y1 - yp) * (y1 - yp) - (y0 - yp) * (y0 - yp)) / 2;
    if (yp >= y1) return ((yp - y0) * (yp - y0) - (yp - y1) * (yp - y1)) / 2;
    return ((yp - y0) * (yp - y0) + (y1 - yp) * (y1 - yp)) / 2;
  }

  /** W_pl (m³) of a hot-finished rectangular hollow section h × b × t (mm), h across the axis: by slices. */
  static double roundedHollow(double h, double b, double t) {
    return 2 * slices(h, b, t, 1) * 1e-9;
  }

  /** ∫ w(y) y^power dy over the upper half of a hollow h × b × t with corners 1.5 t outside, t inside (mm). */
  static double slices(double h, double b, double t, int power) {
    int n = 20000;
    double sum = 0, dy = h / 2 / n;
    for (int i = 0; i < n; i++) {
      double y = (i + 0.5) * dy;
      double w = width(y, h / 2, b / 2, 1.5 * t) - width(y, h / 2 - t, b / 2 - t, t);
      sum += w * Math.pow(y, power) * dy;
    }
    return sum;
  }

  /** The width at y of a rectangle 2a × 2b (a across) with corners of radius r. */
  private static double width(double y, double a, double b, double r) {
    if (y >= a) return 0;
    if (y <= a - r) return 2 * b;
    double dy = y - (a - r);
    return 2 * (b - r + Math.sqrt(r * r - dy * dy));
  }
}
