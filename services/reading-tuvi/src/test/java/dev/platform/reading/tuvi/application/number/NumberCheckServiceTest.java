package dev.platform.reading.tuvi.application.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.qa.IdentityPort;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;
import dev.platform.reading.tuvi.domain.number.Gender;
import dev.platform.reading.tuvi.domain.number.InvalidNumberException;
import dev.platform.reading.tuvi.domain.number.NumberKind;
import dev.platform.reading.tuvi.domain.number.Vehicle;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NumberCheckServiceTest {

  static final String BEARER = "s1";
  static final String OTHER = "s2";
  static final IdentitySubject GUEST = new IdentitySubject("guest", UUID.randomUUID());
  static final IdentitySubject ACCOUNT = new IdentitySubject("account", UUID.randomUUID());
  // 2026-11-28 18:00 UTC = 2026-11-29 01:00 in Vietnam
  static final Instant NOW = Instant.parse("2026-11-28T18:00:00Z");

  /** In-memory usage with the same rules as the JDBC adapter. */
  static final class FakeUsage implements NumberUsagePort {
    record Row(String subject, LocalDate day, String check, String ip) {}

    final List<Row> rows = new ArrayList<>();
    int calls;
    LocalDate purgedBefore;

    private static String hex(byte[] b) {
      return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private int used(IdentitySubject s, LocalDate day) {
      return (int) rows.stream().filter(r -> r.subject.equals(key(s)) && r.day.equals(day)).count();
    }

    private static String key(IdentitySubject s) {
      return s.kind() + ":" + s.id();
    }

    @Override
    public NumberQuota recordOrReuse(
        IdentitySubject s, LocalDate day, byte[] check, byte[] ip, int perSubject, int perIp) {
      calls++;
      String c = hex(check);
      int used = used(s, day);
      if (rows.stream()
          .anyMatch(r -> r.subject.equals(key(s)) && r.day.equals(day) && r.check.equals(c))) {
        return NumberQuota.of(perSubject, used);
      }
      if (used >= perSubject) {
        throw new NumberQuotaExceededException(NumberQuota.of(perSubject, used));
      }
      if (ip != null) {
        Set<String> distinct = new HashSet<>();
        rows.stream()
            .filter(r -> r.day.equals(day) && hex(ip).equals(r.ip))
            .forEach(r -> distinct.add(r.check));
        if (distinct.size() >= perIp) {
          throw new NumberQuotaExceededException(new NumberQuota(perSubject, used, 0));
        }
      }
      rows.add(new Row(key(s), day, c, hex(ip)));
      return NumberQuota.of(perSubject, used + 1);
    }

    @Override
    public NumberQuota quota(IdentitySubject s, LocalDate day, int perSubject) {
      return NumberQuota.of(perSubject, used(s, day));
    }

    @Override
    public int purgeBefore(LocalDate day) {
      purgedBefore = day;
      return 0;
    }
  }

  /** Readable "hash" so the fake can compare; the real adapter is HMAC. */
  static final NumberHasherPort HASHER =
      new NumberHasherPort() {
        @Override
        public byte[] checkHash(String material) {
          return ("c:" + material).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] ipHash(String ip) {
          return ip == null ? null : ("i:" + ip).getBytes(StandardCharsets.UTF_8);
        }
      };

  FakeUsage usage;
  Map<String, IdentitySubject> sessions;
  int identityCalls;
  NumberCheckService service;

  @BeforeEach
  void setUp() {
    usage = new FakeUsage();
    sessions = new HashMap<>(Map.of(BEARER, GUEST, OTHER, ACCOUNT));
    identityCalls = 0;
    IdentityPort identity =
        (IdentityPort)
            Proxy.newProxyInstance(
                IdentityPort.class.getClassLoader(),
                new Class<?>[] {IdentityPort.class},
                (p, m, args) -> {
                  if (!m.getName().equals("whoAmI")) {
                    throw new UnsupportedOperationException(m.getName());
                  }
                  identityCalls++;
                  IdentitySubject s = sessions.get((String) args[0]);
                  if (s == null) {
                    throw new UnauthenticatedException();
                  }
                  return s;
                });
    NumberReading reading =
        new NumberReading(NumberReadingTest.knowledge(Map.of("so:closing", "Lời kết.")));
    service =
        new NumberCheckService(
            identity,
            usage,
            HASHER,
            reading,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new NumberCheckSettings(2, 20));
  }

  static NumberCheckCommand sim(String bearer, String number, String ip) {
    return new NumberCheckCommand(
        bearer, ip, NumberKind.SIM, null, number, 1990, false, Gender.MALE);
  }

  @Test
  void invalid_input_fails_before_any_port_call() {
    assertThatThrownBy(() -> service.check(sim(BEARER, "12345", "1.2.3.4")))
        .isInstanceOf(InvalidNumberException.class)
        .extracting(e -> ((InvalidNumberException) e).reason())
        .isEqualTo("invalid_number");
    assertThatThrownBy(
            () ->
                service.check(
                    new NumberCheckCommand(
                        BEARER,
                        null,
                        NumberKind.SIM,
                        null,
                        "0912345678",
                        2027,
                        false,
                        Gender.MALE)))
        .extracting(e -> ((InvalidNumberException) e).reason())
        .isEqualTo("invalid_birth");
    assertThatThrownBy(
            () ->
                service.check(
                    new NumberCheckCommand(
                        BEARER,
                        null,
                        NumberKind.PLATE,
                        null,
                        "30A12345",
                        1990,
                        false,
                        Gender.MALE)))
        .extracting(e -> ((InvalidNumberException) e).reason())
        .isEqualTo("invalid_number");
    assertThat(identityCalls).isZero();
    assertThat(usage.calls).isZero();
  }

  @Test
  void two_numbers_then_quota_and_a_repeat_is_free() {
    NumberCheckResult first = service.check(sim(BEARER, "0912345678", "1.2.3.4"));
    assertThat(first.quota()).isEqualTo(new NumberQuota(2, 1, 1));
    assertThat(first.score().total()).isEqualTo(7.0);
    assertThat(first.profile().napAm()).isEqualTo("Lộ Bàng Thổ");
    assertThat(first.reading()).containsExactly("Lời kết.");

    assertThat(service.check(sim(BEARER, "0987654321", "1.2.3.4")).quota())
        .isEqualTo(new NumberQuota(2, 2, 0));

    assertThatThrownBy(() -> service.check(sim(BEARER, "0911111111", "1.2.3.4")))
        .isInstanceOf(NumberQuotaExceededException.class)
        .extracting(e -> ((NumberQuotaExceededException) e).quota().remaining())
        .isEqualTo(0);

    // the same number in another spelling costs nothing
    assertThat(service.check(sim(BEARER, "+84 912 345 678", "1.2.3.4")).quota())
        .isEqualTo(new NumberQuota(2, 2, 0));
    assertThat(service.quota(BEARER)).isEqualTo(new NumberQuota(2, 2, 0));
  }

  @Test
  void the_vn_day_is_used() {
    service.check(sim(BEARER, "0912345678", null));
    assertThat(usage.rows.getFirst().day()).isEqualTo(LocalDate.of(2026, 11, 29));
  }

  @Test
  void per_ip_limit_applies_to_a_fresh_subject() {
    service =
        new NumberCheckService(
            proxyIdentity(),
            usage,
            HASHER,
            new NumberReading(NumberReadingTest.knowledge(Map.of())),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new NumberCheckSettings(2, 1));
    service.check(sim(BEARER, "0912345678", "1.2.3.4"));
    assertThatThrownBy(() -> service.check(sim(OTHER, "0987654321", "1.2.3.4")))
        .isInstanceOf(NumberQuotaExceededException.class);
    // another IP is fine
    assertThat(service.check(sim(OTHER, "0987654321", "5.6.7.8")).quota().used()).isEqualTo(1);
  }

  @Test
  void unknown_session_is_unauthenticated() {
    assertThatThrownBy(() -> service.check(sim("nope", "0912345678", null)))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(usage.calls).isZero();
  }

  @Test
  void purge_deletes_before_yesterday() {
    service.purgeExpired();
    assertThat(usage.purgedBefore).isEqualTo(LocalDate.of(2026, 11, 28));
  }

  @Test
  void command_and_exception_never_print_the_number() {
    NumberCheckCommand cmd = sim(BEARER, "0912345678", "1.2.3.4");
    assertThat(cmd.toString())
        .doesNotContain("912345678")
        .doesNotContain("1990")
        .doesNotContain("1.2.3.4");
    assertThat(new NumberQuotaExceededException(new NumberQuota(2, 2, 0)).getMessage())
        .isEqualTo("number_quota");
    assertThat(NumberQuota.of(2, 5)).isEqualTo(new NumberQuota(2, 5, 0));
    assertThat(Arrays.asList(NumberKind.values())).hasSize(3);
    assertThat(Vehicle.values()).hasSize(2);
  }

  private IdentityPort proxyIdentity() {
    return (IdentityPort)
        Proxy.newProxyInstance(
            IdentityPort.class.getClassLoader(),
            new Class<?>[] {IdentityPort.class},
            (p, m, args) -> sessions.get((String) args[0]));
  }
}
