package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JComponent;
import rdm.model.Section;

/**
 * A section drawn as seen along the element: its shape to scale, its dimensions with their symbols, and its
 * neutral axis z (bending axis) through the centroid. Up is the plane of the structure. Also small icons of it.
 */
final class SectionView extends JComponent {
  private static final Color FILL = new Color(0xc9, 0xd3, 0xe0);
  private static final Color EDGE = new Color(0x2a, 0x33, 0x40);
  private static final Color DIM = new Color(0x2b, 0x50, 0xc8);
  private static final Color AXIS = new Color(0xd0, 0x21, 0x6b);

  private Section section;

  SectionView(Section s) {
    section = s;
    setPreferredSize(new Dimension(340, 320));
    setBackground(Color.WHITE);
    setOpaque(true);
  }

  void show(Section s) {
    section = s;
    repaint();
  }

  /** The outline, mm, y up (as drawn: turned if it is). */
  static Shape shape(Section s) {
    if (s.kind().catalogued()) return catalogued(s);
    double[][] rs = s.rectangles();
    if (rs != null) {
      Area a = new Area();
      for (double[] r : rs) if (r[4] > 0) a.add(new Area(new Rectangle2D.Double(r[0], r[1], r[2] - r[0], r[3] - r[1])));
      for (double[] r : rs) if (r[4] < 0) a.subtract(new Area(new Rectangle2D.Double(r[0], r[1], r[2] - r[0], r[3] - r[1])));
      return a;
    }
    return switch (s.kind()) {
      case ROUND -> new Ellipse2D.Double(0, 0, s.p1(), s.p1());
      case TUBE -> {
        Area a = new Area(new Ellipse2D.Double(0, 0, s.p1(), s.p1()));
        double t = Math.min(s.p2(), s.p1() / 2);
        a.subtract(new Area(new Ellipse2D.Double(t, t, s.p1() - 2 * t, s.p1() - 2 * t)));
        yield a;
      }
      // only A, I, v: a stand-in, as high as 2 v
      default -> new RoundRectangle2D.Double(0, 0, s.p3() * 10, s.p3() * 20, s.p3() * 4, s.p3() * 4);
    };
  }

  /** A catalogue section: its I or U with the root fillets, its rectangular hollow with round corners, its tube. */
  private static Shape catalogued(Section s) {
    Section like = s.like().turned(false);
    double h = like.p1(), b = like.p2(), t = like.p3(), r = s.cornerRadius();
    Area a;
    switch (like.kind()) {
      case I_SECTION, CHANNEL -> {
        a = new Area(shape(like));
        double tf = like.p4(), web = like.kind() == Section.Kind.CHANNEL ? 0 : (b - t) / 2;
        for (double y : new double[] {tf, h - tf}) {
          int up = y == tf ? 1 : -1;
          fillet(a, web + t, y, 1, up, r);
          if (web > 0) fillet(a, web, y, -1, up, r);
        }
      }
      case BOX -> {
        a = new Area(new RoundRectangle2D.Double(0, 0, b, h, 2 * r, 2 * r));
        a.subtract(new Area(new RoundRectangle2D.Double(t, t, b - 2 * t, h - 2 * t, 2 * t, 2 * t)));
      }
      default -> {
        return shape(like);
      }
    }
    // the same quarter turn as the rectangles': z ↔ y
    if (s.turned()) a.transform(new AffineTransform(0, 1, 1, 0, 0, 0));
    return a;
  }

  /** Fills the corner at (z, y) between two faces with a fillet of radius r, towards dz, dy (±1). */
  private static void fillet(Area a, double z, double y, int dz, int dy, double r) {
    if (r <= 0) return;
    Area f = new Area(new Rectangle2D.Double(Math.min(z, z + dz * r), Math.min(y, y + dy * r), r, r));
    f.subtract(new Area(new Ellipse2D.Double(z + dz * r - r, y + dy * r - r, 2 * r, 2 * r)));
    a.add(f);
  }

  /** A dimension: its symbol, the two points measured (mm), vertical or not, and where its line stands. */
  private record Dim(String symbol, double z1, double y1, double z2, double y2, boolean vertical, double at) {
    Dim turned() {
      return new Dim(symbol, y1, z1, y2, z2, !vertical, at);
    }
  }

  private static String symbol(String s) {
    return s.equals("t") ? tr("sym.t") : s;
  }

  private static List<Dim> dims(Section s) {
    if (s.kind().catalogued()) return dims(s.like());
    double p1 = s.p1(), p2 = s.p2(), p3 = s.p3(), p4 = s.p4();
    List<Dim> d = new ArrayList<>();
    switch (s.kind()) {
      case RECT -> {
        double off = 0.18 * Math.max(p1, p2);
        d.add(new Dim("b", 0, 0, p1, 0, false, -off));
        d.add(new Dim("h", 0, 0, 0, p2, true, -off));
      }
      case ROUND -> d.add(new Dim("d", 0, p1 / 2, p1, p1 / 2, false, -0.18 * p1));
      case TUBE -> {
        d.add(new Dim("d", 0, p1 / 2, p1, p1 / 2, false, -0.18 * p1));
        d.add(new Dim(symbol("t"), 0, p1 / 2, p2, p1 / 2, false, p1 / 2));
      }
      case BOX -> {
        double off = 0.18 * Math.max(p1, p2);
        d.add(new Dim("h", 0, 0, 0, p1, true, -off));
        d.add(new Dim("b", 0, 0, p2, 0, false, -off));
        d.add(new Dim(symbol("t"), 0, 0.3 * p1, p3, 0.3 * p1, false, 0.3 * p1));
      }
      case I_SECTION, T_SECTION, CHANNEL -> {
        double off = 0.18 * Math.max(p1, p2);
        boolean channel = s.kind() == Section.Kind.CHANNEL, tee = s.kind() == Section.Kind.T_SECTION;
        double web = channel ? 0 : (p2 - p3) / 2;
        d.add(new Dim("h", channel ? 0 : web, 0, 0, p1, true, -off));
        d.add(new Dim("b", 0, p1, p2, p1, false, p1 + off));
        // a little below the middle, off the neutral axis
        d.add(new Dim("tw", web, 0.3 * p1, web + p3, 0.3 * p1, false, 0.3 * p1));
        d.add(new Dim("tf", p2, p1 - p4, p2, p1, true, p2 + off * 0.6));
      }
      case CUSTOM -> d.add(new Dim("v", s.p3() * 10, s.p3() * 10, s.p3() * 10, s.p3() * 20, true, s.p3() * 13));
    }
    if (s.turned()) d.replaceAll(Dim::turned);
    return d;
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = (Graphics2D) g0.create();
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    Section s = section;
    Shape shape;
    try {
      shape = shape(s);
    } catch (RuntimeException ex) {
      g.dispose();
      return;
    }
    Rectangle2D b = shape.getBounds2D();
    if (!(b.getWidth() > 0 && b.getHeight() > 0)) {
      g.dispose();
      return;
    }
    // to scale, with room round it for the dimensions
    double k = Math.min((getWidth() - 140) / b.getWidth(), (getHeight() - 120) / b.getHeight());
    double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
    AffineTransform t = new AffineTransform();
    t.translate(cx - b.getCenterX() * k, cy + b.getCenterY() * k);
    t.scale(k, -k);
    Shape drawn = t.createTransformedShape(shape);
    boolean stand = s.kind() == Section.Kind.CUSTOM;
    g.setColor(FILL);
    if (!stand) g.fill(drawn);
    g.setColor(EDGE);
    g.setStroke(stand ? new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {6, 4}, 0) : new BasicStroke(1.5f));
    g.draw(drawn);
    if (stand) {
      g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
      String txt = "A, I";
      g.drawString(txt, (float) drawn.getBounds2D().getMinX() + 10, (float) drawn.getBounds2D().getMinY() + 22);
    }

    // the neutral axis z (the bending axis) through the centroid G, with the vertical through G
    double[] c = {b.getCenterX(), s.centroid()};
    double[] gp = new double[2];
    t.transform(c, 0, gp, 0, 1);
    Rectangle2D db = drawn.getBounds2D();
    g.setColor(AXIS);
    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {12, 4, 2, 4}, 0));
    g.draw(new Line2D.Double(db.getMinX() - 30, gp[1], db.getMaxX() + 30, gp[1]));
    g.draw(new Line2D.Double(gp[0], db.getMaxY() + 12, gp[0], db.getMinY() - 12));
    g.fill(new Ellipse2D.Double(gp[0] - 3, gp[1] - 3, 6, 6));
    g.setStroke(new BasicStroke(1.2f));
    Symbols.arrow(g, db.getMaxX() + 22, gp[1], db.getMaxX() + 36, gp[1]);
    g.setFont(getFont().deriveFont(Font.BOLD | Font.ITALIC, 13f));
    g.drawString("z", (float) db.getMaxX() + 40, (float) gp[1] + 5);
    g.drawString("G", (float) gp[0] + 6, (float) gp[1] - 6);
    // the axes' key, in a corner: y up the plane of the structure, z out of it
    double kx = 18, ky = getHeight() - 16;
    Symbols.arrow(g, kx, ky, kx, ky - 30);
    Symbols.arrow(g, kx, ky, kx + 30, ky);
    g.drawString("y", (float) kx + 5, (float) ky - 24);
    g.drawString("z", (float) kx + 34, (float) ky + 4);

    g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
    for (Dim d : dims(s)) dim(g, t, d, db);
    g.dispose();
  }

  private void dim(Graphics2D g, AffineTransform t, Dim d, Rectangle2D shape) {
    double[] p = d.vertical ? new double[] {d.z1, d.y1, d.at, d.y1, d.z2, d.y2, d.at, d.y2} : new double[] {d.z1, d.y1, d.z1, d.at, d.z2, d.y2, d.z2, d.at};
    double[] q = new double[8];
    t.transform(p, 0, q, 0, 4);
    g.setColor(DIM);
    // extension lines from the points measured to the dimension line
    g.setStroke(new BasicStroke(0.8f));
    if (Math.hypot(q[2] - q[0], q[3] - q[1]) > 2) g.draw(new Line2D.Double(q[0], q[1], q[2], q[3]));
    if (Math.hypot(q[6] - q[4], q[7] - q[5]) > 2) g.draw(new Line2D.Double(q[4], q[5], q[6], q[7]));
    double ax = q[2], ay = q[3], bx = q[6], by = q[7], len = Math.hypot(bx - ax, by - ay);
    if (len < 1) return;
    double ux = (bx - ax) / len, uy = (by - ay) / len;
    g.setStroke(new BasicStroke(1.1f));
    var fm = g.getFontMetrics();
    int w = fm.stringWidth(d.symbol);
    float lx, ly;
    if (len >= 34) {
      // arrows inside, the symbol beside the middle, away from the shape
      g.draw(new Line2D.Double(ax, ay, bx, by));
      Symbols.head(g, ax, ay, -ux, -uy, 7);
      Symbols.head(g, bx, by, ux, uy, 7);
      double mx = (ax + bx) / 2, my = (ay + by) / 2;
      boolean vertical = Math.abs(uy) > Math.abs(ux);
      if (vertical) {
        boolean left = mx < shape.getCenterX();
        lx = (float) (left ? mx - w - 6 : mx + 6);
        ly = (float) my + fm.getAscent() / 2f - 1;
      } else {
        boolean above = my < shape.getCenterY();
        lx = (float) (mx - w / 2.0);
        ly = (float) (above ? my - 5 : my + fm.getAscent() + 3);
      }
    } else {
      // too short: arrows from outside, pointing in, and the symbol past the far end
      g.draw(new Line2D.Double(ax - ux * 16, ay - uy * 16, bx + ux * 16, by + uy * 16));
      Symbols.head(g, ax, ay, ux, uy, 7);
      Symbols.head(g, bx, by, -ux, -uy, 7);
      lx = (float) (bx + ux * 20 + (Math.abs(ux) > 0.5 ? 0 : 4));
      ly = (float) (by + uy * 20 + (Math.abs(ux) > 0.5 ? fm.getAscent() / 2f - 1 : (uy < 0 ? 0 : fm.getAscent())));
      if (Math.abs(ux) > 0.5 && ux < 0) lx -= w;
    }
    Color c = g.getColor();
    g.setColor(new Color(255, 255, 255, 200));
    g.fillRect((int) lx - 2, (int) (ly - fm.getAscent()), w + 4, fm.getHeight());
    g.setColor(c);
    g.drawString(d.symbol, lx, ly);
  }

  /** A small drawing of the section, its outline only. */
  static Icon icon(Section s, int size) {
    return new Icon() {
      @Override
      public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
          Shape shape = shape(s);
          Rectangle2D b = shape.getBounds2D();
          if (b.getWidth() > 0 && b.getHeight() > 0) {
            double k = (size - 4) / Math.max(b.getWidth(), b.getHeight());
            AffineTransform t = new AffineTransform();
            t.translate(x + size / 2.0 - b.getCenterX() * k, y + size / 2.0 + b.getCenterY() * k);
            t.scale(k, -k);
            Shape drawn = t.createTransformedShape(shape);
            if (s.kind() != Section.Kind.CUSTOM) {
              g.setColor(FILL);
              g.fill(drawn);
            }
            g.setColor(EDGE);
            g.setStroke(s.kind() == Section.Kind.CUSTOM ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {3, 2}, 0)
                : new BasicStroke(1f));
            g.draw(drawn);
          }
        } catch (RuntimeException ignored) {
          // a section being typed may have no shape yet
        }
        g.dispose();
      }

      @Override
      public int getIconWidth() {
        return size;
      }

      @Override
      public int getIconHeight() {
        return size;
      }
    };
  }
}
