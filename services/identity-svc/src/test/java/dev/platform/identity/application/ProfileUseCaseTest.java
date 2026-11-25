package dev.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.adapter.out.crypto.AesGcmBirthCipherAdapter;
import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.ProfileLockedException;
import dev.platform.identity.domain.Subject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class ProfileUseCaseTest {

  static final BirthData BIRTH = new BirthData("1994-03-12T05:30", "Asia/Ho_Chi_Minh", "female");
  static final BirthData OTHER = new BirthData("1990-01-01T00:00", "Asia/Ho_Chi_Minh", "male");

  InMemoryPorts.Profiles profiles = new InMemoryPorts.Profiles();
  InMemoryPorts.Sessions sessions = new InMemoryPorts.Sessions();
  MagicLinkUseCaseTest.MutableClock clock =
      new MagicLinkUseCaseTest.MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
  ProfileUseCase useCase;
  Subject.Guest guest = new Subject.Guest(UUID.randomUUID());
  Subject.Account account = new Subject.Account(UUID.randomUUID());

  @BeforeEach
  void setUp() {
    String key =
        Base64.getEncoder()
            .encodeToString("local-test-profile-enc-key-00000".getBytes(StandardCharsets.UTF_8));
    useCase =
        new ProfileUseCase(
            profiles,
            sessions,
            new AesGcmBirthCipherAdapter(key, new SecureRandom()),
            clock,
            TransactionOperations.withoutTransaction());
  }

  @Test
  void get_is_empty_until_put() {
    assertThat(useCase.get(guest)).isEmpty();
  }

  @Test
  void put_creates_then_updates_the_same_profile() {
    ProfileView created = useCase.put(guest, "Me", BIRTH, Calendar.VN);
    ProfileView updated = useCase.put(guest, "Mom", OTHER, Calendar.CN);

    assertThat(updated.profile().id()).isEqualTo(created.profile().id());
    assertThat(useCase.get(guest).orElseThrow().profile().birth()).isEqualTo(OTHER);
    assertThat(profiles.byId).hasSize(1);
  }

  @Test
  void stored_birth_is_encrypted() {
    useCase.put(guest, "Me", BIRTH, Calendar.VN);

    StoredProfile stored = profiles.byId.values().iterator().next();
    assertThat(new String(stored.birthEnc(), StandardCharsets.ISO_8859_1))
        .doesNotContain("1994-03-12");
    assertThat(stored.keyId()).isEqualTo(1);
  }

  @Test
  void locked_account_cannot_change_birth_but_can_change_label_and_calendar() {
    useCase.put(account, "Me", BIRTH, Calendar.VN);
    profiles.lockedAccounts.add(account.id());

    assertThatThrownBy(() -> useCase.put(account, "Me", OTHER, Calendar.VN))
        .isInstanceOf(ProfileLockedException.class);
    ProfileView view = useCase.put(account, "New", BIRTH, Calendar.CN);

    assertThat(view.locked()).isTrue();
    assertThat(view.profile().label()).isEqualTo("New");
  }

  @Test
  void a_guest_is_never_locked() {
    assertThat(useCase.put(guest, "Me", BIRTH, Calendar.VN).locked()).isFalse();
  }

  @Test
  void deleting_keeps_the_account_lock_so_a_new_profile_cannot_change_birth_again() {
    useCase.put(account, "Me", BIRTH, Calendar.VN);
    profiles.lockedAccounts.add(account.id());

    useCase.delete(account);

    assertThat(useCase.get(account)).isEmpty();
    // A new profile may be created after the delete (the lock stops changes, not creation) ...
    ProfileView recreated = useCase.put(account, "Me", OTHER, Calendar.VN);
    assertThat(recreated.locked()).isTrue();
    // ... but its birth is then fixed again.
    assertThatThrownBy(() -> useCase.put(account, "Me", BIRTH, Calendar.VN))
        .isInstanceOf(ProfileLockedException.class);
  }

  @Test
  void delete_of_a_missing_profile_is_a_no_op() {
    useCase.delete(guest);

    assertThat(useCase.get(guest)).isEmpty();
  }

  @Test
  void merge_moves_the_guest_profile_when_the_account_has_none() {
    ProfileView mine = useCase.put(guest, "Me", BIRTH, Calendar.VN);

    useCase.mergeGuest(guest.id(), account.id());

    assertThat(useCase.get(guest)).isEmpty();
    ProfileView moved = useCase.get(account).orElseThrow();
    assertThat(moved.profile().id()).isEqualTo(mine.profile().id());
    assertThat(moved.profile().birth()).isEqualTo(BIRTH);
    assertThat(sessions.mergedInto).containsEntry(guest.id(), account.id());
  }

  @Test
  void merge_keeps_the_account_profile_and_drops_the_guest_one() {
    useCase.put(account, "Account", OTHER, Calendar.CN);
    useCase.put(guest, "Guest", BIRTH, Calendar.VN);

    useCase.mergeGuest(guest.id(), account.id());

    assertThat(useCase.get(guest)).isEmpty();
    assertThat(useCase.get(account).orElseThrow().profile().label()).isEqualTo("Account");
    assertThat(profiles.byId).hasSize(1);
    assertThat(sessions.mergedInto).containsEntry(guest.id(), account.id());
  }

  @Test
  void merge_without_a_guest_profile_still_marks_the_guest_merged() {
    useCase.mergeGuest(guest.id(), account.id());

    assertThat(sessions.mergedInto).containsEntry(guest.id(), account.id());
  }
}
