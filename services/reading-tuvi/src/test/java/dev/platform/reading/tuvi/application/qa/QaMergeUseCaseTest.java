package dev.platform.reading.tuvi.application.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.qa.QaFakes.MemoryRepository;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QaMergeUseCaseTest {

  static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  final MemoryRepository repo = new MemoryRepository();
  final QaMergeUseCase merge = new QaMergeUseCase(repo);
  final UUID guest = UUID.randomUUID();
  final UUID account = UUID.randomUUID();

  UUID insert(String kind, UUID subject, QaStatus status) {
    UUID id = UUID.randomUUID();
    repo.insert(
        QaReading.create(
            id,
            new IdentitySubject(kind, subject),
            QaKind.FREE,
            QaTopic.SuNghiep,
            "vi",
            "h",
            status,
            null,
            NOW),
        new NewMessage(0, id, new byte[] {1}, null));
    return id;
  }

  @Test
  void moves_only_the_guests_readings_and_keeps_their_status() {
    UUID answered = insert("guest", guest, QaStatus.ANSWERED);
    UUID pending = insert("guest", guest, QaStatus.PENDING);
    UUID otherGuest = insert("guest", UUID.randomUUID(), QaStatus.ANSWERED);
    UUID other = insert("account", UUID.randomUUID(), QaStatus.ANSWERED);

    assertThat(merge.merge(guest, account)).isEqualTo(2);

    assertThat(repo.reading(answered).subjectId()).isEqualTo(account);
    assertThat(repo.reading(answered).subjectKind()).isEqualTo("account");
    assertThat(repo.reading(pending).subjectId()).isEqualTo(account);
    assertThat(repo.reading(pending).status()).isEqualTo(QaStatus.PENDING);
    assertThat(repo.reading(otherGuest).subjectId()).isNotEqualTo(account);
    assertThat(repo.reading(other).subjectId()).isNotEqualTo(account);
  }

  @Test
  void a_repeat_moves_nothing() {
    insert("guest", guest, QaStatus.ANSWERED);

    assertThat(merge.merge(guest, account)).isEqualTo(1);
    assertThat(merge.merge(guest, account)).isZero();
  }
}
