package dev.platform.reading.tuvi.application.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.qa.QaFakes.MemoryRepository;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QaRetentionUseCaseTest {

  static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  final MemoryRepository repo = new MemoryRepository();
  final IdentitySubject subject = new IdentitySubject("account", UUID.randomUUID());
  final QaRetentionUseCase retention = new QaRetentionUseCase(repo, Duration.ofDays(90));

  UUID insert(QaKind kind, QaStatus status, int daysAgo) {
    UUID id = UUID.randomUUID();
    repo.insert(
        QaReading.create(
            id,
            subject,
            kind,
            QaTopic.SuNghiep,
            "vi",
            "h",
            status,
            null,
            NOW.minus(Duration.ofDays(daysAgo))),
        new NewMessage(0, id, new byte[] {1}, null));
    return id;
  }

  @Test
  void free_older_than_90_days_deleted_paid_kept() {
    UUID old = insert(QaKind.FREE, QaStatus.ANSWERED, 91);
    UUID recent = insert(QaKind.FREE, QaStatus.ANSWERED, 89);
    UUID paid = insert(QaKind.PAID, QaStatus.ANSWERED, 400);

    retention.purge(NOW);

    assertThat(repo.reading(old)).isNull();
    assertThat(repo.messages(old)).isEmpty();
    assertThat(repo.reading(recent)).isNotNull();
    assertThat(repo.reading(paid)).isNotNull();
    assertThat(repo.purgedCacheBefore).isEqualTo(NOW.minus(Duration.ofDays(90)));
  }

  @Test
  void an_owed_free_reading_is_never_deleted() {
    UUID pending = insert(QaKind.FREE, QaStatus.PENDING, 120);

    retention.purge(NOW);

    assertThat(repo.reading(pending)).isNotNull();
  }
}
