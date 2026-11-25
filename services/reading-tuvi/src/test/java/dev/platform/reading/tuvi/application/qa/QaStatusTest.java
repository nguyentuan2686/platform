package dev.platform.reading.tuvi.application.qa;

import static dev.platform.reading.tuvi.application.qa.QaStatus.ANSWERED;
import static dev.platform.reading.tuvi.application.qa.QaStatus.FAILED;
import static dev.platform.reading.tuvi.application.qa.QaStatus.PENDING;
import static dev.platform.reading.tuvi.application.qa.QaStatus.REFUNDED;
import static dev.platform.reading.tuvi.application.qa.QaStatus.REFUND_PENDING;
import static dev.platform.reading.tuvi.application.qa.QaStatus.REFUSED;
import static dev.platform.reading.tuvi.application.qa.QaStatus.RETRYING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaStatusTest {

  /** The whole transition table: anything not listed is illegal. */
  static final Map<QaStatus, Set<QaStatus>> ALLOWED =
      Map.of(
          PENDING, EnumSet.of(ANSWERED, RETRYING, REFUND_PENDING, REFUSED, FAILED),
          RETRYING, EnumSet.of(ANSWERED, RETRYING, REFUND_PENDING),
          REFUSED, EnumSet.of(REFUND_PENDING),
          REFUND_PENDING, EnumSet.of(REFUNDED, FAILED),
          ANSWERED, EnumSet.noneOf(QaStatus.class),
          REFUNDED, EnumSet.noneOf(QaStatus.class),
          FAILED, EnumSet.noneOf(QaStatus.class));

  @Test
  void transitions_match_the_table_exactly() {
    for (QaStatus from : QaStatus.values()) {
      for (QaStatus to : QaStatus.values()) {
        assertThat(from.canMoveTo(to))
            .as(from + " -> " + to)
            .isEqualTo(ALLOWED.get(from).contains(to));
      }
    }
  }

  @Test
  void terminal_states_are_never_overwritten() {
    for (QaStatus to : QaStatus.values()) {
      assertThat(ANSWERED.canMoveTo(to)).isFalse();
      assertThat(REFUNDED.canMoveTo(to)).isFalse();
      assertThat(FAILED.canMoveTo(to)).isFalse();
    }
    assertThat(REFUSED.canMoveTo(RETRYING)).isFalse();
  }

  @Test
  void in_progress_is_everything_still_owed_an_answer_or_refund() {
    assertThat(QaStatus.IN_PROGRESS).containsExactlyInAnyOrder(PENDING, RETRYING, REFUND_PENDING);
  }
}
