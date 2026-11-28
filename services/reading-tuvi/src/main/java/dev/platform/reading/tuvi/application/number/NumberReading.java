package dev.platform.reading.tuvi.application.number;

import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Renders the planned number-reading keys into paragraphs from approved knowledge. */
public class NumberReading {

  private static final Logger LOG = LoggerFactory.getLogger(NumberReading.class);
  static final String LANG = "vi";

  private final KnowledgePort knowledge;

  public NumberReading(KnowledgePort knowledge) {
    this.knowledge = knowledge;
  }

  /**
   * One paragraph per key that has approved content. A missing key logs only its prefix (e.g.
   * {@code so:pair}): a key value can carry digits of the user's number.
   */
  public List<String> render(List<String> keys) {
    List<String> out = new ArrayList<>();
    for (String k : keys) {
      Optional<String> text = knowledge.find(new KnowledgeKey(k), LANG);
      if (text.isPresent()) {
        out.add(text.get());
      } else {
        LOG.warn("number reading key {} has no approved knowledge", prefix(k));
      }
    }
    return List.copyOf(out);
  }

  /** {@code so:pair=68} → {@code so:pair}. */
  static String prefix(String key) {
    int eq = key.indexOf('=');
    return eq < 0 ? key : key.substring(0, eq);
  }
}
