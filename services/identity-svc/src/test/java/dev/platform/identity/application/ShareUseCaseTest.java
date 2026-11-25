package dev.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.adapter.out.crypto.AesGcmBirthCipherAdapter;
import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.Subject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class ShareUseCaseTest {

  static final BirthData BIRTH = new BirthData("1994-03-12T05:30", "Asia/Ho_Chi_Minh", "female");

  InMemoryPorts.Profiles profiles = new InMemoryPorts.Profiles();
  InMemoryPorts.Shares shares = new InMemoryPorts.Shares(profiles);
  ProfileUseCase profileUseCase;
  ShareUseCase useCase;
  Subject.Guest guest = new Subject.Guest(UUID.randomUUID());

  @BeforeEach
  void setUp() {
    String key =
        Base64.getEncoder()
            .encodeToString("local-test-profile-enc-key-00000".getBytes(StandardCharsets.UTF_8));
    AesGcmBirthCipherAdapter cipher = new AesGcmBirthCipherAdapter(key, new SecureRandom());
    MagicLinkUseCaseTest.MutableClock clock =
        new MagicLinkUseCaseTest.MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    profileUseCase =
        new ProfileUseCase(
            profiles,
            new InMemoryPorts.Sessions(),
            cipher,
            clock,
            TransactionOperations.withoutTransaction());
    useCase =
        new ShareUseCase(
            shares, cipher, clock, new SecureRandom(), TransactionOperations.withoutTransaction());
  }

  @Test
  void create_is_idempotent_returns_active() {
    profileUseCase.put(guest, "Me", BIRTH, Calendar.VN);

    ShareLink first = useCase.create(guest);
    ShareLink second = useCase.create(guest);

    assertThat(second).isEqualTo(first);
    assertThat(useCase.get(guest)).contains(first);
    assertThat(shares.rows).hasSize(1);
  }

  @Test
  void create_without_profile_conflicts_no_profile() {
    assertThatThrownBy(() -> useCase.create(guest)).isInstanceOf(NoProfileException.class);
  }

  @Test
  void revoke_then_get_is_empty() {
    profileUseCase.put(guest, "Me", BIRTH, Calendar.VN);
    ShareLink link = useCase.create(guest);

    useCase.revoke(guest);

    assertThat(useCase.get(guest)).isEmpty();
    assertThat(useCase.resolve(link.id().value())).isEmpty();
    useCase.revoke(guest); // no-op
  }

  @Test
  void create_after_revoke_issues_new_id() {
    profileUseCase.put(guest, "Me", BIRTH, Calendar.VN);
    ShareLink first = useCase.create(guest);
    useCase.revoke(guest);

    ShareLink second = useCase.create(guest);

    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(useCase.resolve(first.id().value())).isEmpty();
    assertThat(useCase.resolve(second.id().value())).isPresent();
  }

  @Test
  void resolve_returns_the_decrypted_birth_and_ignores_malformed_ids() {
    profileUseCase.put(guest, "Me", BIRTH, Calendar.CN);
    ShareLink link = useCase.create(guest);

    SharedProfile shared = useCase.resolve(link.id().value()).orElseThrow();

    assertThat(shared.birth()).isEqualTo(BIRTH);
    assertThat(shared.calendar()).isEqualTo(Calendar.CN);
    assertThat(useCase.resolve("bad")).isEmpty();
    assertThat(useCase.resolve(null)).isEmpty();
  }
}
