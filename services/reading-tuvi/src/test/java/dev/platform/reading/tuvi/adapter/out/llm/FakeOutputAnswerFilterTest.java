package dev.platform.reading.tuvi.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmTier;
import dev.platform.reading.tuvi.domain.qa.AnswerFilter;
import dev.platform.reading.tuvi.domain.qa.AnswerLimits;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Pass;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Reject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The real fake provider output against the Task 3 answer filter (brief: fake text passes PAID).
 */
class FakeOutputAnswerFilterTest {

  private static LlmRequest request(String user) {
    return new LlmRequest(
        List.of(
            new LlmMessage(LlmMessage.Role.SYSTEM, "Ta là Lão Thiên Cơ."),
            new LlmMessage(LlmMessage.Role.USER, user)),
        200,
        0.3,
        LlmTier.ANY);
  }

  private static final List<LlmRequest> REQUESTS =
      List.of(
          FakeLlmProviderTest.REQ,
          request("CHỦ ĐỀ: tình duyên\nCÂU HỎI: sao?"),
          request("CÂU HỎI: sao?"),
          request("CHỦ ĐỀ: vận năm\nTƯ LIỆU:\n- Lưu niên ở Mệnh.\nCÂU HỎI: năm nay?"),
          request(FakeLlmProviderTest.TIMING_USER));

  @Test
  void fake_ok_output_passes() {
    for (LlmRequest req : REQUESTS) {
      String text = new FakeLlmProvider(FakeMode.OK).complete(req).text();
      for (AnswerLimits limits : AnswerLimits.values()) {
        assertThat(AnswerFilter.check(text, limits))
            .as(limits + ": " + text)
            .isEqualTo(new Pass(text));
      }
    }
  }

  @Test
  void fake_unsafe_output_rejected() {
    for (LlmRequest req : REQUESTS) {
      String text = new FakeLlmProvider(FakeMode.UNSAFE).complete(req).text();
      assertThat(AnswerFilter.check(text, AnswerLimits.PAID)).isEqualTo(new Reject("banned"));
    }
  }
}
