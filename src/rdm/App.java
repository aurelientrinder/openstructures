package rdm;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import rdm.ui.MainFrame;

/** Résistance des matériaux: plane frames and trusses, drawn and solved live. */
public final class App {
  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
        // the default look is fine
      }
      new MainFrame().setVisible(true);
    });
  }
}
