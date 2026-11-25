package dev.platform.reading.tuvi.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmTier;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeLlmProviderTest {

  static final String SYSTEM = "Ta là Lão Thiên Cơ.";
  static final String USER =
      "CHỦ ĐỀ: tài lộc\nTƯ LIỆU:\n- Mệnh có Tử Vi.\n- Tài Bạch có Vũ Khúc.\n- Thừa dòng.\nCÂU HỎI: sao?";
  static final LlmRequest REQ =
      new LlmRequest(
          List.of(
              new LlmMessage(LlmMessage.Role.SYSTEM, SYSTEM),
              new LlmMessage(LlmMessage.Role.USER, USER)),
          200,
          0.3,
          LlmTier.FREE_ONLY);

  @Test
  void ok_output_is_deterministic_and_grounded() {
    var out = new FakeLlmProvider(FakeMode.OK).complete(REQ);

    assertThat(out.text())
        .isEqualTo(
            "Con hỏi về tài lộc, ta xem lá số của con thì thấy thế này. "
                + "Mệnh có Tử Vi. Tài Bạch có Vũ Khúc."
                + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó."
                + " Điều nào hợp với con thì con giữ lấy, điều nào chưa hợp thì con cứ để đó"
                + " mà ngẫm thêm.");
    assertThat(out.text().length()).isGreaterThanOrEqualTo(150);
    assertThat(out.provider()).isEqualTo("fake");
  }

  @Test
  void identity() {
    var p = new FakeLlmProvider(FakeMode.OK);
    assertThat(p.id()).isEqualTo("fake");
    assertThat(p.paid()).isFalse();
    assertThat(p.dailyRequestLimit()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void fail_throws_unavailable() {
    assertThatThrownBy(() -> new FakeLlmProvider(FakeMode.FAIL).complete(REQ))
        .isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void unsafe_contains_remedy_advice() {
    var out = new FakeLlmProvider(FakeMode.UNSAFE).complete(REQ);

    assertThat(out.text()).contains("giải hạn").endsWith(" Con nên đi giải hạn ngay.");
  }
}
