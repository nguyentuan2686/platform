package dev.platform.reading.tuvi.application.number;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NumberReadingTest {

  /** Knowledge with a fixed map of approved texts. */
  static KnowledgePort knowledge(Map<String, String> texts) {
    return new KnowledgePort() {
      @Override
      public Optional<String> find(KnowledgeKey key, String lang) {
        return "vi".equals(lang) ? Optional.ofNullable(texts.get(key.value())) : Optional.empty();
      }

      @Override
      public long version() {
        return 1;
      }
    };
  }

  @Test
  void renders_paragraphs_in_plan_order_and_skips_missing_keys() {
    NumberReading reading =
        new NumberReading(
            knowledge(Map.of("so:intro=sim", "A", "so:closing", "Z", "so:que=4", "Q")));
    assertThat(reading.render(List.of("so:intro=sim", "so:que=4", "so:menh=kim", "so:closing")))
        .containsExactly("A", "Q", "Z");
  }
}
