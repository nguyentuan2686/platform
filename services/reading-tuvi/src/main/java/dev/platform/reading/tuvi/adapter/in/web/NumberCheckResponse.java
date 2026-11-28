package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.number.NumberCheckResult;
import dev.platform.reading.tuvi.application.number.NumberQuota;
import dev.platform.reading.tuvi.domain.number.Profile;
import java.util.List;

/** Body of {@code 200 POST /tuvi/number-check}; it never contains the checked number. */
public record NumberCheckResponse(
    double score,
    String band,
    String bandLabel,
    Menh menh,
    CungPhi cungPhi,
    List<Item> criteria,
    List<String> reading,
    Quota quota) {

  public record Menh(String canChi, String napAm, String element) {}

  public record CungPhi(String name, String group) {}

  public record Item(String key, String label, String detail, double points, double max) {}

  public record Quota(int limit, int used, int remaining) {
    static Quota from(NumberQuota q) {
      return new Quota(q.limit(), q.used(), q.remaining());
    }
  }

  static NumberCheckResponse from(NumberCheckResult r) {
    Profile p = r.profile();
    return new NumberCheckResponse(
        r.score().total(),
        r.score().band().key(),
        r.score().band().label(),
        new Menh(p.canChi(), p.napAm(), p.element().label()),
        new CungPhi(p.cungPhiName(), p.eastGroup() ? "Đông tứ mệnh" : "Tây tứ mệnh"),
        r.score().criteria().stream()
            .map(c -> new Item(c.key(), c.label(), c.detail(), c.points(), c.max()))
            .toList(),
        r.reading(),
        Quota.from(r.quota()));
  }
}
