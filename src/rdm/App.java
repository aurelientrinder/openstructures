package rdm;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import rdm.io.StructureFile;
import rdm.ui.MainFrame;

/**
 * Résistance des matériaux: plane frames and trusses, drawn and solved live.
 *
 * Arguments: a .rdm file to open, or {@code --restore <snapshot> [<file>]} when the application starts itself
 * again (see MainFrame.restart): the drawing in progress, and the file it came from.
 */
public final class App {
  public static void main(String[] args) {
    Settings.applyAtStartup();
    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
        // the default look is fine
      }
      MainFrame frame = new MainFrame();
      frame.setVisible(true);
      if (args.length > 0) open(frame, args);
    });
  }

  private static void open(MainFrame frame, String[] args) {
    try {
      if (args[0].equals("--restore") && args.length > 1) {
        Path snapshot = Path.of(args[1]);
        frame.load(StructureFile.load(snapshot), args.length > 2 ? Path.of(args[2]) : null);
        Files.deleteIfExists(snapshot);
      } else {
        Path p = Path.of(args[0]);
        frame.load(StructureFile.load(p), p);
      }
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(frame, "Impossible d'ouvrir " + args[args.length - 1] + " :\n" + ex.getMessage(), "Ouvrir", JOptionPane.ERROR_MESSAGE);
    }
  }
}
