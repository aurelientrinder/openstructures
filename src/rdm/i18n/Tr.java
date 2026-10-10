package rdm.i18n;

import java.util.Locale;
import java.util.Map;

/**
 * The interface's language: every text on screen is looked up here by key, in the tables {@link En} and
 * {@link Fr}. A text with arguments is a {@link String#format} pattern ({@code %s}, {@code %d}, {@code %%}).
 *
 * To add a language: copy Fr.java, translate the right-hand column, and add it to {@link Lang}.
 */
public final class Tr {
  private Tr() {}

  public enum Lang {
    EN("English", Locale.ENGLISH, '.', En.TEXT),
    FR("Français", Locale.FRENCH, ',', Fr.TEXT);

    /** the language's name, in itself */
    public final String label;
    public final Locale locale;
    /** the decimal separator of the numbers shown */
    public final char decimal;
    final Map<String, String> text;

    Lang(String label, Locale locale, char decimal, Map<String, String> text) {
      this.label = label;
      this.locale = locale;
      this.decimal = decimal;
      this.text = text;
    }

    @Override
    public String toString() {
      return label;
    }

    /** The table, for the checks. */
    public Map<String, String> table() {
      return text;
    }
  }

  /** chosen at the first use (Settings.applyAtStartup sets it); not at class loading, which Lang may trigger */
  private static Lang lang;

  /** The system's language when there is a table for it, else English. */
  public static Lang system() {
    String code = Locale.getDefault().getLanguage();
    for (Lang l : Lang.values()) if (l.locale.getLanguage().equals(code)) return l;
    return Lang.EN;
  }

  public static Lang lang() {
    if (lang == null) lang = system();
    return lang;
  }

  public static void use(Lang l) {
    lang = l;
  }

  /** The text of a key in the current language (English when it has none, the key itself when neither has). */
  public static String tr(String key, Object... args) {
    String t = lang().text.get(key);
    if (t == null) t = Lang.EN.text.get(key);
    if (t == null) return key;
    return args.length == 0 ? t : String.format(Locale.ROOT, t, args);
  }

  /** A number written with a point, with the language's decimal separator. */
  public static String decimal(String number) {
    return number.replace('.', lang().decimal);
  }
}
