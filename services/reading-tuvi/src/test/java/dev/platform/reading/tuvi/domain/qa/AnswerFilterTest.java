package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.SafetyLexicon;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Pass;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Reject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnswerFilterTest {

  /** A safe answer of about 120 characters, valid for FREE and FOLLOW_UP. */
  private static final String SAFE =
      "Ta xem cung Tài Bạch của con thì thấy tiền bạc đến đều theo công việc. "
          + "Con nên giữ một khoản dự phòng để thong dong hơn nhé.";

  /** What the Task 1 fake provider returns in ok mode for a realistic prompt. */
  static final String FAKE_OK =
      "Con hỏi về tài lộc, ta xem lá số của con thì thấy thế này. "
          + "Cung Tài Bạch có Vũ Khúc miếu, Hóa Lộc. Cung Mệnh có Tử Vi, Thiên Phủ."
          + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó.";

  static final String FAKE_UNSAFE = FAKE_OK + " Con nên đi giải hạn ngay.";

  @Test
  void safe_answer_passes_free() {
    assertThat(AnswerFilter.check(SAFE, AnswerLimits.FREE)).isEqualTo(new Pass(SAFE));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          banned    | Năm nay con nên đi cúng để mọi việc êm, ta thấy thế. Con cứ yên tâm.
          banned    | Tình duyên của con chắc chắn sẽ trắc trở, ta nói thật để con biết nhé.
          advice    | Con nên mua cổ phiếu ngân hàng vào tháng tới, ta thấy tài lộc của con vượng.
          advice    | Con cứ ngừng thuốc đi, ta thấy sức khỏe của con năm nay ổn định lắm rồi.
          advice    | Con không cần đi khám đâu, ta thấy cung Tật Ách của con êm đềm lắm.
          advice    | Ta thấy con nên kiện người ấy ra cho rõ ràng, lá số của con có Thái Dương.
          advice    | Số may mắn là bảy, con nhớ lấy mà dùng trong năm nay nhé, ta dặn con thế.
          advice    | Ta thấy con hợp cặp 37 - 73 trong năm nay, con cứ thử xem sao nhé.
          fear      | Năm nay con gặp hạn nặng ở cung Tật Ách, ta lo cho con lắm đấy con ạ.
          fear      | Con phải hóa giải ngay cung Phu Thê, ta thấy năm nay nhiều sóng gió lắm.
          fear      | Con nạp tiền để ta xem kỹ hơn nhé, cung Tài Bạch của con còn nhiều điều.
          citation  | Theo sách xưa thì cung Mệnh của con có Tử Vi, ta thấy con điềm đạm lắm.
          citation  | Ta trích lời người xưa cho con nghe về cung Mệnh có Tử Vi, điềm đạm lắm.
          citation  | Cung Mệnh của con có Tử Vi, nguồn: sách cổ, ta thấy con điềm đạm lắm nhé.
          contact   | Con xem thêm tại https://example.test nhé, ta thấy cung Mệnh của con đẹp.
          contact   | Con viết thư cho ta ở thay@example.test nhé, cung Mệnh của con đẹp lắm.
          contact   | Con gọi cho ta số 0912 345 678 nhé, ta thấy cung Mệnh của con đẹp lắm.
          cjk       | Cung Mệnh của con có Tử Vi 紫微, ta thấy con điềm đạm và được tin cậy.
          diacritic | Cung Menh cua con co Tu Vi, ta thay con diem dam va duoc nguoi tin cay lam.
          voice     | Cung Mệnh có Tử Vi, ta thấy người này điềm đạm và được tin cậy lắm đấy.
          """)
  void one_reject_per_rule(String rule, String answer) {
    assertThat(AnswerFilter.check(answer, AnswerLimits.FREE)).isEqualTo(new Reject(rule));
  }

  @Test
  void length_limits_per_tier() {
    assertThat(AnswerFilter.check("Con cứ bình tâm nhé.", AnswerLimits.FREE))
        .isEqualTo(new Reject("length"));
    assertThat(AnswerFilter.check(SAFE, AnswerLimits.PAID)).isEqualTo(new Reject("length"));
    assertThat(AnswerFilter.check(SAFE, AnswerLimits.FOLLOW_UP)).isInstanceOf(Pass.class);
    String longer = (SAFE + " ").repeat(8).strip(); // ~ 1000 chars
    assertThat(AnswerFilter.check(longer, AnswerLimits.FREE)).isEqualTo(new Reject("length"));
    assertThat(AnswerFilter.check(longer, AnswerLimits.FOLLOW_UP)).isInstanceOf(Pass.class);
    assertThat(AnswerFilter.check(longer, AnswerLimits.PAID)).isInstanceOf(Pass.class);
    assertThat(AnswerFilter.check((SAFE + " ").repeat(20), AnswerLimits.PAID))
        .isEqualTo(new Reject("length"));
    assertThat(AnswerLimits.PAID.min()).isEqualTo(150);
    assertThat(AnswerLimits.PAID.max()).isEqualTo(2000);
    assertThat(AnswerLimits.FOLLOW_UP.min()).isEqualTo(60);
    assertThat(AnswerLimits.FOLLOW_UP.max()).isEqualTo(1200);
    assertThat(AnswerLimits.FREE.min()).isEqualTo(60);
    assertThat(AnswerLimits.FREE.max()).isEqualTo(800);
  }

  @Test
  void leak_markers_rejected() {
    for (String marker :
        List.of(
            "TƯ LIỆU:",
            "CÂU HỎI:",
            "<<<",
            ">>>",
            "system",
            "System prompt",
            "hướng dẫn hệ thống",
            "NGOAI_PHAM_VI",
            "KHONG_DU_TU_LIEU")) {
      String answer = SAFE.replace("thì thấy", "thì thấy " + marker);
      assertThat(AnswerFilter.check(answer, AnswerLimits.FREE))
          .as(marker)
          .isEqualTo(new Reject("leak"));
    }
  }

  @Test
  void ordinary_words_close_to_leak_markers_pass() {
    String answer = SAFE.replace("thì thấy", "theo tư liệu cổ thì thấy hệ thống");
    assertThat(AnswerFilter.check(answer, AnswerLimits.FREE)).isInstanceOf(Pass.class);
  }

  @Test
  void citations_stripped_then_pass() {
    FilterResult r = AnswerFilter.check(SAFE.replace("nhé.", "nhé [1]."), AnswerLimits.FREE);
    assertThat(r).isEqualTo(new Pass(SAFE));
    FilterResult tail = AnswerFilter.check(SAFE + " [2]", AnswerLimits.FREE);
    assertThat(tail).isEqualTo(new Pass(SAFE));
  }

  @Test
  void markdown_is_cleaned() {
    String md = "## Tài lộc\n**Ta xem** cung Tài Bạch của __con__ thì thấy tiền bạc đến đều.";
    assertThat(AnswerFilter.check(md + " Con nên giữ một khoản dự phòng nhé.", AnswerLimits.FREE))
        .isEqualTo(
            new Pass(
                "Tài lộc\nTa xem cung Tài Bạch của con thì thấy tiền bạc đến đều."
                    + " Con nên giữ một khoản dự phòng nhé."));
  }

  @Test
  void year_ranges_are_not_lottery_numbers() {
    String answer = SAFE.replace("thì thấy", "trong năm 2026 - 2027 thì thấy");
    assertThat(AnswerFilter.check(answer, AnswerLimits.FREE)).isInstanceOf(Pass.class);
  }

  @Test
  void fake_ok_output_passes() {
    assertThat(AnswerFilter.check(FAKE_OK, AnswerLimits.PAID)).isEqualTo(new Pass(FAKE_OK));
  }

  @Test
  void fake_unsafe_output_rejected() {
    assertThat(AnswerFilter.check(FAKE_UNSAFE, AnswerLimits.PAID)).isEqualTo(new Reject("banned"));
  }

  @Test
  void null_answer_rejected_for_length() {
    assertThat(AnswerFilter.check(null, AnswerLimits.FREE)).isInstanceOf(Reject.class);
  }

  @Test
  void sentinel_detection() {
    assertThat(Sentinel.detect("  NGOAI_PHAM_VI")).contains(RefusalReason.OFF_TOPIC);
    assertThat(Sentinel.detect("KHONG_DU_TU_LIEU: không đủ")).contains(RefusalReason.INSUFFICIENT);
    assertThat(Sentinel.detect(SAFE)).isEmpty();
    assertThat(Sentinel.detect(null)).isEmpty();
    assertThat(Sentinel.NGOAI_PHAM_VI).isEqualTo("NGOAI_PHAM_VI");
    assertThat(Sentinel.KHONG_DU_TU_LIEU).isEqualTo("KHONG_DU_TU_LIEU");
  }

  @Test
  void banned_lexicon_is_the_style_guide_list() {
    assertThat(SafetyLexicon.BANNED)
        .hasSize(17)
        .contains("chết", "giải hạn", "cúng", "suốt đời", "chắc chắn sẽ", "không tránh khỏi");
  }

  @Test
  void topic_labels() {
    assertThat(QaTopic.values())
        .extracting(QaTopic::labelVi)
        .containsExactly("tình duyên", "sự nghiệp", "tài lộc", "sức khỏe", "gia đạo", "vận năm");
  }
}
