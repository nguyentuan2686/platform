package dev.platform.reading.tuvi.domain;

import java.util.List;

/**
 * One of the twelve palaces; {@code key} is the stable ASCII palace key, e.g. {@code Menh}; {@code
 * decade} is its đại hạn in nominal ages.
 */
public record Palace(
    String key,
    String nameVi,
    String branch,
    boolean isBody,
    Decade decade,
    List<StarPlacement> major,
    List<StarPlacement> minor,
    boolean tuan,
    boolean triet) {

  /** Đại hạn: nominal ages (tuổi mụ) {@code from..to}, inclusive. */
  public record Decade(int from, int to) {

    public boolean contains(int nominalAge) {
      return nominalAge >= from && nominalAge <= to;
    }
  }
}
