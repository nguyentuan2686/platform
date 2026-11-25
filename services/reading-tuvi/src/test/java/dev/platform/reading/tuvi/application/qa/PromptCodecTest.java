package dev.platform.reading.tuvi.application.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.qa.Prompt;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptCodecTest {

  @Test
  void round_trips_multiline_messages() {
    Prompt prompt =
        new Prompt(
            List.of(
                new Prompt.Msg("system", "Quy tắc:\n1. a"),
                new Prompt.Msg("user", "CÂU HỎI:\n<<<\nx\n>>>"),
                new Prompt.Msg("assistant", "")),
            900);

    assertThat(PromptCodec.decode(PromptCodec.encode(prompt))).isEqualTo(prompt);
  }

  @Test
  void separators_inside_content_become_spaces() {
    Prompt prompt = new Prompt(List.of(new Prompt.Msg("user", "a\u001Eb\u001Fc")), 10);

    assertThat(PromptCodec.decode(PromptCodec.encode(prompt)).messages().get(0).content())
        .isEqualTo("a b c");
  }
}
