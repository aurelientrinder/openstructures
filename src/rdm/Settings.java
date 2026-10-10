package rdm;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * What the application remembers between runs (Java user preferences: the registry on Windows).
 *
 * The interface size is Java's {@code sun.java2d.uiScale}: it scales every widget, font and drawing stroke at
 * once and stays sharp, but it is read when the window system starts, so a change applies at the next start.
 */
public final class Settings {
  private Settings() {}

  private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);
  private static final String UI_SCALE = "uiScale";

  /** The sizes offered, as factors; 0 leaves the size to the system (Windows' display scale). */
  public static final double[] UI_SCALES = {0, 1, 1.25, 1.5, 1.75, 2, 2.5, 3};

  public static double uiScale() {
    return PREFS.getDouble(UI_SCALE, 0);
  }

  public static void setUiScale(double s) {
    if (s > 0) PREFS.putDouble(UI_SCALE, s);
    else PREFS.remove(UI_SCALE);
    try {
      PREFS.flush();
    } catch (BackingStoreException ignored) {
      // kept for this run only
    }
  }

  /** Sets the interface size; must run before any AWT or Swing class starts. A size given on the command line wins. */
  static void applyAtStartup() {
    double s = uiScale();
    if (s > 0 && System.getProperty("sun.java2d.uiScale") == null) System.setProperty("sun.java2d.uiScale", Double.toString(s));
  }
}
