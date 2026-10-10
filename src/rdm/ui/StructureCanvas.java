package rdm.ui;

import static rdm.i18n.Tr.tr;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import rdm.model.Element;
import rdm.model.ElementLoad;
import rdm.model.Node;
import rdm.model.Structure;
import rdm.model.Support;
import rdm.solver.ElementResult;
import rdm.solver.Solution;

/**
 * The drawing: world in metres (x right, y up), screen in pixels (y down). Draws the structure, its supports
 * and loads, and the results (deformed shape, N/T/Mf diagram, reactions); takes the tools' clicks.
 * Right or middle drag pans, the wheel zooms.
 */
final class StructureCanvas extends JPanel {
  private static final Color SELECT = new Color(0x2b, 0x50, 0xc8);
  private static final Color LOAD = new Color(0xc8, 0x3a, 0x1e);
  private static final Color REACTION = new Color(0x1d, 0x8a, 0x3c);
  private static final Color DEFORMED = new Color(0xd0, 0x21, 0x6b);
  private static final Color GRID = new Color(0xd8, 0xdc, 0xe4);
  private static final double PICK = 9;

  private final MainFrame app;
  /** pixels per metre, and the screen position of the world origin */
  private double scale = 80, ox = 100, oy = 500;
  /** the line tools' last point (world), null when no line is being drawn */
  private double[] chain;
  private double mouseX, mouseY;
  private Node dragging;
  private boolean dragged;
  private Point panFrom;

  StructureCanvas(MainFrame app) {
    this.app = app;
    setBackground(Color.WHITE);
    setFocusable(true);
    MouseAdapter m = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (SwingUtilities.isRightMouseButton(e) && chain != null) {
          cancelChain();
          return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
          panFrom = e.getPoint();
          return;
        }
        press(e.getX(), e.getY());
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        if (panFrom != null) {
          ox += e.getX() - panFrom.x;
          oy += e.getY() - panFrom.y;
          panFrom = e.getPoint();
          repaint();
        } else if (dragging != null) {
          double[] w = snap(toWorldX(e.getX()), toWorldY(e.getY()));
          if (w[0] == dragging.x && w[1] == dragging.y) return;
          if (!dragged) app.remember();
          dragged = true;
          dragging.x = w[0];
          dragging.y = w[1];
          app.changed();
        }
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        panFrom = null;
        dragging = null;
        dragged = false;
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        app.hover(readout(e.getX(), e.getY()));
        if (chain != null) repaint();
      }

      @Override
      public void mouseWheelMoved(MouseWheelEvent e) {
        double k = Math.pow(1.15, -e.getPreciseWheelRotation());
        double wx = toWorldX(e.getX()), wy = toWorldY(e.getY());
        scale = Math.max(2, Math.min(5000, scale * k));
        ox = e.getX() - wx * scale;
        oy = e.getY() + wy * scale;
        repaint();
      }
    };
    addMouseListener(m);
    addMouseMotionListener(m);
    addMouseWheelListener(m);
    addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.isControlDown()) return;
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          if (chain != null) cancelChain();
          else app.setTool(Tool.SELECT);
          return;
        }
        if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
          deleteSelection();
          return;
        }
        char c = Character.toUpperCase(e.getKeyChar());
        for (Tool t : Tool.values()) if (t.key == c) app.setTool(t);
      }
    });
  }

  // ——— coordinates ———

  double sx(double x) {
    return ox + x * scale;
  }

  double sy(double y) {
    return oy - y * scale;
  }

  double toWorldX(double px) {
    return (px - ox) / scale;
  }

  double toWorldY(double py) {
    return (oy - py) / scale;
  }

  private double[] snap(double x, double y) {
    double g = app.grid;
    return new double[] {clean(Math.round(x / g) * g), clean(Math.round(y / g) * g)};
  }

  /** Rounds away the float noise of grid multiples (0.30000000000000004). */
  private static double clean(double v) {
    return Math.round(v * 1e9) / 1e9;
  }

  void fit() {
    double[] b = app.structure().bounds();
    int w = Math.max(getWidth(), 600), h = Math.max(getHeight(), 400);
    if (b == null) {
      scale = 80;
      ox = 80;
      oy = h - 80;
    } else {
      double bw = Math.max(b[2] - b[0], 1), bh = Math.max(b[3] - b[1], 1);
      scale = Math.min((w - 200) / bw, (h - 200) / bh);
      ox = w / 2.0 - (b[0] + b[2]) / 2 * scale;
      oy = h / 2.0 + (b[1] + b[3]) / 2 * scale;
    }
    repaint();
  }

  void cancelChain() {
    chain = null;
    repaint();
  }

  // ——— hit testing ———

  private Node nodeAt(double px, double py) {
    Node best = null;
    double bd = PICK;
    for (Node n : app.structure().nodes) {
      double d = Math.hypot(sx(n.x) - px, sy(n.y) - py);
      if (d < bd) {
        bd = d;
        best = n;
      }
    }
    return best;
  }

  /** The element under the point and where along it (0–1), or null. */
  private Object[] elementAt(double px, double py) {
    Object[] best = null;
    double bd = PICK;
    for (Element e : app.structure().elements) {
      double ax = sx(e.a.x), ay = sy(e.a.y), bx = sx(e.b.x), by = sy(e.b.y);
      double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      if (l2 == 0) continue;
      double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
      double d = Math.hypot(ax + t * dx - px, ay + t * dy - py);
      if (d < bd) {
        bd = d;
        best = new Object[] {e, t};
      }
    }
    return best;
  }

  /**
   * The node at a world point for the node and line tools: the node there, or a new one, cutting the element
   * it falls on (at the grid point when that is on the element).
   */
  private Node nodeFor(Structure s, double x, double y) {
    double tol = PICK / scale;
    for (Node n : s.nodes) if (Math.hypot(n.x - x, n.y - y) < tol) return n;
    double[] g = snap(x, y);
    for (Element e : s.elements) {
      double dx = e.b.x - e.a.x, dy = e.b.y - e.a.y, l2 = dx * dx + dy * dy;
      double t = ((x - e.a.x) * dx + (y - e.a.y) * dy) / l2;
      if (t <= 0 || t >= 1 || Math.hypot(e.a.x + t * dx - x, e.a.y + t * dy - y) > tol) continue;
      double tg = ((g[0] - e.a.x) * dx + (g[1] - e.a.y) * dy) / l2;
      boolean onGrid = tg > 0 && tg < 1 && Math.hypot(e.a.x + tg * dx - g[0], e.a.y + tg * dy - g[1]) < 1e-9;
      Node n = s.split(e, onGrid ? tg : t);
      if (onGrid) {
        n.x = g[0];
        n.y = g[1];
      }
      return n;
    }
    for (Node n : s.nodes) if (Math.hypot(n.x - g[0], n.y - g[1]) < 1e-9) return n;
    return s.addNode(g[0], g[1]);
  }

  /** A world point for the line tools: on a node, on an element, or on the grid. */
  private double[] pointAt(double px, double py) {
    Node n = nodeAt(px, py);
    if (n != null) return new double[] {n.x, n.y};
    Object[] hit = elementAt(px, py);
    if (hit != null) return new double[] {toWorldX(px), toWorldY(py)};
    return snap(toWorldX(px), toWorldY(py));
  }

  // ——— tools ———

  private void press(int px, int py) {
    Structure s = app.structure();
    Node node = nodeAt(px, py);
    Object[] hit = node == null ? elementAt(px, py) : null;
    Element el = hit == null ? null : (Element) hit[0];
    Tool tool = app.tool();
    switch (tool) {
      case SELECT -> {
        app.select(node != null ? node : el);
        dragging = node;
      }
      case NODE -> {
        double x = toWorldX(px), y = toWorldY(py);
        if (node == null) app.edit(() -> app.select(nodeFor(s, x, y)));
        else app.select(node);
      }
      case BEAM, BAR -> {
        double[] p = pointAt(px, py);
        if (chain == null) {
          chain = p;
          repaint();
          return;
        }
        double[] from = chain;
        if (Math.hypot(from[0] - p[0], from[1] - p[1]) < 1e-9) return;
        app.edit(() -> {
          Node a = nodeFor(s, from[0], from[1]), b = nodeFor(s, p[0], p[1]);
          if (a != b && s.between(a, b) == null) app.select(s.addElement(a, b, tool == Tool.BAR));
        });
        chain = p;
      }
      case ENCASTREMENT, PIVOT, ROLLER, SLIDER, SPRING -> {
        if (node == null) return;
        app.edit(() -> node.support = node.support == tool.support ? Support.NONE : tool.support);
        app.select(node);
      }
      case FORCE -> {
        if (node == null && el != null) {
          addOnElement(el, LoadDialog.force(this, el, new ElementLoad.Force(along(el, (Double) hit[1]), 0, -10e3, false)));
          return;
        }
        if (node == null) return;
        double[] v = ask(tr("canvas.nodeForce", s.number(node)), new String[] {"Fx (kN)", "Fy (kN)"}, new double[] {node.fx / 1e3, node.fy / 1e3});
        if (v != null) app.edit(() -> {
          node.fx = v[0] * 1e3;
          node.fy = v[1] * 1e3;
        });
        app.select(node);
      }
      case MOMENT -> {
        if (node == null && el != null) {
          addOnElement(el, LoadDialog.couple(this, el, new ElementLoad.Couple(along(el, (Double) hit[1]), 10e3)));
          return;
        }
        if (node == null) return;
        double[] v = ask(tr("canvas.nodeMoment", s.number(node)), new String[] {tr("canvas.mz")}, new double[] {node.mz / 1e3});
        if (v != null) app.edit(() -> node.mz = v[0] * 1e3);
        app.select(node);
      }
      case DISTRIBUTED -> {
        if (el == null) return;
        addOnElement(el, LoadDialog.distributed(this, el, ElementLoad.Distributed.uniform(0, -10e3)));
      }
      case HINGE -> {
        if (el == null) return;
        boolean atA = (Double) hit[1] < 0.5;
        app.edit(() -> {
          if (atA) el.hingeA = !el.hingeA;
          else el.hingeB = !el.hingeB;
        });
        app.select(el);
      }
      case DELETE -> {
        if (node != null) app.edit(() -> s.removeNode(node));
        else if (el != null) app.edit(() -> s.removeElement(el));
      }
    }
  }

  /** Adds a load the dialog returned (null: cancelled) and selects its element, whose panel lists its loads. */
  private void addOnElement(Element el, ElementLoad l) {
    if (l != null) app.edit(() -> el.loads.add(l));
    app.select(el);
  }

  /**
   * Where along an element a click falls, as a fraction: on the grid's step from its origin when that is inside it,
   * else as clicked (to the centimetre).
   */
  private double along(Element el, double t) {
    double L = el.length(), x = Math.round(t * L / app.grid) * app.grid;
    if (x <= 1e-9 || x >= L - 1e-9) x = Math.max(0.01, Math.min(L - 0.01, Math.round(t * L * 100) / 100.0));
    return x / L;
  }

  void deleteSelection() {
    Object sel = app.selection();
    Structure s = app.structure();
    if (sel instanceof Node n) app.edit(() -> s.removeNode(n));
    else if (sel instanceof Element e) app.edit(() -> s.removeElement(e));
  }

  /** Asks for a few numbers, prefilled; null when cancelled. */
  private double[] ask(String title, String[] labels, double[] values) {
    JPanel p = new JPanel(new GridLayout(labels.length, 2, 6, 4));
    JTextField[] fields = new JTextField[labels.length];
    for (int i = 0; i < labels.length; i++) {
      p.add(new JLabel(labels[i]));
      fields[i] = new JTextField(Fmt.num(values[i]).replace('−', '-'), 8);
      p.add(fields[i]);
    }
    while (true) {
      if (JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
      try {
        double[] out = new double[labels.length];
        for (int i = 0; i < labels.length; i++) out[i] = Fmt.parse(fields[i].getText());
        return out;
      } catch (NumberFormatException ex) {
        JOptionPane.showMessageDialog(this, tr("load.number"), title, JOptionPane.WARNING_MESSAGE);
      }
    }
  }

  /** What the status bar says under the pointer: the results at that point. */
  private String readout(int px, int py) {
    Solution sol = app.solution();
    if (!sol.ok()) return null;
    Structure s = app.structure();
    Node n = nodeAt(px, py);
    if (n != null) {
      double[] d = sol.disp[s.nodes.indexOf(n)];
      return tr("canvas.readNode", s.number(n), Fmt.mm(d[0]), Fmt.mm(d[1])) + (Double.isNaN(d[2]) ? "" : tr("canvas.readRotation", Fmt.num(d[2] * 1e3)));
    }
    Object[] hit = elementAt(px, py);
    if (hit == null) return null;
    ElementResult r = sol.of((Element) hit[0]);
    double x = (Double) hit[1] * r.L;
    double[] d = r.displacement(x);
    return tr("canvas.readElement", s.number(r.element), Fmt.num(x), Fmt.kN(r.N(x)), Fmt.kN(r.T(x)), Fmt.kNm(r.M(x)), Fmt.mm(Math.hypot(d[0], d[1])));
  }

  // ——— painting ———

  @Override
  protected void paintComponent(Graphics g0) {
    super.paintComponent(g0);
    Graphics2D g = (Graphics2D) g0.create();
    paintScene(g);
    g.dispose();
  }

  /** The drawing as it is on screen, as an image k times as fine (for a sharp print), without the grid or selection. */
  BufferedImage image(int k) {
    BufferedImage img = new BufferedImage(getWidth() * k, getHeight() * k, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, img.getWidth(), img.getHeight());
    g.scale(k, k);
    exporting(g);
    g.dispose();
    return img;
  }

  /** The drawing as it is on screen, as SVG, without the grid or selection. */
  String svg() {
    return SvgGraphics.draw(getWidth(), getHeight(), g -> {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, getWidth(), getHeight());
      exporting(g);
    });
  }

  private void exporting(Graphics2D g) {
    exporting = true;
    try {
      paintScene(g);
    } finally {
      exporting = false;
    }
  }

  /** while drawing for an export: no grid, no selection, no line being drawn */
  private boolean exporting;

  private boolean selected(Object o) {
    return !exporting && app.selection() == o;
  }

  private void paintScene(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    Structure s = app.structure();
    Solution sol = app.solution();
    if (!exporting) paintGrid(g);
    if (sol.ok() && app.diagram != MainFrame.Diagram.NONE) paintDiagram(g, sol);
    paintElements(g, s);
    if (sol.ok() && app.showDeformed) paintDeformed(g, sol);
    paintSupportsAndHinges(g, s);
    paintLoads(g, s);
    if (sol.ok() && app.showReactions) paintReactions(g, s, sol);
    paintNodes(g, s);
    if (chain != null && !exporting) {
      double[] p = pointAt(mouseX, mouseY);
      g.setColor(SELECT);
      g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {6, 4}, 0));
      g.draw(new Line2D.Double(sx(chain[0]), sy(chain[1]), sx(p[0]), sy(p[1])));
      g.drawString(Fmt.num(Math.hypot(p[0] - chain[0], p[1] - chain[1])) + " m", (float) mouseX + 12, (float) mouseY - 8);
    }
  }

  private void paintGrid(Graphics2D g) {
    double step = app.grid;
    while (step * scale < 12) step *= 2;
    int w = getWidth(), h = getHeight();
    g.setColor(GRID);
    for (double x = Math.floor(toWorldX(0) / step) * step; sx(x) < w; x += step) {
      for (double y = Math.floor(toWorldY(h) / step) * step; sy(y) > 0; y += step) g.fillRect((int) Math.round(sx(x)) - 1, (int) Math.round(sy(y)) - 1, 2, 2);
    }
    g.setStroke(new BasicStroke(1f));
    g.draw(new Line2D.Double(sx(0), 0, sx(0), h));
    g.draw(new Line2D.Double(0, sy(0), w, sy(0)));
  }

  private void paintElements(Graphics2D g, Structure s) {
    for (Element e : s.elements) {
      boolean sel = selected(e);
      g.setColor(sel ? SELECT : Symbols.INK);
      g.setStroke(new BasicStroke(e.isBar() ? 2f : 3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.draw(new Line2D.Double(sx(e.a.x), sy(e.a.y), sx(e.b.x), sy(e.b.y)));
      if (app.showNumbers) {
        g.setColor(Color.GRAY);
        g.drawString("(" + s.number(e) + ")", (float) (sx(e.a.x) + sx(e.b.x)) / 2 + 5, (float) (sy(e.a.y) + sy(e.b.y)) / 2 - 5);
      }
    }
  }

  private void paintNodes(Graphics2D g, Structure s) {
    for (Node n : s.nodes) {
      double x = sx(n.x), y = sy(n.y);
      g.setColor(Symbols.INK);
      g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
      if (selected(n)) {
        g.setColor(SELECT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(x - 8, y - 8, 16, 16));
      }
      if (app.showNumbers) {
        g.setColor(SELECT);
        g.drawString(Integer.toString(s.number(n)), (float) x + 7, (float) y + 15);
      }
    }
  }

  private void paintSupportsAndHinges(Graphics2D g, Structure s) {
    for (Node n : s.nodes) {
      double angle = n.support == Support.ENCASTREMENT ? facing(s, n) : n.supportAngle;
      Symbols.support(g, n.support, sx(n.x), sy(n.y), angle);
    }
    // where every element is hinged (a truss node) one circle on the node says it; elsewhere each hinged end has its own
    java.util.Set<Node> pinned = new java.util.HashSet<>();
    for (Node n : s.nodes) {
      List<Element> at = s.elementsAt(n);
      if (at.size() > 0 && at.stream().allMatch(e -> e.a == n ? e.hingeA : e.hingeB)) pinned.add(n);
    }
    for (Element e : s.elements) {
      double dx = sx(e.b.x) - sx(e.a.x), dy = sy(e.b.y) - sy(e.a.y), l = Math.hypot(dx, dy);
      if (l == 0) continue;
      double off = Math.min(9, l / 3);
      if (e.hingeA && !pinned.contains(e.a)) Symbols.hinge(g, sx(e.a.x) + dx / l * off, sy(e.a.y) + dy / l * off);
      if (e.hingeB && !pinned.contains(e.b)) Symbols.hinge(g, sx(e.b.x) - dx / l * off, sy(e.b.y) - dy / l * off);
    }
    for (Node n : pinned) if (n.support != Support.PIVOT && n.support != Support.APPUI_SIMPLE) Symbols.hinge(g, sx(n.x), sy(n.y));
  }

  /** An encastrement faces its elements, as schemes' orient.ts turns the bâti towards its links. */
  private static double facing(Structure s, Node n) {
    double x = 0, y = 0;
    for (Element e : s.elementsAt(n)) {
      Node o = e.a == n ? e.b : e.a;
      double l = Math.hypot(o.x - n.x, o.y - n.y);
      if (l > 0) {
        x += (o.x - n.x) / l;
        y += (o.y - n.y) / l;
      }
    }
    return x == 0 && y == 0 ? 90 : Math.toDegrees(Math.atan2(y, x));
  }

  private void paintLoads(Graphics2D g, Structure s) {
    g.setColor(LOAD);
    for (Node n : s.nodes) {
      double x = sx(n.x), y = sy(n.y);
      double f = Math.hypot(n.fx, n.fy);
      if (f > 0) {
        double ux = n.fx / f, uy = -n.fy / f;
        g.setStroke(new BasicStroke(2f));
        Symbols.arrow(g, x - ux * 50, y - uy * 50, x - ux * 4, y - uy * 4);
        label(g, Fmt.kN(f), x - ux * 58, y - uy * 58);
      }
      if (n.mz != 0) {
        g.setStroke(new BasicStroke(2f));
        Symbols.moment(g, x, y, 20, n.mz > 0);
        label(g, Fmt.kNm(Math.abs(n.mz)), x + 22, y - 22);
      }
    }
    for (Element e : s.elements) for (ElementLoad l : e.loads) paintLoad(g, e, l);
    if (s.selfWeight && !s.elements.isEmpty()) {
      g.setFont(g.getFont().deriveFont(Font.BOLD));
      g.drawString(tr("canvas.selfWeight"), 12, getHeight() - 30);
      g.setFont(g.getFont().deriveFont(Font.PLAIN));
    }
  }

  private void paintLoad(Graphics2D g, Element e, ElementLoad l) {
    double ax = sx(e.a.x), ay = sy(e.a.y), bx = sx(e.b.x), by = sy(e.b.y);
    switch (l) {
      case ElementLoad.Force f -> {
        double[] v = f.local() ? e.toGlobal(f.fx(), f.fy()) : new double[] {f.fx(), f.fy()};
        double m = Math.hypot(v[0], v[1]);
        if (m == 0) return;
        double x = ax + f.t() * (bx - ax), y = ay + f.t() * (by - ay), ux = v[0] / m, uy = -v[1] / m;
        g.setStroke(new BasicStroke(2f));
        Symbols.arrow(g, x - ux * 50, y - uy * 50, x - ux * 3, y - uy * 3);
        label(g, Fmt.kN(m), x - ux * 58, y - uy * 58);
      }
      case ElementLoad.Couple c -> {
        double x = ax + c.t() * (bx - ax), y = ay + c.t() * (by - ay);
        g.setStroke(new BasicStroke(2f));
        Symbols.moment(g, x, y, 16, c.c() > 0);
        label(g, Fmt.kNm(Math.abs(c.c())), x + 18, y - 22);
      }
      case ElementLoad.Distributed d -> {
        // arrows as long as the load where they stand (the larger end at 32 px), tips on the element
        double[] q1 = d.local() ? e.toGlobal(d.qx1(), d.qy1()) : new double[] {d.qx1(), d.qy1()};
        double[] q2 = d.local() ? e.toGlobal(d.qx2(), d.qy2()) : new double[] {d.qx2(), d.qy2()};
        double top = Math.max(Math.hypot(q1[0], q1[1]), Math.hypot(q2[0], q2[1]));
        if (top == 0) return;
        double px = 32 / top;
        double x1 = ax + d.t1() * (bx - ax), y1 = ay + d.t1() * (by - ay), x2 = ax + d.t2() * (bx - ax), y2 = ay + d.t2() * (by - ay);
        int count = Math.max(2, (int) (Math.hypot(x2 - x1, y2 - y1) / 22) + 1);
        g.setStroke(new BasicStroke(1.2f));
        Path2D tails = new Path2D.Double();
        for (int i = 0; i < count; i++) {
          double k = (double) i / (count - 1);
          double qx = q1[0] + k * (q2[0] - q1[0]), qy = q1[1] + k * (q2[1] - q1[1]);
          double x = x1 + k * (x2 - x1), y = y1 + k * (y2 - y1), tx = x - qx * px, ty = y + qy * px;
          if (i == 0) tails.moveTo(tx, ty);
          else tails.lineTo(tx, ty);
          double m = Math.hypot(qx, qy);
          if (m * px < 4) continue;
          g.draw(new Line2D.Double(tx, ty, x - qx / m * 3, y + qy / m * 3));
          Symbols.head(g, x - qx / m * 3, y + qy / m * 3, qx, -qy, 6);
        }
        g.draw(tails);
        g.draw(new Line2D.Double(x1, y1, x1 - q1[0] * px, y1 + q1[1] * px));
        g.draw(new Line2D.Double(x2, y2, x2 - q2[0] * px, y2 + q2[1] * px));
        boolean uniform = q1[0] == q2[0] && q1[1] == q2[1];
        String text = uniform ? Fmt.num(Math.hypot(q1[0], q1[1]) / 1e3) + " kN/m"
            : Fmt.num(Math.hypot(q1[0], q1[1]) / 1e3) + " → " + Fmt.num(Math.hypot(q2[0], q2[1]) / 1e3) + " kN/m";
        double mx = (x1 + x2) / 2, my = (y1 + y2) / 2, qm = Math.hypot(q1[0] + q2[0], q1[1] + q2[1]);
        double ox = qm == 0 ? 0 : (q1[0] + q2[0]) / qm, oy = qm == 0 ? 1 : -(q1[1] + q2[1]) / qm;
        label(g, text, mx - ox * 44, my - oy * 44);
      }
    }
  }

  private void paintReactions(Graphics2D g, Structure s, Solution sol) {
    g.setColor(REACTION);
    g.setStroke(new BasicStroke(2f));
    double tiny = 1e-9 * scaleOfLoads(s, sol);
    for (int i = 0; i < s.nodes.size(); i++) {
      double[] r = sol.reaction[i];
      if (r == null) continue;
      Node n = s.nodes.get(i);
      double x = sx(n.x), y = sy(n.y);
      // drawn on the far side of the support, pointing at the node
      if (Math.abs(r[0]) > tiny) {
        double ux = Math.signum(r[0]);
        Symbols.arrow(g, x - ux * 55, y + 34, x - ux * 12, y + 34);
        label(g, "Rx = " + Fmt.kN(r[0]), x - ux * 60, y + 50);
      }
      if (Math.abs(r[1]) > tiny) {
        double uy = -Math.signum(r[1]);
        Symbols.arrow(g, x + 30, y - uy * 55, x + 30, y - uy * 12);
        label(g, "Ry = " + Fmt.kN(r[1]), x + 34, y - uy * 62);
      }
      if (Math.abs(r[2]) > tiny) {
        Symbols.moment(g, x, y, 30, r[2] > 0);
        label(g, "Mz = " + Fmt.kNm(r[2]), x - 34, y - 38);
      }
    }
  }

  private static double scaleOfLoads(Structure s, Solution sol) {
    double m = 1;
    for (double[] r : sol.reaction) if (r != null) for (double v : r) m = Math.max(m, Math.abs(v));
    return m;
  }

  private void paintDeformed(Graphics2D g, Solution sol) {
    if (sol.maxDisplacement == 0) return;
    double[] b = app.structure().bounds();
    double size = Math.max(Math.hypot(b[2] - b[0], b[3] - b[1]), 1);
    double k = nice(0.08 * size / sol.maxDisplacement) * app.amplify;
    g.setColor(DEFORMED);
    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {7, 4}, 0));
    for (ElementResult r : sol.elements) {
      Path2D p = new Path2D.Double();
      boolean first = true;
      for (double x : r.samples(30)) {
        double[] d = r.displacement(x);
        double wx = r.element.a.x + r.c * x + k * d[0], wy = r.element.a.y + r.s * x + k * d[1];
        if (first) p.moveTo(sx(wx), sy(wy));
        else p.lineTo(sx(wx), sy(wy));
        first = false;
      }
      g.draw(p);
    }
    g.setFont(g.getFont().deriveFont(Font.BOLD));
    g.drawString(tr("canvas.deformed", Fmt.num(k)), 12, getHeight() - 12);
    g.setFont(g.getFont().deriveFont(Font.PLAIN));
  }

  /** 1, 2 or 5 times a power of ten, at most v. */
  private static double nice(double v) {
    double p = Math.pow(10, Math.floor(Math.log10(v)));
    double m = v / p;
    return (m >= 5 ? 5 : m >= 2 ? 2 : 1) * p;
  }

  private interface Quantity {
    double at(ElementResult r, double x);
  }

  private void paintDiagram(Graphics2D g, Solution sol) {
    MainFrame.Diagram d = app.diagram;
    Quantity q = switch (d) {
      case N -> ElementResult::N;
      case T -> ElementResult::T;
      default -> ElementResult::M;
    };
    String unit = d == MainFrame.Diagram.M ? " kN·m" : " kN";
    Color color = switch (d) {
      case N -> new Color(0x1f, 0x6f, 0xd1);
      case T -> new Color(0x1d, 0x8a, 0x3c);
      default -> new Color(0x7b, 0x3f, 0xc4);
    };
    double max = 0;
    for (ElementResult r : sol.elements) {
      max = Math.max(max, d == MainFrame.Diagram.N ? r.maxAbsN() : d == MainFrame.Diagram.T ? r.maxAbsT() : r.maxAbsM());
    }
    if (max < 1e-9) return;
    double px = 55 / max;
    placed.clear();
    List<ElementResult> rs = sol.elements;
    for (ElementResult r : rs) {
      // drawn on the local y side for positive values (screen: y down)
      double nx = -r.s, ny = -r.c;
      Path2D p = new Path2D.Double();
      p.moveTo(sx(r.element.a.x), sy(r.element.a.y));
      for (double x : r.samples(40)) {
        double v = q.at(r, x) * px;
        p.lineTo(sx(r.element.a.x + r.c * x) + nx * v, sy(r.element.a.y + r.s * x) + ny * v);
      }
      p.lineTo(sx(r.element.b.x), sy(r.element.b.y));
      p.closePath();
      Graphics2D f = (Graphics2D) g.create();
      f.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
      f.setColor(color);
      f.fill(p);
      f.dispose();
      g.setColor(color);
      g.setStroke(new BasicStroke(1.2f));
      g.draw(p);

      double v0 = q.at(r, 0), v1 = q.at(r, r.L);
      if (Math.abs(v0 - v1) < 1e-9 * max) {
        tag(g, r, r.L / 2, v0, px, unit, max);
      } else {
        tag(g, r, 0, v0, px, unit, max);
        tag(g, r, r.L, v1, px, unit, max);
      }
      double peak = d == MainFrame.Diagram.M ? r.maxMAt() : -1;
      if (peak > 0 && peak < r.L && Math.abs(q.at(r, peak)) > 1.01 * Math.max(Math.abs(v0), Math.abs(v1))) tag(g, r, peak, q.at(r, peak), px, unit, max);
    }
    g.setColor(color);
    g.setFont(g.getFont().deriveFont(Font.BOLD));
    g.drawString(d + " (max " + Fmt.num(max / 1e3) + unit + ")", 12, 20);
    g.setFont(g.getFont().deriveFont(Font.PLAIN));
  }

  private void tag(Graphics2D g, ElementResult r, double x, double v, double px, String unit, double max) {
    if (Math.abs(v) < 1e-6 * max) return;
    double off = v * px + Math.signum(v) * 10;
    // labels at the ends a little inside the element, so that neighbours don't write over each other
    double xi = Math.max(r.L * 0.12, Math.min(r.L * 0.88, x));
    double X = sx(r.element.a.x + r.c * xi) - r.s * off, Y = sy(r.element.a.y + r.s * xi) - r.c * off;
    String text = Fmt.num(v / 1e3) + unit;
    // the same value where elements meet is written once
    for (double[] p : placed) if (p[2] == Math.rint(v) && Math.hypot(p[0] - X, p[1] - Y) < 60) return;
    placed.add(new double[] {X, Y, Math.rint(v)});
    label(g, text, X, Y);
  }

  private final List<double[]> placed = new java.util.ArrayList<>();

  /** Text centred on a point, on a white halo. */
  private static void label(Graphics2D g, String text, double x, double y) {
    var fm = g.getFontMetrics();
    int w = fm.stringWidth(text);
    float tx = (float) (x - w / 2.0), ty = (float) (y + fm.getAscent() / 2.0 - 1);
    Color c = g.getColor();
    g.setColor(new Color(255, 255, 255, 210));
    g.fillRect((int) tx - 2, (int) (ty - fm.getAscent()), w + 4, fm.getHeight());
    g.setColor(c);
    g.drawString(text, tx, ty);
  }
}
