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
                + "Tài Bạch có Vũ Khúc."
                + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó."
                + " Điều nào hợp với con thì con giữ lấy, điều nào chưa hợp thì con cứ để đó"
                + " mà ngẫm thêm.");
    assertThat(out.text().length()).isGreaterThanOrEqualTo(150);
    assertThat(out.provider()).isEqualTo("fake");
  }

  static final String TIMING_USER =
      "CHỦ ĐỀ: tình duyên\nNGÔN NGỮ: vi\nLÁ SỐ:\n- Cung Mệnh: Thiên Tướng (Đắc)\n"
          + "- Cung Phu Thê: Tử Vi (Bình); gặp Tuần\nTHỜI VẬN:\n"
          + "- Đại hạn hiện tại (năm 2026): cung Phúc Đức, năm 2019–2028\n"
          + "- Lưu niên vào cung Phu Thê: năm 2029 (Kỷ Dậu)\nTƯ LIỆU:\n- Mệnh có Thiên Tướng.\n"
          + "- Phu Thê có Tử Vi thì duyên đến muộn mà bền.\nCÂU HỎI:\n<<<\nKhi nào?\n>>>";

  static LlmRequest request(String... users) {
    List<LlmMessage> messages = new java.util.ArrayList<>();
    messages.add(new LlmMessage(LlmMessage.Role.SYSTEM, SYSTEM));
    for (String u : users) {
      messages.add(new LlmMessage(LlmMessage.Role.USER, u));
      messages.add(new LlmMessage(LlmMessage.Role.ASSISTANT, "Ta đã trả lời con."));
    }
    return new LlmRequest(messages.subList(0, messages.size() - 1), 200, 0.3, LlmTier.ANY);
  }

  @Test
  void ok_output_follows_the_topic_palace_and_the_first_timing_line() {
    var out = new FakeLlmProvider(FakeMode.OK).complete(request(TIMING_USER));

    assertThat(out.text())
        .isEqualTo(
            "Con hỏi về tình duyên, ta xem lá số của con thì thấy thế này. "
                + "Cung Phu Thê: Tử Vi (Bình); gặp Tuần. "
                + "Phu Thê có Tử Vi thì duyên đến muộn mà bền. "
                + "Về thời vận, Đại hạn hiện tại (năm 2026): cung Phúc Đức, năm 2019–2028."
                + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó."
                + " Điều nào hợp với con thì con giữ lấy, điều nào chưa hợp thì con cứ để đó"
                + " mà ngẫm thêm.");
  }

  @Test
  void only_the_last_user_message_counts() {
    var both = new FakeLlmProvider(FakeMode.OK).complete(request(TIMING_USER, USER));
    var alone = new FakeLlmProvider(FakeMode.OK).complete(REQ);

    assertThat(both.text()).isEqualTo(alone.text()).doesNotContain("Phu Thê");
  }

  @Test
  void only_menh_passages_fall_back_to_the_first_one() {
    var out =
        new FakeLlmProvider(FakeMode.OK)
            .complete(request("CHỦ ĐỀ: vận năm\nTƯ LIỆU:\n- Mệnh a.\n- Mệnh b.\nCÂU HỎI: x"));

    assertThat(out.text()).contains(" Mệnh a. Con cứ").doesNotContain("Mệnh b");
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
