package rdm.i18n;

import java.util.HashMap;
import java.util.Map;

/** Builds the tables of En and Fr. Apart from Tr so that loading a table never loads Tr (Tr.Lang loads the tables). */
final class Texts {
  private Texts() {}

  /** A table from key, text, key, text… */
  static Map<String, String> table(String... pairs) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      if (m.put(pairs[i], pairs[i + 1]) != null) throw new IllegalStateException("key twice: " + pairs[i]);
    }
    return Map.copyOf(m);
  }
}
