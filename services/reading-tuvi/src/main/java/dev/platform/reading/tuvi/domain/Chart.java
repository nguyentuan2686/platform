package dev.platform.reading.tuvi.domain;

import java.util.List;

/**
 * Natal chart mirroring the chart-svc JSON (only the parts the reading needs). {@code
 * lunarBirthYear} stays server side: it turns đại hạn ages into calendar years and is never sent to
 * the model.
 */
public record Chart(
    String cucNameVi,
    String thanPalace,
    List<String> tuan,
    List<String> triet,
    List<Palace> palaces,
    int lunarBirthYear) {

  public Palace palace(String key) {
    return palaces.stream()
        .filter(p -> p.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no palace " + key));
  }

  public boolean inTuan(Palace p) {
    return tuan.contains(p.branch());
  }

  public boolean inTriet(Palace p) {
    return triet.contains(p.branch());
  }
}
