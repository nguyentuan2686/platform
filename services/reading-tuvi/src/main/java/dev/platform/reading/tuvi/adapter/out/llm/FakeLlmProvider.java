package dev.platform.reading.tuvi.adapter.out.llm;

import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import java.util.ArrayList;
import java.util.List;

/** Deterministic offline provider (default locally and in CI). Never calls the network. */
public class FakeLlmProvider implements LlmProviderPort {

  private static final String TOPIC_PREFIX = "CHỦ ĐỀ:";
  private static final String FACTS_HEADER = "TƯ LIỆU:";

  private final FakeMode mode;

  public FakeLlmProvider(FakeMode mode) {
    this.mode = mode;
  }

  @Override
  public String id() {
    return "fake";
  }

  @Override
  public boolean paid() {
    return false;
  }

  @Override
  public int dailyRequestLimit() {
    return Integer.MAX_VALUE;
  }

  @Override
  public LlmCompletion complete(LlmRequest request) {
    if (mode == FakeMode.FAIL) {
      throw new LlmUnavailableException("fake provider in fail mode");
    }
    String text = answer(request);
    if (mode == FakeMode.UNSAFE) {
      text += " Con nên đi giải hạn ngay.";
    }
    int prompt = request.messages().stream().mapToInt(m -> tokens(m.content())).sum();
    return new LlmCompletion(text, id(), prompt, tokens(text));
  }

  private static String answer(LlmRequest request) {
    String topic = "điều này";
    List<String> facts = new ArrayList<>();
    boolean inFacts = false;
    for (LlmMessage message : request.messages()) {
      for (String raw : message.content().split("\\R")) {
        String line = raw.strip();
        if (line.startsWith(TOPIC_PREFIX)) {
          topic = line.substring(TOPIC_PREFIX.length()).strip();
          inFacts = false;
        } else if (line.startsWith(FACTS_HEADER)) {
          inFacts = true;
        } else if (inFacts && line.startsWith("- ") && facts.size() < 2) {
          facts.add(line.substring(2).strip());
        }
      }
    }
    return "Con hỏi về "
        + topic
        + ", ta xem lá số của con thì thấy thế này. "
        + String.join(" ", facts)
        + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó.";
  }

  private static int tokens(String text) {
    return Math.max(1, text.length() / 4);
  }
}
