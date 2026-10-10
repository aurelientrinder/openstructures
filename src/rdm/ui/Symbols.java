package rdm.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import rdm.model.Support;

/**
 * Support, hinge and load symbols, in screen pixels. The supports follow the ISO 3952 drawings of schemes'
 * symbols.tsx: the bâti is a line with hatches (drawGround), a pivot a small circle.
 */
final class Symbols {
  private Symbols() {}

  static final Color INK = new Color(0x22, 0x25, 0x2b);
  static final Color PAPER = Color.WHITE;

  /**
   * A support at (x, y), its normal at {@code angle} degrees (counter-clockwise from x, world): drawn with the
   * structure on the normal's side and the bâti on the other.
   */
  static void support(Graphics2D g0, Support s, double x, double y, double angle) {
    if (s == Support.NONE) return;
    Graphics2D g = (Graphics2D) g0.create();
    g.translate(x, y);
    // drawn for a normal pointing up the screen (world 90°); world angles turn the other way on screen
    g.rotate(-Math.toRadians(angle - 90));
    g.setColor(INK);
    g.setStroke(new BasicStroke(1.6f));
    switch (s) {
      case ENCASTREMENT -> ground(g, 0, 26, true);
      case PIVOT -> {
        triangle(g, 16);
        ground(g, 16, 22, false);
        pin(g);
      }
      case APPUI_SIMPLE -> {
        triangle(g, 14);
        g.draw(new Ellipse2D.Double(-8, 14, 6, 6));
        g.draw(new Ellipse2D.Double(2, 14, 6, 6));
        ground(g, 20, 22, false);
        pin(g);
      }
      case GLISSIERE -> {
        Rectangle2D box = new Rectangle2D.Double(-11, -7, 22, 14);
        g.setColor(PAPER);
        g.fill(box);
        g.setColor(INK);
        g.draw(box);
        g.draw(new Ellipse2D.Double(-6, 7, 5, 5));
        g.draw(new Ellipse2D.Double(1, 7, 5, 5));
        ground(g, 12, 22, false);
      }
      default -> {}
    }
    g.dispose();
  }

  private static void triangle(Graphics2D g, double h) {
    Path2D p = new Path2D.Double();
    p.moveTo(0, 0);
    p.lineTo(-h * 0.75, h);
    p.lineTo(h * 0.75, h);
    p.closePath();
    g.setColor(PAPER);
    g.fill(p);
    g.setColor(INK);
    g.draw(p);
  }

  private static void pin(Graphics2D g) {
    Ellipse2D c = new Ellipse2D.Double(-4, -4, 8, 8);
    g.setColor(PAPER);
    g.fill(c);
    g.setColor(INK);
    g.draw(c);
  }

  /** The bâti: a line of half-width w at depth y, hatched on the far side (thick for an encastrement). */
  private static void ground(Graphics2D g, double y, double w, boolean thick) {
    g.setStroke(new BasicStroke(thick ? 3f : 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new Line2D.Double(-w, y, w, y));
    g.setStroke(new BasicStroke(1f));
    for (double x = -w + 6; x <= w + 1; x += 6) g.draw(new Line2D.Double(x, y, x - 7, y + 8));
  }

  /** A hinge (rotule) at the end of an element: an open circle. */
  static void hinge(Graphics2D g, double x, double y) {
    Ellipse2D c = new Ellipse2D.Double(x - 4.5, y - 4.5, 9, 9);
    g.setColor(PAPER);
    g.fill(c);
    g.setColor(INK);
    g.setStroke(new BasicStroke(1.6f));
    g.draw(c);
  }

  /** An arrow from (x1, y1) to its tip (x2, y2). */
  static void arrow(Graphics2D g, double x1, double y1, double x2, double y2) {
    g.draw(new Line2D.Double(x1, y1, x2, y2));
    head(g, x2, y2, x2 - x1, y2 - y1, 9);
  }

  /** A filled arrow head at the tip, pointing along (dx, dy). */
  static void head(Graphics2D g, double x, double y, double dx, double dy, double size) {
    double l = Math.hypot(dx, dy);
    if (l == 0) return;
    double ux = dx / l, uy = dy / l;
    Path2D p = new Path2D.Double();
    p.moveTo(x, y);
    p.lineTo(x - size * ux + size * 0.4 * uy, y - size * uy - size * 0.4 * ux);
    p.lineTo(x - size * ux - size * 0.4 * uy, y - size * uy + size * 0.4 * ux);
    p.closePath();
    g.fill(p);
  }

  /** A couple around (x, y): an arc with its head showing the turn, counter-clockwise when ccw. */
  static void moment(Graphics2D g, double x, double y, double r, boolean ccw) {
    double start = -60, extent = 260;
    g.draw(new Arc2D.Double(x - r, y - r, 2 * r, 2 * r, start, extent, Arc2D.OPEN));
    // Arc2D angles go counter-clockwise on screen, as world angles do
    double a = Math.toRadians(ccw ? start + extent : start);
    double px = x + r * Math.cos(a), py = y - r * Math.sin(a);
    double tx = ccw ? -Math.sin(a) : Math.sin(a), ty = ccw ? -Math.cos(a) : Math.cos(a);
    head(g, px, py, tx, ty, 8);
  }
}
