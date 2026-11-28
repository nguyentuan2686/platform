package dev.platform.reading.tuvi.application.number;

import dev.platform.reading.tuvi.application.qa.IdentityPort;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.domain.number.NumberInput;
import dev.platform.reading.tuvi.domain.number.NumberReadingPlan;
import dev.platform.reading.tuvi.domain.number.NumberScore;
import dev.platform.reading.tuvi.domain.number.NumberScorer;
import dev.platform.reading.tuvi.domain.number.Profile;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The free number check (xem số spec §7): validates the input first (an invalid one costs nothing
 * and touches no port), then counts the check against the caller's daily quota (only HMACs are
 * stored), then scores and renders the reading. The number is never logged or stored.
 */
public class NumberCheckService {

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  private final IdentityPort identity;
  private final NumberUsagePort usage;
  private final NumberHasherPort hasher;
  private final NumberReading reading;
  private final Clock clock;
  private final NumberCheckSettings settings;

  public NumberCheckService(
      IdentityPort identity,
      NumberUsagePort usage,
      NumberHasherPort hasher,
      NumberReading reading,
      Clock clock,
      NumberCheckSettings settings) {
    this.identity = identity;
    this.usage = usage;
    this.hasher = hasher;
    this.reading = reading;
    this.clock = clock;
    this.settings = settings;
  }

  /**
   * @throws dev.platform.reading.tuvi.domain.number.InvalidNumberException {@code invalid_number}
   *     or {@code invalid_birth}
   * @throws NumberQuotaExceededException when the subject or the IP used up today's checks
   */
  public NumberCheckResult check(NumberCheckCommand cmd) {
    LocalDate today = today();
    NumberInput in = NumberInput.parse(cmd.kind(), cmd.vehicle(), cmd.number());
    Profile profile =
        Profile.of(
            cmd.birthYear() == null ? 0 : cmd.birthYear(),
            cmd.beforeTet(),
            cmd.gender(),
            today.getYear());
    IdentitySubject subject = identity.whoAmI(cmd.bearer());
    NumberQuota quota =
        usage.recordOrReuse(
            subject,
            today,
            hasher.checkHash(in.hashMaterial()),
            cmd.clientIp() == null || cmd.clientIp().isBlank()
                ? null
                : hasher.ipHash(cmd.clientIp().strip()),
            settings.perSubject(),
            settings.perIp());
    NumberScore score = NumberScorer.score(in, profile);
    return new NumberCheckResult(
        score, profile, reading.render(NumberReadingPlan.keys(in, profile, score)), quota);
  }

  public NumberQuota quota(String bearer) {
    return usage.quota(identity.whoAmI(bearer), today(), settings.perSubject());
  }

  /** Deletes the usage rows older than yesterday (VN days); returns the number deleted. */
  public int purgeExpired() {
    return usage.purgeBefore(today().minusDays(1));
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), VN);
  }
}
