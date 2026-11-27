package dev.platform.reading.tuvi.adapter.out.llm;

import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic offline provider (default locally and in CI). Never calls the network. It reads the
 * last user message and echoes what is specific to the question: the {@code LÁ SỐ} line of the
 * topic's palace (not Mệnh), a {@code TƯ LIỆU} passage not about Mệnh, and the first {@code THỜI
 * VẬN} line when there is one; it falls back to the first passage.
 */
public class FakeLlmProvider implements LlmProviderPort {

  private static final String TOPIC_PREFIX = "CHỦ ĐỀ:";
  private static final String CHART_HEADER = "LÁ SỐ:";
  private static final String TIMING_HEADER = "THỜI VẬN:";
  private static final String FACTS_HEADER = "TƯ LIỆU:";
  private static final String QUESTION_HEADER = "CÂU HỎI:";
  private static final String MENH = "Mệnh";

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
    Map<String, List<String>> sections =
        Map.of(
            CHART_HEADER, new ArrayList<>(),
            TIMING_HEADER, new ArrayList<>(),
            FACTS_HEADER, new ArrayList<>());
    String section = "";
    String user =
        request.messages().stream()
            .filter(m -> m.role() == LlmMessage.Role.USER)
            .reduce((a, b) -> b)
            .map(LlmMessage::content)
            .orElse("");
    for (String raw : user.split("\\R")) {
      String line = raw.strip();
      if (line.startsWith(TOPIC_PREFIX)) {
        topic = line.substring(TOPIC_PREFIX.length()).strip();
        section = "";
      } else if (sections.containsKey(line)) {
        section = line;
      } else if (line.startsWith(QUESTION_HEADER)) {
        section = "";
      } else if (sections.containsKey(section) && line.startsWith("- ")) {
        sections.get(section).add(line.substring(2).strip());
      }
    }
    List<String> facts = new ArrayList<>();
    notAboutMenh(sections.get(CHART_HEADER)).ifPresent(facts::add);
    notAboutMenh(sections.get(FACTS_HEADER)).ifPresent(facts::add);
    if (facts.isEmpty() && !sections.get(FACTS_HEADER).isEmpty()) {
      facts.add(sections.get(FACTS_HEADER).getFirst());
    }
    sections.get(TIMING_HEADER).stream().findFirst().ifPresent(t -> facts.add("Về thời vận, " + t));
    // At least 150 characters even without facts, so it passes the PAID answer limits.
    List<String> parts = new ArrayList<>();
    parts.add("Con hỏi về " + topic + ", ta xem lá số của con thì thấy thế này.");
    facts.forEach(f -> parts.add(sentence(f)));
    parts.add("Con cứ bình tâm mà làm, việc gì cũng có thời của nó.");
    parts.add(
        "Điều nào hợp với con thì con giữ lấy, điều nào chưa hợp thì con cứ để đó mà ngẫm thêm.");
    return String.join(" ", parts);
  }

  private static Optional<String> notAboutMenh(List<String> lines) {
    return lines.stream().filter(l -> !l.contains(MENH)).findFirst();
  }

  private static String sentence(String text) {
    return text.endsWith(".") ? text : text + ".";
  }

  private static int tokens(String text) {
    return Math.max(1, text.length() / 4);
  }
}
