package rdm.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Locale;
import java.util.Map;

/**
 * A Graphics2D that writes SVG: what the drawing needs (shapes, strokes, text, colours with transparency,
 * translations and rotations) and nothing more; images and clipping are left out. The copies made by create()
 * write to the same document.
 */
final class SvgGraphics extends Graphics2D {
  private final StringBuilder out;
  /** for the font metrics */
  private static final Graphics2D MEASURE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

  private AffineTransform transform = new AffineTransform();
  private Color color = Color.BLACK, background = Color.WHITE;
  private Stroke stroke = new BasicStroke(1f);
  private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
  private float alpha = 1;
  private final RenderingHints hints = new RenderingHints(null);

  private SvgGraphics(StringBuilder out) {
    this.out = out;
  }

  /** The SVG of a drawing of the given size (pixels). */
  static String draw(int width, int height, java.util.function.Consumer<Graphics2D> painter) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>%n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
        + "viewBox=\"0 0 %d %d\">%n", width, height, width, height));
    SvgGraphics g = new SvgGraphics(sb);
    painter.accept(g);
    return sb.append("</svg>\n").toString();
  }

  // ——— what is written ———

  @Override
  public void draw(Shape s) {
    if (!(stroke instanceof BasicStroke b)) {
      fill(stroke.createStrokedShape(s));
      return;
    }
    double k = Math.sqrt(Math.abs(transform.getDeterminant()));
    out.append("<path d=\"").append(path(s)).append("\" fill=\"none\"").append(paint("stroke"));
    out.append(String.format(Locale.ROOT, " stroke-width=\"%.2f\"", b.getLineWidth() * k));
    out.append(b.getEndCap() == BasicStroke.CAP_ROUND ? " stroke-linecap=\"round\"" : b.getEndCap() == BasicStroke.CAP_SQUARE ? " stroke-linecap=\"square\"" : "");
    out.append(b.getLineJoin() == BasicStroke.JOIN_ROUND ? " stroke-linejoin=\"round\"" : b.getLineJoin() == BasicStroke.JOIN_BEVEL ? " stroke-linejoin=\"bevel\"" : "");
    float[] dash = b.getDashArray();
    if (dash != null) {
      out.append(" stroke-dasharray=\"");
      for (int i = 0; i < dash.length; i++) out.append(i == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.2f", dash[i] * k));
      out.append('"');
    }
    out.append("/>\n");
  }

  @Override
  public void fill(Shape s) {
    out.append("<path d=\"").append(path(s)).append('"').append(paint("fill")).append("/>\n");
  }

  @Override
  public void drawString(String text, float x, float y) {
    if (text.isEmpty()) return;
    out.append("<text");
    if (transform.getType() == AffineTransform.TYPE_IDENTITY || transform.getType() == AffineTransform.TYPE_TRANSLATION) {
      out.append(String.format(Locale.ROOT, " x=\"%.2f\" y=\"%.2f\"", x + transform.getTranslateX(), y + transform.getTranslateY()));
    } else {
      double[] m = new double[6];
      transform.getMatrix(m);
      out.append(String.format(Locale.ROOT, " transform=\"matrix(%.5f %.5f %.5f %.5f %.2f %.2f)\" x=\"%.2f\" y=\"%.2f\"", m[0], m[1], m[2], m[3], m[4], m[5], x, y));
    }
    out.append(" font-family=\"").append(family()).append('"');
    out.append(String.format(Locale.ROOT, " font-size=\"%.2f\"", font.getSize2D()));
    if (font.isBold()) out.append(" font-weight=\"bold\"");
    if (font.isItalic()) out.append(" font-style=\"italic\"");
    out.append(paint("fill")).append(" xml:space=\"preserve\">").append(escape(text)).append("</text>\n");
  }

  private String family() {
    String f = font.getFamily();
    return switch (f) {
      case Font.SANS_SERIF, Font.DIALOG, Font.DIALOG_INPUT -> "Segoe UI, Helvetica, Arial, sans-serif";
      case Font.SERIF -> "serif";
      case Font.MONOSPACED -> "monospace";
      default -> escape(f) + ", sans-serif";
    };
  }

  private String paint(String what) {
    StringBuilder b = new StringBuilder(String.format(" %s=\"#%02x%02x%02x\"", what, color.getRed(), color.getGreen(), color.getBlue()));
    float a = alpha * color.getAlpha() / 255f;
    if (a < 1) b.append(String.format(Locale.ROOT, " %s-opacity=\"%.3f\"", what, a));
    return b.toString();
  }

  private String path(Shape s) {
    StringBuilder b = new StringBuilder();
    double[] c = new double[6];
    for (PathIterator it = s.getPathIterator(transform); !it.isDone(); it.next()) {
      switch (it.currentSegment(c)) {
        case PathIterator.SEG_MOVETO -> b.append(String.format(Locale.ROOT, "M%.2f %.2f", c[0], c[1]));
        case PathIterator.SEG_LINETO -> b.append(String.format(Locale.ROOT, "L%.2f %.2f", c[0], c[1]));
        case PathIterator.SEG_QUADTO -> b.append(String.format(Locale.ROOT, "Q%.2f %.2f %.2f %.2f", c[0], c[1], c[2], c[3]));
        case PathIterator.SEG_CUBICTO -> b.append(String.format(Locale.ROOT, "C%.2f %.2f %.2f %.2f %.2f %.2f", c[0], c[1], c[2], c[3], c[4], c[5]));
        case PathIterator.SEG_CLOSE -> b.append('Z');
        default -> {}
      }
    }
    return b.toString();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  // ——— state ———

  @Override
  public Graphics create() {
    SvgGraphics g = new SvgGraphics(out);
    g.transform = new AffineTransform(transform);
    g.color = color;
    g.background = background;
    g.stroke = stroke;
    g.font = font;
    g.alpha = alpha;
    g.hints.putAll(hints);
    return g;
  }

  @Override
  public void dispose() {}

  @Override
  public Color getColor() {
    return color;
  }

  @Override
  public void setColor(Color c) {
    if (c != null) color = c;
  }

  @Override
  public Paint getPaint() {
    return color;
  }

  @Override
  public void setPaint(Paint p) {
    if (p instanceof Color c) color = c;
  }

  @Override
  public Composite getComposite() {
    return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
  }

  @Override
  public void setComposite(Composite c) {
    alpha = c instanceof AlphaComposite a ? a.getAlpha() : 1;
  }

  @Override
  public Stroke getStroke() {
    return stroke;
  }

  @Override
  public void setStroke(Stroke s) {
    stroke = s;
  }

  @Override
  public Font getFont() {
    return font;
  }

  @Override
  public void setFont(Font f) {
    if (f != null) font = f;
  }

  @Override
  public FontMetrics getFontMetrics(Font f) {
    return MEASURE.getFontMetrics(f);
  }

  @Override
  public FontRenderContext getFontRenderContext() {
    return MEASURE.getFontRenderContext();
  }

  @Override
  public void setBackground(Color c) {
    background = c;
  }

  @Override
  public Color getBackground() {
    return background;
  }

  @Override
  public void setRenderingHint(RenderingHints.Key k, Object v) {
    hints.put(k, v);
  }

  @Override
  public Object getRenderingHint(RenderingHints.Key k) {
    return hints.get(k);
  }

  @Override
  public void setRenderingHints(Map<?, ?> h) {
    hints.clear();
    hints.putAll(h);
  }

  @Override
  public void addRenderingHints(Map<?, ?> h) {
    hints.putAll(h);
  }

  @Override
  public RenderingHints getRenderingHints() {
    return (RenderingHints) hints.clone();
  }

  // ——— transforms ———

  @Override
  public void translate(int x, int y) {
    transform.translate(x, y);
  }

  @Override
  public void translate(double x, double y) {
    transform.translate(x, y);
  }

  @Override
  public void rotate(double theta) {
    transform.rotate(theta);
  }

  @Override
  public void rotate(double theta, double x, double y) {
    transform.rotate(theta, x, y);
  }

  @Override
  public void scale(double sx, double sy) {
    transform.scale(sx, sy);
  }

  @Override
  public void shear(double shx, double shy) {
    transform.shear(shx, shy);
  }

  @Override
  public void transform(AffineTransform t) {
    transform.concatenate(t);
  }

  @Override
  public void setTransform(AffineTransform t) {
    transform = new AffineTransform(t);
  }

  @Override
  public AffineTransform getTransform() {
    return new AffineTransform(transform);
  }

  // ——— the rest, through draw and fill ———

  @Override
  public void drawString(String s, int x, int y) {
    drawString(s, (float) x, (float) y);
  }

  @Override
  public void drawString(AttributedCharacterIterator it, int x, int y) {
    drawString(it, (float) x, (float) y);
  }

  @Override
  public void drawString(AttributedCharacterIterator it, float x, float y) {
    StringBuilder b = new StringBuilder();
    for (char c = it.first(); c != AttributedCharacterIterator.DONE; c = it.next()) b.append(c);
    drawString(b.toString(), x, y);
  }

  @Override
  public void drawGlyphVector(GlyphVector g, float x, float y) {
    fill(g.getOutline(x, y));
  }

  @Override
  public void drawLine(int x1, int y1, int x2, int y2) {
    draw(new Line2D.Double(x1, y1, x2, y2));
  }

  @Override
  public void fillRect(int x, int y, int w, int h) {
    fill(new Rectangle(x, y, w, h));
  }

  @Override
  public void clearRect(int x, int y, int w, int h) {
    Color c = color;
    color = background;
    fillRect(x, y, w, h);
    color = c;
  }

  @Override
  public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) {
    draw(new RoundRectangle2D.Double(x, y, w, h, aw, ah));
  }

  @Override
  public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) {
    fill(new RoundRectangle2D.Double(x, y, w, h, aw, ah));
  }

  @Override
  public void drawOval(int x, int y, int w, int h) {
    draw(new Ellipse2D.Double(x, y, w, h));
  }

  @Override
  public void fillOval(int x, int y, int w, int h) {
    fill(new Ellipse2D.Double(x, y, w, h));
  }

  @Override
  public void drawArc(int x, int y, int w, int h, int start, int extent) {
    draw(new Arc2D.Double(x, y, w, h, start, extent, Arc2D.OPEN));
  }

  @Override
  public void fillArc(int x, int y, int w, int h, int start, int extent) {
    fill(new Arc2D.Double(x, y, w, h, start, extent, Arc2D.PIE));
  }

  @Override
  public void drawPolyline(int[] xs, int[] ys, int n) {
    java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
    for (int i = 0; i < n; i++) {
      if (i == 0) p.moveTo(xs[i], ys[i]);
      else p.lineTo(xs[i], ys[i]);
    }
    draw(p);
  }

  @Override
  public void drawPolygon(int[] xs, int[] ys, int n) {
    draw(new java.awt.Polygon(xs, ys, n));
  }

  @Override
  public void fillPolygon(int[] xs, int[] ys, int n) {
    fill(new java.awt.Polygon(xs, ys, n));
  }

  @Override
  public boolean hit(Rectangle r, Shape s, boolean onStroke) {
    return (onStroke ? stroke.createStrokedShape(s) : s).intersects(r);
  }

  @Override
  public GraphicsConfiguration getDeviceConfiguration() {
    return MEASURE.getDeviceConfiguration();
  }

  @Override
  public void setPaintMode() {}

  @Override
  public void setXORMode(Color c) {}

  // no clipping: the whole drawing is written
  @Override
  public Rectangle getClipBounds() {
    return null;
  }

  @Override
  public void clipRect(int x, int y, int w, int h) {}

  @Override
  public void setClip(int x, int y, int w, int h) {}

  @Override
  public Shape getClip() {
    return null;
  }

  @Override
  public void setClip(Shape clip) {}

  @Override
  public void clip(Shape s) {}

  @Override
  public void copyArea(int x, int y, int w, int h, int dx, int dy) {}

  // no images in the drawing
  @Override
  public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
    return true;
  }

  @Override
  public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {}

  @Override
  public void drawRenderedImage(RenderedImage img, AffineTransform xform) {}

  @Override
  public void drawRenderableImage(RenderableImage img, AffineTransform xform) {}

  @Override
  public boolean drawImage(Image img, int x, int y, ImageObserver o) {
    return true;
  }

  @Override
  public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) {
    return true;
  }

  @Override
  public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) {
    return true;
  }

  @Override
  public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) {
    return true;
  }

  @Override
  public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o) {
    return true;
  }

  @Override
  public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o) {
    return true;
  }
}
