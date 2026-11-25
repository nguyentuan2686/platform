package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.Prompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Text form of a {@link Prompt}, stored encrypted with a pending message so the retry job can call
 * the model again without the birth data: {@code maxTokens}, then {@code role US content} records
 * separated by RS; any RS or US inside a content becomes a space.
 */
final class PromptCodec {

  private static final char RS = '\u001E';
  private static final char US = '\u001F';

  private PromptCodec() {}

  static String encode(Prompt prompt) {
    StringBuilder sb = new StringBuilder().append(prompt.maxTokens());
    for (Prompt.Msg m : prompt.messages()) {
      String content = m.content().replace(RS, ' ').replace(US, ' ');
      sb.append(RS).append(m.role()).append(US).append(content);
    }
    return sb.toString();
  }

  static Prompt decode(String text) {
    String[] records = text.split(String.valueOf(RS), -1);
    List<Prompt.Msg> messages = new ArrayList<>();
    for (int i = 1; i < records.length; i++) {
      int cut = records[i].indexOf(US);
      messages.add(new Prompt.Msg(records[i].substring(0, cut), records[i].substring(cut + 1)));
    }
    return new Prompt(List.copyOf(messages), Integer.parseInt(records[0]));
  }
}
