package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.TodayReading;
import java.util.List;

public record TodayResponse(
    String date,
    String lunar,
    String dayCanChi,
    String character,
    String opening,
    List<AreaResponse> areas,
    String closing) {

  public record AreaResponse(String area, String label, String level, String line) {}

  static TodayResponse from(TodayReading r) {
    return new TodayResponse(
        r.date().toString(),
        r.lunar(),
        r.dayCanChi(),
        r.character(),
        r.opening(),
        r.areas().stream()
            .map(a -> new AreaResponse(a.area().name(), a.label(), a.level().name(), a.line()))
            .toList(),
        r.closing());
  }
}
